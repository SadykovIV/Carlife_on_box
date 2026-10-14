package org.carlife.hulab;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Native logical reconnect retains the first real VERSION and cannot reorder old VIDEO after ACK. */
public final class NativeSessionBoundaryTest {
    static final class Transport implements MdTransport {
        final MdWriterRaceTest.BarrierOutput out=new MdWriterRaceTest.BarrierOutput();
        final boolean nativeUsb;
        Transport(boolean n){nativeUsb=n;}
        public InputStream input(){return new ByteArrayInputStream(new byte[0]);}
        public OutputStream output(){return out;}
        public String name(){return nativeUsb?"DIRECT_USB_AOA":"DEV_STREAM_OVER_ADB";}
        public boolean isNativeUsb(){return nativeUsb;}
        public void close(){out.release.countDown();}
        public boolean awaitStopped(long ms){return true;}
    }
    static byte[] version(){return MdWireTest.hex("08011000");}
    public static void main(String[] args)throws Exception {
        final Transport t=new Transport(true);final MdMain.Session s=new MdMain.Session(t);
        s.version=true;s.initialized=true;s.videoGeneration=7;
        s.videos.add(new MdMain.Packet(MdWireTest.hex("000000020000000d000000010000000000020001aa"),7));
        s.writer=new Thread(new Runnable(){public void run(){s.writeLoop();}});s.writer.start();
        if(!t.out.started.await(1,TimeUnit.SECONDS))throw new AssertionError("old VIDEO never entered actual writer");
        s.dispatch(1,0x1800a,new byte[0]);s.dispatch(1,0x18001,version());
        // A source which already crossed its enqueue guard before PAUSE resumes late.
        s.videos.add(new MdMain.Packet(MdWireTest.hex("000000020000000d000000010000000000020001cc"),7));
        s.videos.add(new MdMain.Packet(MdWireTest.hex("000000020000000d000000010000000000020001bb"),s.videoGeneration));
        t.out.release.countDown();if(!t.out.newVideo.await(1,TimeUnit.SECONDS))throw new AssertionError("new generation did not reach writer");
        s.close();s.writer.join(1000);
        if(s.writer.isAlive()||t.out.written.size()!=3)throw new AssertionError("stale VIDEO crossed native boundary");
        int[] ids=new int[3];for(int i=0;i<3;i++)ids[i]=MdMain.readFrame(new DataInputStream(new ByteArrayInputStream(t.out.written.get(i)))).id;
        if(ids[0]!=0x20001||ids[1]!=0x10002||ids[2]!=0x20001)throw new AssertionError("native VERSION_ACK ordering "+Arrays.toString(ids));
        if(s.staleVideoWriteDrops.get()!=1||s.nativeLogicalSession!=2)throw new AssertionError("generation/boundary evidence missing");

        MdMain.Session dev=new MdMain.Session(new Transport(false));dev.dispatch(1,0x18001,version());dev.dispatch(1,0x1800a,new byte[0]);
        try{dev.dispatch(1,0x18001,version());throw new AssertionError("development duplicate version accepted");}catch(IOException expected){}finally{dev.close();}
        MdMain.Session nativeSession=new MdMain.Session(new Transport(true));
        for(int i=0;i<4;i++){
            nativeSession.dispatch(1,0x18001,version());
            if(!nativeSession.version)throw new AssertionError("fresh version lost on reconnect "+i);
            nativeSession.dispatch(1,0x1800a,new byte[0]);
        }
        if(nativeSession.nativeLogicalSession!=4)throw new AssertionError("three logical reconnects not observed");nativeSession.close();
        System.out.println("PASS native paused VERSION boundary across in-flight VIDEO, stale generation rejection, three logical reconnects and unchanged development duplicate-version rejection");
    }
}
