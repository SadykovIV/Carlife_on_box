package org.carlife.hulab;

import android.os.SystemClock;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;

/** Runs the actual writer and watchdog with completion at the previously racy clock read. */
public final class MdWatchdogRaceTest {
    static final class HeldOutput extends OutputStream {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        volatile int delivered;
        public void write(int b)throws IOException {throw new IOException("unexpected single-byte write");}
        public void write(byte[] b,int off,int length)throws IOException {
            entered.countDown();
            try {if(!release.await(2,TimeUnit.SECONDS))throw new IOException("test writer barrier expired");}
            catch(InterruptedException e){throw new IOException(e);}
            delivered+=length;
        }
    }
    static final class MemorySocket extends Socket {
        final HeldOutput output=new HeldOutput();volatile boolean closed;
        public void setTcpNoDelay(boolean value){}public void setSoTimeout(int value){}public void setSendBufferSize(int value){}
        public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]);}
        public OutputStream getOutputStream(){return output;}
        public void close(){closed=true;output.release.countDown();}
    }
    static MdMain.Session start(final MemorySocket socket)throws Exception {
        final MdMain.Session session=new MdMain.Session(socket);
        session.controls.add(new MdMain.Packet(new byte[]{1,2,3},-1));
        session.writer=new Thread(new Runnable(){public void run(){session.writeLoop();}},"watchdog-test-writer");
        session.writer.start();
        if(!socket.output.entered.await(2,TimeUnit.SECONDS)||session.writingSince<=0)throw new AssertionError("actual writer did not enter timed write");
        return session;
    }
    static void stop(MdMain.Session session)throws Exception {
        SystemClock.nextRead=null;SystemClock.fixedTime=-1;
        session.close();session.writer.join(2000);
        if(session.writer.isAlive())throw new AssertionError("writer remained alive");
    }
    public static void main(String[] args)throws Exception {
        final MemorySocket socket=new MemorySocket();final MdMain.Session session=start(socket);
        try {
            final long started=session.writingSince;
            // Fast successful writes must survive even when uptime is far above the three-second limit.
            SystemClock.fixedTime=started+1;
            SystemClock.nextRead=new Runnable(){public void run(){
                socket.output.release.countDown();
                long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                while(session.writingSince!=0&&System.nanoTime()<limit)Thread.yield();
                if(session.writingSince!=0)throw new AssertionError("real writer did not finish between watchdog reads");
            }};
            if(session.closeIfWriteStalled()||!session.open||socket.closed||socket.output.delivered!=3)throw new AssertionError("successful write falsely killed as backpressure");
            // An old observation also must not kill a now-completed write after the timeout threshold.
            session.writingSince=started;SystemClock.fixedTime=started+3001;
            SystemClock.nextRead=new Runnable(){public void run(){session.writingSince=0;}};
            if(session.closeIfWriteStalled()||!session.open)throw new AssertionError("stale completed write closed session");
            // A different, newly started write invalidates the previous observation.
            session.writingSince=started;
            SystemClock.nextRead=new Runnable(){public void run(){session.writingSince=started+3000;}};
            if(session.closeIfWriteStalled()||!session.open)throw new AssertionError("old observation closed new write");
            session.writingSince=0;
            if(session.closeIfWriteStalled())throw new AssertionError("idle session closed");
        } finally {stop(session);}
        MemorySocket blockedSocket=new MemorySocket();MdMain.Session blocked=start(blockedSocket);
        try {
            SystemClock.fixedTime=blocked.writingSince+3001;
            if(!blocked.closeIfWriteStalled()||blocked.open||!blockedSocket.closed)throw new AssertionError("genuinely stalled actual writer was not closed");
        } finally {stop(blocked);}
        System.out.println("PASS actual write/watchdog completion race, stale/new write recheck, idle connection and genuine >3s blocked write closure");
    }
}
