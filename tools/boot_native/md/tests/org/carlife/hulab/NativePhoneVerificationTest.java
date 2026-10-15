package org.carlife.hulab;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** Official MD wire facts; expected MD5 vectors independently checked with OpenSSL. */
public final class NativePhoneVerificationTest {
    static int checks;
    interface Checked {void run()throws Exception;}
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void rejects(Checked test,String marker)throws Exception{
        try{test.run();throw new AssertionError("accepted "+marker);}
        catch(IOException expected){check(expected.getMessage().contains(marker),expected.toString());}
    }
    static byte[] identity(String brand,String model,String sdk)throws Exception{return MdMain.Proto.strings(4,brand,14,model,20,sdk);}
    static byte[] challenge(String value)throws Exception{return MdMain.Proto.strings(1,value);}
    static String answer(NativePhoneVerification v,String request)throws Exception{
        byte[] response=v.response(challenge(request));
        check(response.length==34&&response[0]==10&&response[1]==32,"response is field1 32-byte lowerhex");
        return new String((byte[])MdMain.Proto.parse(response).get(1),StandardCharsets.UTF_8);
    }
    static final class Memory implements MdTransport {
        final boolean nativeUsb;Memory(boolean value){nativeUsb=value;}
        public InputStream input(){return new ByteArrayInputStream(new byte[0]);}
        public OutputStream output(){return new ByteArrayOutputStream();}
        public String name(){return "offline-phone-verification";}
        public boolean isNativeUsb(){return nativeUsb;}
        public void close(){}public boolean awaitStopped(long timeout){return true;}
    }
    static final class Keys extends MdMain.InputSink {
        final List<Integer> seen=new ArrayList<Integer>();
        synchronized void hardkey(int key){seen.add(key);}
    }
    static MdMain.Frame take(MdMain.Session s,int expected)throws Exception{
        MdMain.Frame f=MdMain.readFrame(new DataInputStream(new ByteArrayInputStream(s.controls.remove().data)));
        check(f.id==expected,"queued command order "+expected);return f;
    }
    static byte[] startup(MdMain.Session s)throws Exception{
        take(s,0x10002);take(s,0x1001b);take(s,0x10018);return take(s,0x10004).payload;
    }
    static void sessionChecks()throws Exception{
        boolean oldBoot=MdMain.bootNative;MdMain.bootNative=true;
        PrintStream previous=System.out;ByteArrayOutputStream log=new ByteArrayOutputStream();
        System.setOut(new PrintStream(log,true,"UTF-8"));
        String nonce="2.0;session-nonce-do-not-log";
        try{
            final MdMain.Session early=new MdMain.Session(new Memory(true));
            rejects(new Checked(){public void run()throws Exception{early.dispatch(1,0x18048,challenge("2.0;early"));}},"requires sent MD_INFO");
            check(early.controls.isEmpty(),"no answer before VERSION/MD_INFO");early.close();
            Keys keys=new Keys();final MdMain.Session s=new MdMain.Session(new Memory(true),keys);
            s.dispatch(1,0x18001,MdMain.Proto.ints(1,2,2,0));
            s.dispatch(1,0x18048,challenge(nonce));
            byte[] sent=startup(s);byte[] first=take(s,0x10049).payload;
            check(Arrays.equals(first,new NativePhoneVerification(sent).response(challenge(nonce))),"actual queued MD_INFO identity drives native response");
            check(s.controls.isEmpty()&&!s.initialized&&!s.statisticReplied,"checksum does not emit a success/result or start media");
            NativePhoneVerification captured=s.phoneVerification;
            s.initialized=true;s.videoGeneration=7;s.pendingInit=new PendingVideoInit(1280,720,30,7);
            MdMain.Packet video=new MdMain.Packet(new byte[]{42},7);s.videos.add(video);
            s.dispatch(1,0x18001,MdMain.Proto.ints(1,2,2,0));s.dispatch(1,0x18048,challenge(nonce));
            check(Arrays.equals(take(s,0x10049).payload,first)&&s.phoneVerification==captured,"duplicate VERSION/challenge retains sent identity and returns same response");
            check(s.videoGeneration==7&&s.pendingInit!=null&&s.videos.peek()==video&&s.initialized&&s.open,"verification preserves pending source/video generation/session");
            s.dispatch(1,0x1804a,MdMain.Proto.ints(1,1));check(s.controls.isEmpty(),"positive HU result acknowledged without new auth traffic");
            rejects(new Checked(){public void run()throws Exception{s.dispatch(1,0x1804a,MdMain.Proto.ints(1,0));}},"reported rejection");
            rejects(new Checked(){public void run()throws Exception{s.dispatch(1,0x18048,challenge("legacy_8.6.7"));}},"AUTH_UNSUPPORTED_LEGACY");
            check(s.controls.isEmpty(),"unsupported legacy request cannot enqueue a fabricated response");
            for(int code:new int[]{1,14,30,15,16,20,21,22,23,24,31,32})s.dispatch(6,0x68008,MdMain.Proto.ints(1,code));
            check(keys.seen.equals(Arrays.asList(1,14,30,15,16,20,21,22,23,24,31,32)),"native touch dispatcher forwards supported hardkeys");
            s.dispatch(6,0x68008,MdMain.Proto.ints(1,11));check(keys.seen.size()==12&&s.open&&s.videoGeneration==7,"AA-only NAVI not injected and session kept");
            s.dispatch(1,0x1800a,new byte[0]);s.dispatch(1,0x18001,MdMain.Proto.ints(1,2,2,1));
            byte[] nextInfo=startup(s);check(s.nativeLogicalSession==2&&s.phoneVerification!=captured,"logical reconnect owns fresh verification identity");
            s.dispatch(1,0x18048,challenge("2.0;new-session"));check(Arrays.equals(take(s,0x10049).payload,new NativePhoneVerification(nextInfo).response(challenge("2.0;new-session"))),"reconnected response bound to current MD_INFO");
            s.close();check(s.controls.isEmpty()&&s.videos.isEmpty(),"terminal cleanup drains auth/video queues");
            final MdMain.Session dev=new MdMain.Session(new Memory(false));dev.dispatch(1,0x18001,MdMain.Proto.ints(1,1,2,0));dev.controls.clear();
            rejects(new Checked(){public void run()throws Exception{dev.dispatch(1,0x18048,challenge("2.0;dev"));}},"AUTH_UNSUPPORTED");
            check(dev.controls.isEmpty()&&dev.phoneVerification==null,"SELF development auth contract unchanged");dev.close();
        }finally{System.setOut(previous);MdMain.bootNative=oldBoot;}
        check(!log.toString("UTF-8").contains("session-nonce-do-not-log"),"nonce omitted from diagnostic logs");
        check(log.toString("UTF-8").contains("await_result"),"diagnostics distinguish response from HU acceptance");
    }
    public static void main(String[] args)throws Exception {
        final byte[] info=identity("qti","TBox Ultra 1","35");
        final NativePhoneVerification v=new NativePhoneVerification(info);
        String a=answer(v,"2.0;2026-10-14-12-01-33");
        check(a.equals("e330041700aabf8c288c329b01bd6beb"),"ASCII official formula vector");
        check(answer(v,"2.0;2026-10-14-12-01-33").equals(a),"repeat challenge idempotent");
        check(answer(v,"2.0;different").matches("[0-9a-f]{32}")&&!answer(v,"2.0;different").equals(a),"new nonce recomputed");
        Arrays.fill(info,(byte)0);
        check(answer(v,"2.0;2026-10-14-12-01-33").equals(a),"captured sent identity is immutable");
        NativePhoneVerification another=new NativePhoneVerification(identity("qti","TBox Ultra 1","29"));
        check(answer(another,"2.0;2026-10-14-12-01-33").equals("e483275f34ea4d31b8e9cda02f8e885f"),"new logical session uses its own sent SDK");
        NativePhoneVerification parts=new NativePhoneVerification(identity("Baidu","Box","35"));
        check(answer(parts,"2.0;abc;ignored").equals("a2f8af76010ca56e06376fd05326a7dc"),"only second semicolon part participates");
        check(answer(parts,"2.0;abc;;").equals(answer(parts,"2.0;abc")),"Java split trailing empty semantics");
        NativePhoneVerification unicode=new NativePhoneVerification(identity("品牌","盒子😀","35"));
        check(answer(unicode,"2.0;n\u0000x").equals("2c14a1c1c1f182d7dcd61333667bdb22"),"JNI modified UTF8 for NUL and surrogate pairs");
        for(final String unsupported:new String[]{"random_8.6.7","2.0","2.0;","2.0;;tail","3.0;abc"})
            rejects(new Checked(){public void run()throws Exception{v.response(challenge(unsupported));}},"AUTH_UNSUPPORTED_LEGACY");
        rejects(new Checked(){public void run()throws Exception{v.response(new byte[0]);}},"requires string field");
        rejects(new Checked(){public void run()throws Exception{v.response(MdMain.Proto.ints(1,7));}},"requires string field");
        rejects(new Checked(){public void run()throws Exception{v.response(new byte[]{10,2,(byte)0xc3,0x28});}},"invalid UTF-8");
        rejects(new Checked(){public void run()throws Exception{v.response(new byte[65536]);}},"payload bound");
        rejects(new Checked(){public void run()throws Exception{new NativePhoneVerification(MdMain.Proto.strings(4,"brand",14,"model"));}},"requires string field 20");
        for(int[] pair:new int[][]{{1,3},{14,4},{30,3},{15,88},{16,87},{20,23},{21,21},{22,22},{23,19},{24,20},{31,126},{32,127}})
            check(NativeSessionProtocol.androidKeyCode(pair[0])==pair[1],"native Android hardkey mapping "+pair[0]);
        for(int unknown:new int[]{-1,6,7,9,11,17,18,33,65538})
            check(NativeSessionProtocol.androidKeyCode(unknown)==-1,"no invented AA/rotary/volume/app key mapping "+unknown);
        sessionChecks();
        System.out.println("PASS "+checks+" v2 phone verification and public Android hardkey checks; offline, no HU acceptance claim");
    }
}
