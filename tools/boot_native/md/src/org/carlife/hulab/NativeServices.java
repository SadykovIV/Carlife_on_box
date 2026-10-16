package org.carlife.hulab;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.MediaMetadata;
import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;

/** Native Android producers. No Android Auto bridge and no callbacks writing USB. */
final class NativeServices implements AutoCloseable {
    static final class Output {final int id;final byte[] payload;Output(int id,byte[] p){this.id=id;payload=p;}}
    private final NativeServiceState wire=new NativeServiceState();
    private final NativeVoice voice;
    private final NativeMicrophone microphone;
    private HandlerThread thread;private Handler worker;private Context context;
    private MediaSessionManager manager;private volatile MediaController controller;
    private MediaController.Callback mediaCallback;private Notifications notifications;
    private volatile boolean callHold,actualCommunication;private volatile boolean closed;private volatile Process appLaunchProcess;private boolean appLaunchPending;private Runnable appLaunchLatest;private boolean mediaReady,naviReady,voicePrepared,ticking,capabilitiesReported,pollSessions,verificationUnavailableLogged;
    private final StartupRetry contextRetry=new StartupRetry(),mediaRetry=new StartupRetry(),naviRetry=new StartupRetry();
    private long voiceNext;private int voiceAttempts;
    private final Runnable initializeTask=new Runnable(){public void run(){initialize();}};
    private volatile PendingIntent routeStop;
    private String routeKey,coverKey;private byte[] cover=new byte[0];private long controllerEpoch;
    private MediaSessionManager.OnActiveSessionsChangedListener sessions;private long mediaRegistrationEpoch;
    NativeServices(String recognizerComponent){voice=new NativeVoice(wire,recognizerComponent);microphone=new NativeMicrophone(new NativeMicrophone.Demand(){public void changed(boolean wanted){wire.applicationMicrophone(wanted);}},new NativeMicrophone.RouteListener(){public void changed(NativeMicrophone.Recorder recorder){voice.onMicrophoneRoute(recorder);}public void communicationChanged(boolean active){actualCommunication=active;voice.onCommunicationChanged(active||callHold);}});voice.attachMicrophone(microphone);}
    synchronized void start(){
        if(thread!=null||closed)return;thread=new HandlerThread("carlife-native-services");thread.start();worker=new Handler(thread.getLooper());
        worker.post(initializeTask);microphone.start();
    }
    private void initialize(){
        if(closed)return;long now=SystemClock.uptimeMillis();
        attempt(contextRetry,"context",now,new Registration(){public boolean acquire()throws Exception{context=AndroidRuntimeContext.get();return context!=null;}public void release(){context=null;}});
        if(closed)return;
        if(contextRetry.ready()){
            if(!voicePrepared){try{voice.prepare(context,AndroidRuntimeContext.mainHandler());voicePrepared=true;}catch(Exception e){diagnostic("voice_context_not_ready",e);}}
            attempt(mediaRetry,"media",now,new Registration(){public boolean acquire()throws Exception{return registerMedia();}public void release(){releaseMedia();}});
            attempt(naviRetry,"navigation",now,new Registration(){public boolean acquire()throws Exception{return registerNotifications();}public void release(){releaseNotifications();}});
            if(closed)return;
            if(voicePrepared&&voice.retryAvailability()&&now>=voiceNext){voice.refreshAvailability();voiceNext=now+StartupRetry.backoff(++voiceAttempts);}
        }
        boolean nextMedia=mediaRetry.ready(),nextNavi=naviRetry.ready();boolean changed=!capabilitiesReported||mediaReady!=nextMedia||naviReady!=nextNavi;mediaReady=nextMedia;naviReady=nextNavi;if(changed){wire.capabilities(mediaReady,naviReady);capabilitiesReported=true;}
        if(mediaReady&&!ticking){ticking=true;tick();}
        long next=contextRetry.next();
        if(contextRetry.ready()){
            next=Math.min(mediaRetry.next(),naviRetry.next());
            if(!voicePrepared)next=Math.min(next,now+30000);
            else if(voice.retryAvailability())next=Math.min(next,voiceNext);
        }
        if(!closed&&next!=Long.MAX_VALUE)worker.postDelayed(initializeTask,Math.max(100,next-SystemClock.uptimeMillis()));
    }
    private void attempt(StartupRetry retry,String name,long now,Registration registration){
        if(retry.run(now,registration))diagnostic(name+(retry.ready()?"_registered":retry.blocked?"_registration_denied_or_unsupported":"_registration_retry"),retry.failure);
    }
    private static android.os.IBinder service(String name)throws Exception{return (android.os.IBinder)Class.forName("android.os.ServiceManager").getMethod("checkService",String.class).invoke(null,name);}
    private boolean registerMedia()throws Exception {
        android.os.IBinder binder=service(Context.MEDIA_SESSION_SERVICE);if(binder==null||!binder.isBinderAlive())return false;
        ensureMediaFramework();
        // Context caches managers; a manager constructed before its binder exists retains null.
        // A fresh instance is used only for a failed registration, never for an active listener.
        manager=MediaSessionManager.class.getConstructor(Context.class).newInstance(context);
        final long registration=++mediaRegistrationEpoch;sessions=new MediaSessionManager.OnActiveSessionsChangedListener(){public void onActiveSessionsChanged(List<MediaController> list){if(!closed&&registration==mediaRegistrationEpoch)select(list);}};
        manager.addOnActiveSessionsChangedListener(sessions,null,worker);
        // AOSP swallows RemoteException here. Verify that its listener wrapper was actually kept.
        Boolean registered=listenerRegistered(manager,sessions);if(Boolean.FALSE.equals(registered))return false;
        pollSessions=registered==null;if(pollSessions&&!verificationUnavailableLogged){verificationUnavailableLogged=true;diagnostic("media_listener_verification_unavailable_polling_snapshot",null);}
        select(manager.getActiveSessions(null));return binder.isBinderAlive();
    }
    private static void ensureMediaFramework()throws Exception {
        // app_process + ActivityThread.systemMain does not run ActivityThread.main's
        // per-process mainline initialization. Only fill the two media registries;
        // never re-run all module initializers or replace an existing manager.
        Class<?> platform;
        try{platform=Class.forName("android.media.MediaFrameworkPlatformInitializer");}
        catch(ClassNotFoundException olderPlatform){return;}
        Class<?> serviceManager=Class.forName("android.media.MediaServiceManager");
        initializeMediaRegistries(serviceManager,platform);
        try{initializeMediaRegistries(serviceManager,Class.forName("android.media.MediaFrameworkInitializer"));}
        catch(ClassNotFoundException olderPlatform){/* Before mainline media registration. */}
    }
    static synchronized void initializeMediaRegistries(Class<?> managerType,Class<?>... registries)throws Exception {
        for(Class<?> registry:registries){
            java.lang.reflect.Method getter=registry.getMethod("getMediaServiceManager");
            if(getter.invoke(null)!=null)continue;
            Object instance=managerType.getConstructor().newInstance();
            registry.getMethod("setMediaServiceManager",managerType).invoke(null,instance);
            if(getter.invoke(null)==null)throw new IllegalStateException("media registry initialization not retained: "+registry.getName());
        }
    }
    // This optional AOSP detail must not become an OEM compatibility requirement.
    static Boolean listenerRegistered(Object manager,Object listener){
        try{java.lang.reflect.Field f=manager.getClass().getDeclaredField("mListeners");f.setAccessible(true);Object value=f.get(manager);return value instanceof Map?((Map<?,?>)value).containsKey(listener):null;}
        catch(ReflectiveOperationException|SecurityException inaccessible){return null;}
    }
    private boolean registerNotifications()throws Exception {
        android.os.IBinder binder=service(Context.NOTIFICATION_SERVICE);if(binder==null||!binder.isBinderAlive())return false;
        notifications=new Notifications();
        int user=((Integer)Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null)).intValue();
        NotificationListenerService.class.getMethod("registerAsSystemService",Context.class,ComponentName.class,int.class).invoke(notifications,context,new ComponentName("org.carlife.hulab","org.carlife.hulab.NativeServices$Notifications"),user);
        return binder.isBinderAlive();
    }
    private void releaseMedia(){
        ++controllerEpoch;++mediaRegistrationEpoch;MediaController old=controller;controller=null;MediaSessionManager oldManager=manager;manager=null;
        try{if(old!=null&&mediaCallback!=null)old.unregisterCallback(mediaCallback);}catch(RuntimeException e){diagnostic("media_callback_close_failed",e);}
        try{if(oldManager!=null&&sessions!=null)oldManager.removeOnActiveSessionsChangedListener(sessions);}catch(RuntimeException e){diagnostic("media_listener_close_failed",e);}
        try{wire.media("","","","","",0,0,false,0,new byte[0]);}catch(IOException ignored){}
    }
    private void releaseNotifications(){
        Notifications old=notifications;notifications=null;routeKey=null;routeStop=null;
        try{if(old!=null)NotificationListenerService.class.getMethod("unregisterAsSystemService").invoke(old);}catch(Exception e){diagnostic("notification_listener_close_failed",e);}
        try{wire.route(null);}catch(IOException ignored){}
    }
    interface Registration {boolean acquire()throws Exception;void release();}
    /** Worker-owned retry state; close may fence an in-flight registration from another thread. */
    static final class StartupRetry {
        private volatile boolean closed,ready;boolean blocked;private int failures;private long due;Throwable failure;
        boolean run(long now,Registration registration){
            if(closed||ready||blocked||now<due)return false;
            boolean acquired=false;failure=null;
            try{acquired=registration.acquire();if(acquired&&!closed){ready=true;failure=null;return true;}}
            catch(Exception e){failure=e;blocked=permanent(e);}catch(LinkageError e){failure=e;blocked=true;}
            try{registration.release();}catch(RuntimeException ignored){}
            if(!closed&&!blocked)due=now+backoff(++failures);return true;
        }
        boolean ready(){return ready&&!closed;}
        long next(){return closed||ready||blocked?Long.MAX_VALUE:due;}
        void close(){closed=true;ready=false;}
        static long backoff(int attempt){return Math.min(30000,1000L<<Math.min(5,Math.max(0,attempt-1)));}
        static boolean permanent(Throwable e){for(Throwable x=e;x!=null;x=x.getCause())if(x instanceof SecurityException||x instanceof UnsupportedOperationException||x instanceof NoSuchMethodException||x instanceof NoSuchFieldException||x instanceof ClassNotFoundException||x instanceof IllegalAccessException||x instanceof NullPointerException)return true;return false;}
    }
    void onHuInfo(byte[] payload){try{wire.huInfo(payload);}catch(IOException e){diagnostic("hu_info_invalid",e);}}
    boolean onCommand(int id,byte[] payload)throws IOException {
        if(closed)return false;if(wire.command(id,payload))return true;
        if(id==0x18028){Map<Integer,Object> f=MdMain.Proto.parse(payload);if(MdMain.Proto.number(f,1,-1)==2&&MdMain.Proto.number(f,2,-1)==0)stopNavigation();if(MdMain.Proto.number(f,1,-1)==4){if(MdMain.Proto.number(f,2,0)==1){voice.start();return true;}voice.stop();return true;}}
        if(id==0x1801e)return launchPhone();if(id==0x1801f)return launchNavi();if(id==0x18020)return launchMedia();return false;
    }
    boolean onHardkey(final int key){
        if(closed)return false;if(key==0x21){voice.start();return true;}if(key==0x22){voice.stop();return true;}
        if(key==0x0b)return launchNavi();if(key==0x09)return launchMedia();
        if(key!=0x0f&&key!=0x10&&key!=0x1f&&key!=0x20)return false;
        final MediaController target=controller;Handler h=worker;if(target==null||h==null)return false;
        h.post(new Runnable(){public void run(){if(closed||target!=controller)return;try{MediaController.TransportControls c=target.getTransportControls();if(key==0x0f)c.skipToPrevious();else if(key==0x10)c.skipToNext();else if(key==0x1f)c.play();else c.pause();}catch(RuntimeException e){diagnostic("media_key_failed",e);}}});return true;
    }
    void callAudioHold(boolean value){if(closed||callHold==value)return;callHold=value;microphone.callHold(value);voice.onCommunicationChanged(value||actualCommunication);}
    boolean callAudioRetired(){return !closed&&callHold&&microphone.callRetired()&&voice.callRetired()&&wire.nonCallMicrophoneIdle();}
    void callVoice(boolean value){if(!closed)wire.callVoice(value);}
    void callMicrophone(boolean value){if(!closed)wire.callMicrophone(value);}
    void onMic(byte[] pcm,int timestamp){if(!closed&&!callHold){microphone.onMic(pcm,timestamp);voice.onMic(pcm,timestamp);}}
    Output poll(){NativeServiceState.Output p=wire.poll();return p==null?null:new Output(p.id,p.payload);}
    boolean routeNaviWanted(){return wire.routeNaviWanted();}
    boolean voiceActive(){return wire.voiceModuleActive();}
    void assistantPlaybackActive(boolean active){if(closed)return;try{wire.voiceModule(false,active);}catch(IOException e){diagnostic("assistant_module_encode_failed",e);}}
    private synchronized boolean dispatchAppLaunch(final Runnable action){
        if(closed||worker==null||context==null)return false;
        if(appLaunchPending){appLaunchLatest=action;diagnostic("app_launch_coalesced_latest",null);return true;}
        appLaunchPending=true;
        Thread launch=new Thread(new Runnable(){public void run(){
            Runnable next=action;
            while(next!=null){
                try{if(!closed)next.run();}catch(RuntimeException e){diagnostic("app_launch_failed",e);}
                synchronized(NativeServices.this){next=closed?null:appLaunchLatest;appLaunchLatest=null;if(next==null)appLaunchPending=false;}
            }
        }},"carlife-app-launch");
        launch.setDaemon(true);try{launch.start();return true;}catch(RuntimeException e){appLaunchPending=false;appLaunchLatest=null;diagnostic("app_launch_dispatch_failed",e);return false;}
    }
    private boolean launchPhone(){
        return dispatchAppLaunch(new Runnable(){public void run(){
            if(closed)return;
            try{Intent intent=new Intent(Intent.ACTION_DIAL);ResolveInfo r=context.getPackageManager().resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY);
                if(r==null||r.activityInfo==null||!r.activityInfo.enabled||!r.activityInfo.exported){diagnostic("dialer_unavailable",null);return;}
                intent.setComponent(new ComponentName(r.activityInfo.packageName,r.activityInfo.name));launchIntent(intent,r.activityInfo.packageName);
            }catch(RuntimeException e){diagnostic("dialer_launch_failed",e);}
        }});
    }
    private boolean launchNavi(){return dispatchAppLaunch(new Runnable(){public void run(){for(String pkg:new String[]{"ru.yandex.yandexnavi","ru.yandex.yandexmaps"})if(launch(pkg))return;diagnostic("yandex_app_unavailable",null);}});}
    private boolean launchMedia(){final MediaController c=controller;if(c==null)return false;return dispatchAppLaunch(new Runnable(){public void run(){if(!closed&&c==controller)launch(c.getPackageName());}});}
    private boolean launch(String pkg){
        if(closed)return false;
        try{return launchIntent(context.getPackageManager().getLaunchIntentForPackage(pkg),pkg);}
        catch(RuntimeException e){diagnostic("app_launch_failed",e);return false;}
    }
    private boolean launchIntent(Intent intent,String pkg){
        if(closed||intent==null||intent.getComponent()==null)return false;
        Process child=null;final ByteArrayOutputStream output=new ByteArrayOutputStream();
        try{
            ArrayList<String> args=NativeVoice.voiceUiAmArgs(intent.getAction(),intent.getComponent().flattenToString(),intent.getDataString(),MdMain.sourceDisplayId);
            if(intent.getCategories()!=null)for(String category:intent.getCategories()){args.add("-c");args.add(category);}
            // This one coalesced launch slot never blocks USB or the notification,
            // navigation-stop and metadata worker. app_process has no registered
            // ApplicationThread; am checks this process's unchanged UID instead.
            synchronized(this){if(closed)return false;child=new ProcessBuilder(args).redirectErrorStream(true).start();appLaunchProcess=child;}
            final InputStream stream=child.getInputStream();
            Thread drain=new Thread(new Runnable(){public void run(){try{byte[] b=new byte[512];int n;while((n=stream.read(b))!=-1)synchronized(output){int take=Math.min(n,4096-output.size());if(take>0)output.write(b,0,take);}}catch(IOException ignored){}}},"carlife-app-am-output");drain.setDaemon(true);drain.start();
            boolean completed=child.waitFor(3000,TimeUnit.MILLISECONDS);if(completed)drain.join(50);
            String result;synchronized(output){result=new String(output.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);}
            boolean ok=!closed&&completed&&NativeVoice.amStartSucceeded(child.exitValue(),result);
            MdMain.logFields("native_services","state",ok?"app_launched":"app_launch_failed","package",pkg,"display_id",MdMain.sourceDisplayId,"backend","am_same_uid","completed",completed,"launch_output",result);return ok;
        }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();diagnostic("app_launch_failed",e);return false;}
        finally{synchronized(this){if(appLaunchProcess==child)appLaunchProcess=null;}if(child!=null){if(child.isAlive())child.destroyForcibly();try{child.getInputStream().close();}catch(IOException ignored){}}}
    }
    private void select(List<MediaController> list){try{selectInternal(list);}catch(RuntimeException e){diagnostic("session_selection_failed",e);++controllerEpoch;MediaController old=controller;controller=null;try{if(old!=null&&mediaCallback!=null)old.unregisterCallback(mediaCallback);}catch(RuntimeException ignored){}refresh();}}
    private void selectInternal(List<MediaController> list){
        if(closed)return;MediaController chosen=null;
        if(list!=null)for(MediaController c:list){if(c==null)continue;if(chosen==null)chosen=c;PlaybackState p=c.getPlaybackState();if(p!=null&&p.getState()==PlaybackState.STATE_PLAYING){chosen=c;break;}}
        if(chosen!=null&&controller!=null&&chosen.getSessionToken().equals(controller.getSessionToken())){refresh();return;}
        if(controller!=null&&mediaCallback!=null)controller.unregisterCallback(mediaCallback);controller=chosen;final long token=++controllerEpoch;
        if(chosen!=null){mediaCallback=new MediaController.Callback(){public void onMetadataChanged(MediaMetadata m){if(token==controllerEpoch)refresh();}public void onPlaybackStateChanged(PlaybackState p){if(token==controllerEpoch)refresh();}public void onSessionDestroyed(){if(token==controllerEpoch)try{select(manager.getActiveSessions(null));}catch(RuntimeException e){select(null);}}};chosen.registerCallback(mediaCallback,worker);}
        refresh();
    }
    private void refresh(){
        if(closed)return;MediaController c=controller;
        try{if(c==null){wire.media("","","","","",0,0,false,0,new byte[0]);return;}MediaMetadata m=c.getMetadata();PlaybackState p=c.getPlaybackState();boolean playing=p!=null&&p.getState()==PlaybackState.STATE_PLAYING;long duration=m==null?0:m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            List<MediaSession.QueueItem> q=c.getQueue();wire.media(c.getPackageName(),value(m,MediaMetadata.METADATA_KEY_TITLE),value(m,MediaMetadata.METADATA_KEY_ARTIST),value(m,MediaMetadata.METADATA_KEY_ALBUM),value(m,MediaMetadata.METADATA_KEY_MEDIA_ID),duration,q==null?0:q.size(),playing,position(p,duration),cover(m,c.getPackageName()));
        }catch(RuntimeException e){diagnostic("media_refresh_failed",e);}catch(IOException e){diagnostic("metadata_encode_failed",e);}
    }
    private byte[] cover(MediaMetadata m,String source){
        Bitmap art=m==null?null:m.getBitmap(MediaMetadata.METADATA_KEY_ART);if(art==null&&m!=null)art=m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        String key=source+"|"+value(m,MediaMetadata.METADATA_KEY_MEDIA_ID)+"|"+value(m,MediaMetadata.METADATA_KEY_TITLE)+"|"+value(m,MediaMetadata.METADATA_KEY_ALBUM)+"|"+(art==null?0:art.getGenerationId());
        if(key.equals(coverKey))return cover;coverKey=key;cover=new byte[0];if(art==null||art.getWidth()<=0||art.getHeight()<=0)return cover;
        Bitmap scaled=null;try{double scale=Math.min(1.0,128.0/Math.max(art.getWidth(),art.getHeight()));scaled=Bitmap.createScaledBitmap(art,Math.max(1,(int)(art.getWidth()*scale)),Math.max(1,(int)(art.getHeight()*scale)),true);
            for(int quality:new int[]{75,50,30}){ByteArrayOutputStream out=new ByteArrayOutputStream();if(scaled.compress(Bitmap.CompressFormat.JPEG,quality,out)&&out.size()<=28000){cover=out.toByteArray();break;}}
        }catch(RuntimeException e){diagnostic("cover_encode_failed",e);}finally{if(scaled!=null&&scaled!=art)scaled.recycle();}return cover;
    }
    private void tick(){
        if(closed)return;if(pollSessions&&manager!=null)try{select(manager.getActiveSessions(null));}catch(RuntimeException e){diagnostic("media_snapshot_poll_failed",e);}MediaController c=controller;
        try{if(c!=null){MediaMetadata m=c.getMetadata();wire.progress(position(c.getPlaybackState(),m==null?0:m.getLong(MediaMetadata.METADATA_KEY_DURATION)),SystemClock.elapsedRealtime());}}catch(Exception e){diagnostic("progress_failed",e);}
        worker.postDelayed(new Runnable(){public void run(){tick();}},1000);
    }
    static int position(PlaybackState p,long duration){if(p==null)return 0;long n=p.getPosition();if(p.getState()==PlaybackState.STATE_PLAYING&&p.getLastPositionUpdateTime()>0)n+=(long)(Math.max(0,SystemClock.elapsedRealtime()-p.getLastPositionUpdateTime())*p.getPlaybackSpeed());if(duration>0)n=Math.min(n,duration);return NativeServiceState.saturate(n);}
    private static String value(MediaMetadata m,String key){String v=m==null?null:m.getString(key);return v==null?"":v;}
    private void stopNavigation(){
        final PendingIntent target=routeStop;Handler h=worker;
        if(target==null||h==null){diagnostic("navigation_stop_action_unavailable",null);return;}
        h.post(new Runnable(){public void run(){if(closed||target!=routeStop)return;try{target.send();diagnostic("navigation_stop_action_sent",null);}catch(PendingIntent.CanceledException e){diagnostic("navigation_stop_action_cancelled",e);}}});
    }
    private void posted(StatusBarNotification s){try{postedInternal(s);}catch(RuntimeException e){diagnostic("notification_parse_failed",e);}}
    private void postedInternal(StatusBarNotification s){
        if(closed||s==null||!YandexNotificationRoute.supports(s.getPackageName()))return;Notification n=s.getNotification();
        YandexNotificationRoute.Turn turn=YandexNotificationData.read(context,s.getPackageName(),n);
        if(turn!=null)turn=turn.withSource(s.getKey());
        try{if(turn!=null){boolean first=!s.getKey().equals(routeKey);routeKey=s.getKey();routeStop=YandexNotificationData.stopAction(context,s.getPackageName(),n);wire.route(turn);if(first)MdMain.logFields("native_navigation","source",s.getPackageName(),"turn",turn.nextTurn,"meters",turn.meters,"icon_bytes",turn.icon.length);}else if(s.getKey().equals(routeKey)){routeKey=null;routeStop=null;wire.route(null);}if(turn==null)diagnostic("yandex_notification_without_explicit_supported_maneuver",null);}catch(IOException e){diagnostic("navigation_encode_failed",e);}
    }
    private static String text(Bundle b,String key){CharSequence s=b.getCharSequence(key);return s==null?"":s.toString();}
    private final class Notifications extends NotificationListenerService {
        public void onListenerConnected(){Handler h=worker;if(h!=null)h.post(new Runnable(){public void run(){if(closed||Notifications.this!=notifications)return;try{StatusBarNotification[] all=getActiveNotifications();if(all!=null){Arrays.sort(all,new Comparator<StatusBarNotification>(){public int compare(StatusBarNotification a,StatusBarNotification b){return Long.compare(a.getPostTime(),b.getPostTime());}});for(StatusBarNotification s:all)posted(s);}}catch(RuntimeException e){diagnostic("notification_snapshot_failed",e);}}});}
        public void onNotificationPosted(final StatusBarNotification s){Handler h=worker;if(h!=null)h.post(new Runnable(){public void run(){if(!closed&&Notifications.this==notifications)posted(s);}});}
        public void onNotificationRemoved(final StatusBarNotification s){Handler h=worker;if(h!=null)h.post(new Runnable(){public void run(){if(!closed&&Notifications.this==notifications&&s!=null&&s.getKey().equals(routeKey)){routeKey=null;routeStop=null;try{wire.route(null);}catch(IOException ignored){}}}});}
    }
    public synchronized void close(){
        if(closed)return;closed=true;Process launch=appLaunchProcess;appLaunchProcess=null;if(launch!=null)launch.destroyForcibly();contextRetry.close();mediaRetry.close();naviRetry.close();voice.close();wire.close();microphone.close();Handler h=worker;final HandlerThread t=thread;
        if(h!=null){h.removeCallbacks(initializeTask);h.post(new Runnable(){public void run(){releaseMedia();releaseNotifications();cover=new byte[0];if(t!=null)t.quitSafely();}});}
    }
    static String causeChain(Throwable e){
        if(e==null)return null;StringBuilder b=new StringBuilder();Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable x=e;x!=null&&seen.add(x);x=x.getCause()){if(b.length()>0)b.append(" <- ");b.append(x.getClass().getName()).append(": ").append(x.getMessage());}
        return bounded(b.toString(),1200);
    }
    private static String bounded(String value,int limit){return value.length()<=limit?value:value.substring(0,limit)+" [truncated]";}
    private static void diagnostic(String event,Throwable e){
        String stack=null;if(e!=null){StringWriter text=new StringWriter();e.printStackTrace(new PrintWriter(text));stack=bounded(text.toString(),3600);}
        MdMain.logFields("native_services","state",event,"error_type",e==null?null:e.getClass().getSimpleName(),"error_chain",causeChain(e),"error_stack",stack);
    }
}
