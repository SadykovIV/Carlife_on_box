package org.carlife.hulab;

import java.io.*;
import java.net.SocketTimeoutException;
import java.util.*;

/** Fixed reference wire fixtures plus adversarial state/boundary checks; no Android calls. */
public final class CarLifeFrameReaderTest {
    static int checks;
    static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    static byte[] hex(String s){s=s.replace(" ","");byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);return b;}
    static byte[] join(byte[]...parts)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] p:parts)b.write(p);return b.toByteArray();}
    static byte[] outer(int channel,byte[] inner)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.writeInt(channel);d.writeInt(inner.length);d.write(inner);return b.toByteArray();}
    static CarLifeFrameReader reader(byte[] input){return new CarLifeFrameReader(new ByteArrayInputStream(input),null);}
    static void frame(CarLifeFrameReader r,int channel,int id,String payload)throws Exception{CarLifeFrameReader.Frame f=r.readNext();check(f!=null&&f.channel==channel&&f.id==id&&Arrays.equals(f.payload,hex(payload)),"expected frame "+Integer.toHexString(id));}
    interface Checked{void run()throws Exception;}
    static void rejects(Checked fn,String message)throws Exception{try{fn.run();throw new AssertionError("accepted "+message);}catch(IOException e){check(e.getMessage()!=null&&e.getMessage().contains(message),"unexpected error "+e);}}
    static class Time implements CarLifeFrameReader.Clock{long now;public long millis(){return now;}}
    static final Object TIMEOUT=new Object();
    static final class Script extends InputStream {
        final Object[] pieces;int piece,offset;
        Script(Object...pieces){this.pieces=pieces;}
        public int read()throws IOException{
            while(piece<pieces.length){Object o=pieces[piece];if(o==TIMEOUT){piece++;throw new SocketTimeoutException("fixture boundary");}
                byte[] b=(byte[])o;if(offset<b.length)return b[offset++]&255;piece++;offset=0;}
            return -1;
        }
        public int read(byte[] b,int o,int n)throws IOException{if(n==0)return 0;int v=read();if(v<0)return -1;b[o]=(byte)v;return 1;}
    }
    public static void main(String[] args)throws Exception {
        // Normative complete v1 and v2 have identical framing.
        frame(reader(hex("000000010000000c000400000001800108011000")),1,0x18001,"08011000");
        frame(reader(hex("000000010000000c000400000001800108021000")),1,0x18001,"08021000");
        final List<CarLifeFrameReader.Header> observed=new ArrayList<CarLifeFrameReader.Header>();
        CarLifeFrameReader opaque=new CarLifeFrameReader(new ByteArrayInputStream(hex("000000010000000c000400640001800108021000")),new CarLifeFrameReader.Diagnostics(){public void accept(CarLifeFrameReader.Header h){observed.add(h);}});
        frame(opaque,1,0x18001,"08021000");
        CarLifeFrameReader.Header h=observed.get(observed.size()-1);
        check(h.reserved==100&&h.headerHex.equals("0004006400018001")&&h.outerLength==12&&h.availableLength==4,"reserved diagnostics expose header only");
        // Exact recorded Headswap VW fixture, including opaque reserved=100.
        frame(new CarLifeFrameReader(new Script(hex("00000001000000080004006400018001000000010000000408021001")),null),1,0x18001,"08021001");
        // A whole outer with nonempty body is not held simply because declared is longer.
        frame(reader(hex("000000010000000c000500000001800108021000")),1,0x18001,"08021000");
        // Reference min(declared, available): foreign tail does NOT become payload.
        frame(reader(outer(1,hex("000400000001800108021000010203040506070809"))),1,0x18001,"08021000");
        // Valid concatenated messages in one outer, unlike foreign trailing bytes.
        CarLifeFrameReader chain=reader(outer(1,hex("0004000000018001080210000000000000018009")));
        frame(chain,1,0x18001,"08021000");frame(chain,1,0x18009,"");
        // Pending channel1 is not corrupted by a complete channel6 message.
        CarLifeFrameReader interleaved=reader(join(outer(1,hex("0004006400018001")),outer(6,hex("0002000000068008080e")),outer(1,hex("0802")),outer(1,hex("1001"))));
        frame(interleaved,6,0x68008,"080e");frame(interleaved,1,0x18001,"08021001");
        // Tail of pending body + next message + partial third header, then continuation.
        CarLifeFrameReader mixed=reader(join(outer(1,hex("0004006800018028")),outer(1,hex("08061000000200680001804a08010000")),outer(1,hex("000000018009"))));
        frame(mixed,1,0x18028,"08061000");frame(mixed,1,0x1804a,"0801");frame(mixed,1,0x18009,"");
        // Exactly declared zero plus short tail becomes a pending next header, not payload.
        CarLifeFrameReader shortTail=reader(join(outer(1,hex("00000000000180090000")),outer(1,hex("00000001800a"))));
        frame(shortTail,1,0x18009,"");frame(shortTail,1,0x1800a,"");
        // Data-channel framing uses12-byte header and the same per-channel assembly.
        frame(reader(join(outer(5,hex("000000030000000000058001")),outer(5,hex("010203")))),5,0x58001,"010203");
        CarLifeFrameReader.Frame microphone=reader(join(outer(5,hex("00000004fffffff000058001")),outer(5,hex("01020304")))).readNext();
        check(microphone.timestamp==-16&&microphone.id==0x58001&&Arrays.equals(microphone.payload,hex("01020304")),"microphone timestamp bits survive fragmented data header, including unsigned wrap");
        // Short inner without pending is diagnostic-only, as reference dispatch.
        frame(reader(join(outer(1,hex("0001")),outer(1,hex("0000000000018009")))),1,0x18009,"");
        // Boundary timeouts preserve pending until its absolute (non-sliding) deadline.
        Time time=new Time();CarLifeFrameReader bounded=new CarLifeFrameReader(new Script(outer(1,hex("0004000000018001")),TIMEOUT,TIMEOUT,outer(1,hex("08021000"))),null,time,3000);
        check(bounded.readNext()==null,"timeout while split body pending");time.now=2999;check(bounded.readNext()==null,"pending preserved before deadline");frame(bounded,1,0x18001,"08021000");
        Time idleTime=new Time();CarLifeFrameReader idle=new CarLifeFrameReader(new Script(TIMEOUT,TIMEOUT,outer(1,hex("0000000000018009"))),null,idleTime,10);
        check(idle.readNext()==null,"idle boundary1");idleTime.now=1000000;check(idle.readNext()==null,"no idle session deadline");frame(idle,1,0x18009,"");
        final Time expiry=new Time();final CarLifeFrameReader expired=new CarLifeFrameReader(new Script(outer(1,hex("0004000000018001")),TIMEOUT,outer(1,hex("08")),TIMEOUT,TIMEOUT),null,expiry,3000);
        check(expired.readNext()==null,"first pending timeout");expiry.now=2000;check(expired.readNext()==null,"progress does not reset pending deadline");expiry.now=3000;
        rejects(new Checked(){public void run()throws Exception{expired.readNext();}},"incomplete inner deadline");
        rejects(new Checked(){public void run()throws Exception{expired.readNext();}},"already failed");
        rejects(new Checked(){public void run()throws Exception{new CarLifeFrameReader(new Script(hex("00"),TIMEOUT),null).readNext();}},"timeout inside outer header");
        rejects(new Checked(){public void run()throws Exception{new CarLifeFrameReader(new Script(hex("000000010000000c0004"),TIMEOUT),null).readNext();}},"timeout inside outer body");
        rejects(new Checked(){public void run()throws Exception{reader(hex("000000010000000c0004")).readNext();}},"");
        rejects(new Checked(){public void run()throws Exception{reader(outer(1,hex("0004000000018001"))).readNext();}},"EOF with incomplete inner");
        rejects(new Checked(){public void run()throws Exception{reader(hex("0000000700000000")).readNext();}},"invalid outer");
        rejects(new Checked(){public void run()throws Exception{reader(hex("0000000100100001")).readNext();}},"invalid outer");
        rejects(new Checked(){public void run()throws Exception{reader(hex("00000001ffffffff")).readNext();}},"invalid outer");
        rejects(new Checked(){public void run()throws Exception{reader(outer(1,hex("0000000000068008"))).readNext();}},"invalid inner");
        rejects(new Checked(){public void run()throws Exception{reader(outer(5,hex("ffffffff0000000000058001"))).readNext();}},"invalid inner");
        rejects(new Checked(){public void run()throws Exception{reader(outer(5,hex("001000000000000000058001"))).readNext();}},"invalid inner");
        // Two large partial channels cannot allocate an unbounded aggregate backlog.
        rejects(new Checked(){public void run()throws Exception{
            reader(join(outer(3,hex("000c35000000000000030006")),outer(4,hex("000c35000000000000040003")),outer(3,new byte[524288]),outer(4,new byte[524288]))).readNext();
        }},"aggregate incomplete inner");
        // A diagnostics consumer cannot corrupt normal framing by throwing.
        frame(new CarLifeFrameReader(new ByteArrayInputStream(outer(1,hex("0000000000018009"))),new CarLifeFrameReader.Diagnostics(){public void accept(CarLifeFrameReader.Header d){throw new IllegalStateException("test");}}),1,0x18009,"");
        System.out.println("PASS "+checks+" CarLife native framing checks (no Android/device I/O)");
    }
}
