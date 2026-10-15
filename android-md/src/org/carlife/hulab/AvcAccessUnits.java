package org.carlife.hulab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Codec-buffer AU adapter. Never edits NAL payloads or SPS; container prefixes only. */
final class AvcAccessUnits {
    static final int MAX_AU = 2 * 1024 * 1024;
    static final int CONFIG = 2, PARTIAL = 8;
    static final byte[] START = {0, 0, 0, 1};
    private byte[] sps, pps;
    private byte[] formatSps,formatPps;
    private final boolean originalFormatGuard;
    private final VideoMode expectedMode;
    private int guardedConfigs;
    private ByteArrayOutputStream partial;
    private long partialPts;
    private int partialFlags;
    private boolean sawIdr;

    AvcAccessUnits(){this(false,null);}
    AvcAccessUnits(boolean guard){this(guard,null);}
    AvcAccessUnits(boolean guard,VideoMode mode){originalFormatGuard=guard;expectedMode=mode;}
    int guardedConfigs(){return guardedConfigs;}
    void addFormatCsd(byte[] raw)throws IOException{
        for(byte[] nal:split(normalize(raw))){int type=nal[0]&31;if(type==7)formatSps=nal;if(type==8)formatPps=nal;}
        if(originalFormatGuard){sps=formatSps;pps=formatPps;if(sps!=null&&pps!=null)validateParameterIds();}else addCsd(raw);
    }
    void validateParameterIds()throws IOException{
        if(expectedMode!=null)expectedMode.validateSps(sps);
        else if(sps.length<5||(sps[1]&255)!=66||(sps[3]&255)!=31)throw new IOException("original outputFormat CSD is not Baseline/Level31");
        Bits a=new Bits(sps);a.skip(24);int sid=a.ue();Bits b=new Bits(pps);int pid=b.ue(),psid=b.ue();if(sid>31||pid>255||sid!=psid)throw new IOException("outputFormat PPS/SPS ID mismatch");
    }
    void guardedConfig(byte[] raw)throws IOException{
        if(formatSps==null||formatPps==null)throw new IOException("OEM CSD guard requires original outputFormat CSD first");
        boolean gotSps=false,gotPps=false;
        for(byte[] nal:split(normalize(raw))){
            int t=nal[0]&31;
            if(t==7){gotSps=true;if(nal.length!=formatSps.length)throw new IOException("OEM CSD conflict changes SPS length");for(int i=0;i<nal.length;i++)if(nal[i]!=formatSps[i]&&!provenLevelOverwrite(i,nal[i],formatSps[i]))throw new IOException("OEM CSD conflict beyond proven level_idc overwrite");}
            else if(t==8){gotPps=true;if(!Arrays.equals(nal,formatPps))throw new IOException("OEM CSD conflict changes PPS");}
            else throw new IOException("unexpected NAL in guarded codec config");
        }
        if(!gotSps||!gotPps)throw new IOException("guarded config missing SPS/PPS");
        guardedConfigs++;
    }

    private boolean provenLevelOverwrite(int index,byte raw,byte original){
        if(index!=3||(raw&255)!=10)return false;
        int level=original&255;
        // Same pinned libstagefright overwrite observed in the accepted 720p31
        // and captured 1080p40 runs. Geometry/level/DPB validation is still mandatory.
        return level==31||level==40&&expectedMode!=null&&expectedMode.levelIdc==40;
    }

    static final class Au {
        final byte[] bytes;
        final long ptsUs;
        final int flags;
        final boolean idr;
        final String nalTypes;
        Au(byte[] b, long pts, int f, boolean key, String types) {
            bytes=b; ptsUs=pts; flags=f; idr=key; nalTypes=types;
        }
    }

    void addCsd(byte[] raw) throws IOException {
        for (byte[] nal : split(normalize(raw))) {
            int type=nal[0]&31;
            if (type==7) sps=nal;
            if (type==8) pps=nal;
        }
        if(expectedMode!=null&&sps!=null&&pps!=null)validateParameterIds();
    }

    Au accept(byte[] raw, long ptsUs, int flags) throws IOException {
        if (raw.length>MAX_AU) throw new IOException("codec buffer exceeds 2MiB AU bound");
        if ((flags&CONFIG)!=0) { if(originalFormatGuard)guardedConfig(raw);else addCsd(raw); return null; }
        if (partial!=null || (flags&PARTIAL)!=0) {
            if (partial==null) { partial=new ByteArrayOutputStream(); partialPts=ptsUs; partialFlags=0; }
            if (ptsUs!=partialPts) throw new IOException("partial AU changed PTS");
            if (partial.size()+raw.length>MAX_AU) throw new IOException("partial AU bound exceeded");
            partial.write(raw); partialFlags|=flags;
            if ((flags&PARTIAL)!=0) return null;
            raw=partial.toByteArray(); flags=partialFlags&~PARTIAL; partial=null;
        }
        if (raw.length==0) return null;
        byte[] annex=normalize(raw);
        List<byte[]> nals=split(annex);
        boolean vcl=false,idr=false,hasSps=false,hasPps=false;
        StringBuilder types=new StringBuilder();
        for (byte[] nal:nals) {
            int type=nal[0]&31;
            if (types.length()>0) types.append(','); types.append(type);
            vcl|=type==1||type==5; idr|=type==5;
            if (type==7) {if(originalFormatGuard&&!Arrays.equals(nal,formatSps))throw new IOException("conflicting in-band SPS; no silent replacement");sps=nal;hasSps=true;}
            if (type==8) {if(originalFormatGuard&&!Arrays.equals(nal,formatPps))throw new IOException("conflicting in-band PPS; no silent replacement");pps=nal;hasPps=true;}
            if(originalFormatGuard&&(type==1||type==5)){if(pps==null)throw new IOException("slice before outputFormat PPS");Bits bits=new Bits(nal);bits.ue();bits.ue();int slicePid=bits.ue();int pid=new Bits(pps).ue();if(slicePid!=pid)throw new IOException("slice references another PPS/generation");}
        }
        if (!vcl) return null;
        if(expectedMode!=null&&sps!=null&&pps!=null)validateParameterIds();
        if (!sawIdr&&!idr) throw new IOException("first VCL AU is not IDR");
        if (idr) {
            if(sps==null||pps==null) throw new IOException("IDR has no original codec SPS/PPS");
            if(!hasSps||!hasPps) {
                ByteArrayOutputStream joined=new ByteArrayOutputStream();
                joined.write(START);joined.write(sps);joined.write(START);joined.write(pps);joined.write(annex);
                annex=joined.toByteArray();
            }
            sawIdr=true;
        }
        if(annex.length>MAX_AU)throw new IOException("complete AU+CSD exceeds 2MiB bound");
        return new Au(annex,ptsUs,flags,idr,types.toString());
    }

    boolean hasPartial(){return partial!=null;}
    static int startLength(byte[] b,int i) {
        if(i+3<=b.length&&b[i]==0&&b[i+1]==0&&b[i+2]==1)return 3;
        if(i+4<=b.length&&b[i]==0&&b[i+1]==0&&b[i+2]==0&&b[i+3]==1)return 4;
        return 0;
    }
    static List<byte[]> split(byte[] annex)throws IOException {
        List<byte[]> list=new ArrayList<byte[]>();int at=0;
        while(at<annex.length) {
            int prefix=startLength(annex,at);
            if(prefix==0)throw new IOException("missing Annex B prefix");
            int begin=at+prefix,end=begin;
            while(end<annex.length&&startLength(annex,end)==0)end++;
            if(end==begin)throw new IOException("empty NAL");
            list.add(Arrays.copyOfRange(annex,begin,end));at=end;
        }
        return list;
    }
    static byte[] normalize(byte[] raw)throws IOException {
        if(raw.length==0)return raw;
        if(startLength(raw,0)!=0) { split(raw); return raw; }
        // Android AVC csd may be a single SPS/PPS without a container prefix.
        if((raw[0]&31)==7||(raw[0]&31)==8) {
            ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(START);b.write(raw);return b.toByteArray();
        }
        // MediaCodec AVCC length-prefixed buffers: convert lengths, never rewrite NAL payload.
        ByteArrayOutputStream b=new ByteArrayOutputStream();int at=0;
        while(at<raw.length) {
            if(raw.length-at<4)throw new IOException("truncated AVC length prefix");
            long n=((raw[at]&255L)<<24)|((raw[at+1]&255L)<<16)|((raw[at+2]&255L)<<8)|(raw[at+3]&255L);at+=4;
            if(n<1||n>raw.length-at)throw new IOException("invalid AVC length prefix");
            b.write(START);b.write(raw,at,(int)n);at+=(int)n;
        }
        return b.toByteArray();
    }
    /** Read-only RBSP syntax reader, only parameter IDs and initial slice header fields. */
    static final class Bits {
        final byte[] data;int bit;
        Bits(byte[] nal)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int zeros=0;for(int i=1;i<nal.length;i++){int v=nal[i]&255;if(zeros>=2&&v==3){if(i+1>=nal.length||(nal[i+1]&255)>3)throw new IOException("invalid emulation prevention");zeros=0;continue;}b.write(v);zeros=v==0?zeros+1:0;}data=b.toByteArray();}
        int one()throws IOException{if(bit>=data.length*8)throw new IOException("truncated RBSP");return(data[bit/8]>>(7-(bit++%8)))&1;}
        void skip(int n)throws IOException{for(int i=0;i<n;i++)one();}
        int ue()throws IOException{int z=0;while(one()==0){if(++z>30)throw new IOException("RBSP exponential overflow");}int v=0;for(int i=0;i<z;i++)v=(v<<1)|one();return((1<<z)-1)+v;}
    }
}
