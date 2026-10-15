package org.carlife.hulab;
import java.io.*;import java.nio.file.*;import java.util.*;
public final class OemCsdGuardTest{
 static int n;interface Try{void run()throws Exception;}static void check(boolean x,String s){n++;if(!x)throw new AssertionError(s);}static void rejects(Try t)throws Exception{try{t.run();throw new AssertionError("accepted incompatible CSD");}catch(IOException e){n++;}}
 public static void main(String[]a)throws Exception{
  byte[]csd=HexFixture.read(Paths.get("android-md/tests/fixtures/libx264-baseline31-csd.h264")),vcl=HexFixture.read(Paths.get("android-md/tests/fixtures/libx264-baseline31-vcl.h264"));List<byte[]>nals=AvcAccessUnits.split(csd);byte[]sps=nals.get(0),pps=nals.get(1),raw=csd.clone();raw[7]=10;
  AvcAccessUnits g=new AvcAccessUnits(true);g.addFormatCsd(csd);check(g.accept(raw,0,2)==null,"known config mutation audited, no frame");AvcAccessUnits.Au out=g.accept(vcl,12345,1);ByteArrayOutputStream expected=new ByteArrayOutputStream();expected.write(csd);expected.write(vcl);check(Arrays.equals(out.bytes,expected.toByteArray()),"selected original codec format SPS/PPS byte-identical");check(raw[7]==10&&csd[7]==31,"neither input representation changed");
  byte[]bad=raw.clone();bad[6]^=1;rejects(()->g.accept(bad,0,2));byte[]badPps=raw.clone();badPps[badPps.length-1]^=1;rejects(()->g.accept(badPps,0,2));
  ByteArrayOutputStream inband=new ByteArrayOutputStream();inband.write(raw);inband.write(vcl);rejects(()->g.accept(inband.toByteArray(),12346,1));
  rejects(()->new AvcAccessUnits(true).accept(raw,0,2));rejects(()->g.accept(new byte[]{0,0,0,1,0x65,(byte)0xd0},12347,1));
  byte[]mismatch=csd.clone();mismatch[7]=10;rejects(()->new AvcAccessUnits(true).addFormatCsd(mismatch));
  // Exact 1080p runtime pair: evidence/source hash retained in video-mode-fixtures.json.
  byte[]format1080=HexFixture.read(Paths.get("android-md/tests/fixtures/mode-qcom-captured-1920x1080-l40.csd"));
  byte[]raw1080=HexFixture.read(Paths.get("android-md/tests/fixtures/mode-qcom-captured-1920x1080-l40.raw-config"));
  byte[]formatBefore=format1080.clone(),rawBefore=raw1080.clone();
  AvcAccessUnits fullHd=new AvcAccessUnits(true,VideoMode.of(1920,1080,30));fullHd.addFormatCsd(format1080);
  check(fullHd.accept(raw1080,0,2)==null&&fullHd.guardedConfigs()==1,"exact captured1080 pair admitted after full mode validation");
  // This tiny slice header only tests byte retention/PPS ownership, not image decoding.
  AvcAccessUnits.Au fullHdAu=fullHd.accept(new byte[]{0,0,0,1,0x65,(byte)0xb8},12350,1);
  check(Arrays.equals(AvcAccessUnits.split(fullHdAu.bytes).get(0),AvcAccessUnits.split(format1080).get(0)),"original captured level40 SPS emitted byte-identically");
  check(Arrays.equals(format1080,formatBefore)&&Arrays.equals(raw1080,rawBefore),"neither captured CSD representation edited");
  for(int index:new int[]{5,6,8,14,18,23,26}){byte[]other=raw1080.clone();other[index]^=1;rejects(()->fullHd.accept(other,0,2));}
  byte[]wrongRawLevel=raw1080.clone();wrongRawLevel[7]=11;rejects(()->fullHd.accept(wrongRawLevel,0,2));
  rejects(()->new AvcAccessUnits(true,VideoMode.of(1920,720,30)).addFormatCsd(format1080));
  rejects(()->new AvcAccessUnits(true,VideoMode.of(1920,1080,60)).addFormatCsd(format1080));
  ByteArrayOutputStream conflictingInband=new ByteArrayOutputStream();conflictingInband.write(raw1080);conflictingInband.write(new byte[]{0,0,0,1,0x65,(byte)0xb8});
  rejects(()->fullHd.accept(conflictingInband.toByteArray(),12351,1));
  rejects(()->new AvcAccessUnits(true).addFormatCsd(format1080));
  System.out.println("PASS "+n+" OEM CSD source-selection guard checks; no NAL byte editing");
 }
}
