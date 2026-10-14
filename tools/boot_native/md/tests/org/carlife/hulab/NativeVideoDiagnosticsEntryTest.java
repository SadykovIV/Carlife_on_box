package org.carlife.hulab;
/** Exercise real CLI decisions, stopped at the pre-resource bounds check. */
public final class NativeVideoDiagnosticsEntryTest {
    static int n;
    static void check(boolean value,String why){n++;if(!value)throw new AssertionError(why);}
    static void entry(boolean boot,String trace)throws Exception{
        String[] args=trace==null?new String[]{"--boot-native",String.valueOf(boot),"--transport","native-usb","--video-source","mediacodec","--seconds","99999"}:
            new String[]{"--boot-native",String.valueOf(boot),"--transport","native-usb","--video-source","mediacodec","--video-trace",trace,"--seconds","99999"};
        try{MdMain.main(args);throw new AssertionError("resource-free bounds sentinel did not stop entry");}
        catch(IllegalArgumentException expected){check("bounds".equals(expected.getMessage()),"stopped exactly after policy before resources");}
    }
    public static void main(String[] args)throws Exception{
        check(MediaCodecDisplaySource.fullVideoTraceDefault,"shared default remains full");
        entry(true,null);check(!MediaCodecDisplaySource.fullVideoTraceDefault,"native boot default selects light");
        MediaCodecDisplaySource light=new MediaCodecDisplaySource(null,null,1);
        entry(true,"full");check(MediaCodecDisplaySource.fullVideoTraceDefault,"explicit full overrides native default");
        check(!light.fullVideoTrace,"existing source keeps its immutable diagnostic mode");
        MediaCodecDisplaySource full=new MediaCodecDisplaySource(null,null,2);
        entry(true,"auto");check(!MediaCodecDisplaySource.fullVideoTraceDefault,"auto returns boot default light");
        check(full.fullVideoTrace,"later policy cannot change old full source");
        entry(false,null);check(MediaCodecDisplaySource.fullVideoTraceDefault,"non-boot native entry preserves prior full behavior");
        entry(false,"auto");check(MediaCodecDisplaySource.fullVideoTraceDefault,"auto outside boot remains full");
        entry(false,"full");check(MediaCodecDisplaySource.fullVideoTraceDefault,"full outside boot unchanged");
        try{MdMain.main(new String[]{"--video-trace","silent"});throw new AssertionError("unknown diagnostic policy accepted");}
        catch(IllegalArgumentException expected){check("unsupported explicit laboratory option silent".equals(expected.getMessage()),"unknown policy rejects during actual argument parsing");}
        MdMain.bootNative=false;MediaCodecDisplaySource.configureVideoDiagnostics(true);
        System.out.println("PASS "+n+" actual entry policy/source snapshot checks; no device resources opened");
    }
}
