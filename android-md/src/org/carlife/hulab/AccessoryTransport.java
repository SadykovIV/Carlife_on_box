package org.carlife.hulab;

import java.io.*;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One accessory fd, one kernel reader, one Session writer. Kernel reads always have
 * 16384-byte capacity: small Java stream reads consume a private retained transfer.
 * A bounded pump decouples frame-boundary idle timeouts from old f_accessory drivers
 * that ignore O_NONBLOCK. Cancellation uses the platform's asynchronous-close path;
 * a failed/quiescence timeout poisons this owner rather than opening a second fd.
 */
final class AccessoryTransport implements MdTransport {
    static final int TRANSFER_BYTES=16384,QUEUE_TRANSFERS=2,POLL_MS=100,READ_TIMEOUT_MS=1000;
    interface Io extends Closeable {
        boolean pollRead(int timeoutMs)throws IOException;
        boolean pollWrite(int timeoutMs)throws IOException;
        int read(byte[] transfer)throws IOException;
        int write(byte[] bytes,int offset,int length)throws IOException;
    }
    private final Io io;
    private final ArrayBlockingQueue<byte[]> transfers=new ArrayBlockingQueue<byte[]>(QUEUE_TRANSFERS);
    private volatile boolean closed,readerStopped,closeFinished;
    private volatile IOException failure,closeFailure;
    private volatile int writesInFlight;
    private volatile long kernelReadCalls;
    private volatile boolean kernelReadInFlight;
    private final Thread reader;
    private final InputStream input=new InputStream(){
        private byte[] buffered;
        private int position;
        public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
        public int read(byte[] b,int off,int len)throws IOException{
            if(b==null)throw new NullPointerException("buffer");
            if(off<0||len<0||off>b.length-len)throw new IndexOutOfBoundsException();
            if(len==0)return 0;
            if(closed)throw new EOFException("accessory transport closed");
            if(buffered==null||position==buffered.length){
                buffered=transfers.poll();position=0;
                if(buffered==null){
                    if(failure!=null)throw failure;
                    try{buffered=transfers.poll(READ_TIMEOUT_MS,TimeUnit.MILLISECONDS);}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("accessory consumer interrupted");}
                    if(closed)throw new EOFException("accessory transport closed");
                    if(buffered==null){if(failure!=null)throw failure;throw new SocketTimeoutException("accessory idle read");}
                }
            }
            int n=Math.min(len,buffered.length-position);System.arraycopy(buffered,position,b,off,n);position+=n;return n;
        }
    };
    private final OutputStream output=new OutputStream(){
        public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
        public void write(byte[] b,int off,int len)throws IOException{
            if(b==null)throw new NullPointerException("buffer");
            if(off<0||len<0||off>b.length-len)throw new IndexOutOfBoundsException();
            synchronized(AccessoryTransport.this){if(closed)throw new EOFException("accessory closed");if(writesInFlight!=0)throw new IOException("accessory permits one writer");writesInFlight++;}
            try{
                int end=off+len;
                while(off<end){
                    if(closed)throw new EOFException("accessory write canceled");
                    // Session's existing 3s watchdog closes stalled writes, including a driver
                    // syscall that ignores nonblocking mode. This does not increase that budget.
                    if(!io.pollWrite(POLL_MS))continue;
                    int requested=Math.min(TRANSFER_BYTES,end-off),n=io.write(b,off,requested);
                    if(n<0||n>requested)throw new IOException("invalid accessory write count "+n);
                    if(n==0)continue;off+=n;
                }
            }finally{writesInFlight--;}
        }
    };
    AccessoryTransport(Io io){
        this.io=io;
        reader=new Thread(new Runnable(){public void run(){pump();}},"md-accessory-reader");
        reader.setDaemon(true);reader.start();
    }
    private void pump(){
        byte[] transfer=new byte[TRANSFER_BYTES];
        try{
            while(!closed){
                if(!io.pollRead(POLL_MS))continue;
                int n;kernelReadCalls++;kernelReadInFlight=true;
                try{n=io.read(transfer);}finally{kernelReadInFlight=false;}
                if(n<0)throw new EOFException("USB accessory detached");
                if(n>transfer.length)throw new IOException("invalid accessory read count");
                if(n==0)continue;
                byte[] owned=Arrays.copyOf(transfer,n);
                while(!closed&&!transfers.offer(owned,POLL_MS,TimeUnit.MILLISECONDS)){}
            }
        }catch(IOException e){if(!closed)failure=e;}
        catch(InterruptedException e){if(!closed)failure=new InterruptedIOException("accessory reader interrupted");Thread.currentThread().interrupt();}
        finally{readerStopped=true;}
    }
    public InputStream input(){return input;}
    public OutputStream output(){return output;}
    public void writePacket(byte[] packet)throws IOException{
        if(packet.length<16)throw new IOException("short outgoing CarLife packet");
        // HeadSwap/Wizcar transport: outer8 is its own USB transfer, followed by
        // inner data in <=16KiB chunks. One session writer owns both operations.
        output.write(packet,0,8);output.write(packet,8,packet.length-8);
    }
    public String name(){return "DIRECT_USB_AOA";}
    public boolean isNativeUsb(){return true;}
    long kernelReadCalls(){return kernelReadCalls;}
    boolean kernelReadInFlight(){return kernelReadInFlight;}
    String readerFailure(){return failure==null?null:failure.toString();}
    public void close()throws IOException{
        synchronized(this){if(closed){if(closeFailure!=null)throw closeFailure;return;}closed=true;}
        reader.interrupt();transfers.clear();
        try{io.close();}catch(IOException e){closeFailure=e;throw e;}finally{closeFinished=true;}
    }
    public boolean awaitStopped(long timeoutMs){
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while((!closeFinished||!readerStopped||writesInFlight!=0)&&System.nanoTime()<until){
            try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
        }
        return closed&&closeFinished&&readerStopped&&writesInFlight==0&&closeFailure==null;
    }
}
