package org.carlife.hulab;
import java.io.IOException;

/** One accepted geometry and AVC capability request; default remains 1280x720. */
final class VideoMode {
    // AVC limits in AOSP android15-release MediaCodecInfo.VideoCapabilities,
    // applyLevelLimits: (level_idc, Android level constant, MaxFS, MaxMBPS, MaxDPB).
    private static final int[][] LEVELS={{31,512,3600,108000,18000},{32,1024,5120,216000,20480},{40,2048,8192,245760,32768},{42,8192,8704,522240,34816}};
    final int width,height,fps,levelIdc,codecLevel,maxFrameMacroblocks,maxMacroblocksPerSecond,maxDpbMacroblocks;
    private VideoMode(int w,int h,int rate,int[] level){width=w;height=h;fps=rate;levelIdc=level[0];codecLevel=level[1];maxFrameMacroblocks=level[2];maxMacroblocksPerSecond=level[3];maxDpbMacroblocks=level[4];}
    static VideoMode of(int width,int height,int fps)throws IOException{
        if(width<320||width>2560||height<180||height>1440||(width&1)!=0||(height&1)!=0||fps<1||fps>60)throw new IOException("unsupported video geometry/rate");
        long blocks=((width+15L)/16)*((height+15L)/16);
        for(int[] level:LEVELS)if(blocks<=level[2]&&blocks*fps<=level[3])return new VideoMode(width,height,fps,level);
        throw new IOException("video mode exceeds supported AVC Level4.2 frame/MBPS limits");
    }
    static VideoMode requested(String profile,int hintWidth,int hintHeight,int fps)throws IOException{
        if("720p".equals(profile))return of(1280,720,fps);
        if("auto".equals(profile))return of(hintWidth,hintHeight,fps);
        if(profile==null||!profile.matches("[0-9]{3,4}x[0-9]{3,4}"))throw new IOException("video profile must be 720p, auto or WIDTHxHEIGHT");
        int x=profile.indexOf('x');return of(Integer.parseInt(profile.substring(0,x)),Integer.parseInt(profile.substring(x+1)),fps);
    }
    ProjectionGeometry geometry(int sourceWidth,int sourceHeight){return new ProjectionGeometry(sourceWidth,sourceHeight,width,height,0);}
    void validateSps(byte[] nal)throws IOException{
        AvcSps s=AvcSps.parse(nal);
        if(s.width!=width||s.height!=height||s.levelIdc!=levelIdc)throw new IOException("AVC SPS mode differs from accepted "+this+": "+s.width+"x"+s.height+" level_idc="+s.levelIdc);
        if(s.codedMacroblocks>maxFrameMacroblocks||s.codedMacroblocks*fps>maxMacroblocksPerSecond||s.references>16||s.references*s.codedMacroblocks>maxDpbMacroblocks)throw new IOException("AVC SPS exceeds accepted frame/MBPS/DPB level limits");
    }
    public String toString(){return width+"x"+height+"@"+fps+" Baseline/"+levelIdc;}
}
