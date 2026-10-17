package org.carlife.hulab;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.io.*;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Default: launch an installed voice UI; explicit component: external-PCM recognizer. */
final class NativeVoice {
    private final NativeServiceState wire;private final String component;
    private Context context;private Handler main;private SpeechRecognizer recognizer;
    private volatile boolean available,closed,active,availabilityBlocked,availabilityPending,callAudioActive,applicationReply;
    private boolean prepared;
    private long epoch;private Pipe pipe;
    private volatile long micWithoutPipePackets,micWithoutPipeBytes;
    private final VoiceUiState uiState=new VoiceUiState();
    private NativeMicrophone microphone;
    private volatile Process uiLaunchProcess;private Process searchProcess;
    private Thread availabilityThread;
    private volatile String[] savedVoiceSettings={null,null,null};
    private volatile boolean voiceSettingsObserved;
    private long uiProcessToken;
    NativeVoice(NativeServiceState wire,String component){this.wire=wire;this.component=component;}
    synchronized void attachMicrophone(NativeMicrophone value){microphone=value;}
    synchronized void prepare(Context c,Handler h){if(prepared||closed)return;prepared=true;context=c;main=h;refreshAvailability();}
    void refreshAvailability(){
        synchronized(this){
            if(closed||available||availabilityBlocked||availabilityPending||main==null)return;
            availabilityPending=true;
            availabilityThread=new Thread(new Runnable(){public void run(){
            try{if(closed)return;
                refreshVoiceSettings();if(closed)return;
                if(!explicitRecognizer()) {available=hasVoiceUi();diagnostic(available?"voice_launch_handler_available":"voice_ui_handler_unavailable");return;}
                if(android.os.Build.VERSION.SDK_INT<33){availabilityBlocked=true;diagnostic("external_audio_api_unavailable");return;}
                available=hasExplicitRecognizer();diagnostic(available?"explicit_recognizer_ready":"explicit_recognizer_not_ready");
            }catch(SecurityException e){availabilityBlocked=true;diagnostic("recognizer_permission_denied");}
            catch(RuntimeException e){diagnostic("recognizer_readiness_error "+e.getClass().getSimpleName());}
            finally{synchronized(NativeVoice.this){availabilityPending=false;if(availabilityThread==Thread.currentThread())availabilityThread=null;}}
            }},"carlife-voice-availability");
            availabilityThread.setDaemon(true);
            try{availabilityThread.start();}catch(RuntimeException e){availabilityPending=false;availabilityThread=null;diagnostic("voice_availability_dispatch_failed","error_type",e.getClass().getSimpleName());}
        }
    }
    /** Bare app_process cannot acquire SettingsProvider through an ApplicationThread.
     * Use the existing bounded settings Binder CLI on this worker; UI/main never waits. */
    private void refreshVoiceSettings(){
        String[] keys={"voice_interaction_service","assistant","voice_recognition_service"};
        String[] values={null,null,null};
        try{
            for(int i=0;i<(explicitRecognizer()?3:2);i++){
                if(closed||Thread.currentThread().isInterrupted())return;
                values[i]=settingValue(NativeVideoSettings.command("/system/bin/settings","--user","current","get","secure",keys[i]));
            }
            if(!closed){savedVoiceSettings=values;voiceSettingsObserved=true;}
        }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();diagnostic("voice_settings_cli_unavailable","error_type",e.getClass().getSimpleName());}
    }
    static String settingValue(String value){if(value==null)return null;String clean=value.trim();return clean.isEmpty()||"null".equals(clean)?null:clean;}
    private boolean hasExplicitRecognizer(){
        ComponentName selected=ComponentName.unflattenFromString(component);
        if(selected==null){availabilityBlocked=true;diagnostic("invalid_explicit_recognizer_component");return false;}
        try{
            android.content.pm.ServiceInfo info=context.getPackageManager().getServiceInfo(selected,0);
            return info.enabled&&info.exported&&info.applicationInfo!=null&&info.applicationInfo.enabled
                &&"android.permission.BIND_SPEECH_RECOGNITION_SERVICE".equals(info.permission);
        }catch(PackageManager.NameNotFoundException missing){return false;}
    }
    boolean retryAvailability(){return !closed&&!available&&!availabilityBlocked;}
    boolean start(){
        Handler h=main;diagnostic("start_trigger","closed",closed,"prepared",prepared,"available",available,"active",active);
        if(closed||h==null){rejectStart(closed?"closed":"not_prepared");return false;}
        if(callAudioActive){rejectStart("call_audio_priority");return false;}
        if(!available){rejectStart(availabilityBlocked?"availability_blocked":"recognizer_unavailable");refreshAvailability();return false;}
        if(!explicitRecognizer())return dispatchVoiceUi();
        boolean posted=h.post(new Runnable(){public void run(){begin();}});if(!posted)rejectStart("handler_closed");diagnostic("start_dispatch","posted",posted);return posted;
    }
    private void rejectStart(String reason){try{wire.voiceModule(true,false);}catch(IOException ignored){}diagnostic("start_rejected","reason",reason);}
    void stop(){diagnostic("stop_trigger");if(!explicitRecognizer()){finishUi(uiState.token(),"hu_stop");return;}Handler h=main;if(h!=null)h.post(new Runnable(){public void run(){finish(epoch,false,null);}});}
    private boolean explicitRecognizer(){return component!=null&&!component.isEmpty();}
    static String[] voiceUiActions(){return new String[]{Intent.ACTION_VOICE_COMMAND,RecognizerIntent.ACTION_WEB_SEARCH,Intent.ACTION_ASSIST};}
    private Intent voiceUiIntent(String action){
        Intent i=new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if(RecognizerIntent.ACTION_WEB_SEARCH.equals(action))i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
        ResolveInfo r=context.getPackageManager().resolveActivity(i,PackageManager.MATCH_DEFAULT_ONLY);
        if(r==null||r.activityInfo==null||!r.activityInfo.enabled||!r.activityInfo.exported)return null;
        ResolveInfo home=context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);
        String homePackage=home==null||home.activityInfo==null?null:home.activityInfo.packageName;
        String homeClass=home==null||home.activityInfo==null?null:home.activityInfo.name;
        if(!voiceUiTargetWithLookup(r.activityInfo.packageName,r.activityInfo.name,homePackage,homeClass,new AssistantLookup(){public String installedPackage(){return installedAssistantPackage();}}))return null;
        return i.setComponent(new ComponentName(r.activityInfo.packageName,r.activityInfo.name));
    }
    /** A generic HOME catcher is not proof of voice support; an explicitly selected
     * installed assistant can intentionally combine its launcher and voice Activity. */
    static boolean voiceUiTargetAllowed(String pkg,String cls,String homePkg,String homeCls,String assistantPkg){
        return pkg!=null&&cls!=null&&(!(pkg.equals(homePkg)&&cls.equals(homeCls))||pkg.equals(assistantPkg));
    }
    interface AssistantLookup { String installedPackage(); }
    static boolean voiceUiTargetWithLookup(String pkg,String cls,String homePkg,String homeCls,AssistantLookup lookup){
        // Normal dedicated handlers do not depend on a Settings provider or Google.
        if(voiceUiTargetAllowed(pkg,cls,homePkg,homeCls,null))return true;
        if(pkg==null||cls==null)return false;
        try{return voiceUiTargetAllowed(pkg,cls,homePkg,homeCls,lookup.installedPackage());}
        catch(SecurityException unavailable){
            // Bare app_process has no registered ApplicationThread for ContentResolver.
            // Optional HOME-as-assistant evidence is unavailable; this is not a global
            // recognizer permission failure and must not block unrelated voice handlers.
            diagnostic("assistant_setting_unreadable","error_detail",startFailureDetail(unavailable));return false;
        }
    }
    private String installedAssistantPackage(){
        PackageManager pm=context.getPackageManager();
        String[] observed=savedVoiceSettings;
        for(int index=0;index<2;index++){
            String saved=observed[index];
            ComponentName selected=saved==null?null:ComponentName.unflattenFromString(saved);if(selected==null)continue;
            try{
                android.content.pm.ServiceInfo info=pm.getServiceInfo(selected,0);
                if(info.enabled&&info.exported&&info.applicationInfo!=null&&info.applicationInfo.enabled&&"android.permission.BIND_VOICE_INTERACTION".equals(info.permission))return selected.getPackageName();
            }catch(PackageManager.NameNotFoundException missing){}
            if(index==1)try{
                android.content.pm.ActivityInfo info=pm.getActivityInfo(selected,0);
                if(info.enabled&&info.exported&&info.applicationInfo!=null&&info.applicationInfo.enabled)return selected.getPackageName();
            }catch(PackageManager.NameNotFoundException missing){}
        }
        return null;
    }
    private ArrayList<Intent> voiceUiCandidates(){
        ArrayList<Intent> result=new ArrayList<Intent>();Intent i;
        // App-owned microphone remains independent. The tested Yandex ask_alice
        // URI opens a text dialog/no-op, not the screen microphone's listening mode.
        for(String action:voiceUiActions()){i=voiceUiIntent(action);if(i!=null)result.add(i);}
        return result;
    }
    private boolean hasVoiceUi(){return !voiceUiCandidates().isEmpty();}
    /** Admission is nonblocking; the worker records actual launch success separately. */
    private boolean dispatchVoiceUi(){
        final long token;
        synchronized(this){
            if(closed)return false;
            if(uiState.phase()!=VoiceUiState.Phase.IDLE){diagnostic("voice_ui_trigger_coalesced");return true;}
            ArrayList<int[]> prior=new ArrayList<int[]>();
            if(microphone!=null)for(NativeMicrophone.Recorder recorder:microphone.observedRecorders())if(recorder.eligible())prior.add(new int[]{recorder.uid,recorder.session});
            token=uiState.begin(SystemClock.uptimeMillis(),prior.toArray(new int[prior.size()][]));
        }
        try{
            if(microphone!=null)microphone.resumeOwnedRecorder();
            main.postDelayed(new Runnable(){public void run(){expireUi(token);}},VoiceUiState.START_TIMEOUT_MS);
            Thread t=new Thread(new Runnable(){public void run(){if(!launchVoiceUi(token))finishUi(token,"launch_failed");}},"carlife-voice-ui");t.setDaemon(true);t.start();
            diagnostic("voice_ui_dispatch","token",token,"queued",true,"recording_started",false,"display_id",MdMain.sourceDisplayId);return true;
        }catch(RuntimeException e){finishUi(token,"dispatch_failed");diagnostic("voice_ui_dispatch_failed","error_detail",startFailureDetail(e));return false;}
    }
    private boolean launchVoiceUi(long token){
        try{for(Intent intent:voiceUiCandidates()){
            if(closed||!uiState.current(token)||uiState.expired(SystemClock.uptimeMillis()))return false;
            try{
                String pkg=intent.getComponent().getPackageName();
                int uid=context.getPackageManager().getApplicationInfo(pkg,0).uid;
                if(!uiState.target(token,uid,pkg))continue;
                if(launchVoiceUiWithAm(intent,token)){
                    onMicrophoneRoute(microphone==null?null:microphone.verifiedRecorder());
                    if(uiState.launched(token,SystemClock.uptimeMillis()))reportUiRunning(token);
                    if(microphone!=null)microphone.refreshRecorders();
                    return uiState.current(token);
                }
            }
            catch(RuntimeException e){
                diagnostic("voice_ui_launch_error","action",intent.getAction(),"error_detail",startFailureDetail(e));
            }
            catch(PackageManager.NameNotFoundException e){diagnostic("voice_ui_package_disappeared");}
        }}catch(RuntimeException e){diagnostic("voice_ui_resolve_error","error_detail",startFailureDetail(e));}
        return false;
    }
    static ArrayList<String> voiceUiAmArgs(String action,String target){
        return voiceUiAmArgs(action,target,null,MdMain.sourceDisplayId);
    }
    static ArrayList<String> voiceUiAmArgs(String action,String target,String uri,int display){
        if(display<0)throw new IllegalArgumentException("voice display");
        ArrayList<String> args=new ArrayList<String>();args.add("/system/bin/am");args.add("start");args.add("-W");args.add("--user");args.add("current");args.add("--display");args.add(Integer.toString(display));args.add("-a");args.add(action);args.add("-n");args.add(target);args.add("-f");args.add("0x10000000");
        if(uri!=null){args.add("-d");args.add(uri);}
        if(RecognizerIntent.ACTION_WEB_SEARCH.equals(action)){args.add("--es");args.add(RecognizerIntent.EXTRA_LANGUAGE_MODEL);args.add(RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);}return args;
    }
    static boolean amStartSucceeded(int exit,String result){
        if(exit!=0||result==null||result.contains("Error:")||result.contains("Exception"))return false;
        for(String line:result.split("\\r?\\n"))if("Status: ok".equals(line.trim()))return true;
        return false;
    }
    private boolean launchVoiceUiWithAm(Intent intent,long token){
        if(closed||!uiState.current(token))return false;
        Process child=null;final ByteArrayOutputStream output=new ByteArrayOutputStream();Thread drain=null;
        try{
            // app_process has no ActivityManager-registered ApplicationThread. The normal
            // shell-command entry checks permissions itself under our unchanged UID.
            ArrayList<String> args=voiceUiAmArgs(intent.getAction(),intent.getComponent().flattenToString(),intent.getDataString(),MdMain.sourceDisplayId);
            synchronized(this){if(closed||callAudioActive||!uiState.current(token)||uiState.expired(SystemClock.uptimeMillis()))return false;child=new ProcessBuilder(args).redirectErrorStream(true).start();uiLaunchProcess=child;uiProcessToken=token;}final InputStream in=child.getInputStream();
            drain=new Thread(new Runnable(){public void run(){try{byte[] b=new byte[512];int n;while((n=in.read(b))!=-1)synchronized(output){int take=Math.min(n,4096-output.size());if(take>0)output.write(b,0,take);}}catch(IOException ignored){}}},"carlife-voice-am-output");drain.setDaemon(true);drain.start();
            if(!child.waitFor(uiState.remaining(token,SystemClock.uptimeMillis()),TimeUnit.MILLISECONDS)){diagnostic("voice_ui_am_timeout","action",intent.getAction());return false;}drain.join(50);
            String result;synchronized(output){result=new String(output.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);}
            boolean ok=uiState.current(token)&&!uiState.expired(SystemClock.uptimeMillis())&&amStartSucceeded(child.exitValue(),result);
            diagnostic(ok?"voice_ui_launched":"voice_ui_am_failed","action",intent.getAction(),"component",intent.getComponent().flattenToString(),"backend","am_same_uid","exit_code",child.exitValue(),"am_reported_ok",amStartSucceeded(child.exitValue(),result),"recording_started",false);return ok;
        }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();diagnostic("voice_ui_am_error","error_detail",startFailureDetail(e));return false;}
        finally{synchronized(this){if(uiLaunchProcess==child)uiLaunchProcess=null;}if(child!=null){if(child.isAlive())child.destroyForcibly();try{child.getInputStream().close();}catch(IOException ignored){}}}
    }
    void onCommunicationChanged(boolean active){
        callAudioActive=active;if(!active||closed)return;
        if(!explicitRecognizer()){finishUi(uiState.token(),"call_audio_priority");return;}
        Handler h=main;if(h!=null)h.post(new Runnable(){public void run(){finish(epoch,false,"call_audio_priority");}});
    }
    void onMicrophoneRoute(final NativeMicrophone.Recorder recorder){
        if(explicitRecognizer()||closed)return;final Handler h=main;if(h==null)return;
        final long token=uiState.token();
        h.post(new Runnable(){public void run(){
            if(closed||!uiState.current(token))return;
            if(uiState.route(token,recorder==null?-1:recorder.uid,recorder==null?-1:recorder.session,recorder==null?null:recorder.pkg,SystemClock.uptimeMillis()))reportUiRunning(token);
            if(active&&!applicationReply&&(recorder==null||!uiState.ownsRecorder(recorder.uid,recorder.session,recorder.pkg)))h.postDelayed(new Runnable(){public void run(){
                NativeMicrophone.Recorder actual=microphone==null?null:microphone.verifiedRecorder();
                if(uiState.current(token)&&active&&!applicationReply&&(actual==null||!uiState.ownsRecorder(actual.uid,actual.session,actual.pkg)))finishUi(token,"recorder_closed_or_preempted");
            }},500);
        }});
    }
    /** Same app's MEDIA answer must be audible on Honda while its persistent
     * MIC stays open. Release wire ownership immediately, but keep the UI turn. */
    synchronized void onApplicationReply(boolean replying){
        if(closed||explicitRecognizer()||applicationReply==replying)return;
        if(replying&&!NativeVoiceFocus.State.navigator(uiState.targetPackage()))return;
        applicationReply=replying;
        if(replying){wire.microphone(true,false);try{wire.voiceModule(true,false);}catch(IOException ignored){}diagnostic("application_reply_media_handoff","explicit_voice_active",active);}
        else if(active&&microphone!=null){NativeMicrophone.Recorder recorder=microphone.verifiedRecorder();if(recorder!=null&&uiState.ownsRecorder(recorder.uid,recorder.session,recorder.pkg)){try{wire.voiceModule(true,true);wire.microphone(true,true);}catch(IOException ignored){}}}
    }
    void onApplicationFocusTurn(int uid,String pkg,long turn,boolean gained){
        if(closed||explicitRecognizer())return;
        if(gained)uiState.focusGranted(uid,pkg,turn);
        else{long token=uiState.focusEnded(uid,pkg,turn);if(token!=0)finishUi(token,"application_voice_focus_ended");}
    }
    private synchronized void reportUiRunning(long token){
        if(closed||!uiState.current(token)||uiState.phase()!=VoiceUiState.Phase.RUNNING||active)return;
        active=true;try{if(!applicationReply){wire.voiceModule(true,true);wire.microphone(true,true);}}
        catch(IOException e){finishUi(token,"wire_error");return;}
        diagnostic("voice_ui_running","token",token,"target_package",uiState.targetPackage(),"recorder_route_verified",true,"wire_delivery_confirmed",false);
    }
    private void expireUi(final long token){
        if(closed||!uiState.current(token))return;
        long left=uiState.remaining(token,SystemClock.uptimeMillis());
        if(left<=0){finishUi(token,active?"voice_ui_session_timeout":"voice_ui_recorder_not_started");return;}
        if(main!=null)main.postDelayed(new Runnable(){public void run(){expireUi(token);}},left);
    }
    private void finishUi(long token,String reason){
        Process child=null;
        synchronized(this){
            NativeMicrophone.Recorder owned=microphone==null?null:microphone.verifiedRecorder();
            // Focus loss already retires its route on the microphone worker.
            // Posting a second suppression by recorder identity could arrive after
            // a new focus grant reuses that same recorder and cancel the new turn.
            boolean cancelRecorder=!"application_voice_focus_ended".equals(reason)&&owned!=null&&uiState.ownsPendingOrRunningRecorder(token,owned.uid,owned.session,owned.pkg);
            if(!uiState.finish(token))return;
            if(cancelRecorder&&microphone!=null)microphone.suppressOwnedRecorder(owned);
            active=false;
            if(uiProcessToken==token){child=uiLaunchProcess;uiLaunchProcess=null;}
            wire.microphone(true,false);try{wire.voiceModule(true,false);}catch(IOException ignored){}
        }
        if(child!=null)child.destroyForcibly();
        diagnostic("voice_ui_finished","token",token,"reason",reason,"unrelated_app_recorders_unchanged",true);
    }
    void onMic(byte[] pcm,int timestamp){
        // Ordinary apps receive PCM through NativeMicrophone. Do not take the
        // Activity-launch lock on the USB reader in the default UI mode.
        if(!explicitRecognizer()){if(++micWithoutPipePackets==1)diagnostic("first_mic_without_pipe","pcm_bytes",pcm==null?0:pcm.length);micWithoutPipeBytes+=pcm==null?0:pcm.length;return;}
        Pipe p;boolean firstIgnored=false;synchronized(this){p=pipe;if(p==null){firstIgnored=++micWithoutPipePackets==1;micWithoutPipeBytes+=pcm==null?0:pcm.length;}}if(firstIgnored)diagnostic("first_mic_without_pipe","pcm_bytes",pcm==null?0:pcm.length);if(p!=null&&!p.offer(pcm)){final long token=p.token;main.post(new Runnable(){public void run(){finish(token,false,"mic_backpressure");}});}
    }
    private void begin(){
        if(callAudioActive){rejectStart("call_audio_priority");return;}
        if(closed||!available||pipe!=null){diagnostic("begin_rejected","reason",closed?"closed":!available?"recognizer_unavailable":"already_in_progress");return;}
        final long token=++epoch;
        String stage="create_pcm_pipe";
        try {
            final Pipe p=new Pipe(token);synchronized(this){pipe=p;}
            stage="select_recognizer";
            ComponentName selected=component==null||component.isEmpty()?null:ComponentName.unflattenFromString(component);
            String configuredDefault=savedVoiceSettings[2];
            diagnostic("recognizer_selection","token",token,"selection",component==null||component.isEmpty()?"system_default":"explicit","selected_component",component,"default_component",configuredDefault,"default_component_is_observed_setting",voiceSettingsObserved);
            if(component!=null&&!component.isEmpty()&&selected==null)throw new IOException("invalid recognizer component");
            stage="create_speech_recognizer";
            recognizer=selected==null?SpeechRecognizer.createSpeechRecognizer(context):SpeechRecognizer.createSpeechRecognizer(context,selected);
            stage="set_recognition_listener";
            recognizer.setRecognitionListener(new RecognitionListener(){
                public void onReadyForSpeech(Bundle b){if(token!=epoch||closed)return;diagnostic("recognizer_ready","token",token);active=true;try{wire.voiceModule(true,true);wire.microphone(true,true);diagnostic("vr_microphone_requested","token",token,"wire_delivery_confirmed",false);}catch(IOException e){finish(token,false,"wire_error");}}
                public void onBeginningOfSpeech(){if(token==epoch&&!closed)diagnostic("speech_begin","token",token);}public void onRmsChanged(float v){}public void onBufferReceived(byte[] b){}
                public void onEndOfSpeech(){endAudio(token);}
                public void onError(int error){finish(token,false,"recognizer_error_"+error);}
                public void onResults(Bundle b){results(token,b);}
                public void onPartialResults(Bundle b){}public void onEvent(int type,Bundle b){}
            });
            stage="build_external_audio_intent";
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,p.read)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,16000)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);
            stage="start_listening";
            recognizer.startListening(intent);stage="start_pcm_writer";p.start();
            stage="schedule_timeout";
            main.postDelayed(new Runnable(){public void run(){finish(token,false,"recognition_timeout");}},15000);
            diagnostic("start_external_pcm16_mono_16000 provider_support_requires_runtime_test","token",token);
        }catch(Exception e){diagnostic("start_exception","token",token,"stage",stage,"error_type",e.getClass().getName(),"error_detail",startFailureDetail(e));finish(token,false,e.getClass().getSimpleName());}
    }
    private void results(long token,Bundle b){
        if(token!=epoch||closed)return;String query=null;
        try{ArrayList<String> list=b==null?null:b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);query=list==null||list.isEmpty()?null:list.get(0);}catch(RuntimeException e){diagnostic("recognizer_invalid_result");}
        finish(token,false,null);
        if(query!=null&&!query.trim().isEmpty()&&!closed)dispatchSearchResult(query);
    }
    static ArrayList<String> searchAmArgs(String target,String query,int display){
        ArrayList<String> args=voiceUiAmArgs(Intent.ACTION_WEB_SEARCH,target,null,display);
        args.add("--es");args.add(SearchManager.QUERY);args.add(query);return args;
    }
    private void dispatchSearchResult(final String query){
        Thread worker=new Thread(new Runnable(){public void run(){
            Process child=null;final ByteArrayOutputStream output=new ByteArrayOutputStream();
            try{
                if(closed)return;Intent intent=voiceUiIntent(Intent.ACTION_WEB_SEARCH);
                if(intent==null){diagnostic("search_handler_unavailable");return;}
                ArrayList<String> args=searchAmArgs(intent.getComponent().flattenToString(),query,MdMain.sourceDisplayId);
                synchronized(NativeVoice.this){if(closed)return;if(searchProcess!=null)searchProcess.destroyForcibly();child=new ProcessBuilder(args).redirectErrorStream(true).start();searchProcess=child;}
                final InputStream stream=child.getInputStream();Thread drain=new Thread(new Runnable(){public void run(){try{byte[] b=new byte[512];int n;while((n=stream.read(b))!=-1)synchronized(output){int take=Math.min(n,4096-output.size());if(take>0)output.write(b,0,take);}}catch(IOException ignored){}}},"carlife-search-am-output");drain.setDaemon(true);drain.start();
                boolean done=child.waitFor(3000,TimeUnit.MILLISECONDS);if(done)drain.join(50);
                String result;synchronized(output){result=new String(output.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);}
                diagnostic(!closed&&done&&amStartSucceeded(child.exitValue(),result)?"search_dispatched":"search_launch_failed");
                // am echoes Intent extras: never log stdout, command or query.
            }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();diagnostic("search_launch_failed","error_type",e.getClass().getSimpleName());}
            finally{synchronized(NativeVoice.this){if(searchProcess==child)searchProcess=null;}if(child!=null){if(child.isAlive())child.destroyForcibly();try{child.getInputStream().close();}catch(IOException ignored){}}}
        }},"carlife-search-ui");worker.setDaemon(true);try{worker.start();}catch(RuntimeException e){diagnostic("search_dispatch_failed","error_type",e.getClass().getSimpleName());}
    }
    private void endAudio(long token){if(token!=epoch)return;diagnostic("speech_end","token",token);Pipe p;synchronized(this){p=pipe;}if(p!=null)p.end();wire.microphone(true,false);}
    private void finish(long token,boolean unused,String reason){
        if(token!=epoch)return;++epoch;boolean wasReady=active;active=false;Pipe p;synchronized(this){p=pipe;pipe=null;}if(p!=null)p.close();
        SpeechRecognizer old=recognizer;recognizer=null;
        if(old!=null){try{old.cancel();}catch(RuntimeException e){diagnostic("recognizer_cancel_failed "+e.getClass().getSimpleName());}try{old.destroy();}catch(RuntimeException e){diagnostic("recognizer_destroy_failed "+e.getClass().getSimpleName());}}
        wire.microphone(true,false);try{wire.voiceModule(true,false);}catch(IOException ignored){}
        if(reason!=null)diagnostic(reason,"token",token);
        diagnostic("finished","token",token,"reason",reason==null?"stop_or_result":reason,"recognizer_was_ready",wasReady);
    }
    synchronized boolean callRetired(){return !active&&pipe==null;}
    boolean active(){return active&&!closed;}
    void close(){Process child;synchronized(this){closed=true;if(availabilityThread!=null)availabilityThread.interrupt();if(!explicitRecognizer())finishUi(uiState.token(),"close");child=uiLaunchProcess;uiLaunchProcess=null;if(searchProcess!=null){searchProcess.destroyForcibly();searchProcess=null;}}available=false;active=false;if(child!=null)child.destroyForcibly();Handler h=main;if(h!=null&&explicitRecognizer())h.post(new Runnable(){public void run(){finish(epoch,false,null);}});Pipe p;synchronized(this){p=pipe;pipe=null;diagnostic("closed","mic_packets_without_pipe",micWithoutPipePackets,"mic_pcm_bytes_without_pipe",micWithoutPipeBytes);}if(p!=null)p.close();}
    private static void diagnostic(String detail,Object...fields){Object[] all=new Object[fields.length+2];all[0]="state";all[1]=detail;System.arraycopy(fields,0,all,2,fields.length);MdMain.logFields("native_voice",all);}
    /** Startup exceptions only: no recognizer results, audio buffers or Intent extras. */
    static String startFailureDetail(Throwable error){
        StringBuilder b=new StringBuilder();Throwable t=error;
        for(int cause=0;t!=null&&cause<4;cause++,t=t.getCause()){
            if(cause>0)b.append(" | caused by ");b.append(t.getClass().getName());
            String message=t.getMessage();if(message!=null)b.append(": ").append(message.substring(0,Math.min(message.length(),512)).replace('\n',' ').replace('\r',' '));
            StackTraceElement[] frames=t.getStackTrace();for(int i=0;i<Math.min(frames.length,12);i++)b.append(" <- ").append(frames[i]);
            if(b.length()>4096){b.setLength(4096);break;}
        }
        return b.toString();
    }
    private final class Pipe {
        final long token;final ParcelFileDescriptor read,write;final MicQueue chunks=new MicQueue();
        volatile boolean disposed;Thread writer;long packets,pcmBytes,rejectedPackets,writtenBytes;
        Pipe(long t)throws IOException{token=t;ParcelFileDescriptor[] p=ParcelFileDescriptor.createPipe();read=p[0];write=p[1];}
        synchronized boolean offer(byte[] p){++packets;pcmBytes+=p==null?0:p.length;boolean accepted=chunks.offer(p);if(!accepted)++rejectedPackets;if(packets==1)diagnostic("first_mic_packet","token",token,"pcm_bytes",p==null?0:p.length,"offer_succeeded",accepted);return accepted;}
        void start(){writer=new Thread(new Runnable(){public void run(){try(OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(write)){while(!disposed){byte[] b=chunks.poll();if(b!=null){out.write(b);synchronized(Pipe.this){writtenBytes+=b.length;}}else if(chunks.ended())break;}}catch(Exception e){if(!disposed){diagnostic("mic_writer_error","token",token,"error_type",e.getClass().getSimpleName());main.post(new Runnable(){public void run(){finish(token,false,"mic_pipe_closed");}});}}}},"carlife-hu-mic");writer.setDaemon(true);writer.start();}
        void end(){chunks.end();}
        void close(){synchronized(this){if(!disposed)diagnostic("mic_summary","token",token,"packets",packets,"received_pcm_bytes",pcmBytes,"rejected_packets",rejectedPackets,"written_pcm_bytes_snapshot",writtenBytes,"queued_pcm_bytes_at_close",chunks.queuedBytes());disposed=true;}chunks.close();try{read.close();}catch(IOException ignored){}try{write.close();}catch(IOException ignored){}if(writer!=null)writer.interrupt();}
    }
    /** At most one second of PCM; USB ingestion never waits on the recognizer pipe. */
    static final class MicQueue {
        private final ArrayBlockingQueue<byte[]> chunks=new ArrayBlockingQueue<byte[]>(64);
        private int queued;private boolean ended,closed;
        synchronized boolean offer(byte[] p){if(ended||closed)return true;if(p==null||p.length==0)return true;if((p.length&1)!=0||p.length>32000||queued+p.length>32000)return false;byte[] copy=p.clone();if(!chunks.offer(copy))return false;queued+=copy.length;return true;}
        byte[] poll()throws InterruptedException{byte[] b=chunks.poll(200,java.util.concurrent.TimeUnit.MILLISECONDS);if(b!=null)synchronized(this){queued=Math.max(0,queued-b.length);}return b;}
        synchronized boolean ended(){return ended;}
        synchronized void end(){ended=true;}
        synchronized void close(){closed=true;ended=true;chunks.clear();queued=0;}
        synchronized int queuedBytes(){return queued;}
    }

}
