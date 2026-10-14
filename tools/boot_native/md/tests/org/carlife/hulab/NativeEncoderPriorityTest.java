package org.carlife.hulab;
import android.media.MediaFormat;
import java.util.*;
/** Compare complete emitted format maps, opt-in isolation, and the actual pending-INIT path. */
public final class NativeEncoderPriorityTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static MediaCodecDisplaySource.Selection plan(boolean realtime){return new MediaCodecDisplaySource.Selection("TEST","repeat-previous-frame-after","max-fps-to-encoder",1280,720,30,realtime);}
    static void onlyPriorityDiff(String path){
        MdMain.videoPath=path;
        MediaCodecDisplaySource.Selection old=new MediaCodecDisplaySource.Selection("TEST","repeat-previous-frame-after","max-fps-to-encoder",1280,720,30);
        Map<String,Object> baseline=old.format().snapshot(), changed=plan(true).format().snapshot();
        check(!baseline.containsKey("priority"),"ordinary/default call retains omitted priority");
        check(Integer.valueOf(0).equals(changed.remove("priority")),"native experiment requests realtime0");
        check(changed.equals(baseline),"complete format otherwise byte-value/type equivalent: "+path);
        check(baseline.equals(old.format().snapshot()),"constructing realtime selection cannot mutate existing default");
        check(baseline.equals(plan(false).format().snapshot()),"explicit false agrees with legacy constructor");
        check(Integer.valueOf(4000000).equals(baseline.get("bitrate"))&&Integer.valueOf(30).equals(baseline.get("frame-rate")),"rate and bitrate unchanged");
        check(Float.valueOf(30).equals(baseline.get("max-fps-to-encoder"))&&Integer.valueOf(2).equals(baseline.get("i-frame-interval")),"cap and GOP unchanged");
        check(baseline.containsKey("repeat-previous-frame-after")==path.equals("direct"),"EGL never enables framework repeat");
    }
    public static void main(String[]args)throws Exception{
        MdMain.displayWidth=1920;MdMain.displayHeight=1080;
        onlyPriorityDiff("egl");onlyPriorityDiff("direct");
        MdMain.videoPath="egl";MdMain.bootNative=true;MdMain.videoSource="mediacodec";
        MdMain.Session s=new MdMain.Session(new NativeCompatibilityTest.Memory());
        s.dispatch(1,0x18001,MdWireTest.hex("08021000"));s.controls.clear();
        s.dispatch(1,0x18007,MdWireTest.hex("0880081080061800"));
        PendingVideoInit ticket=s.pendingInit;MediaCodecDisplaySource.Selection selected=plan(true);
        s.completeBootInit(ticket,selected);
        check(s.codecSelection==selected&&s.codecSelection.format().getInteger("priority")==0,"accepted asynchronous INIT retains exact opted-in selection used by encoder");
        check(s.width==1280&&s.height==720&&s.acceptedFps==30,"accepted video mode unchanged");
        s.controls.clear();s.dispatch(1,0x18007,MdWireTest.hex("08a00610e003180f"));
        check(s.codecSelection==selected,"repeated INIT cannot replace opted-in plan");
        check(Arrays.equals(NativeCompatibilityTest.take(s,0x10008).payload,MdWireTest.hex("08800a10d005181e")),"repeat ACK remains1280x720@30");
        s.close();MdMain.bootNative=false;
        System.out.println("PASS "+checks+" native priority experiment invariants; no hardware benefit asserted");
    }
}
