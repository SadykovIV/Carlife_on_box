package org.carlife.hulab;
import java.io.*;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;

/** Real Session EOF/writer termination and owner cleanup; no device calls. */
public final class NativeOwnerRetirementTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static final class Memory implements MdTransport {
        final boolean nativeUsb;final InputStream input;volatile boolean closed,quiescent=true,failWrite;
        final CountDownLatch writeFailed=new CountDownLatch(1);
        Memory(boolean nativeUsb,InputStream input){this.nativeUsb=nativeUsb;this.input=input;}
        public InputStream input(){return input;}
        public OutputStream output(){return new ByteArrayOutputStream();}
        public void writePacket(byte[] packet)throws IOException{if(failWrite){writeFailed.countDown();throw new IOException("fixture USB write error");}}
        public String name(){return nativeUsb?"DIRECT_USB_AOA":"DEV_STREAM_OVER_ADB";}
        public boolean isNativeUsb(){return nativeUsb;}
        public void close(){closed=true;}
        public boolean awaitStopped(long ms){return closed&&quiescent;}
    }
    static Memory memory(boolean nativeUsb,int major)throws Exception {
        return new Memory(nativeUsb,new ByteArrayInputStream(MdMain.packet(1,0x18001,MdMain.Proto.ints(1,major,2,0),0)));
    }
    static MdMain.Session accepted(boolean nativeUsb,int major)throws Exception {
        MdMain.Session s=new MdMain.Session(memory(nativeUsb,major));s.dispatch(1,0x18001,MdMain.Proto.ints(1,major,2,0));return s;
    }
    static void restore(){MdMain.bootNative=true;MdMain.running=true;}
    public static void main(String[] args)throws Exception {
        MdMain.videoSource="mediacodec";restore();
        for(int major:new int[]{1,2}){
            Memory t=memory(true,major);MdMain.Session s=new MdMain.Session(t);
            s.run();check(s.everNegotiated&&"peer_eof".equals(s.terminalReason),"actual accepted VERSION followed by EOF records sticky negotiation");
            MdMain.finishSessionOwner(s,t);
            check(!MdMain.running&&t.closed&&s.closeComplete&&!s.writer.isAlive(),"negotiated boot owner stops, permitting no next fd, v"+major);
            restore();
        }
        Memory rejected=memory(true,0);MdMain.Session rs=new MdMain.Session(rejected);rs.run();MdMain.finishSessionOwner(rs,rejected);
        check(!rs.everNegotiated&&MdMain.running,"rejected VERSION never grants negotiated recovery");

        final int[] reads={0};
        Memory idle=new Memory(true,new InputStream(){public int read()throws IOException{if(reads[0]++==0)throw new SocketTimeoutException("pre-AOA idle");return -1;}});
        MdMain.Session is=new MdMain.Session(idle);is.run();MdMain.finishSessionOwner(is,idle);
        check(reads[0]>=2&&!is.everNegotiated&&MdMain.running,"boundary idle and pre-negotiation EOF retain existing wait/reopen policy");

        MdMain.Session live=accepted(true,2);live.initialized=true;live.dispatch(1,0x1800a,new byte[0]);
        check(live.open&&MdMain.running&&live.everNegotiated,"PAUSE alone is not terminal");
        live.dispatch(1,0x18001,MdMain.Proto.ints(1,2,2,1));
        check(live.open&&live.everNegotiated&&live.nativeLogicalSession==2&&MdMain.running,"fresh logical VERSION after PAUSE stays on live owner");
        live.dispatch(1,0x1800b,new byte[0]);check(live.open&&live.everNegotiated&&MdMain.running,"RESET does not retire negotiated owner");
        live.close();MdMain.finishSessionOwner(live,live.transport);restore();

        MdMain.Session dev=accepted(false,1);MdMain.finishSessionOwner(dev,dev.transport);
        check(MdMain.running,"DEV accepted session retains baseline outer loop");
        MdMain.bootNative=false;MdMain.Session lab=accepted(true,1);MdMain.finishSessionOwner(lab,lab.transport);
        check(MdMain.running,"non-boot native laboratory session retains baseline policy");restore();

        Memory badWrite=memory(true,2);badWrite.failWrite=true;final MdMain.Session ws=new MdMain.Session(badWrite);
        ws.dispatch(1,0x18001,MdMain.Proto.ints(1,2,2,0));ws.writer=new Thread(new Runnable(){public void run(){ws.writeLoop();}});ws.writer.start();
        check(badWrite.writeFailed.await(1,TimeUnit.SECONDS),"actual writer encountered fixture USB failure");ws.writer.join(1000);
        MdMain.finishSessionOwner(ws,badWrite);
        check(!MdMain.running&&"write_error".equals(ws.terminalReason)&&ws.closeComplete,"write failure retires negotiated fd after cleanup");restore();

        Memory stuck=memory(true,1);stuck.quiescent=false;MdMain.Session ss=new MdMain.Session(stuck);ss.dispatch(1,0x18001,MdMain.Proto.ints(1,1,2,0));
        try{MdMain.finishSessionOwner(ss,stuck);throw new AssertionError("uncertain descriptor owner accepted");}catch(IOException expected){check(!MdMain.running,"failed transport quiescence cannot reopen or return successful retirement");}restore();

        final MdMain.Session delayed=accepted(true,2);delayed.close();final Object source=new Object();MdMain.CAPTURE_LEASE.acquire(source,"test delayed source close");
        check(!delayed.awaitTerminalCleanup(20),"retained source lease blocks retirement even after fd close");
        Thread release=new Thread(new Runnable(){public void run(){try{Thread.sleep(100);}catch(InterruptedException e){throw new AssertionError(e);}MdMain.CAPTURE_LEASE.release(source,true);}});release.start();
        long began=System.nanoTime();MdMain.finishSessionOwner(delayed,delayed.transport);release.join(1000);
        check(!MdMain.running&&!MdMain.CAPTURE_LEASE.isHeld()&&TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)>=80,"actual delayed cleanup precedes successful process retirement");restore();
        check(AsyncRingLog.headerLimit("native_owner_retire")==4,"bounded first terminal owner evidence retained in archived header");
        MdMain.bootNative=false;
        System.out.println("PASS "+checks+" negotiated boot-owner retirement/EOF/write/PAUSE/RESET/source-lease checks; no device I/O");
    }
}
