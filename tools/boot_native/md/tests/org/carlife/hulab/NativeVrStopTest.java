package org.carlife.hulab;

import java.util.concurrent.Callable;

/** Explicit HU cancellation is separate from normal microphone end-of-speech. */
public final class NativeVrStopTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void await(Callable<Boolean> c,String why)throws Exception{NativeVrAudioSessionTest.await(c,why);checks++;}
    static int count(NativeVrAudioSessionTest.Fixture f,int id)throws Exception{int n=0;for(byte[] p:NativeVrAudioSessionTest.packets(f))if(NativeVrAudioSessionTest.id(p)==id)n++;return n;}
    public static void main(String[] args)throws Exception{
        boolean oldBoot=MdMain.bootNative,oldServices=MdMain.nativeServicesEnabled;String oldAudio=MdMain.audioSource;
        MdMain.bootNative=true;MdMain.nativeServicesEnabled=false;MdMain.audioSource="split";MdMain.videoSource="mediacodec";android.os.SystemClock.fixedTime=1000;
        final NativeVrAudioSessionTest.Fixture f=new NativeVrAudioSessionTest.Fixture(true);
        try{
            f.session.maybeStartAudio();f.players.emit(NativeVrAudioSessionTest.set(1),NativeVrAudioSessionTest.set(2),NativeVrAudioSessionTest.set(3));
            await(new Callable<Boolean>(){public Boolean call(){return f.session.audio.active()&&f.session.speech.active()&&f.session.assistant.active();}},"all playback routes active");f.writer();
            await(new Callable<Boolean>(){public Boolean call()throws Exception{return count(f,0x50002)==1;}},"assistant INIT reaches wire");
            NativeServiceState union=NativeServicesSessionTest.state(f.session.services);union.voiceModule(true,true);union.voiceModule(true,false);f.session.wake();
            check(f.session.assistant.active()&&f.session.services.voiceActive()&&!f.session.assistantHuStopped,"natural microphone end preserves assistant answer");
            f.session.dispatch(1,0x18028,MdMain.Proto.ints(1,4,2,0));
            await(new Callable<Boolean>(){public Boolean call()throws Exception{return f.session.assistant.retired()&&count(f,0x50004)==1&&NativeVrAudioSessionTest.status(f,4,0)>=0;}},"HU module4 STOP retires VR and reports IDLE");
            check(NativeVrAudioSessionTest.first(f,0x50004)<NativeVrAudioSessionTest.status(f,4,0),"explicit stop still writes VR_STOP before VR0");
            check(f.session.audio.active()&&f.session.speech.active()&&f.platform.unregisters==0,"explicit VR stop preserves MUSIC/TTS and shared policy");
            for(int i=0;i<5;i++){f.session.maybeStartAudio();f.players.emit(NativeVrAudioSessionTest.set(1),NativeVrAudioSessionTest.set(2),NativeVrAudioSessionTest.set(3));}
            check(f.session.assistantHuStopped&&f.session.assistant.retired()&&count(f,0x50002)==1,"continuing same assistant player cannot auto-resume after HU stop");
            f.players.emit(NativeVrAudioSessionTest.set(1),NativeVrAudioSessionTest.set(2),NativeVrAudioSessionTest.set(4));
            await(new Callable<Boolean>(){public Boolean call()throws Exception{return f.session.assistant.active()&&count(f,0x50002)==2;}},"new real assistant player resumes capture");
            f.session.dispatch(6,0x68008,MdMain.Proto.ints(1,0x22));
            await(new Callable<Boolean>(){public Boolean call()throws Exception{return f.session.assistant.retired()&&count(f,0x50004)==2;}},"VR STOP hardkey shares cancellation semantics");
            f.session.dispatch(6,0x68008,MdMain.Proto.ints(1,0x21));
            await(new Callable<Boolean>(){public Boolean call()throws Exception{return f.session.assistant.active()&&count(f,0x50002)==3;}},"explicit voice START resumes even the same continuing player");
            f.session.explicitAssistantStop();await(new Callable<Boolean>(){public Boolean call(){return f.session.assistant.retired();}},"stop before same-player new playback cycle");
            f.players.emit(NativeVrAudioSessionTest.set(1),NativeVrAudioSessionTest.set(2),NativeVrAudioSessionTest.set());
            f.players.emit(NativeVrAudioSessionTest.set(1),NativeVrAudioSessionTest.set(2),NativeVrAudioSessionTest.set(4));
            await(new Callable<Boolean>(){public Boolean call(){return f.session.assistant.active();}},"observed stop/start of same player is a new real start");
            Thread queued;
            synchronized(f.session){
                queued=new Thread(new Runnable(){public void run(){f.players.emit(NativeVrAudioSessionTest.set(1),NativeVrAudioSessionTest.set(2),NativeVrAudioSessionTest.set(5));}},"test-pre-stop-assistant-callback");queued.setDaemon(true);queued.start();final Thread waiting=queued;
                await(new Callable<Boolean>(){public Boolean call(){return waiting.getState()==Thread.State.BLOCKED;}},"new player snapshot accepted before HU stop but listener queued");f.session.explicitAssistantStop();
            }
            queued.join(1000);await(new Callable<Boolean>(){public Boolean call(){return f.session.assistant.retired();}},"capture retires after raced stop");f.session.maybeStartAudio();
            check(!queued.isAlive()&&f.session.assistantHuStopped&&f.session.assistant.retired(),"already accepted callback cannot undo later HU cancellation");
            f.session.dispatch(1,0x1800b,new byte[0]);check(f.session.assistantHuStopped,"video RESET does not resume explicitly stopped VR");
            check(f.session.audio.active()&&f.session.speech.active(),"all stop/resume scenarios leave media and navigation intact");
            System.out.println("PASS "+checks+" explicit VR STOP/natural mic end/resume/race/audio-isolation checks");
        }finally{f.close();MdMain.bootNative=oldBoot;MdMain.nativeServicesEnabled=oldServices;MdMain.audioSource=oldAudio;android.os.SystemClock.fixedTime=-1;}
    }
}
