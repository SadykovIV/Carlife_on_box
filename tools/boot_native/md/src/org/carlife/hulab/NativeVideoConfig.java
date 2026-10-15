package org.carlife.hulab;

import java.io.IOException;

/** Immutable, versioned setting. An absent/invalid saved value follows the HU mode with capability fallback. */
final class NativeVideoConfig {
    static final String KEY="carlife_video_config", STATUS_KEY="carlife_video_status";
    static final NativeVideoConfig DEFAULT=new NativeVideoConfig("auto","auto","default");
    final String mode,rate,provenance;
    private NativeVideoConfig(String mode,String rate,String provenance){this.mode=mode;this.rate=rate;this.provenance=provenance;}
    static NativeVideoConfig saved(String value){
        if(value==null||value.isEmpty())return DEFAULT;
        try{
            String[] parts=value.split(":",-1);
            if(parts.length!=3||!parts[0].equals("v1"))throw new IOException("setting format");
            return checked(parts[1],parts[2],"saved");
        }catch(IOException invalid){return new NativeVideoConfig("auto","auto","invalid_saved_default");}
    }
    static NativeVideoConfig checked(String mode,String rate,String provenance)throws IOException{
        if(!rate.equals("auto")&&!rate.equals("15")&&!rate.equals("30")&&!rate.equals("60"))throw new IOException("FPS must be auto,15,30,60");
        VideoMode.requested(mode,1280,720,rate.equals("auto")?30:Integer.parseInt(rate));
        return new NativeVideoConfig(mode,rate,provenance);
    }
    NativeVideoConfig override(String cliMode,String cliRate)throws IOException{
        if(cliMode==null&&cliRate==null)return this;
        return checked(cliMode==null?mode:cliMode,cliRate==null?rate:cliRate,provenance+"+cli");
    }
    int fps(int hint){return rate.equals("auto")?NativeSessionProtocol.fps(hint):Integer.parseInt(rate);}
    VideoMode requested(int width,int height,int hint)throws IOException{
        int fps=fps(hint);
        try{return VideoMode.requested(mode,width,height,fps);}
        catch(IOException invalid){if(!mode.equals("auto"))throw invalid;return VideoMode.of(1280,720,fps);}
    }
    String encoded(){return "v1:"+mode+":"+rate;}
    String status(int width,int height,int fps){
        String reason=mode.equals("auto")?"HU hint / codec capability":mode;
        return width+"x"+height+" @ "+fps+" FPS; negotiated, not measured; "+reason+"; "+provenance;
    }
}
