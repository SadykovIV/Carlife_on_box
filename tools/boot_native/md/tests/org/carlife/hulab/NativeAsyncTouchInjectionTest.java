package org.carlife.hulab;
import java.io.*;
import java.util.*;
import android.os.SystemClock;
import android.view.InputEvent;

/** Actual native dispatch chooses the platform mode; only platform allocation/IPC is mocked. */
public final class NativeAsyncTouchInjectionTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static final class Sink extends MdMain.InputSink {
        final List<Integer> modes=new ArrayList<Integer>(),actions=new ArrayList<Integer>(),keyModes=new ArrayList<Integer>();
        final List<int[]> ids=new ArrayList<int[]>();
        int reject=-1,throwAction=-1;long simulatedIpcMs=3;
        boolean injectTouchFrameWithMode(TouchFrame f,int mode){
            modes.add(mode);actions.add(f.fullAction());ids.add(f.ids.clone());
            // This fixture verifies measured elapsed fields, not device speed.
            SystemClock.fixedTime+=simulatedIpcMs;
            if(f.action==throwAction)throw new IllegalStateException("fixture IPC exception");
            return f.action!=reject;
        }
        boolean event(InputEvent e,int display,int mode){keyModes.add(mode);return true;}
    }
    static MdMain.Session session(boolean nativeUsb,Sink sink)throws Exception{
        MdMain.Session s=new MdMain.Session(new NativeTouchRecoveryTest.Memory(nativeUsb),sink);
        s.version=true;s.everNegotiated=true;s.huMajor=2;s.initialized=true;
        s.geometry=new ProjectionGeometry(1920,1080,1280,720,0);s.videoGeneration=7;
        s.videos.add(new MdMain.Packet(new byte[]{42},7));return s;
    }
    static void touch(MdMain.Session s,byte[] p,long time)throws Exception{SystemClock.fixedTime=time;s.dispatch(6,0x68001,p);}
    static byte[] single(int action,int x,int y){return MdMain.Proto.ints(1,action,2,x,3,y);}
    static byte[] dual(int action,int x,int y,int x2,int y2){return MdMain.Proto.ints(1,action,2,x,3,y,4,x2,5,y2);}
    static void nativeOrderedModesAndCancel()throws Exception{
        Sink k=new Sink();MdMain.Session s=session(true,k);
        touch(s,single(0,10,20),100);touch(s,dual(0x105,10,20,30,40),110);
        touch(s,dual(2,11,21,31,41),120);touch(s,dual(0x106,11,21,31,41),130);
        touch(s,single(1,11,21),140);
        check(k.actions.equals(Arrays.asList(0,0x105,2,0x106,1)),"async mode must not reorder/drop pointer lifecycle");
        check(k.modes.equals(Arrays.asList(0,0,0,0,0)),"all native touchscreen events choose actual ASYNC0 boundary");
        check(s.open&&s.videoGeneration==7&&s.videos.size()==1&&k.state.last==null,"source and session remain untouched");
        touch(s,single(0,10,20),150);k.cancel();
        check(k.actions.get(k.actions.size()-1)==3&&k.modes.get(k.modes.size()-1)==0,"control-path CANCEL uses same native async sequence");
        k.event(null,0);
        check(k.keyModes.equals(Arrays.asList(2)),"unchanged default event path used by hard keys retains WAIT_FOR_FINISH2");
    }
    static void rejectionRecoveryStillOrdered()throws Exception{
        Sink k=new Sink();MdMain.Session s=session(true,k);touch(s,single(0,10,20),200);k.reject=1;touch(s,single(1,10,20),210);
        check(k.actions.equals(Arrays.asList(0,1,3))&&k.modes.equals(Arrays.asList(0,0,0)),"false async admission still emits attempted-contact CANCEL in order");
        check(k.state.last==null&&k.ignoreUntilDown&&s.touchRecoveries==1&&s.open,"local recovery preserved");
        k.reject=-1;touch(s,single(0,20,30),220);touch(s,single(2,21,31),230);
        check(k.actions.equals(Arrays.asList(0,1,3,0,2)),"fresh DOWN resumes coherent input after admission failure");
        k.throwAction=2;touch(s,single(2,22,32),240);
        check(s.open&&s.touchRecoveries==2&&k.actions.get(k.actions.size()-1)==3,"platform exception remains input-local with CANCEL");
    }
    static void devModeAndTiming()throws Exception{
        Sink d=new Sink();MdMain.Session dev=session(false,d);touch(dev,single(0,10,20),300);touch(dev,single(2,11,21),310);touch(dev,single(1,11,21),320);
        check(d.modes.equals(Arrays.asList(2,2,2)),"DEV touchscreen behavior retains WAIT_FOR_FINISH2");
        Sink n=new Sink();n.simulatedIpcMs=174;MdMain.Session nativeS=session(true,n);touch(nativeS,single(0,10,20),1000);
        check(nativeS.nativeTouchIngressUptime==1000&&n.nativeInputCount==1,"input identity/timing begins at native dispatch before raw logging");
        check(SystemClock.fixedTime==1174,"async selection does not fabricate zero delay or impose a50ms timing cap");
    }
    public static void main(String[] args)throws Exception{
        MdMain.bootNative=true;MdMain.touchProfile="recognized";MdMain.running=true;
        PrintStream old=System.out;ByteArrayOutputStream buffer=new ByteArrayOutputStream();System.setOut(new PrintStream(buffer,true,"UTF-8"));
        try{nativeOrderedModesAndCancel();rejectionRecoveryStillOrdered();devModeAndTiming();}
        finally{System.setOut(old);SystemClock.fixedTime=-1;MdMain.bootNative=false;}
        String text=buffer.toString("UTF-8");
        check(text.contains("\"injection_mode\":0")&&text.contains("asynchronous_admission_only; app_handling_and_visible_response_not_confirmed"),"acceptance explicitly excludes application handling/visible response");
        check(text.contains("\"ingress_uptime_ms\":1000")&&text.contains("\"inject_started_uptime_ms\":1000")&&text.contains("\"inject_completed_uptime_ms\":1174")&&text.contains("\"inject_call_duration_ms\":174"),"raw measured timestamps and duration retained without cap");
        check(text.contains("\"injection_mode\":2")&&text.contains("wait_for_finish_result"),"DEV timing/semantics explicit and mode unchanged");
        System.out.println("PASS "+checks+" native ASYNC touchscreen selection/order/CANCEL/rejection/timing and DEV/hardkey mode checks; simulated platform, no device calls");
    }
}
