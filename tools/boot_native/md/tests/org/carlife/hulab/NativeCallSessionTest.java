package org.carlife.hulab;

import java.io.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** Actual session writer and workers with injected platform endpoints, never an Android call. */
public final class NativeCallSessionTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static void await(Callable<Boolean> test,String message)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!test.call()){if(System.nanoTime()>end)throw new AssertionError(message);Thread.sleep(2);}checks++;}
    static final class Platform implements NativeCallAudio.Platform {
        volatile int mode,opens,downClosed,upClosed,writes;volatile boolean muted,failOpen,failClose;volatile byte[] last;
        public int mode(){return mode;}
        public boolean microphoneMuted(){return muted;}
        public NativeCallAudio.Downlink openDownlink(){opens++;return new NativeCallAudio.Downlink(){
            public void start(){}
            public int read(byte[] b,int o,int n){Arrays.fill(b,o,o+n,(byte)13);return n;}
            public void close(){downClosed++;}
        };}
        public NativeCallAudio.Uplink openUplink()throws Exception{opens++;if(failOpen)throw new IOException("test open failure");return new NativeCallAudio.Uplink(){
            public void start(){}
            public int write(byte[] b,int o,int n){last=Arrays.copyOfRange(b,o,o+n);writes++;return n;}
            public void flush(){}
            public void close()throws Exception{upClosed++;if(failClose)throw new IOException("test uncertain cleanup");}
        };}
    }
    static final class Fixture {
        final NativeAudioSessionTest.Memory wire=new NativeAudioSessionTest.Memory();
        final NativeAudioSessionTest.Playback players=new NativeAudioSessionTest.Playback();
        final Platform platform=new Platform();final SourceLease lease=new SourceLease();
        final MdMain.Session session;NativeCallSession call;
        Fixture()throws Exception{
            session=new MdMain.Session(wire,new NativeAudioSessionTest.Keys(),new PcmAudioSession.Factory(){public PcmAudioSession.Source create(){return new PcmAudioSessionTest.Fake();}},players);
            session.version=true;session.huMajor=2;session.initialized=true;session.audioNegotiated=true;session.services=new NativeServices(null);
            session.maybeStartAudio();
        }
        void start(){call=session.startCalls(new NativeCallAudio(platform,lease));}
        void writer(){session.writer=new Thread(()->session.writeLoop(),"test-call-writer");session.writer.start();}
        List<byte[]> packets(){synchronized(wire.packets){return new ArrayList<byte[]>(wire.packets);}}
        int first(int id)throws Exception{List<byte[]> packets=packets();for(int i=0;i<packets.size();i++)if(NativeAudioSessionTest.id(packets.get(i))==id)return i;return -1;}
        int last(int id)throws Exception{List<byte[]> packets=packets();for(int i=packets.size()-1;i>=0;i--)if(NativeAudioSessionTest.id(packets.get(i))==id)return i;return -1;}
        int status(int module,int value)throws Exception{return NativeSplitAudioSessionTest.firstStatus(packets(),module,value);}
        void close()throws Exception{session.close();check(session.awaitTerminalCleanup(2000),"call + media + writer retirement confirmed");}
    }
    public static void main(String[] args)throws Exception{
        MdMain.bootNative=true;MdMain.nativeServicesEnabled=false;MdMain.audioSource="remote-submix";MdMain.videoSource="mediacodec";
        android.os.SystemClock.fixedTime=-1;
        final Fixture idle=new Fixture();idle.start();Thread.sleep(150);check(idle.platform.opens==0&&!idle.call.holding(),"NORMAL does not intercept or hold sources");idle.platform.mode=1;Thread.sleep(150);check(idle.platform.opens==0,"RINGTONE does not invent a conversation");idle.close();

        final Fixture live=new Fixture();live.players.emit(new int[]{1},new int[]{1});live.writer();
        await(()->live.first(CarLifeAudio.DATA)>=0,"ordinary music begins");
        live.platform.mode=3;live.start();
        await(()->live.first(0x10024)>=0&&live.call.streaming(),"call pair starts and MIC command reaches transport");
        check(live.platform.opens==2&&live.session.audio.retired()&&live.lease.isHeld(),"one pair opens after ordinary capture retirement");
        check(live.first(CarLifeAudio.STOP)<live.status(4,1),"ordinary MEDIA STOP completes before call VR admission");
        int callInit=live.last(CarLifeAudio.INIT);
        check(live.status(4,1)<callInit&&callInit<live.first(0x10024),"call order VR1 -> new MEDIA_INIT -> MIC_START");
        check(live.first(0x50002)<0&&live.first(0x50003)<0,"call never uses half-duplex VR PCM");
        check(live.status(1,1)<0&&live.status(1,2)<0,"call does not activate Honda PHONE priority");
        byte[] mic=new byte[640];Arrays.fill(mic,(byte)7);live.session.dispatch(5,0x58001,mic,null,123);
        await(()->live.platform.writes>0,"MIC enters current Android uplink");check(live.platform.last[0]==7,"uplink PCM preserved");
        live.platform.muted=true;int writes=live.platform.writes;live.session.dispatch(5,0x58001,mic,null,124);
        await(()->live.platform.writes>writes,"muted MIC consumed");check(live.platform.last[0]==0&&mic[0]==7,"Android microphone mute zeroes call without mutating ingress");
        live.session.dispatch(1,0x18028,MdMain.Proto.ints(1,4,2,0));live.session.muteAudio();
        check(live.call.streaming()&&live.session.services.voiceActive(),"unrelated assistant/music STOP cannot retire call owner");
        live.session.dispatch(1,0x18052,CarLifeAudioTest.feature(1));check(live.call.streaming(),"Bluetooth feature cannot redirect USB-only call");
        live.session.dispatch(1,0x1800a,new byte[0]);live.session.dispatch(1,0x1800b,new byte[0]);
        check(live.call.streaming()&&live.platform.opens==2,"video RESET preserves active call pair and fixes");
        live.platform.mode=0;
        await(()->!live.call.holding()&&live.status(4,0)>=0,"mode exit releases wire and Android pair");
        check(live.first(0x10023)<live.last(CarLifeAudio.STOP)&&live.last(CarLifeAudio.STOP)<live.status(4,0),"teardown MIC_END -> MEDIA_STOP -> VR0");
        check(live.platform.downClosed==1&&live.platform.upClosed==1&&!live.lease.isHeld(),"both directions closed on mode exit");
        check(!live.session.callHold&&!live.session.callMediaWireActive,"normal ownership restored");
        live.session.audioBluetooth=false;live.session.explicitMediaPlay();
        await(()->live.session.audio.active(),"normal music resumes after call ownership retirement");
        live.close();

        final Fixture waiting=new Fixture();waiting.players.emit(new int[]{2},new int[]{2});
        await(()->waiting.session.audio.active(),"queued ordinary source active");waiting.platform.mode=3;waiting.start();
        await(()->waiting.session.callHold,"waiting takeover has requested predecessor retirement");
        check(waiting.platform.opens==0,"queued ordinary STOP must complete before opening call endpoints");
        waiting.platform.mode=2;waiting.writer();
        await(()->waiting.call.streaming(),"nonzero family transition while waiting cannot wedge owner");check(waiting.call.family()==1,"opens newest PSTN family, not stale VoIP");
        waiting.platform.mode=3;
        await(()->waiting.platform.opens==4&&waiting.call.streaming(),"midstream family transition fully reopens pair");
        check(waiting.platform.downClosed==1&&waiting.platform.upClosed==1,"family switch retires both old endpoints first");
        waiting.close();

        final Fixture canceled=new Fixture();canceled.players.emit(new int[]{3},new int[]{3});await(()->canceled.session.audio.active(),"cancel fixture predecessor active");canceled.platform.mode=3;canceled.start();
        await(()->canceled.session.callHold,"waiting cancel hold");canceled.platform.mode=0;
        await(()->!canceled.call.holding()&&!canceled.session.callHold,"mode ends before ready releases takeover");check(canceled.platform.opens==0&&canceled.first(0x10024)<0,"no transient endpoints/MIC demand before admission");canceled.close();

        final Fixture reset=new Fixture();reset.platform.mode=3;reset.start();await(()->reset.call.streaming()&&reset.call.queue.size()>0,"pair can prepare before USB writer starts");
        final CarLifeAudio.Item oldInit=reset.call.queue.poll(System.nanoTime()/1000000L);
        check(oldInit!=null&&oldInit.id==CarLifeAudio.INIT,"call INIT queued");
        reset.session.dispatch(1,0x18001,MdMain.Proto.ints(1,2,2,0));
        check(reset.call.streaming(),"same VERSION retransmission retains existing call as normal session contract requires");
        reset.session.dispatch(1,0x1800a,new byte[0]);
        reset.session.dispatch(1,0x18001,MdMain.Proto.ints(1,2,2,0));
        await(()->reset.call.retired(),"new logical VERSION closes worker and pair");
        check(!reset.call.current(oldInit)&&!reset.session.audio.queue.current(oldInit),"old call item cannot cross VERSION or collide with ordinary MEDIA epoch");
        reset.session.audioWritten(oldInit);check(!reset.session.callMediaWireActive&&!reset.session.audioWireActive,"late completion cannot activate replacement session");
        reset.close();

        final Fixture race=new Fixture();race.platform.mode=3;race.start();await(()->race.call.streaming()&&race.call.queue.size()>0,"race call initialized");
        final CarLifeAudio.Item late=race.call.queue.poll(System.nanoTime()/1000000L);final Thread completion;
        synchronized(race.session.audio.queue){
            completion=new Thread(()->race.session.audioWritten(late),"test-late-call-init-completion");completion.setDaemon(true);completion.start();
            await(()->completion.getState()==Thread.State.BLOCKED,"call INIT completion waits on session epoch owner");
            race.session.resetAudio();
        }
        completion.join(1000);check(!completion.isAlive()&&!race.session.callMediaWireActive&&!race.session.audioWireActive,"late call INIT cannot resurrect audio after logical reset");race.close();

        final Fixture status=new Fixture();status.platform.mode=3;status.start();
        await(()->status.call.streaming()&&status.call.queue.size()>0,"status regression call initialized");
        CarLifeAudio.Item statusInit=status.call.queue.poll(System.nanoTime()/1000000L);
        status.session.audioWritten(statusInit);
        check(status.session.pendingMusicStatus!=null&&status.session.pendingMusicStatus.active,"call INIT announces MUSIC once");
        MdMain.Session.MusicStatus retained=status.session.pendingMusicStatus;
        CarLifeAudio.Item data=new CarLifeAudio.Item(status.call.queue,CarLifeAudio.MEDIA,CarLifeAudio.DATA,new byte[3840],0,0,status.call.queue.epoch());
        status.session.audioWritten(data);
        check(status.session.pendingMusicStatus==retained,"PCM completion preserves an already coalesced state edge");
        long writtenBefore=status.call.queue.written;
        for(int i=0;i<50;i++){
            status.session.pendingMusicStatus=null; // writer drained the previous control slot
            status.session.audioWritten(data);
            check(status.session.pendingMusicStatus==null,"drained PCM completion does not enqueue 50Hz MUSIC status");
        }
        check(status.call.queue.written==writtenBefore+50,"PCM packets remain counted without control amplification");
        status.call.queue.stop(status.call.queue.epoch(),System.nanoTime()/1000000L);
        status.session.audioWritten(status.call.queue.poll(System.nanoTime()/1000000L));
        check(status.session.pendingMusicStatus!=null&&!status.session.pendingMusicStatus.active,"call STOP still announces MUSIC inactive");
        status.close();

        final Fixture failing=new Fixture();failing.platform.mode=3;failing.platform.failOpen=true;failing.start();failing.writer();
        await(()->failing.platform.downClosed==1&&!failing.call.holding(),"partial pair failure restores ownership");
        check(!failing.session.callHold&&!failing.lease.isHeld()&&failing.first(0x10024)<0&&failing.first(CarLifeAudio.INIT)<0,"failure emits no call INIT/MIC and releases downlink");failing.close();

        final Fixture bounded=new Fixture();bounded.platform.mode=3;bounded.start();bounded.writer();await(()->bounded.first(0x10024)>=0,"bounded ingress call ready");
        for(int i=0;i<100;i++)bounded.call.onMic(new byte[32000],i);
        check(bounded.call.droppedMicrophoneBytes()>0,"MIC overload sheds old samples instead of growing latency");
        bounded.session.close();check(bounded.session.awaitTerminalCleanup(2000)&&bounded.platform.upClosed==1&&bounded.platform.downClosed==1,"USB loss immediately retires pair and worker");
        System.out.println("NativeCallSessionTest PASS "+checks+" worker/writer/owner/order/RESET checks; generated fixtures are not a real SIM/VoIP call acceptance");
    }
}
