package org.carlife.hulab;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class VideoModeTest {
    static int checks;interface Task{void run()throws Exception;}
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static void rejects(Task t,String why)throws Exception{try{t.run();throw new AssertionError(why);}catch(IOException expected){checks++;}}
    // Minimal standards-shaped SPS for malformed crop and DPB boundary tests;
    // successful modes above/below also use independent encoder fixtures.
    static byte[] syntheticSps(int level,int codedWidth,int codedHeight,int refs,int cropRight,int cropBottom)throws Exception{
        StringBuilder bits=new StringBuilder();
        for(int value:new int[]{0,0,0,0,refs})ue(bits,value);
        bits.append('0');ue(bits,codedWidth/16-1);ue(bits,codedHeight/16-1);
        bits.append("111");ue(bits,0);ue(bits,cropRight);ue(bits,0);ue(bits,cropBottom);bits.append("01");
        while(bits.length()%8!=0)bits.append('0');
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{0x67,66,0,(byte)level});int zeros=0;
        for(int i=0;i<bits.length();i+=8){int v=Integer.parseInt(bits.substring(i,i+8),2);if(zeros>=2&&v<=3){out.write(3);zeros=0;}out.write(v);zeros=v==0?zeros+1:0;}return out.toByteArray();
    }
    static void ue(StringBuilder bits,int value){String binary=Integer.toBinaryString(value+1);for(int i=1;i<binary.length();i++)bits.append('0');bits.append(binary);}
    public static void main(String[] args)throws Exception{
        int[][] rows={{1280,720,31},{1280,480,31},{1728,648,32},{1920,720,40},{1920,1080,40},{2240,976,42}};
        for(int[] row:rows){
            final VideoMode mode=VideoMode.of(row[0],row[1],30);check(mode.levelIdc==row[2],"level table "+mode);
            final byte[] csd=HexFixture.read(Paths.get("android-md/tests/fixtures/mode-baseline-"+row[0]+"x"+row[1]+"-l"+row[2]+".csd"));
            final byte[] sps=AvcAccessUnits.split(csd).get(0);AvcSps parsed=AvcSps.parse(sps);mode.validateSps(sps);
            check(parsed.width==row[0]&&parsed.height==row[1]&&parsed.references==1,"actual x264 dimensions/crops "+mode);
            if(row[1]==1080)check(parsed.codedMacroblocks==120*68,"1088 coded lines crop to1080");
            AvcAccessUnits guarded=new AvcAccessUnits(true,mode);guarded.addFormatCsd(csd);check(guarded.accept(csd,0,2)==null,"unchanged higher-level CSD valid "+mode);
            final byte[] changed=csd.clone();changed[7]=10;
            if(row[2]==31||row[2]==40)check(guarded.accept(changed,0,2)==null,"runtime-proven31/40->10 guard applies after requested-mode validation");
            else{final AvcAccessUnits strict=guarded;rejects(new Task(){public void run()throws Exception{strict.accept(changed,0,2);}},"unproven32/42 levels must not inherit OEM overwrite exception");}
            for(int n=0;n<sps.length;n++){final byte[] cut=Arrays.copyOf(sps,n);rejects(new Task(){public void run()throws Exception{AvcSps.parse(cut);}},"truncated SPS "+n);}
            final byte[] wrong=sps.clone();wrong[3]=(byte)(row[2]==31?32:31);rejects(new Task(){public void run()throws Exception{mode.validateSps(wrong);}},"mismatched actual level");
            ProjectionGeometry g=mode.geometry(1920,1080);for(float[] p:new float[][]{{20,20},{1899,1059},{959.5f,539.5f}}){float[] v=g.toVideo(p[0],p[1]),back=g.toSource(v[0],v[1]);check(back!=null&&Math.abs(back[0]-p[0])<.01&&Math.abs(back[1]-p[1])<.01,"video/input same mode inverse");}
        }
        check(VideoMode.of(1280,720,60).levelIdc==32,"MBPS not just frame size selects level");
        check(VideoMode.requested("720p",2240,976,30).width==1280,"default remains720");
        check(VideoMode.requested("auto",2240,976,30).height==976,"auto keeps exact HU hint for runtime capability probe");
        check(VideoMode.requested("1920x720",800,480,30).width==1920,"explicit selected mode independent of hint");
        for(final int[] bad:new int[][]{{2560,1440,30},{2240,976,61},{319,480,30},{1281,720,30}})rejects(new Task(){public void run()throws Exception{VideoMode.of(bad[0],bad[1],bad[2]);}},"unsupported level/geometry/fps");
        final VideoMode baseline=VideoMode.of(1280,720,30);final byte[] wrongSize=AvcAccessUnits.split(HexFixture.read(Paths.get("android-md/tests/fixtures/mode-baseline-1280x480-l31.csd"))).get(0);
        // Retained outputFormat from the accepted c2.qti.avc.encoder baseline,
        // not a new device run. Keep the proven raw level10 / format31 exception.
        byte[] qcom=HexFixture.read(Paths.get("android-md/tests/fixtures/mode-qcom-captured-1280x720-l31.csd"));
        baseline.validateSps(AvcAccessUnits.split(qcom).get(0));
        AvcAccessUnits qcomGuard=new AvcAccessUnits(true,baseline);qcomGuard.addFormatCsd(qcom);
        byte[] qcomRaw=qcom.clone();qcomRaw[7]=10;
        check(qcomGuard.accept(qcomRaw,0,2)==null&&qcomGuard.guardedConfigs()==1,"retained QCOM CSD guard remains accepted");
        AvcAccessUnits.Au qcomAu=qcomGuard.accept(new byte[]{0,0,0,1,0x65,(byte)0xb8},0,1);
        check(Arrays.equals(AvcAccessUnits.split(qcomAu.bytes).get(0),AvcAccessUnits.split(qcom).get(0)),"retained original QCOM level31 SPS emitted unaltered");
        rejects(new Task(){public void run()throws Exception{baseline.validateSps(wrongSize);}},"correct level but wrong pixel dimensions");
        rejects(new Task(){public void run()throws Exception{AvcSps.parse(new byte[]{0x67,100,0,31,1,2,3});}},"unexpected profile");
        rejects(new Task(){public void run()throws Exception{AvcSps.parse(new byte[]{0x67,66,0,31,0,0,3});}},"truncated emulation prevention");
        baseline.validateSps(syntheticSps(31,1280,720,5,0,0));check(true,"level31 exact DPB bound accepted");
        rejects(new Task(){public void run()throws Exception{baseline.validateSps(syntheticSps(31,1280,720,6,0,0));}},"SPS DPB overflow rejected despite matching geometry");
        rejects(new Task(){public void run()throws Exception{AvcSps.parse(syntheticSps(31,1280,720,1,641,0));}},"negative width from crop rejected");
        rejects(new Task(){public void run()throws Exception{AvcSps.parse(syntheticSps(31,1280,720,1,0,361));}},"negative height from crop rejected");
        rejects(new Task(){public void run()throws Exception{baseline.validateSps(syntheticSps(31,1280,736,1,0,8));}},"coded FS exceeds level31 even when crop produces accepted720");
        System.out.println("PASS "+checks+" real x264 and retained QCOM SPS/crop/level/geometry/guard checks; no new hardware mode claim");
    }
}
