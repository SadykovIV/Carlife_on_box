package org.carlife.hulab;

import java.io.*;

/** Saved settings are immutable for a logical session and never override an old ticket. */
public final class NativeVideoConfigTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static final class Transport implements MdTransport {
        public InputStream input(){return new ByteArrayInputStream(new byte[0]);}
        public OutputStream output(){return new ByteArrayOutputStream();}
        public String name(){return "TEST_NATIVE";} public boolean isNativeUsb(){return true;}
        public void close(){} public boolean awaitStopped(long ms){return true;}
    }
    static void version(MdMain.Session s,int minor)throws Exception{s.dispatch(1,0x18001,MdMain.Proto.ints(1,2,2,minor));}
    static void init(MdMain.Session s)throws Exception{s.dispatch(1,0x18007,MdMain.Proto.ints(1,1920,2,1080,3,60));}
    static void baseline(String value)throws Exception{
        NativeVideoConfig c=NativeVideoConfig.saved(value);VideoMode m=c.requested(1920,1080,60);
        check(c.encoded().equals("v1:auto:auto")&&m.width==1920&&m.height==1080&&m.fps==30,"invalid/absent setting follows valid HU geometry: "+value);
        VideoMode fallback=c.requested(123,45,0);
        check(fallback.width==1280&&fallback.height==720&&fallback.fps==30,"invalid HU geometry retains720 fallback: "+value);
    }
    public static void main(String[] args)throws Exception{
        for(String value:new String[]{null,"","v2:1920x1080:30","v1:1921x1080:30","v1:1920x1080:31","v1:2560x1440:60","v1:auto:0","v1:720p:30:extra","v1:bogus:auto","v1:720p: 30"})baseline(value);
        NativeVideoConfig selected=NativeVideoConfig.saved("v1:1920x1080:30");
        VideoMode m=selected.requested(1024,600,15);check(m.width==1920&&m.height==1080&&m.fps==30&&m.levelIdc==40,"manual mode/FPS initial selection");
        m=NativeVideoConfig.saved("v1:auto:auto").requested(1920,1080,15);check(m.width==1920&&m.fps==15,"auto follows valid HU geometry/rate");
        m=NativeVideoConfig.saved("v1:auto:auto").requested(1024,768,30);check(m.width==1024&&m.height==768&&m.fps==30,"auto reports exact HU CarLife request, not inferred physical panel geometry");
        m=NativeVideoConfig.saved("v1:720p:30").requested(1024,768,30);check(m.width==1280&&m.height==720&&m.fps==30,"explicit720 overrides HU1024x768 request");
        m=NativeVideoConfig.saved("v1:auto:auto").requested(123,45,0);check(m.width==1280&&m.fps==30,"invalid HU hint falls back");
        m=NativeVideoConfig.saved("v1:1920x1080:60").requested(1024,600,30);check(m.fps==60&&m.levelIdc==42,"60 requests an adequate level; no hardware claim");
        check(selected.override("720p",null).requested(1024,600,15).width==1280,"CLI mode overrides saved mode only");
        check(selected.override(null,"15").requested(1024,600,60).fps==15,"CLI FPS overrides saved rate only");
        check(selected.override(null,null)==selected,"absent CLI preserves saved setting");
        check(selected.bootDefaults("auto","15")==selected,"valid saved selection wins boot controller defaults for both mode and rate");
        m=NativeVideoConfig.saved("v1:720p:30").bootDefaults("auto",null).requested(1024,768,30);
        check(m.width==1280&&m.height==720&&m.fps==30,"real boot controller --video-mode auto cannot hide saved720p");
        m=NativeVideoConfig.saved("v1:auto:auto").bootDefaults("720p","60").requested(1024,768,15);
        check(m.width==1024&&m.height==768&&m.fps==15,"explicit saved auto is an owner selection, not an absent setting");
        for(String absent:new String[]{null,"","v1:bad:30"}){
            m=NativeVideoConfig.saved(absent).bootDefaults("800x480","15").requested(1024,768,30);
            check(m.width==800&&m.height==480&&m.fps==15,"absent/invalid saved config retains boot CLI fallback: "+absent);
        }

        MdMain.bootNative=true;MdMain.videoSource="mediacodec";MdMain.running=true;
        MdMain.explicitVideoMode=false;MdMain.explicitVideoFps=false;
        MdMain.Session s=new MdMain.Session(new Transport());version(s,0);init(s);
        PendingVideoInit first=s.pendingInit;
        check(first.hintWidth==1920&&first.hintHeight==1080&&first.hintFps==60,"original INIT hint survives deferred worker");
        check(s.videoConfig==null&&!s.initialized,"USB dispatch does not access Android settings or acknowledge before capability");
        check(s.acceptVideoConfig(first,selected)==selected,"worker accepts current immutable snapshot");
        check(s.acceptVideoConfig(first,NativeVideoConfig.DEFAULT)==selected,"same logical session cannot replace snapshot");
        init(s);check(s.pendingInit==first,"repeated INIT preserves ticket");
        s.dispatch(1,0x1800b,new byte[0]);init(s);PendingVideoInit second=s.pendingInit;
        check(s.acceptVideoConfig(first,NativeVideoConfig.DEFAULT)==null,"RESET rejects stale settings read");
        check(s.acceptVideoConfig(second,NativeVideoConfig.DEFAULT)==selected,"RESET alone preserves logical-session settings");
        version(s,1);check(s.videoConfig==null,"fresh VERSION clears session snapshot");init(s);PendingVideoInit third=s.pendingInit;
        check(s.acceptVideoConfig(second,selected)==null,"new VERSION rejects old settings callback");
        check(!s.failBootInitIfCurrent(second)&&s.open,"stale capability failure cannot close new logical session");
        check(s.acceptVideoConfig(third,NativeVideoConfig.DEFAULT)==NativeVideoConfig.DEFAULT,"new logical session obtains fresh setting");
        MdMain.displayWidth=1920;MdMain.displayHeight=1080;
        s.completeBootInit(third,new MediaCodecDisplaySource.Selection("TEST","repeat-previous-frame-after","max-fps-to-encoder",1280,720,30));
        check(s.videoSettingsStatus.contains("1280x720 @ 30 FPS")&&s.videoSettingsStatus.contains("not measured"),"status reports accepted not measured rate");
        s.close();check(s.acceptVideoConfig(third,selected)==null,"EOF rejects stale callback");
        MdMain.Session legacy=new MdMain.Session(new Transport());
        legacy.dispatch(1,0x18001,MdMain.Proto.ints(1,1,2,0));
        legacy.dispatch(1,0x18007,MdMain.Proto.ints(1,1280,2,720,3,30));
        PendingVideoInit legacyTicket=legacy.pendingInit;
        check(!legacy.nativeCompatibility()&&legacy.acceptVideoConfig(legacyTicket,selected)==selected,"saved settings also apply to native USB protocol v1");
        VideoMode legacyMode=legacy.cachedVideoConfig(legacyTicket).requested(legacyTicket.hintWidth,legacyTicket.hintHeight,legacyTicket.hintFps);
        legacy.completeBootInit(legacyTicket,new MediaCodecDisplaySource.Selection("TEST","repeat-previous-frame-after","max-fps-to-encoder",legacyMode.width,legacyMode.height,legacyMode.fps));
        check(legacy.width==1920&&legacy.height==1080&&legacy.acceptedFps==30,"legacy INIT_DONE reflects configured mode without changing version semantics");
        legacy.close();
        MdMain.Session changed=new MdMain.Session(new Transport());version(changed,0);init(changed);
        PendingVideoInit old=changed.pendingInit;changed.acceptVideoConfig(old,selected);
        check(!changed.reloadVideoConfigIfChanged(NativeVideoConfig.saved(selected.encoded()))&&changed.open&&changed.pendingInit==old,
            "same saved configuration keeps session, source generation and pending INIT intact");
        check(changed.reloadVideoConfigIfChanged(NativeVideoConfig.saved("v1:720p:30"))&&!changed.open&&changed.pendingInit==null,
            "different saved setting closes owned session and invalidates pending source work");
        check("video_settings_changed".equals(changed.terminalReason),"clean settings reconnect carries exact terminal reason");
        check(!changed.reloadVideoConfigIfChanged(NativeVideoConfig.DEFAULT),"closed owner cannot apply a late setting callback twice");
        MdMain.explicitVideoMode=true;MdMain.nativeVideoMode="auto";
        MdMain.Session cli=new MdMain.Session(new Transport());version(cli,0);init(cli);
        NativeVideoConfig bootSaved=NativeVideoConfig.saved("v1:720p:30");
        check(cli.acceptVideoConfig(cli.pendingInit,bootSaved)==bootSaved,
            "boot session ignores controller --video-mode auto in favor of saved owner selection");
        check(cli.reloadVideoConfigIfChanged(NativeVideoConfig.saved("v1:800x480:30"))&&!cli.open,
            "saved selection change triggers reconnect despite controller --video-mode auto");
        cli.close();MdMain.explicitVideoMode=false;MdMain.nativeVideoMode="auto";
        MdMain.bootNative=false;
        System.out.println("PASS "+checks+" saved video config/CLI/HU hint/RESET/VERSION/EOF/status checks; no hardware claim");
    }
}
