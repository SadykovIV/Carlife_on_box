package org.carlife.hulab;
import java.io.*;
import java.util.*;
import android.os.SystemClock;

/** Actual Session/native dispatch and canonical lifecycle; only Android injection is replaced. */
public final class NativeTouchRecoveryTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static final class Memory implements MdTransport {
        final boolean nativeUsb;Memory(boolean n){nativeUsb=n;}
        public InputStream input(){return new ByteArrayInputStream(new byte[0]);}
        public OutputStream output(){return new ByteArrayOutputStream();}
        public String name(){return "input-test";}public boolean isNativeUsb(){return nativeUsb;}
        public void close(){}public boolean awaitStopped(long ms){return true;}
    }
    static final class Sink extends MdMain.InputSink {
        final List<TouchFrame> injected=new ArrayList<TouchFrame>();int rejectAction=-1,throwAction=-1;boolean throwCancel;
        boolean injectTouchFrame(TouchFrame f){injected.add(f);if(f.action==throwAction||(throwCancel&&f.action==3))throw new IllegalStateException("fixture Android injection exception");return f.action!=rejectAction;}
    }
    static MdMain.Session session(boolean nativeUsb,Sink sink)throws Exception{
        MdMain.Session s=new MdMain.Session(new Memory(nativeUsb),sink);s.version=true;s.everNegotiated=true;s.huMajor=2;s.initialized=true;s.geometry=new ProjectionGeometry(1920,1080,1280,720,0);s.videoGeneration=7;s.videos.add(new MdMain.Packet(new byte[]{42},7));return s;
    }
    static void preserved(MdMain.Session s){check(s.open&&s.initialized&&s.everNegotiated&&s.videoGeneration==7&&s.videos.size()==1&&s.terminalReason==null,"input must not retire owner/reset source/clear video");}
    static byte[] single(int a,int x,int y){return MdMain.Proto.ints(1,a,2,x,3,y);}
    static byte[] dual(int a,int x,int y,int x2,int y2){return MdMain.Proto.ints(1,a,2,x,3,y,4,x2,5,y2);}
    static byte[] hex(String str){String h=str.replace(" ","");byte[] b=new byte[h.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(h.substring(2*i,2*i+2),16);return b;}
    static void send(MdMain.Session s,int id,byte[] p,long time)throws Exception{SystemClock.fixedTime=time;s.dispatch(6,id,p);}
    static void legacy(MdMain.Session s,byte[] p,long time)throws Exception{send(s,0x68001,p,time);}
    static void duplicateAndRestart()throws Exception{
        for(int[] f:new int[][]{{198797,198826,571,236},{222116,222158,956,210}}){
            Sink k=new Sink();MdMain.Session s=session(true,k);legacy(s,single(0,f[2],f[3]),f[0]);legacy(s,single(0,f[2],f[3]),f[1]);
            check(k.injected.size()==1&&k.state.downTime==f[0]&&s.touchRecoveries==1,"actual Honda duplicate DOWN stays idempotent with original downTime");
            legacy(s,single(1,f[2],f[3]),f[1]+100);check(k.injected.size()==2&&k.state.ids.length==0,"duplicate still allows coherent UP");preserved(s);
        }
        Sink k=new Sink();MdMain.Session s=session(true,k);legacy(s,single(0,10,20),100);legacy(s,single(0,30,40),110);
        check(k.injected.size()==3&&k.injected.get(1).action==3&&k.injected.get(2).action==0&&k.state.downTime==110,"changed DOWN cancels old contact before new downTime");preserved(s);
        legacy(s,single(2,35,45),120);legacy(s,single(0,35,45),130);
        check(k.injected.get(4).action==3&&k.injected.get(5).action==0,"DOWN following MOVE starts fresh gesture even at same coords");
    }
    static void legacyTransitions()throws Exception{
        Sink k=new Sink();MdMain.Session s=session(true,k);
        legacy(s,single(0,0,0),100);legacy(s,dual(0x105,0,0,100,100),101);legacy(s,dual(2,0,0,110,110),102);
        check(Arrays.equals(k.state.ids,new int[]{0,1})&&k.injected.get(1).actionIndex==1,"single to actual dual fields + POINTER_DOWN accepted, origin zeros not absence");
        legacy(s,dual(6,0,0,110,110),103);legacy(s,single(2,120,120),104);legacy(s,single(1,120,120),105);
        check(k.injected.size()==6&&k.injected.get(4).ids[0]==1&&k.injected.get(5).ids[0]==1&&k.state.last==null,"first pointer lift compacts coords but retains ID1 through dual-to-single");preserved(s);
        Sink invalid=new Sink();MdMain.Session q=session(true,invalid);legacy(q,single(0,1,1),100);legacy(q,MdMain.Proto.ints(1,0x105,2,1,3,1,4,20),101);
        check(q.touchRecoveries==1&&invalid.ignoreUntilDown&&invalid.state.last==null,"partial second-coordinate pair is not guessed");preserved(q);
    }
    static void androidRefusalAndExceptions()throws Exception{
        Sink k=new Sink();MdMain.Session s=session(true,k);legacy(s,single(0,260,497),280080);k.rejectAction=1;legacy(s,single(1,260,497),280263);
        check(k.injected.size()==3&&k.injected.get(2).action==3&&k.injected.get(2).xs[0]==390.25f&&k.injected.get(2).ys[0]==745.75f,"rejected Honda UP explicitly CANCELs attempted mapped contact after state was cleared");
        check(k.ignoreUntilDown&&k.state.last==null&&k.lastAttempt==null,"failed UP leaves no local contact");preserved(s);
        legacy(s,single(2,270,500),280270);check(k.injected.size()==3,"MOVE after failure ignored until fresh DOWN");
        k.rejectAction=-1;legacy(s,single(0,270,500),280280);legacy(s,single(1,270,500),280290);check(k.injected.size()==5&&!k.ignoreUntilDown,"next gesture works after Android refusal");
        Sink p=new Sink();MdMain.Session q=session(true,p);legacy(q,single(0,10,10),100);legacy(q,dual(0x105,10,10,20,20),101);p.rejectAction=6;legacy(q,dual(6,10,10,20,20),102);
        check(p.injected.get(3).action==3&&p.injected.get(3).ids.length==2,"rejected POINTER_UP CANCEL contains all attempted IDs, including removed one");preserved(q);
        Sink r=new Sink();MdMain.Session t=session(true,r);legacy(t,single(0,10,10),100);r.throwAction=2;r.throwCancel=true;legacy(t,single(2,12,12),101);
        check(r.ignoreUntilDown&&r.state.last==null&&r.lastAttempt==null,"RuntimeException and failed CANCEL remain local and clear contact");preserved(t);
    }
    static void action3AndMalformed()throws Exception{
        Sink k=new Sink();MdMain.Session s=session(true,k);
        send(s,0x6800e,hex("00000000 0700000000"),100);
        send(s,0x6800e,hex("00000105 0700000000 0900640064"),101);
        check(Arrays.equals(k.state.ids,new int[]{7,9}),"existing fixed ACTION3 contract unchanged");
        send(s,0x6800e,hex("00000002 0700000000 0700640064"),102);
        check(k.state.last==null&&k.ignoreUntilDown&&s.touchRecoveries==1,"duplicate ACTION3 IDs cancel locally without inventing new wire format");preserved(s);
        send(s,0x6800e,new byte[]{0,0,0},103);preserved(s);
        legacy(s,new byte[]{8,(byte)0x80},104);check(s.touchRecoveries==3,"malformed protobuf before dispatchTouch is contained");preserved(s);
        legacy(s,single(0,20,20),105);check(k.state.last!=null&&!k.ignoreUntilDown,"fresh DOWN recovers after malformed wire payload");
        MdMain.Session uninit=session(true,new Sink());uninit.initialized=false;legacy(uninit,single(0,10,10),100);check(uninit.open&&uninit.sink.state.last==null,"early touch cannot inject or terminate session");
        try{s.dispatch(1,0x18001,new byte[]{8,(byte)0x80});throw new AssertionError("malformed CMD swallowed");}catch(IOException expected){checks++;}
        try{s.dispatch(1,0x18048,new byte[0]);throw new AssertionError("mandatory auth swallowed");}catch(IOException expected){checks++;}
    }
    static void devUnchangedAndNoNativeCountLimit()throws Exception{
        Sink k=new Sink();MdMain.Session s=session(false,k);legacy(s,single(0,10,10),100);
        try{legacy(s,single(0,10,10),101);throw new AssertionError("DEV duplicate accepted");}catch(IOException expected){check(expected.getMessage().contains("DOWN"),"DEV duplicate lifecycle remains strict");}
        try{legacy(s,dual(0x105,10,10,20,20),102);throw new AssertionError("DEV profile changed");}catch(IOException expected){check(expected.getMessage().contains("profile"),"DEV wire-family rule unchanged");}
        try{legacy(s,new byte[]{8,(byte)0x80},103);throw new AssertionError("DEV malformed swallowed");}catch(IOException expected){checks++;}
        Sink n=new Sink();MdMain.Session nativeS=session(true,n);nativeS.touchCount=200000;legacy(nativeS,single(0,1,2),100);legacy(nativeS,single(1,1,2),101);check(nativeS.touchCount==200002&&n.injected.size()==2,"native cumulative counter is not a lifetime input cutoff");
        s.touchCount=200000;try{legacy(s,single(1,10,10),104);throw new AssertionError("DEV count bound removed");}catch(IOException expected){check(expected.getMessage().contains("count"),"DEV input bound retained");}
    }
    static void nativeHoldAndDevTimeout()throws Exception{
        Sink k=new Sink();MdMain.Session s=session(true,k);
        legacy(s,single(0,10,10),100);legacy(s,dual(0x105,10,10,20,20),101);
        SystemClock.fixedTime=5101;s.checkInputTimeout();
        check(k.state.ids.length==2&&k.injected.size()==2&&!k.ignoreUntilDown,"native two-contact hold survives5s without artificial idle CANCEL");
        legacy(s,dual(2,12,12,22,22),5102);legacy(s,dual(0x106,12,12,22,22),5103);legacy(s,single(1,12,12),5104);
        check(k.injected.size()==5&&k.state.last==null&&s.touchRecoveries==0,"native MOVE/POINTER_UP/UP remain coherent after long hold");preserved(s);
        Sink d=new Sink();MdMain.Session dev=session(false,d);legacy(dev,single(0,10,10),100);SystemClock.fixedTime=5101;dev.checkInputTimeout();
        check(d.state.last==null&&d.injected.get(1).action==3&&d.ignoreUntilDown,"DEV3s idle timeout unchanged");
    }
    static void boundedEvidence()throws Exception{
        Sink k=new Sink();MdMain.Session s=session(true,k);legacy(s,single(0,10,10),100);
        for(int i=0;i<40;i++)legacy(s,single(2,10+i,10),101+i);
        check(s.touchOrdinaryIngress==8&&s.touchPriorityIngress==0,"ordinary MOVE cannot exhaust priority ingress allocation");
        legacy(s,dual(0x105,50,10,80,20),150);check(s.touchPriorityIngress==1,"real dual transition still records raw ingress after MOVE quota");
        for(int i=0;i<30;i++)legacy(s,dual(0x105,50,10,80,20),151+i);
        check(s.touchPriorityIngress==24&&s.touchOrdinaryIngress==8,"total ingress at most32 with8 ordinary and24 priority");
        for(int i=0;i<20;i++)legacy(s,new byte[]{8,(byte)0x80},200+i);
        check(s.touchRecoveries>16,"recovery counter continues beyond raw/header quota");
        byte[] huge=new byte[10000];Arrays.fill(huge,(byte)0x10);String hx=MdMain.Session.touchHex(huge);
        check(hx.length()==191&&hx.split(" ").length==64&&!hx.contains("101010"),"hex prefix bounded64B and separated for existing privacy redactor");
        check(AsyncRingLog.headerLimit("native_touch_ingress")==32&&AsyncRingLog.headerLimit("native_touch_recovered")==16,"bounded independent header quotas");
    }
    public static void main(String[] args)throws Exception{
        MdMain.bootNative=true;MdMain.touchProfile="recognized";MdMain.running=true;
        ByteArrayOutputStream logs=new ByteArrayOutputStream();PrintStream old=System.out;System.setOut(new PrintStream(logs,true,"UTF-8"));
        try{duplicateAndRestart();legacyTransitions();androidRefusalAndExceptions();action3AndMalformed();devUnchangedAndNoNativeCountLimit();nativeHoldAndDevTimeout();boundedEvidence();}
        finally{System.setOut(old);SystemClock.fixedTime=-1;MdMain.bootNative=false;}
        String text=logs.toString("UTF-8");check(text.contains("\"policy\":\"duplicate_down_ignored\"")&&text.contains("\"policy\":\"cancel_wait_down\""),"recovery reasons retained");
        check(text.contains("\"recovery_count\":21")&&text.contains("\"payload_hex\":null"),"every recovery has compact tail event after full detail budget");
        check(text.contains("\"payload_hex\":\"08 00"),"prevalidation wire evidence retains spaced bytes");
        check(text.contains("\"event\":\"native_touch_ingress_tail\"")&&text.contains("\"payload_hex\":\"08 80\""),"tail raw continues after header allocation and includes malformed protobuf");
        System.out.println("PASS "+checks+" actual native touch dispatch/recovery/dual/ACTION3/Android refusal/DEV boundary/evidence checks; no device calls");
    }
}
