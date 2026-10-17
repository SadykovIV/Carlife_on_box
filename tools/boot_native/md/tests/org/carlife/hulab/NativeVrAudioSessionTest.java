package org.carlife.hulab;

import java.util.*;
import java.util.concurrent.*;

/** Third playback lane: real capture workers/queues/writer, injected Android policy. */
public final class NativeVrAudioSessionTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void await(Callable<Boolean> condition,String why)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.call()){if(System.nanoTime()>end)throw new AssertionError(why);Thread.sleep(2);}checks++;}
    static Set<Integer> set(Integer... ids){return new HashSet<Integer>(Arrays.asList(ids));}
    static final class Players implements PlaybackActivity.Source {
        PlaybackActivity.Observer observer;boolean defer;
        PlaybackActivity.Snapshot current=new PlaybackActivity.Snapshot(set(),set(),set(),set());
        public void start(PlaybackActivity.Observer value){observer=value;if(!defer)value.update(current);}
        public void close(){}
        void emit(Set<Integer> media,Set<Integer> tts,Set<Integer> vr){Set<Integer> all=new HashSet<Integer>(media);all.addAll(tts);all.addAll(vr);current=new PlaybackActivity.Snapshot(all,media,tts,vr);observer.update(current);}
    }
    static final class Fixture {
        final SplitAudioSourceTest.Platform platform=new SplitAudioSourceTest.Platform();
        final SourceLease global=new SourceLease();
        final AudioPolicySources policy=SplitAudioSourceTest.owner(platform,global);
        final NativeAudioSessionTest.Memory wire=new NativeAudioSessionTest.Memory();
        final Players players=new Players();final MdMain.Session session;
        Fixture(boolean services)throws Exception{
            session=new MdMain.Session(wire,new NativeAudioSessionTest.Keys(),new MdMain.Session.AudioInputs(policy.factory(CarLifeAudio.Lane.MEDIA),policy.factory(CarLifeAudio.Lane.TTS),policy.factory(CarLifeAudio.Lane.VR),policy.lease(CarLifeAudio.Lane.MEDIA),policy.lease(CarLifeAudio.Lane.TTS),policy.lease(CarLifeAudio.Lane.VR),policy),players);
            session.version=true;session.huMajor=2;session.initialized=true;session.audioNegotiated=true;
            if(services)session.services=new NativeServices(null);
        }
        void writer(){session.writer=new Thread(new Runnable(){public void run(){session.writeLoop();}},"test-three-lane-writer");session.writer.start();}
        void close()throws Exception{session.close();check(session.awaitTerminalCleanup(1000),"terminal cleanup includes VR source and writer");check(policy.retired()&&!global.isHeld(),"all three policy leases retired");}
    }
    static List<byte[]> packets(Fixture f){return NativeSplitAudioSessionTest.packets(f.wire);}
    static int id(byte[] p)throws Exception{return NativeAudioSessionTest.id(p);}
    static int first(Fixture f,int id)throws Exception{return NativeSplitAudioSessionTest.firstId(packets(f),id);}
    static int status(Fixture f,int module,int state)throws Exception{return NativeSplitAudioSessionTest.firstStatus(packets(f),module,state);}
    public static void main(String[] args)throws Exception{
        boolean oldBoot=MdMain.bootNative,oldServices=MdMain.nativeServicesEnabled;String oldAudio=MdMain.audioSource;
        MdMain.bootNative=true;MdMain.nativeServicesEnabled=false;MdMain.audioSource="split";MdMain.videoSource="mediacodec";android.os.SystemClock.fixedTime=1000;
        try{
            Set<Integer> usages=new HashSet<Integer>();for(CarLifeAudio.Lane lane:CarLifeAudio.Lane.values())for(int usage:AudioUsage.forLane(lane))check(usages.add(usage),"each Android usage belongs to exactly one AudioMix");
            check(Arrays.equals(AudioUsage.forLane(CarLifeAudio.Lane.VR),new int[]{16}),"only USAGE_ASSISTANT routes to VR");
            check(Arrays.equals(AudioUsage.forLane(CarLifeAudio.Lane.TTS),new int[]{12}),"navigation remains isolated from assistant");
            check(!usages.contains(2)&&!usages.contains(3)&&!usages.contains(7),"voice call and microphone sources not captured as playback");
            PlaybackActivity.Snapshot legacy=new PlaybackActivity.Snapshot(set(1,2),set(1));check(legacy.speech.equals(set(2))&&legacy.assistant.isEmpty(),"legacy two-set fixtures retain speech classification");
            try{new PlaybackActivity.Snapshot(set(1),set(1),set(),set(1));throw new AssertionError("overlapping classification accepted");}catch(IllegalArgumentException expected){checks++;}
            Map<Integer,Object> init=MdMain.Proto.parse(CarLifeAudio.Lane.VR.init());
            check(MdMain.Proto.number(init,1,0)==16000&&MdMain.Proto.number(init,2,0)==1&&MdMain.Proto.number(init,3,0)==16,"VR actual capture and declared PCM are16k mono16bit");

            final Fixture live=new Fixture(true);live.players.defer=true;live.session.maybeStartAudio();
            await(new Callable<Boolean>(){public Boolean call(){return live.session.audio.active();}},"unknown playback warms only MEDIA capture");
            check(live.platform.registers==1&&!live.session.playback.assistantActive()&&live.session.assistant.retired()&&live.session.speech.retired()&&live.session.audio.queue.size()==0&&live.session.assistant.queue.size()==0&&live.session.speech.queue.size()==0,"unknown playback opens no MEDIA wire and cannot seize assistant or speech lane");
            live.players.emit(set(1),set(2),set(3));
            await(new Callable<Boolean>(){public Boolean call(){return live.session.audio.queue.size()==3&&live.session.speech.queue.size()==3&&live.session.assistant.queue.size()==3;}},"three independently formatted workers captured two chunks each");
            check(live.platform.registers==1&&live.platform.records.size()==3,"one policy registers all three disjoint sinks once");
            live.writer();await(new Callable<Boolean>(){public Boolean call()throws Exception{int count=0;for(byte[] p:packets(live))if(id(p)==0x30006||id(p)==0x40003||id(p)==0x50003)count++;return count==6;}},"all distinct PCM sources reach transport");
            List<Integer> dataChannels=new ArrayList<Integer>();for(byte[] p:packets(live))if(id(p)==0x30006||id(p)==0x40003||id(p)==0x50003){int ch=NativeAudioSessionTest.channel(p);dataChannels.add(ch);check(p[20]==(byte)((ch-2)*17),"lane carries only its own source samples");check(p.length==20+(ch==3?3840:640),"wire payload keeps native lane format");}
            check(dataChannels.equals(Arrays.asList(3,4,5,3,4,5)),"three-lane round robin has no starvation or PCM duplication");
            check(status(live,4,1)>=0&&status(live,4,1)<first(live,0x50002),"union VR1 is written before VR_INIT");
            check(status(live,2,1)>=0&&status(live,2,1)<first(live,0x40001),"TTS still announces NAVI before its INIT");
            check(first(live,0x58001)<0,"outgoing assistant cannot be confused with microphone uplink");
            NativeServiceState union=NativeServicesSessionTest.state(live.session.services);union.voiceModule(true,true);
            live.players.emit(set(1),set(2),set());
            await(new Callable<Boolean>(){public Boolean call()throws Exception{return first(live,0x50004)>=0;}},"real assistant playback stop writes VR_STOP");
            check(live.session.audio.active()&&live.session.speech.active()&&live.platform.unregisters==0,"assistant stop leaves media and navigation capture alive");
            check(live.session.services.voiceActive()&&status(live,4,0)<0,"UI recognition owner prevents VR0 after playback stop");
            union.voiceModule(true,false);live.session.wake();await(new Callable<Boolean>(){public Boolean call()throws Exception{return status(live,4,0)>=0;}},"last voice owner emits IDLE");
            check(first(live,0x50004)<status(live,4,0),"STOP completed before final VR0");
            live.players.emit(set(1),set(2),set(3));await(new Callable<Boolean>(){public Boolean call(){return live.session.assistant.active();}},"assistant can restart under the same audio policy");
            PlaybackActivity.Observer stale=live.players.observer;live.players.defer=true;
            live.session.dispatch(1,0x18028,MdMain.Proto.ints(1,3,2,0));
            check(live.session.assistant.active()&&live.session.speech.active()&&live.session.playback.assistantActive(),"radio pause preserves both confirmed spoken lanes while observer rebases");
            stale.update(new PlaybackActivity.Snapshot(set(),set(),set(),set()));check(live.session.assistant.active(),"old observer cannot stop current assistant route");
            live.session.dispatch(1,0x1800a,new byte[0]);live.session.dispatch(1,0x1800b,new byte[0]);
            check(live.session.assistant.active()&&live.session.speech.active(),"video PAUSE/RESET do not reset speech playback ownership");
            live.session.dispatch(1,0x18001,MdMain.Proto.ints(1,2,2,0));
            await(new Callable<Boolean>(){public Boolean call(){return live.session.audio.retired()&&live.session.speech.retired()&&live.session.assistant.retired();}},"new logical VERSION retires all sources");
            check(!live.session.assistantAnnounced&&!live.session.assistantWireActive&&live.session.assistant.queue.size()==0&&!union.voiceModuleActive(),"VERSION clears VR epoch and both service owners");live.close();

            final Fixture fallback=new Fixture(false);long epoch=fallback.session.assistant.queue.epoch();fallback.session.assistant.queue.start(epoch,1000);fallback.writer();
            await(new Callable<Boolean>(){public Boolean call()throws Exception{return first(fallback,0x50002)>=0;}},"VR works when optional Android metadata services are disabled");
            check(status(fallback,4,1)<first(fallback,0x50002),"fallback status also precedes INIT");fallback.session.assistant.queue.stop(epoch,1000);fallback.session.wake();
            await(new Callable<Boolean>(){public Boolean call()throws Exception{return status(fallback,4,0)>=0;}},"fallback VR0 follows STOP");check(first(fallback,0x50004)<status(fallback,4,0),"fallback ordering retained");fallback.close();

            final Fixture fence=new Fixture(true);long oldEpoch=fence.session.assistant.queue.epoch();fence.session.assistant.queue.start(oldEpoch,1000);final CarLifeAudio.Item oldInit=fence.session.assistant.queue.poll(1000);final Thread completion;
            synchronized(fence.session.audio.queue){completion=new Thread(new Runnable(){public void run(){fence.session.audioWritten(oldInit);}},"test-late-vr-completion");completion.setDaemon(true);completion.start();await(new Callable<Boolean>(){public Boolean call(){return completion.getState()==Thread.State.BLOCKED;}},"late completion blocks on audio epoch owner");fence.session.resetAudio();}
            completion.join(1000);check(!completion.isAlive()&&!fence.session.assistantWireActive&&!fence.session.assistantAnnounced&&!fence.session.services.voiceActive(),"stale VR_INIT completion cannot activate next epoch");fence.close();
            System.out.println("PASS "+checks+" real three-lane writer/worker/VR-union/RESET/lease checks; Android-HU acceptance requires device");
        }finally{MdMain.bootNative=oldBoot;MdMain.nativeServicesEnabled=oldServices;MdMain.audioSource=oldAudio;android.os.SystemClock.fixedTime=-1;}
    }
}
