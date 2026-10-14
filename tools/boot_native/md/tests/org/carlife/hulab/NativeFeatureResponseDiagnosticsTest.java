package org.carlife.hulab;
import java.io.*;
import java.util.*;

/** Bounded raw diagnostic only: no change to feature response semantics. */
public final class NativeFeatureResponseDiagnosticsTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static MdMain.Session session(boolean nativeUsb)throws Exception{MdMain.Session s=new MdMain.Session(new NativeTouchRecoveryTest.Memory(nativeUsb),new NativeTouchRecoveryTest.Sink());s.version=true;s.huMajor=2;return s;}
    public static void main(String[] args)throws Exception{
        MdMain.bootNative=true;PrintStream old=System.out;ByteArrayOutputStream bytes=new ByteArrayOutputStream();System.setOut(new PrintStream(bytes,true,"UTF-8"));
        try{
            MdMain.Session s=session(true);byte[] golden=MdWireTest.hex("0801120f0a0b4d554c54495f544f5543481001");s.dispatch(1,0x18052,golden);s.dispatch(1,0x18052,golden);
            check(s.open&&s.controls.isEmpty()&&s.featureConfigRecorded,"diagnostic does not ACK/reconfigure/drop response");
            String log=bytes.toString("UTF-8");check(log.contains("\"payload_hex\":\"08 01 12 0f 0a 0b 4d 55 4c 54 49 5f 54 4f 55 43 48 10 01\"")&&log.contains("\"truncated\":false"),"independent golden feature bytes retained without conversion");
            check(log.split("\\\"event\\\":\\\"native_feature_config\\\"",-1).length-1==1,"only first response retained per Session");
            bytes.reset();MdMain.Session dev=session(false);dev.dispatch(1,0x18052,golden);check(!bytes.toString("UTF-8").contains("native_feature_config")&&!dev.featureConfigRecorded,"DEV diagnostic unchanged");
            bytes.reset();char[] chars=new char[3000];Arrays.fill(chars,'x');byte[] big=MdMain.Proto.strings(1,new String(chars));MdMain.Session larger=session(true);larger.dispatch(1,0x18052,big);log=bytes.toString("UTF-8");
            check(log.contains("\"captured_bytes\":2048")&&log.contains("\"truncated\":true")&&log.contains("\"payload_length\":"+big.length),"oversized valid response explicitly bounded prefix with full length");
            for(String line:log.split("\n"))check(line.getBytes("UTF-8").length<AsyncRingLog.LINE_BYTES,"diagnostic fits bounded async logger line");
            bytes.reset();MdMain.Session malformed=session(true);try{malformed.dispatch(1,0x18052,new byte[]{8,(byte)0x80});throw new AssertionError("existing protobuf validation bypassed");}catch(IOException expected){checks++;}
            check(bytes.toString("UTF-8").contains("\"payload_hex\":\"08 80\""),"raw retained before malformed response fails existing validator");
            check(AsyncRingLog.headerLimit("native_feature_config")==2,"bounded two-session header evidence");
        }finally{System.setOut(old);MdMain.bootNative=false;}
        System.out.println("PASS "+checks+" feature response raw/golden/once-per-session/prefix/dev/validation checks; no device I/O");
    }
}
