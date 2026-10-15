package org.carlife.hulab;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Native split integration with real queues/workers/writer and an injected Android routing boundary. */
public final class NativeSplitAudioSessionTest {
    static int checks;
    static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    static void await(Callable<Boolean> v,String why)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!v.call()){if(System.nanoTime()>end)throw new AssertionError(why);Thread.sleep(2);}checks++;}
    static class Players extends NativeAudioSessionTest.Playback {
        boolean defer;
        public void start(PlaybackActivity.Observer o){observer=o;if(!defer)emit(lastOutput,lastMedia);}
    }
    static final class Fixture {
        final SplitAudioSourceTest.Platform platform=new SplitAudioSourceTest.Platform();
        final SourceLease global=new SourceLease();
        final AudioPolicySources policy=SplitAudioSourceTest.owner(platform,global);
        final NativeAudioSessionTest.Memory wire=new NativeAudioSessionTest.Memory();
        final NativeAudioSessionTest.Keys keys=new NativeAudioSessionTest.Keys();
        final Players players=new Players();final MdMain.Session session;
        Fixture()throws Exception{this(false);}
        Fixture(final boolean suppliedRoute)throws Exception{session=new MdMain.Session(wire,keys,new MdMain.Session.AudioInputs(policy.factory(CarLifeAudio.Lane.MEDIA),policy.factory(CarLifeAudio.Lane.TTS),policy.lease(CarLifeAudio.Lane.MEDIA),policy.lease(CarLifeAudio.Lane.TTS),policy),players){synchronized void updateRouteStatus(){if(!suppliedRoute)super.updateRouteStatus();}};session.version=true;session.huMajor=2;session.initialized=true;session.audioNegotiated=true;}
        void writer(){session.writer=new Thread(new Runnable(){public void run(){session.writeLoop();}},"test-native-split-writer");session.writer.start();}
        void close()throws Exception{session.close();check(session.awaitTerminalCleanup(1000),"split terminal cleanup includes both lanes and policy");check(policy.retired()&&!global.isHeld(),"split terminal cleanup releases global policy fence");}
    }
    static int id(byte[] p)throws Exception{return NativeAudioSessionTest.id(p);}
    static int channel(byte[] p)throws Exception{return NativeAudioSessionTest.channel(p);}
    static int moduleStatus(byte[] p,int wanted)throws Exception{
        if(channel(p)!=1||id(p)!=0x10026)return -1;
        byte[] payload=Arrays.copyOfRange(p,16,p.length);
        ByteArrayInputStream input=new ByteArrayInputStream(payload);
        while(input.available()>0){int tag=(int)MdMain.Proto.varint(input);if((tag&7)==2){byte[] value=new byte[(int)MdMain.Proto.varint(input)];if(input.read(value)!=value.length)throw new EOFException("test protobuf payload");if((tag>>>3)==2){Map<Integer,Object> row=MdMain.Proto.parse(value);if(MdMain.Proto.number(row,1,-1)==wanted)return MdMain.Proto.number(row,2,-1);}}else if((tag&7)==0)MdMain.Proto.varint(input);else throw new IOException("test module payload wire type");}
        return -1;
    }
    static List<byte[]> packets(NativeAudioSessionTest.Memory wire){synchronized(wire.packets){return new ArrayList<byte[]>(wire.packets);}}
    static int firstId(List<byte[]> packets,int wanted)throws Exception{for(int i=0;i<packets.size();i++)if(id(packets.get(i))==wanted)return i;return -1;}
    static int firstStatus(List<byte[]> packets,int module,int state)throws Exception{for(int i=0;i<packets.size();i++)if(moduleStatus(packets.get(i),module)==state)return i;return -1;}
    public static void main(String[] args)throws Exception{
        MdMain.bootNative=true;MdMain.videoSource="mediacodec";MdMain.audioSource="split";android.os.SystemClock.fixedTime=1000;
        final Fixture idle=new Fixture();idle.players.defer=true;idle.session.maybeStartAudio();
        check(idle.platform.registers==0&&idle.session.audio.queue.size()==0&&idle.session.speech.queue.size()==0,"no initial snapshot cannot seize MEDIA or TTS");
        idle.players.emit(new int[0],new int[0]);check(idle.platform.registers==0,"known idle creates no audio route");
        idle.players.emit(new int[]{1},new int[]{1});await(new Callable<Boolean>(){public Boolean call(){return idle.session.audio.active();}},"media-only snapshot starts MEDIA");
        check(idle.session.speech.retired()&&idle.session.speech.queue.size()==0,"media-only snapshot does not invent speech");
        idle.players.emit(new int[]{2},new int[0]);await(new Callable<Boolean>(){public Boolean call(){return idle.session.audio.retired()&&idle.session.speech.active();}},"speech-only snapshot stops MEDIA and starts independent TTS");
        idle.players.emit(new int[0],new int[0]);await(new Callable<Boolean>(){public Boolean call(){return idle.session.speech.retired()&&idle.policy.retired();}},"fully idle snapshot releases both policy routes");idle.close();

        final Fixture radio=new Fixture();radio.players.lastOutput=new int[]{1,2};radio.players.lastMedia=new int[]{1};radio.session.maybeStartAudio();
        await(new Callable<Boolean>(){public Boolean call(){return radio.session.audio.active()&&radio.session.speech.active();}},"active initial snapshot starts both roles");
        radio.players.defer=true;PlaybackActivity.Observer old=radio.players.observer;
        radio.session.dispatch(1,0x18028,MdMain.Proto.ints(1,3,2,0));radio.session.maybeStartAudio();
        await(new Callable<Boolean>(){public Boolean call(){return radio.session.audio.retired();}},"MUSIC0 retires MEDIA");
        check(radio.session.speech.active()&&radio.platform.unregisters==0,"MUSIC0 preserves live TTS during asynchronous observer rebase");
        old.update(new PlaybackActivity.Snapshot(Collections.<Integer>emptySet(),Collections.<Integer>emptySet()));
        check(radio.session.speech.active()&&radio.session.audioHuMuted,"stale pre-radio snapshot cannot alter either post-radio lane");
        radio.players.emit(new int[]{2},new int[0]);radio.session.dispatch(1,0x18028,MdMain.Proto.ints(1,3,2,1));
        check(radio.session.audioHuMuted&&radio.session.audio.retired()&&radio.session.speech.active(),"MUSIC1 status cannot resume MEDIA or suppress TTS");
        radio.session.dispatch(1,0x1800a,new byte[0]);radio.session.dispatch(1,0x1800b,new byte[0]);
        check(radio.session.speech.active()&&radio.platform.unregisters==0,"VIDEO_PAUSE and RESET leave speech capture alive");
        radio.players.emit(new int[]{1,2},new int[]{1});await(new Callable<Boolean>(){public Boolean call(){return radio.session.audio.active();}},"actual new media start resumes only its role");
        radio.session.dispatch(1,0x18001,MdMain.Proto.ints(1,2,2,0));await(new Callable<Boolean>(){public Boolean call(){return radio.session.audio.retired()&&radio.session.speech.retired();}},"new VERSION retires both logical audio sources");
        check(!radio.session.audioNegotiated&&radio.session.audio.queue.size()==0&&radio.session.speech.queue.size()==0&&!radio.session.speechWireActive,"new VERSION discards both previous audio epochs");radio.close();

        final Fixture ordered=new Fixture();long me=ordered.session.audio.queue.epoch(),te=ordered.session.speech.queue.epoch();
        ordered.session.audio.queue.start(me,1000);ordered.session.speech.queue.start(te,1000);
        for(int i=0;i<3;i++){ordered.session.audio.queue.data(me,new byte[3840],1000+i*20,1000);ordered.session.speech.queue.data(te,new byte[640],1000+i*20,1000);}
        ordered.writer();await(new Callable<Boolean>(){public Boolean call()throws Exception{List<byte[]> p=packets(ordered.wire);int data=0;for(byte[] b:p)if(id(b)==0x30006||id(b)==0x40003)data++;return data==6;}},"both lanes reach native transport");
        List<byte[]> seen=packets(ordered.wire);int navOn=firstStatus(seen,2,1),ttsInit=firstId(seen,0x40001);
        check(navOn>=0&&navOn<ttsInit,"NAVI1 is on wire before first TTS_INIT");
        List<Integer> dataChannels=new ArrayList<Integer>();for(byte[] p:seen)if(id(p)==0x30006||id(p)==0x40003)dataChannels.add(channel(p));
        check(dataChannels.equals(Arrays.asList(3,4,3,4,3,4)),"MEDIA and TTS data alternate without lane starvation");
        ordered.session.speech.queue.stop(te,1000);ordered.session.wake();await(new Callable<Boolean>(){public Boolean call()throws Exception{return firstStatus(packets(ordered.wire),2,0)>=0;}},"TTS stop restores NAVI idle");
        seen=packets(ordered.wire);check(firstId(seen,0x40002)<firstStatus(seen,2,0),"TTS_STOP is written before NAVI0");
        ordered.close();

        final Fixture fence=new Fixture();long oldEpoch=fence.session.speech.queue.epoch();fence.session.speech.queue.start(oldEpoch,1000);final CarLifeAudio.Item oldInit=fence.session.speech.queue.poll(1000);final Thread completion;
        synchronized(fence.session.audio.queue){
            completion=new Thread(new Runnable(){public void run(){fence.session.audioWritten(oldInit);}},"test-stale-speech-completion");completion.setDaemon(true);completion.start();
            await(new Callable<Boolean>(){public Boolean call(){return completion.getState()==Thread.State.BLOCKED;}},"TTS completion waits for status ownership");
            fence.session.resetAudio();
        }
        completion.join(1000);check(!completion.isAlive()&&!fence.session.speechWireActive&&fence.session.pendingNaviStatus==null,"stale TTS INIT completion cannot publish NAVI status into next VERSION");fence.close();

        final Fixture route=new Fixture(true);route.session.routeNavigationActive=true;long routeEpoch=route.session.speech.queue.epoch();route.session.speech.queue.start(routeEpoch,1000);route.writer();
        await(new Callable<Boolean>(){public Boolean call()throws Exception{return firstId(packets(route.wire),0x40001)>=0;}},"route TTS init sent");
        route.session.speech.queue.stop(routeEpoch,1000);route.session.wake();await(new Callable<Boolean>(){public Boolean call()throws Exception{return firstId(packets(route.wire),0x40002)>=0&&route.session.naviWireActive;}},"route speech stop preserves ongoing NAVI");
        check(firstStatus(packets(route.wire),2,0)<0,"ongoing route never emits NAVI0 on prompt end");
        route.session.moduleStatusDue=1;route.session.nativeProgress();await(new Callable<Boolean>(){public Boolean call(){return route.session.controls.isEmpty()&&route.session.pendingNaviStatus==null;}},"late module report drained");
        check(route.session.naviWireActive&&firstStatus(packets(route.wire),2,0)<0,"late module burst must not transiently mark active navigation idle");route.close();

        final Fixture degraded=new Fixture();degraded.players.defer=true;degraded.session.maybeStartAudio();degraded.players.observer.failed("optional observer denied");await(new Callable<Boolean>(){public Boolean call(){return degraded.session.audio.active();}},"degraded basic MEDIA fallback is available");
        check(degraded.session.speech.retired()&&degraded.session.speech.queue.size()==0,"degraded observer does not invent active TTS");
        degraded.session.dispatch(6,0x68008,MdMain.Proto.ints(1,0x20));await(new Callable<Boolean>(){public Boolean call(){return degraded.session.audio.retired();}},"explicit Pause retires split MEDIA under degraded observer");
        degraded.session.dispatch(1,0x18028,MdMain.Proto.ints(1,3,2,1));check(degraded.session.audioHuMuted&&degraded.session.audio.retired(),"status query cannot undo explicit Pause");
        degraded.session.dispatch(6,0x68008,MdMain.Proto.ints(1,0x1f));await(new Callable<Boolean>(){public Boolean call(){return degraded.session.audio.active();}},"explicit Play resumes split MEDIA under degraded observer");degraded.close();
        MdMain.audioSource="off";MdMain.bootNative=false;android.os.SystemClock.fixedTime=-1;
        System.out.println("PASS "+checks+" native split integration checks; device routing is not simulated acceptance");
    }
}
