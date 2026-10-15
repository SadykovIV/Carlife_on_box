package org.carlife.hulab;
import java.io.IOException;

/** Read-only H.264 Baseline SPS geometry, including 4:2:0 frame-crop units. */
final class AvcSps {
    final int width,height,levelIdc,references;
    final long codedMacroblocks;
    private AvcSps(int w,int h,int level,int refs,long blocks){width=w;height=h;levelIdc=level;references=refs;codedMacroblocks=blocks;}
    static AvcSps parse(byte[] nal)throws IOException{
        if(nal.length<5||(nal[0]&31)!=7||(nal[0]&128)!=0||(nal[1]&255)!=66)throw new IOException("expected Baseline SPS NAL");
        int level=nal[3]&255;AvcAccessUnits.Bits b=new AvcAccessUnits.Bits(nal);b.skip(24);
        if(b.ue()>31||b.ue()>12)throw new IOException("SPS identifier/frame-num bound");
        int poc=b.ue();
        if(poc==0){if(b.ue()>12)throw new IOException("SPS POC bound");}
        else if(poc==1){b.one();b.ue();b.ue();int cycle=b.ue();if(cycle>255)throw new IOException("SPS POC cycle bound");for(int i=0;i<cycle;i++)b.ue();}
        else if(poc!=2)throw new IOException("SPS POC type");
        int refs=b.ue();b.one();int mw=b.ue(),mh=b.ue();if(mw>1023||mh>1023||refs>16)throw new IOException("SPS coded geometry/reference bound");
        int frameOnly=b.one();if(frameOnly==0)b.one();b.one();long left=0,right=0,top=0,bottom=0;
        if(b.one()!=0){left=b.ue();right=b.ue();top=b.ue();bottom=b.ue();}
        long codedWidth=(mw+1L)*16,codedHeight=(mh+1L)*16*(2-frameOnly);
        long width=codedWidth-2*(left+right),height=codedHeight-2*(2-frameOnly)*(top+bottom);
        if(width<1||height<1||width>codedWidth||height>codedHeight)throw new IOException("SPS invalid crop");
        if(b.one()!=0)vui(b);
        if(b.one()!=1)throw new IOException("SPS missing RBSP stop bit");
        while(b.bit<b.data.length*8)if(b.one()!=0)throw new IOException("SPS trailing RBSP bits");
        return new AvcSps((int)width,(int)height,level,refs,(mw+1L)*(mh+1L)*(2-frameOnly));
    }
    private static int bits(AvcAccessUnits.Bits b,int n)throws IOException{int result=0;for(int i=0;i<n;i++)result=(result<<1)|b.one();return result;}
    private static void hrd(AvcAccessUnits.Bits b)throws IOException{
        int count=b.ue();if(count>31)throw new IOException("SPS HRD count");b.skip(8);
        for(int i=0;i<=count;i++){b.ue();b.ue();b.one();}b.skip(20);
    }
    private static void vui(AvcAccessUnits.Bits b)throws IOException{
        if(b.one()!=0){int aspect=bits(b,8);if(aspect==255)b.skip(32);}
        if(b.one()!=0)b.one();
        if(b.one()!=0){b.skip(4);if(b.one()!=0)b.skip(24);}
        if(b.one()!=0){b.ue();b.ue();}
        if(b.one()!=0)b.skip(65);
        boolean nal=b.one()!=0;if(nal)hrd(b);boolean vcl=b.one()!=0;if(vcl)hrd(b);
        if(nal||vcl)b.one();b.one();
        if(b.one()!=0){b.one();for(int i=0;i<6;i++)b.ue();}
    }
}
