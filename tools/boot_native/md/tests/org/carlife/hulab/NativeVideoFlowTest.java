package org.carlife.hulab;

import java.io.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

/** Actual native admission/writer and FPS command completion, no Android codec calls. */
public final class NativeVideoFlowTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static final class Transport implements MdTransport {
        final List<byte[]> writes=Collections.synchronizedList(new ArrayList<byte[]>());
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        volatile boolean block;
        public InputStream input(){return new ByteArrayInputStream(new byte[0]);}
        public OutputStream output(){return new ByteArrayOutputStream();}
        public void writePacket(byte[] p)throws IOException{
            if(block){block=false;entered.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new IOException("test writer timeout");}catch(InterruptedException e){throw new IOException(e);}}
            writes.add(p.clone());
        }
        public String name(){return "DIRECT_USB_AOA";}public boolean isNativeUsb(){return true;}
        public void close(){release.countDown();}public boolean awaitStopped(long ms){return true;}
    }
    static final class FpsSession extends MdMain.Session {
        int requested,applied;boolean supported=true;
        FpsSession(Transport t)throws IOException{super(t);}
        boolean requestSourceFrameRate(int fps){requested=fps;return supported;}
        int sourceFrameRate(){return applied;}
    }
    static void ready(MdMain.Session s){s.version=true;s.huMajor=2;s.initialized=true;s.videoGeneration=7;}
    static void awaitWrites(Transport t,int n)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(t.writes.size()<n&&System.nanoTime()<end)Thread.sleep(2);check(t.writes.size()>=n,"writer progress to "+n);}
    static MdMain.Frame read(byte[] p)throws IOException{return MdMain.readFrame(new DataInputStream(new ByteArrayInputStream(p)));}
    static AvcAccessUnits.Au au(boolean idr,int length,long pts){byte[] b=new byte[length];Arrays.fill(b,(byte)(idr?0x65:0x41));return new AvcAccessUnits.Au(b,pts,0,idr,idr?"7,8,5":"1");}
    public static void main(String[] args)throws Exception{
        MdMain.videoSource="mediacodec";MdMain.audioSource="off";android.os.SystemClock.fixedTime=1000;
        Transport t=new Transport();final MdMain.Session s=new MdMain.Session(t);ready(s);
        long began=System.nanoTime();
        for(int i=0;i<20;i++)s.sendAccessUnit(au(i==0,65536,1000000L+i*33333),7);
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)<300,"overflow never waits for the old one-second enqueue timeout");
        check(s.open&&s.nativeVideos.snapshot(1000).bytes<=VideoQueue.MAX_BYTES,"pressure keeps session alive and memory bounded");
        check(s.nativeVideos.snapshot(1000).awaitingIdr,"dependent chain lost: wait for a new IDR");
        s.sendAccessUnit(au(false,100,2000000),7);check(s.nativeVideos.isEmpty(),"dependent P cannot escape after overflow");
        // A real independent libx264 AU, preserving original SPS/PPS bytes.
        AvcAccessUnits parser=new AvcAccessUnits();
        AvcAccessUnits.Au recovery=parser.accept(HexFixture.read(Paths.get("android-md/tests/fixtures/libx264-baseline31-au.h264")),2100000,0);
        s.sendAccessUnit(recovery,7);check(!s.nativeVideos.isEmpty(),"original IDR+CSD admits recovery");
        s.sendAccessUnit(au(false,100,2200000),6);check(s.staleVideoEnqueueDrops.get()==1,"late prior-generation source rejected");
        s.writer=new Thread(new Runnable(){public void run(){s.writeLoop();}});s.writer.start();awaitWrites(t,1);
        check(Arrays.equals(read(t.writes.get(0)).payload,recovery.bytes),"first packet after loss is exact current IDR with CSD");
        s.close();s.writer.join(1000);check(!s.writer.isAlive(),"close retires actual writer");

        // An already-started packet finishes before RESET/VERSION; no partial USB packet is abandoned.
        Transport held=new Transport();held.block=true;final MdMain.Session boundary=new MdMain.Session(held);ready(boundary);
        boundary.sendAccessUnit(recovery,7);boundary.writer=new Thread(new Runnable(){public void run(){boundary.writeLoop();}});boundary.writer.start();
        check(held.entered.await(1,TimeUnit.SECONDS),"real writer holds first old-generation packet");
        boundary.dispatch(1,0x1800a,new byte[0]);boundary.dispatch(1,0x18001,MdMain.Proto.ints(1,2,2,0));
        long generation=boundary.videoGeneration;
        boundary.sendAccessUnit(au(false,100,2300000),7);
        boundary.sendAccessUnit(recovery,generation);held.release.countDown();awaitWrites(held,6);
        check(read(held.writes.get(0)).channel==2&&read(held.writes.get(1)).id==0x10002,"in-flight old AU precedes new VERSION reply");
        int videos=0;for(byte[] bytes:held.writes)if(read(bytes).channel==2)videos++;
        check(videos==2&&boundary.open,"only old in-flight and current recovery AU, no stale tail");boundary.close();boundary.writer.join(1000);

        FpsSession fps=new FpsSession(new Transport());ready(fps);fps.dispatch(1,0x1800c,MdMain.Proto.ints(1,15));
        check(fps.requested==15&&fps.controls.isEmpty(),"FPS request queues apply, no premature DONE");
        fps.applied=15;fps.completeFrameRateChange();check(read(fps.controls.remove().data).id==0x1000d&&fps.acceptedFps==15,"DONE follows actual applied FPS");
        fps.dispatch(1,0x1800c,MdMain.Proto.ints(1,30));fps.videoGeneration++;fps.applied=30;fps.completeFrameRateChange();check(fps.controls.isEmpty(),"RESET cancels old-generation FPS ACK");
        fps.supported=false;fps.dispatch(1,0x1800c,MdMain.Proto.ints(1,20));check(fps.controls.isEmpty(),"unsupported pacing cannot claim success");
        fps.supported=true;fps.codecSelection=new MediaCodecDisplaySource.Selection("TEST","repeat-previous-frame-after","max-fps-to-encoder",1280,720,30);
        fps.requested=0;fps.dispatch(1,0x1800c,MdMain.Proto.ints(1,60));check(fps.requested==0&&fps.controls.isEmpty(),"HU FPS cannot exceed accepted encoder capacity");
        fps.codecSelection=new MediaCodecDisplaySource.Selection("TEST","repeat-previous-frame-after","max-fps-to-encoder",1280,720,60);
        fps.applied=30;fps.dispatch(1,0x1800c,MdMain.Proto.ints(1,60));check(fps.requested==60&&fps.controls.isEmpty(),"60 FPS admitted only after adequate encoder selection; waits actual pacing");
        fps.applied=60;fps.completeFrameRateChange();check(read(fps.controls.remove().data).id==0x1000d&&fps.acceptedFps==60,"60 FPS command ACK follows pacing confirmation");
        fps.dispatch(1,0x1800c,MdMain.Proto.ints(1,15));fps.applied=15;fps.completeFrameRateChange();check(read(fps.controls.remove().data).id==0x1000d&&fps.acceptedFps==15,"HU can reduce manually selected60 FPS");fps.close();
        // Native protocol v1 uses the same bounded AU admission, independent of handshake features.
        MdMain.Session v1=new MdMain.Session(new Transport());ready(v1);v1.huMajor=1;
        AvcAccessUnits.Au scene=au(false,550784,2400000);
        v1.sendAccessUnit(scene,7);check(v1.nativeVideos.isEmpty()&&v1.nativeVideos.snapshot(1000).awaitingIdr,"large P cannot start native v1 decoder without real IDR");
        v1.sendAccessUnit(recovery,7);check(v1.videos.isEmpty()&&v1.nativeVideos.poll(7,1000)!=null,"native v1 uses bounded native queue rather than legacy blocking queue");
        v1.sendAccessUnit(scene,7);
        MdMain.Packet scenePacket=v1.nativeVideos.poll(7,1000);
        check(scenePacket!=null&&Arrays.equals(read(scenePacket.data).payload,scene.bytes)&&v1.open,"native v1 large scene-change P reaches unchanged wire");
        v1.sendAccessUnit(au(false,AvcAccessUnits.MAX_AU,2500000),7);
        check(v1.nativeVideos.snapshot(1000).bytes==AvcAccessUnits.MAX_AU+20,"maximum completeAU is bounded one packet");
        v1.videoGeneration++;check(v1.nativeVideos.poll(8,1000)==null,"RESET cancels pending largeAU");
        v1.sendAccessUnit(scene,8);check(v1.nativeVideos.isEmpty(),"RESET-cleared native queue rejects even valid large P until current IDR");
        v1.sendAccessUnit(recovery,8);check(v1.nativeVideos.poll(8,1000)!=null,"real current-generation IDR restores native chain after RESET");v1.close();
        android.os.SystemClock.fixedTime=-1;
        System.out.println("PASS "+checks+" native video admission/recovery/writer/FPS completion checks; no hardware claim");
    }
}
