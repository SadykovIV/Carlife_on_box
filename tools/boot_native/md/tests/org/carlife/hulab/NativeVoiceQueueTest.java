package org.carlife.hulab;
public final class NativeVoiceQueueTest {
    static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        check(NativeVoice.settingValue(null)==null&&NativeVoice.settingValue("null\n")==null&&NativeVoice.settingValue(" \n")==null,"absent secure CLI setting cannot become a false assistant component");
        check("voice.app/.Service".equals(NativeVoice.settingValue("voice.app/.Service\r\n")),"bounded secure CLI read removes only surrounding command whitespace");
        NativeVoice.MicQueue q=new NativeVoice.MicQueue();byte[] pcm=new byte[]{1,2,3,4};check(q.offer(pcm),"PCM accepted");pcm[0]=99;check(q.poll()[0]==1,"USB buffer not retained");check(q.queuedBytes()==0,"drain accounted");check(!q.offer(new byte[3]),"unaligned PCM rejected");check(!q.offer(new byte[32002]),"oversized chunk rejected");
        for(int i=0;i<50;i++)check(q.offer(new byte[640]),"one second standard PCM packets");check(q.queuedBytes()==32000,"exact one-second bound");long t=System.nanoTime();check(!q.offer(new byte[2]),"full queue signals cancellation");check(System.nanoTime()-t<100000000L,"ingress does not block");check(q.poll().length==640&&q.offer(new byte[640]),"capacity restored by actual drain");q.end();check(q.offer(new byte[4])&&q.queuedBytes()==32000,"late microphone packet after end discarded");
        q.close();check(q.ended()&&q.queuedBytes()==0,"close drops all pending PCM");check(q.offer(new byte[10])&&q.queuedBytes()==0,"closed callback cannot retain audio");check(q.poll()==null,"closed queue empty");
        java.util.List<String> launch=NativeVoice.voiceUiAmArgs("android.intent.action.VOICE_COMMAND","voice.app/.Ui");
        check(launch.equals(java.util.Arrays.asList("/system/bin/am","start","-W","--user","current","--display","0","-a","android.intent.action.VOICE_COMMAND","-n","voice.app/.Ui","-f","0x10000000")),"launch uses direct argv, current user and captured source display");
        check(!launch.contains("su")&&!launch.contains("sh")&&!launch.contains("-c"),"launch neither switches UID nor invokes a shell interpreter");
        check(NativeVoice.voiceUiAmArgs("android.speech.action.WEB_SEARCH","voice.app/.Ui").subList(13,16).equals(java.util.Arrays.asList("--es","android.speech.extra.LANGUAGE_MODEL","web_search")),"speech search uses recognizer language model without a text query");
        java.util.List<String> data=NativeVoice.voiceUiAmArgs("android.intent.action.VIEW","voice.app/.Ui","example://voice?query=",0);
        check(data.subList(13,15).equals(java.util.Arrays.asList("-d","example://voice?query=")),"explicit URI argv retained literally, without implying a working voice entry");
        check(!NativeVoice.voiceUiTargetAllowed("launcher.app","Home","launcher.app","Home",null),"generic HOME catcher is not a confirmed voice handler");
        check(!NativeVoice.voiceUiTargetAllowed("launcher.app","Home","launcher.app","Home","missing.assistant"),"stale or unrelated assistant setting cannot validate HOME catcher");
        check(NativeVoice.voiceUiTargetAllowed("voice.app","Voice","launcher.app","Home",null),"ordinary dedicated voice handler needs no mandatory Google or new recognizer dependency");
        check(NativeVoice.voiceUiTargetAllowed("launcher.app","Voice","launcher.app","Home",null),"separate voice Activity in launcher package stays allowed");
        check(NativeVoice.voiceUiTargetAllowed("launcher.app","Home","launcher.app","Home","launcher.app"),"installed explicitly selected assistant may share HOME Activity");
        final int[] settingsReads={0};NativeVoice.AssistantLookup deniedSettings=new NativeVoice.AssistantLookup(){public String installedPackage(){settingsReads[0]++;throw new SecurityException("bare app_process has no registered ApplicationThread");}};
        check(NativeVoice.voiceUiTargetWithLookup("voice.app","Voice","launcher.app","Home",deniedSettings)&&settingsReads[0]==0,"dedicated voice handler stays eligible without accessing unavailable Settings");
        check(!NativeVoice.voiceUiTargetWithLookup("launcher.app","Home","launcher.app","Home",deniedSettings)&&settingsReads[0]==1,"unverified HOME is skipped locally when Settings is inaccessible");
        check(NativeVoice.voiceUiTargetWithLookup("another.voice","Listen","launcher.app","Home",deniedSettings)&&settingsReads[0]==1,"failed optional HOME verification cannot block later dedicated candidates");
        check(NativeVoice.voiceUiTargetWithLookup("launcher.app","Home","launcher.app","Home",new NativeVoice.AssistantLookup(){public String installedPackage(){return "launcher.app";}}),"successful installed-assistant verification still permits intentional HOME voice handler");
        check(NativeVoice.voiceUiAmArgs("android.intent.action.VIEW","voice.app/.Ui",null,3).get(6).equals("3"),"explicit capture display retained for supported non-native tests");
        check(NativeVoice.amStartSucceeded(0,"Starting: Intent...\nStatus: ok\nActivity: app/.Ui\n"),"confirmed successful am launch accepted");
        check(NativeVoice.amStartSucceeded(0,"Warning: Activity not started, intent delivered.\r\nStatus: ok\r\n"),"existing Activity delivery accepted, recorder evidence still separate");
        check(!NativeVoice.amStartSucceeded(1,"Status: ok\n"),"nonzero launch exit rejected");
        check(!NativeVoice.amStartSucceeded(0,"Status: timeout\n"),"am timeout not successful");
        check(!NativeVoice.amStartSucceeded(0,"Status: ok\nError: permission denied"),"explicit am error not successful");
        check(!NativeVoice.amStartSucceeded(0,"Status: okay\n"),"partial status cannot claim launch success");
        java.util.List<String> search=NativeVoice.searchAmArgs("search.app/.Ui","literal; $(text) \n query",0);
        check(search.subList(search.size()-3,search.size()).equals(java.util.Arrays.asList("--es","query","literal; $(text) \n query")),"recognized query is one literal argv value, never shell code");
        check(!search.contains("sh")&&!search.contains("su")&&search.get(6).equals("0"),"result search retains current UID and captured display");
        NativeServiceState wire=new NativeServiceState();wire.microphone(false,true);check(wire.poll().id==0x10024,"app acquired microphone");
        NativeVoice ui=new NativeVoice(wire,null);check(!ui.start(),"unprepared UI does not report accepted launch");check(wire.poll().id==0x10026,"unprepared launch explicitly reports VR IDLE");ui.stop();check(wire.poll()==null,"UI stop preserves app microphone lease without fictitious VR status");ui.close();check(wire.poll()==null,"UI close cannot terminate app recorder");
        NativeServiceState ownership=new NativeServiceState();ownership.microphone(false,true);ownership.poll();
        NativeVoice owned=new NativeVoice(ownership,null);
        java.lang.reflect.Field sf=NativeVoice.class.getDeclaredField("uiState");sf.setAccessible(true);VoiceUiState st=(VoiceUiState)sf.get(owned);
        java.lang.reflect.Method ready=NativeVoice.class.getDeclaredMethod("reportUiRunning",long.class);ready.setAccessible(true);
        long first=st.begin(10,-1,-1);st.target(first,10060,"voice.app");st.launched(first,20);st.route(first,10060,7,"voice.app",30);ready.invoke(owned,first);
        check(owned.active(),"verified UI request owns VR state");check(ownership.poll().id==0x10026&&ownership.poll()==null,"VR RUNNING emitted once without duplicate shared MIC start");
        owned.stop();check(!owned.active(),"STOP retires owned VR state");check(ownership.poll().id==0x10026&&ownership.poll()==null,"STOP sends VR IDLE while preserving app-owned MIC");
        ready.invoke(owned,first);owned.stop();check(ownership.poll()==null&&!owned.active(),"late ready and repeated STOP cannot resurrect VR or release app MIC");
        long pending=st.begin(40,-1,-1);st.target(pending,10060,"voice.app");owned.close();
        check(ownership.poll().id==0x10026&&ownership.poll()==null,"close cancels pending launch with VR IDLE only");
        check(!st.launched(pending,50)&&!st.route(pending,10060,8,"voice.app",60),"closed generation rejects late launch/recorder evidence");
        ownership.microphone(false,false);check(ownership.poll().id==0x10023&&ownership.poll()==null,"actual recorder owner alone releases final shared MIC lease");
        NativeServiceState union=new NativeServiceState();union.voiceModule(false,true);check(union.poll().id==0x10026&&union.voiceModuleActive(),"assistant output owns VR RUNNING");
        union.voiceModule(true,true);check(union.poll()==null,"listening joins active output without duplicate module transition");
        union.voiceModule(true,false);check(union.voiceModuleActive()&&union.poll()==null,"listening end cannot clear playing assistant response");
        union.voiceModule(false,false);check(!union.voiceModuleActive()&&union.poll().id==0x10026&&union.poll()==null,"VR IDLE only after final output owner ends");
        union.voiceModule(true,true);union.poll();union.voiceModule(false,true);union.voiceModule(false,false);check(union.voiceModuleActive()&&union.poll()==null,"output end preserves live listening session");
        union.voiceModule(true,false);check(!union.voiceModuleActive()&&union.poll().id==0x10026,"last listening owner ends union");
        union.voiceModule(false,true);union.close();union.voiceModule(false,true);check(!union.voiceModuleActive()&&union.poll()==null,"closed session cannot be resurrected by stale playback callback");
        System.out.println("NativeVoiceQueueTest PASS "+checks+" checks; recognizer/provider acceptance requires Android device");
    }
}
