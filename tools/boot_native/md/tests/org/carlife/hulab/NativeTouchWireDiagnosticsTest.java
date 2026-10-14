package org.carlife.hulab;
import java.io.*;
import java.security.*;
import java.util.*;

/** Fixed raw fixtures through the actual parser + native dispatch; no USB/Android calls. */
public final class NativeTouchWireDiagnosticsTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static byte[] hex(String s){return CarLifeFrameReaderTest.hex(s);}
    static byte[] outer(int c,byte[] p)throws Exception{return CarLifeFrameReaderTest.outer(c,p);}
    static byte[] join(byte[]...p)throws Exception{return CarLifeFrameReaderTest.join(p);}
    static CarLifeFrameReader reader(byte[] p){return new CarLifeFrameReader(new ByteArrayInputStream(p),null);}
    static String sha(byte[] p)throws Exception{byte[] d=MessageDigest.getInstance("SHA-256").digest(p);StringBuilder b=new StringBuilder();for(byte v:d)b.append(String.format("%02x",v&255));return b.toString();}
    public static void main(String[] args)throws Exception{
        byte[] inner=hex("000820b400068001 080010d30218b702");CarLifeFrameReader.Frame f=reader(outer(6,inner)).readNext();CarLifeFrameReader.TouchWire w=f.touchWire;
        check(Arrays.equals(w.innerHeader,hex("000820b400068001"))&&Arrays.equals(w.availableBodyPrefix,hex("080010d30218b702")),"actual Honda inner header/reserved and complete body preserved separately");
        check(w.declaredLength==8&&w.availableLength==8&&w.deliveredLength==8&&w.innerSha256.equals(sha(inner)),"whole inner byte hash and sizes independent of protobuf");
        byte[] extra=hex("000220b4000680ff 0801 deadbeef0000000000");CarLifeFrameReader.Frame mismatch=reader(outer(6,extra)).readNext();
        check(Arrays.equals(mismatch.payload,hex("0801"))&&Arrays.equals(mismatch.touchWire.availableBodyPrefix,hex("0801deadbeef0000000000"))&&mismatch.touchWire.availableLength==11&&mismatch.touchWire.deliveredLength==2,"reference min-delivery unchanged but discarded foreign suffix visible before decode");
        CarLifeFrameReader.Frame shortBody=reader(outer(6,hex("001020b400068001 0800"))).readNext();
        check(shortBody.payload.length==2&&shortBody.touchWire.declaredLength==16&&shortBody.touchWire.availableLength==2,"short whole outer still delivers available body, diagnostics honest");
        CarLifeFrameReader split=reader(join(outer(6,hex("000820b400068001")),outer(1,hex("0000000000018009")),outer(6,hex("080010d3")),outer(6,hex("0218b702"))));
        check(split.readNext().touchWire==null,"interleaved CMD has no touch payload capture");CarLifeFrameReader.Frame assembled=split.readNext();
        check(Arrays.equals(assembled.touchWire.innerHeader,w.innerHeader)&&Arrays.equals(assembled.touchWire.availableBodyPrefix,w.availableBodyPrefix)&&assembled.touchWire.innerSha256.equals(w.innerSha256),"header-only + multiple same-channel outers reassemble exact touch evidence");
        CarLifeFrameReader chain=reader(outer(6,join(inner,hex("000221b500068008 080e"))));CarLifeFrameReader.Frame first=chain.readNext(),second=chain.readNext();
        check(first.touchWire.innerSha256.equals(sha(inner))&&Arrays.equals(second.touchWire.innerHeader,hex("000221b500068008"))&&second.touchWire.availableLength==2,"concatenated inners carry their own header/reserved, no cross contamination");
        check(reader(outer(2,hex("000000020000123400020001 aabb"))).readNext().touchWire==null,"VIDEO never captures media bytes in touch evidence");
        byte[] large=new byte[4096+8];byte[] hdr=hex("000120b4000680ff");System.arraycopy(hdr,0,large,0,8);Arrays.fill(large,8,large.length,(byte)0xff);CarLifeFrameReader.Frame bounded=reader(outer(6,large)).readNext();
        check(bounded.payload.length==1&&bounded.touchWire.availableBodyPrefix.length==2048&&bounded.touchWire.availableLength==4096&&bounded.touchWire.innerSha256.equals(sha(large)),"bounded prefix keeps full raw available length/hash, never changes delivered length");
        PrintStream old=System.out;ByteArrayOutputStream logs=new ByteArrayOutputStream();MdMain.bootNative=true;System.setOut(new PrintStream(logs,true,"UTF-8"));
        try{
            MdMain.Session s=NativeTouchRecoveryTest.session(true,new NativeTouchRecoveryTest.Sink());s.dispatch(f.channel,f.id,f.payload,f.touchWire);
            check(logs.toString("UTF-8").contains("\"inner_header_hex\":\"00 08 20 b4 00 06 80 01\"")&&logs.toString("UTF-8").contains("\"reserved_u16\":8372"),"native dispatch logs full header correlated with input_count");
            logs.reset();s.dispatch(mismatch.channel,mismatch.id,mismatch.payload,mismatch.touchWire);String output=logs.toString("UTF-8");
            check(output.contains("\"available_payload_hex\":\"08 01 de ad be ef 00 00 00 00 00\"")&&output.indexOf("native_touch_wire")<output.indexOf("native_touch_unknown"),"unknown TOUCH opcode retains raw body before unsupported dispatch");
            logs.reset();s.dispatch(bounded.channel,bounded.id,bounded.payload,bounded.touchWire);output=logs.toString("UTF-8");
            check(output.contains("\"truncated\":true")&&output.contains("\"available_length\":4096"),"oversized raw explicitly truncated, not claimed full");for(String line:output.split("\n"))check(line.getBytes("UTF-8").length<AsyncRingLog.LINE_BYTES,"wire diagnostic fits bounded logger line");
            check(s.open&&s.videoGeneration==7,"raw diagnostics does not close/reset native session");
            logs.reset();MdMain.Session dev=NativeTouchRecoveryTest.session(false,new NativeTouchRecoveryTest.Sink());dev.dispatch(f.channel,f.id,f.payload,f.touchWire);check(!logs.toString("UTF-8").contains("native_touch_wire"),"DEV log behavior unchanged");
        }finally{System.setOut(old);MdMain.bootNative=false;}
        check(AsyncRingLog.headerLimit("native_touch_wire")==8,"bounded first wire header retention; tail remains unrestricted by event quota");
        System.out.println("PASS "+checks+" raw TOUCH header/body/reserved/split/interleaved/mismatch/unknown/bounds/media-exclusion checks; no device I/O");
    }
}
