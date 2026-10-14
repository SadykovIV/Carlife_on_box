package org.carlife.hulab;

import java.io.*;
import java.util.*;

/** Actual accessory adapter: logical packet boundaries, partial syscalls and no replay. */
public final class AccessoryPacketWriteTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static final class Call {
        final int offset,requested,accepted;
        Call(int o,int r,int a){offset=o;requested=r;accepted=a;}
    }
    static final class RecordingIo implements AccessoryTransport.Io {
        final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        final List<Call> calls=new ArrayList<Call>();
        volatile boolean closed;
        int maxWrite=Integer.MAX_VALUE,failCall=-1;
        public boolean pollRead(int ms)throws IOException{
            try{Thread.sleep(2);}catch(InterruptedException e){throw new InterruptedIOException("fixture stop");}
            return false;
        }
        public boolean pollWrite(int ms){return !closed;}
        public int read(byte[] b){throw new AssertionError("no fixture USB input");}
        public int write(byte[] b,int off,int len)throws IOException {
            if(calls.size()+1==failCall){calls.add(new Call(off,len,-1));throw new IOException("injected write failure");}
            int n=Math.min(maxWrite,len);calls.add(new Call(off,len,n));sent.write(b,off,n);return n;
        }
        public void close(){closed=true;}
    }
    static byte[] packet(int innerBytes)throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        d.writeInt(2);d.writeInt(innerBytes);
        for(int i=0;i<innerBytes;i++)d.writeByte(i*71+19);
        return b.toByteArray();
    }
    static void stop(AccessoryTransport t)throws Exception{t.close();check(t.awaitStopped(1000),"fixture owned reader stopped");}
    static final class MemoryTransport implements MdTransport {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();int writes,flushes;
        final OutputStream out=new OutputStream(){public void write(int b){bytes.write(b);}public void write(byte[] b){writes++;bytes.write(b,0,b.length);}public void flush(){flushes++;}};
        public InputStream input(){return new ByteArrayInputStream(new byte[0]);}
        public OutputStream output(){return out;}
        public String name(){return "TEST_STREAM";}public boolean isNativeUsb(){return false;}
        public void close(){}public boolean awaitStopped(long timeout){return true;}
    }
    public static void main(String[] args)throws Exception {
        byte[] wire=packet(32773);
        RecordingIo full=new RecordingIo();AccessoryTransport t=new AccessoryTransport(full);
        t.writePacket(wire);
        check(full.calls.size()==4,"outer8 + 16KiB + 16KiB + final5");
        check(full.calls.get(0).offset==0&&full.calls.get(0).requested==8,"outer is its own syscall");
        check(full.calls.get(1).offset==8&&full.calls.get(1).requested==16384,"inner starts at8 not coalesced with outer");
        check(full.calls.get(2).offset==16392&&full.calls.get(2).requested==16384,"second inner chunk exact offset");
        check(full.calls.get(3).offset==32776&&full.calls.get(3).requested==5,"last inner tail exact");
        check(Arrays.equals(wire,full.sent.toByteArray()),"wire bytes unchanged across transfer boundaries");stop(t);

        RecordingIo shortIo=new RecordingIo();shortIo.maxWrite=3;AccessoryTransport s=new AccessoryTransport(shortIo);
        byte[] small=packet(29);s.writePacket(small);
        check(shortIo.calls.get(0).requested==8&&shortIo.calls.get(1).requested==5&&shortIo.calls.get(2).requested==2,"short outer write retries only remaining outer bytes");
        check(shortIo.calls.get(3).offset==8&&shortIo.calls.get(3).requested==29,"inner waits until outer fully completed");
        int end=0;boolean bounded=true,contiguous=true;
        for(Call c:shortIo.calls){bounded&=c.requested<=16384;contiguous&=c.offset==end;end+=c.accepted;}
        check(bounded&&contiguous&&end==small.length,"all partial syscalls contiguous and bounded");
        check(Arrays.equals(small,shortIo.sent.toByteArray()),"short writes duplicate/drop no bytes");stop(s);

        RecordingIo between=new RecordingIo();between.failCall=2;AccessoryTransport b=new AccessoryTransport(between);
        try{b.writePacket(wire);throw new AssertionError("missing failure");}catch(IOException e){check(e.getMessage().equals("injected write failure"),"error after outer propagated");}
        check(between.calls.size()==2,"no retry/replay after failing first inner syscall");
        check(Arrays.equals(Arrays.copyOf(wire,8),between.sent.toByteArray()),"only original outer8 sent on between-segments failure");stop(b);

        RecordingIo middle=new RecordingIo();middle.failCall=3;AccessoryTransport m=new AccessoryTransport(middle);
        try{m.writePacket(wire);throw new AssertionError("missing failure");}catch(IOException e){check(e.getMessage().equals("injected write failure"),"mid-inner failure propagated");}
        check(middle.calls.size()==3&&Arrays.equals(Arrays.copyOf(wire,16392),middle.sent.toByteArray()),"mid-inner failure preserves prefix and does not resend header");stop(m);

        RecordingIo invalid=new RecordingIo();AccessoryTransport v=new AccessoryTransport(invalid);
        try{v.writePacket(new byte[15]);throw new AssertionError("short packet accepted");}catch(IOException expected){check(invalid.calls.isEmpty(),"invalid packet refused before kernel writes");}stop(v);
        MemoryTransport stream=new MemoryTransport();stream.writePacket(wire);
        check(stream.writes==1&&stream.flushes==1&&Arrays.equals(wire,stream.bytes.toByteArray()),"default nonnative path remains one stream write and flush");
        System.out.println("PASS "+checks+" actual AccessoryTransport packet-write checks; no Android/device I/O");
    }
}
