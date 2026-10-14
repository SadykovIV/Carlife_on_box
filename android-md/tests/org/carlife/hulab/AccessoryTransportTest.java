package org.carlife.hulab;

import java.io.*;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.*;

/** Tests the actual adapter with a USB-like transfer reader, not a Java socket substitute. */
public final class AccessoryTransportTest {
    static int checks;
    static void check(boolean b,String reason){checks++;if(!b)throw new AssertionError(reason);}
    static final class TransferIo implements AccessoryTransport.Io {
        final BlockingQueue<byte[]> rx=new LinkedBlockingQueue<byte[]>();
        final ByteArrayOutputStream tx=new ByteArrayOutputStream();
        final CountDownLatch entered=new CountDownLatch(1),unblock=new CountDownLatch(1);
        volatile boolean closed,blockRead,blockWrite,ignoreCancel;
        volatile int readCalls,minRead=Integer.MAX_VALUE,closeCount;
        int maxWrite=7;
        public boolean pollRead(int ms)throws IOException{
            if(closed)throw new EOFException();
            if(blockRead)return true;
            if(!rx.isEmpty())return true;
            try{Thread.sleep(Math.min(ms,5));}catch(InterruptedException e){throw new InterruptedIOException();}
            return !rx.isEmpty();
        }
        public boolean pollWrite(int ms){return !closed;}
        void block()throws IOException{
            entered.countDown();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){try{if(unblock.await(10,TimeUnit.MILLISECONDS))break;}catch(InterruptedException e){if(!ignoreCancel)throw new InterruptedIOException();}}
            if(closed)throw new EOFException("closed syscall");
        }
        public int read(byte[] b)throws IOException{
            readCalls++;minRead=Math.min(minRead,b.length);
            if(blockRead){block();return -1;}
            byte[] transfer=rx.poll();if(transfer==null)return 0;
            // Model the dangerous driver's one-transfer semantics: excess is discarded.
            int n=Math.min(b.length,transfer.length);System.arraycopy(transfer,0,b,0,n);return n;
        }
        public int write(byte[] b,int off,int len)throws IOException{
            if(blockWrite)block();if(closed)throw new EOFException();
            int n=Math.min(len,maxWrite);synchronized(tx){tx.write(b,off,n);}return n;
        }
        public void close(){closed=true;closeCount++;if(!ignoreCancel)unblock.countDown();}
    }
    static void stop(AccessoryTransport t)throws Exception{t.close();check(t.awaitStopped(1000),"transport stops");}
    public static void main(String[] args)throws Exception{
        TransferIo io=new TransferIo();byte[] full=new byte[16384];for(int i=0;i<full.length;i++)full[i]=(byte)(i*31);
        io.rx.add(full);AccessoryTransport t=new AccessoryTransport(io);
        ByteArrayOutputStream got=new ByteArrayOutputStream();byte[] small=new byte[3];
        while(got.size()<full.length){int n=t.input().read(small,0,Math.min(3,full.length-got.size()));got.write(small,0,n);}
        check(Arrays.equals(full,got.toByteArray()),"all 16384 transfer bytes survive 3-byte Java reads");
        check(io.readCalls==1&&io.minRead>=16384,"no small kernel read even for byte-stream consumer");
        byte[] wire=MdWireTest.hex("000000010000000c000400000001800108011000");
        io.rx.add(Arrays.copyOfRange(wire,0,7));io.rx.add(Arrays.copyOfRange(wire,7,wire.length));
        DataInputStream stream=new DataInputStream(t.input());check(MdMain.readFrame(stream).id==0x18001,"real CarLife header fragmented between USB transfers");
        byte[] joined=new byte[wire.length*3];for(int i=0;i<3;i++)System.arraycopy(wire,0,joined,i*wire.length,wire.length);io.rx.add(joined);
        for(int i=0;i<3;i++)check(MdMain.readFrame(stream).id==0x18001,"coalesced frames retain next outer header");
        t.output().write(full);check(Arrays.equals(full,io.tx.toByteArray()),"short writes retain exact bytes and order");
        try{t.input().read();throw new AssertionError("idle should time out");}catch(SocketTimeoutException expected){checks++;}
        stop(t);t.close();check(io.closeCount==1,"idempotent sole descriptor close");

        TransferIo blocked=new TransferIo();blocked.blockRead=true;AccessoryTransport b=new AccessoryTransport(blocked);
        check(blocked.entered.await(1,TimeUnit.SECONDS),"driver ignoring nonblock entered read");
        long start=System.nanoTime();stop(b);check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1200,"cancel releases blocked driver reader");

        final TransferIo writing=new TransferIo();writing.blockWrite=true;final AccessoryTransport w=new AccessoryTransport(writing);
        final CountDownLatch failed=new CountDownLatch(1);
        Thread writer=new Thread(new Runnable(){public void run(){try{w.output().write(new byte[40]);}catch(IOException expected){failed.countDown();}}});writer.start();
        check(writing.entered.await(1,TimeUnit.SECONDS),"blocked writer entered");w.close();
        check(failed.await(1,TimeUnit.SECONDS)&&w.awaitStopped(1000),"close cancels in-flight write and reader");writer.join(1000);

        // Negative control: a driver that cannot be canceled must fail the reopen barrier.
        TransferIo stuck=new TransferIo();stuck.blockRead=true;stuck.ignoreCancel=true;AccessoryTransport bad=new AccessoryTransport(stuck);
        check(stuck.entered.await(1,TimeUnit.SECONDS),"stuck read entered");bad.close();
        check(!bad.awaitStopped(60),"uncancelable owner fails quiescence; controller must refuse reopen");
        stuck.unblock.countDown();check(bad.awaitStopped(1000),"negative-control fixture released");

        // A new fd/session is independent after actual detach, with no retained old transfer bytes.
        TransferIo next=new TransferIo();next.rx.add(wire);AccessoryTransport reopened=new AccessoryTransport(next);
        check(MdMain.readFrame(new DataInputStream(reopened.input())).id==0x18001,"fresh owner reconnect starts new frame");stop(reopened);

        TransferIo fullQueue=new TransferIo();for(int i=0;i<8;i++)fullQueue.rx.add(wire);AccessoryTransport q=new AccessoryTransport(fullQueue);
        Thread.sleep(60);stop(q);check(fullQueue.readCalls<=AccessoryTransport.QUEUE_TRANSFERS+1,"reader bounded to2 transfers plus one in-flight transfer");

        TransferIo negotiation=new TransferIo();AccessoryTransport nt=new AccessoryTransport(negotiation);MdMain.videoSource="mediacodec";
        MdMain.Session session=new MdMain.Session(nt);
        session.dispatch(1,0x18001,MdWireTest.hex("08011000"));session.dispatch(1,0x18003,new byte[0]);
        MdMain.Frame match=MdMain.readFrame(new DataInputStream(new ByteArrayInputStream(session.controls.remove().data)));
        MdMain.Frame info=MdMain.readFrame(new DataInputStream(new ByteArrayInputStream(session.controls.remove().data)));
        check(match.id==0x10002&&MdMain.Proto.number(MdMain.Proto.parse(match.payload),1,0)==1,"same Session negotiates native CarLife1.0");
        String marker=new String((byte[])MdMain.Proto.parse(info.payload).get(15),"UTF-8");
        check(marker.equals("DIRECT_USB_AOA;source=MediaCodecDisplaySource;video=ANNEXB_AU;timestamp=ENCODER_PTS_MS")&&!marker.contains("DEV_STREAM"),"native metadata opts into exact AU semantics without ADB marker");
        check(session.controls.isEmpty(),"no proprietary transport handshake or envelope emitted");
        try{session.dispatch(1,0x18001,MdWireTest.hex("08011000"));throw new AssertionError("live duplicate version accepted");}catch(IOException expected){checks++;}
        session.dispatch(1,0x1800a,new byte[0]);long previousGeneration=session.videoGeneration;
        session.controls.add(new MdMain.Packet(new byte[]{99},-1));
        session.dispatch(1,0x18001,MdWireTest.hex("08011000"));
        check(session.version&&!session.initialized&&!session.paused&&session.geometry==null&&session.videoGeneration>previousGeneration,"native paused VERSION boundary resets negotiation and advances generation");
        check(session.controls.size()==1&&MdMain.readFrame(new DataInputStream(new ByteArrayInputStream(session.controls.remove().data))).id==0x10002,"fresh version consumed once and old pending control discarded");
        session.close();check(nt.awaitStopped(1000),"native Session close releases adapter owner");MdMain.videoSource="bootstrap";
        System.out.println("PASS "+checks+" USB transfer buffering, short writes, cancellation, failed-quiescence negative control and reconnect checks; no device I/O");
    }
}
