package org.carlife.hulab;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Platform accessory fd only. No USB mode/property changes and no permission changes. */
final class AndroidAccessoryIo implements AccessoryTransport.Io {
    static final String PATH="/dev/usb_accessory";
    private final FileDescriptor fd;
    private final Method closeAndSignal;
    private volatile boolean closed;
    static final class OpenFailure extends IOException {
        final boolean retryable;
        OpenFailure(String message,Throwable cause,boolean retryable){super(message,cause);this.retryable=retryable;}
    }
    AndroidAccessoryIo()throws IOException{
        // The public Os nonblocking flag alone cannot cancel legacy accessory read syscalls.
        // Resolve Android's standard asynchronous-close helper before acquiring the descriptor.
        try{closeAndSignal=Class.forName("libcore.io.IoBridge").getDeclaredMethod("closeAndSignalBlockedThreads",FileDescriptor.class);}
        catch(Exception e){throw new OpenFailure("stock Android asynchronous close API unavailable; refusing unbounded USB I/O",e,false);}
        FileDescriptor opened=null;
        try{
            opened=Os.open(PATH,OsConstants.O_RDWR|OsConstants.O_NONBLOCK|OsConstants.O_CLOEXEC,0);
            if(!OsConstants.S_ISCHR(Os.fstat(opened).st_mode))throw new IOException("accessory path is not a character device");
            int flags=Os.fcntlInt(opened,OsConstants.F_GETFL,0);
            if((flags&OsConstants.O_NONBLOCK)==0)throw new IOException("accessory descriptor missing O_NONBLOCK");
            fd=opened;
        }catch(ErrnoException e){
            if(opened!=null)try{Os.close(opened);}catch(ErrnoException ignored){}
            throw new OpenFailure("accessory open/stat errno="+e.errno,e,e.errno==OsConstants.ENOENT||e.errno==OsConstants.ENODEV||e.errno==OsConstants.EBUSY||e.errno==OsConstants.ENXIO);
        }catch(IOException e){if(opened!=null)try{Os.close(opened);}catch(ErrnoException ignored){}throw e;}
    }
    private boolean poll(short events,int timeout)throws IOException{
        if(closed)throw new EOFException("accessory canceled");
        StructPollfd entry=new StructPollfd();entry.fd=fd;entry.events=events;
        try{
            int n=Os.poll(new StructPollfd[]{entry},Math.min(timeout,AccessoryTransport.POLL_MS));
            if(closed)throw new EOFException("accessory canceled");
            if(n==0)return false;
            if((entry.revents&events)!=0)return true;
            if((entry.revents&(OsConstants.POLLERR|OsConstants.POLLHUP|OsConstants.POLLNVAL))!=0)throw new EOFException("accessory poll detach revents="+entry.revents);
            return false;
        }catch(ErrnoException e){if(e.errno==OsConstants.EINTR&&!closed)return false;throw new IOException("accessory poll errno="+e.errno,e);}
    }
    public boolean pollRead(int ms)throws IOException{return poll((short)OsConstants.POLLIN,ms);}
    public boolean pollWrite(int ms)throws IOException{return poll((short)OsConstants.POLLOUT,ms);}
    public int read(byte[] transfer)throws IOException{
        if(transfer.length<AccessoryTransport.TRANSFER_BYTES)throw new IOException("unsafe accessory read size");
        if(closed)throw new EOFException("accessory canceled");
        try{int n=Os.read(fd,transfer,0,transfer.length);return n==0?-1:n;}
        catch(ErrnoException e){if((e.errno==OsConstants.EAGAIN||e.errno==OsConstants.EINTR)&&!closed)return 0;throw new IOException("accessory read errno="+e.errno,e);}
    }
    public int write(byte[] bytes,int off,int len)throws IOException{
        if(closed)throw new EOFException("accessory canceled");
        try{return Os.write(fd,bytes,off,len);}
        catch(ErrnoException e){if((e.errno==OsConstants.EAGAIN||e.errno==OsConstants.EINTR)&&!closed)return 0;throw new IOException("accessory write errno="+e.errno,e);}
    }
    public synchronized void close()throws IOException{
        if(closed)return;closed=true;
        try{closeAndSignal.invoke(null,fd);}
        catch(InvocationTargetException e){throw new IOException("stock asynchronous accessory close failed",e.getCause());}
        catch(Exception e){throw new IOException("stock asynchronous accessory close denied",e);}
    }
}
