package org.carlife.hulab;
import java.io.*;
import java.util.*;

/** Independent fixed protocol bytes, reference sequence and previous source-lifecycle invariants. */
public final class NativeCompatibilityTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static final class Memory implements MdTransport {
        final boolean nativeUsb;Memory(){this(true);}Memory(boolean n){nativeUsb=n;}
        public InputStream input(){return new ByteArrayInputStream(new byte[0]);}
        public OutputStream output(){return new ByteArrayOutputStream();}
        public String name(){return "DIRECT_USB_AOA";}public boolean isNativeUsb(){return nativeUsb;}
        public void close(){}public boolean awaitStopped(long ms){return true;}
    }
    static MdMain.Frame take(MdMain.Session s,int id)throws Exception {
        MdMain.Frame f=MdMain.readFrame(new DataInputStream(new ByteArrayInputStream(s.controls.remove().data)));check(f.id==id,"expected id0x"+Integer.toHexString(id)+" got0x"+Integer.toHexString(f.id));return f;
    }
    static void selectedModeAndBoundedDiagnostics()throws Exception {
        MdMain.bootNative=true;MdMain.videoSource="mediacodec";MdMain.displayWidth=1920;MdMain.displayHeight=1080;
        MdMain.Session v2=new MdMain.Session(new Memory());v2.dispatch(1,0x18001,MdWireTest.hex("08021000"));v2.controls.clear();
        v2.dispatch(1,0x18007,MdWireTest.hex("0880081080061800")); // Independent1024x768@0 wire fixture.
        check(v2.pendingInit.width==1280&&v2.pendingInit.height==720&&v2.pendingInit.fps==30,"native2 selects R720 rather than echoing4:3 HU request");
        PendingVideoInit ticket=v2.pendingInit;
        v2.dispatch(1,0x18007,MdWireTest.hex("08a00610e003180f")); //800x480@15 repeat while pending.
        check(v2.pendingInit==ticket&&v2.controls.isEmpty(),"repeat pendingINIT cannot replace selected mode or acknowledge early");
        v2.completeBootInit(ticket,new MediaCodecDisplaySource.Selection("TEST","repeat-previous-frame-after","max-fps-to-encoder",1280,720,30));
        check(Arrays.equals(take(v2,0x10008).payload,MdWireTest.hex("08800a10d005181e")),"actual encoder/ACK1280x720@30 golden");take(v2,0x1001b);
        check(v2.codecSelection.width==v2.width&&v2.codecSelection.height==v2.height&&v2.geometry.videoWidth==v2.width&&v2.geometry.videoHeight==v2.height,"acceptedmode agrees across encoder ACK input");
        check(v2.geometry.left==0&&v2.geometry.top==0&&v2.geometry.contentWidth==1280&&v2.geometry.contentHeight==720,"source1920x1080 has no output bars");
        float[] center=v2.geometry.toSource(640,360);check(Math.abs(center[0]-960.25)<.001&&Math.abs(center[1]-540.25)<.001,"input remains accepted-video tosource once, noHUrequest/DPIscale");
        v2.dispatch(1,0x18007,MdWireTest.hex("088008108006180f"));check(Arrays.equals(take(v2,0x10008).payload,MdWireTest.hex("08800a10d005181e")),"accepted repeatINIT retains choice andfps");
        PrintStream previous=System.out;ByteArrayOutputStream logs=new ByteArrayOutputStream();System.setOut(new PrintStream(logs));
        try {
            TouchFrame raw=new TouchFrame(0,new int[]{0},new float[]{640},new float[]{360},0,1,100,"legacy-single");
            for(int i=0;i<40;i++)v2.recordTouchMapping(0x68001,raw,raw.mapped(v2.geometry),v2.geometry);
            for(int i=0;i<11;i++)v2.dispatch(6,0x68005,new byte[0]);
        } finally {System.setOut(previous);}
        String evidence=logs.toString("UTF-8");
        check(evidence.split("\"event\":\"native_touch_mapping\"",-1).length-1==32,"only32 actual raw/mapped log lines after40samples");
        check(evidence.contains("\"raw_xs\":\"[640.0]\"")&&evidence.contains("\"mapped_xs\":\"[960.25]\""),"log distinguishes raw andmapped coordinates");
        check(evidence.split("\"event\":\"native_touch_unknown\"",-1).length-1==8,"unknownopcode log bounded at8 actual lines");
        v2.close();
        MdMain.Session v1=new MdMain.Session(new Memory());v1.dispatch(1,0x18001,MdWireTest.hex("08011000"));v1.controls.clear();
        v1.dispatch(1,0x18007,MdWireTest.hex("088008108006181e"));check(v1.pendingInit.width==1024&&v1.pendingInit.height==768,"nativev1 still honors requested1024x768");
        v1.completeBootInit(v1.pendingInit,new MediaCodecDisplaySource.Selection("TEST","repeat-previous-frame-after","max-fps-to-encoder",1024,768,30));
        check(Arrays.equals(take(v1,0x10008).payload,MdWireTest.hex("088008108006181e"))&&v1.geometry.top==96,"v1 ACKandfit unchanged");v1.close();
        MdMain.bootNative=false;MdMain.videoSource="bootstrap";
        MdMain.Session dev=new MdMain.Session(new Memory(false));dev.dispatch(1,0x18001,MdWireTest.hex("08011000"));dev.controls.clear();dev.dispatch(1,0x18007,MdWireTest.hex("088008108006181e"));
        check(Arrays.equals(take(dev,0x10008).payload,MdWireTest.hex("088008108006181e"))&&dev.geometry.top==96,"devv1 ACKandfit unchanged");dev.close();
    }
    public static void main(String[] args)throws Exception {
        byte[] multiFeature=NativeSessionProtocol.multitouchFeatureRequest();
        check(Arrays.equals(multiFeature,MdWireTest.hex("0801120f0a0b4d554c54495f544f5543481001")),"SDK feature list: cnt1, MULTI_TOUCH=1 exact protobuf bytes");
        check(Arrays.equals(MdMain.packet(1,0x10051,multiFeature,0),MdWireTest.hex("000000010000001b00130000000100510801120f0a0b4d554c54495f544f5543481001")),"MULTI_TOUCH request uses CMD0x10051 unchanged outer/inner framing");
        MdMain.bootNative=true;MdMain.videoSource="mediacodec";
        MdMain.Session s=new MdMain.Session(new Memory());
        s.dispatch(1,0x18001,MdWireTest.hex("08021000"));
        check(Arrays.equals(take(s,0x10002).payload,MdWireTest.hex("0801")),"2.0 MATCH1 golden");
        take(s,0x1001b);take(s,0x10018);
        Map<Integer,Object> info=MdMain.Proto.parse(take(s,0x10004).payload);
        check(MdMain.Proto.number(info,21,0)==29&&MdMain.Proto.number(info,25,0)==1,"actual SDK/device type");
        s.dispatch(1,0x18001,MdWireTest.hex("08021000"));check(s.controls.isEmpty()&&s.nativeLogicalSession==1,"duplicate VERSION no ACK/reset");
        s.dispatch(1,0x18003,MdWireTest.hex("0a054c696e7578"));check(s.controls.isEmpty(),"MD_INFO did not wait for HU_INFO and is not duplicated");
        s.dispatch(1,0x18027,new byte[0]);
        check(Arrays.equals(take(s,0x1004b).payload,MdWireTest.hex("0801")),"MD accepts HU (not challenge proof)");
        check(Arrays.equals(take(s,0x10051).payload,multiFeature),"native feature request advertises actual multi-pointer decoder; no transparent-mode override");take(s,0x10031);
        s.dispatch(1,0x18027,new byte[0]);check(s.controls.isEmpty(),"STATISTIC repeat idempotent");
        s.dispatch(1,0x1804a,MdWireTest.hex("0801"));check(s.controls.isEmpty()&&s.version,"positive HU_AUTHEN_RESULT is a result, not a challenge");
        try{s.dispatch(1,0x1804a,MdWireTest.hex("0800"));throw new AssertionError("negative HU auth result ignored");}catch(IOException expected){checks++;}
        s.dispatch(1,0x18043,MdWireTest.hex("08021202080012020802"));
        check(Arrays.equals(take(s,0x10044).payload,MdWireTest.hex("0802120408001000120408021000")),"repeated metadata subscriptions preserved, unsupported producers reported false");
        s.moduleStatusDue=1;s.nativeProgress();
        check(Arrays.equals(take(s,0x10026).payload,MdWireTest.hex("0807120408011000120408021000120408031000120408041000120408061000120408081000120408091000")),"7 idle modules golden");
        s.dispatch(1,0x18007,MdWireTest.hex("08800a10d0051800"));
        check(s.pendingInit.fps==30&&s.pendingInit.width==1280&&s.controls.isEmpty(),"Honda fps0 accepted as30, waits for actual capability");
        PendingVideoInit ticket=s.pendingInit;s.dispatch(1,0x18001,MdWireTest.hex("08021000"));check(s.pendingInit==ticket,"duplicate version keeps pending INIT");
        s.dispatch(1,0x18007,MdWireTest.hex("088008108006181e"));check(s.pendingInit==ticket&&s.controls.isEmpty(),"duplicate INIT retains original pending mode without ACK");
        s.dispatch(1,0x1800b,new byte[0]);check(s.pendingInit==null&&!s.initialized,"pre-initialization RESET cancels pending capability");s.completeBootInit(ticket,null);check(s.controls.isEmpty(),"stale capability callback cannot ACK after RESET");
        s.dispatch(1,0x18007,MdWireTest.hex("08800a10d0051800"));ticket=s.pendingInit;
        MdMain.displayWidth=1920;MdMain.displayHeight=1080;
        s.completeBootInit(ticket,new MediaCodecDisplaySource.Selection("TEST","repeat-previous-frame-after","max-fps-to-encoder",1280,720,30));
        check(Arrays.equals(take(s,0x10008).payload,MdWireTest.hex("08800a10d005181e")),"accepted INIT_DONE golden");take(s,0x1001b);
        check(s.nativeFallbackReady(s.nativeStartDue)&&!s.nativeFallbackReady(s.nativeStartDue-1),"1500ms fallback boundary");
        long generation=s.videoGeneration;MediaCodecDisplaySource source=new MediaCodecDisplaySource(s,s.codecSelection,generation);s.mediaSource=source;
        long startDue=s.nativeStartDue;s.dispatch(1,0x18007,MdWireTest.hex("088008108006180f"));check(Arrays.equals(take(s,0x10008).payload,MdWireTest.hex("08800a10d005181e"))&&s.videoGeneration==generation&&s.mediaSource==source&&s.nativeStartDue==startDue,"duplicate INIT ACKs actual prior mode, preserves source and timer");
        s.dispatch(1,0x1800b,new byte[0]);check(s.initialized&&s.mediaSource==source&&s.videoGeneration==generation&&source.syncFrameRequested.get(),"native RESET queues keyframe without tearing down source");
        s.dispatch(1,0x1800a,new byte[0]);check(!s.nativeFallbackReady(Long.MAX_VALUE)&&s.nativeStartDue==0&&!source.syncFrameRequested.get()&&!source.requestKeyFrame(),"PAUSE cancels fallback and stale source keyframe");
        s.dispatch(1,0x1800a,new byte[0]);s.dispatch(1,0x18001,MdWireTest.hex("08021001"));check(s.huMinor==1&&s.nativeLogicalSession==2&&s.controls.size()==4,"new paused2.1 session full negotiation");
        check(NativeSessionProtocol.versionStatus(MdMain.Proto.parse(MdWireTest.hex("08031005")))==1,"no arbitrary HU version allowlist");
        check(NativeSessionProtocol.fps(15)==15&&NativeSessionProtocol.fps(60)==30&&NativeSessionProtocol.fps(-1)==30,"reference automatic fps selection");
        s.close();check(!s.nativeFallbackReady(Long.MAX_VALUE),"closed session never starts fallback");MdMain.bootNative=false;
        selectedModeAndBoundedDiagnostics();
        System.out.println("PASS "+checks+" native2.0/2.1 handshake, reference bytes, repeated VERSION, fps0, pending INIT/RESET, fallback cancellation; no device I/O");
    }
}
