package org.carlife.hulab;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Independent libx264 fixtures; verifies AU semantics and unchanged codec SPS bytes. */
public final class AvcAccessUnitsTest {
 static int checks;
 static void check(boolean x,String why){checks++;if(!x)throw new AssertionError(why);}
 interface Throwing{void run()throws Exception;}
 static void rejects(Throwing r,String why)throws Exception{try{r.run();throw new AssertionError("accepted "+why);}catch(IOException expected){checks++;}}
 public static void main(String[] args)throws Exception{
  Path root=Paths.get("android-md/tests/fixtures");final byte[] raw=HexFixture.read(root.resolve("libx264-baseline31-au.h264"));final byte[] csd=HexFixture.read(root.resolve("libx264-baseline31-csd.h264"));final byte[] vcl=HexFixture.read(root.resolve("libx264-baseline31-vcl.h264"));
  long pts=123456789L;AvcAccessUnits direct=new AvcAccessUnits();AvcAccessUnits.Au a=direct.accept(raw,pts,1);
  check(a!=null&&a.idr&&a.ptsUs==pts&&a.flags==1,"complete AU preserves PTS/flags");
  check(Arrays.equals(a.bytes,raw),"complete Annex B is byte-identical, no SPS rewrite");
  AvcAccessUnits separate=new AvcAccessUnits();check(separate.accept(csd,0,2)==null,"CSD creates no video frame");
  AvcAccessUnits.Au b=separate.accept(vcl,pts,1);ByteArrayOutputStream expected=new ByteArrayOutputStream();expected.write(csd);expected.write(vcl);
  check(Arrays.equals(b.bytes,expected.toByteArray()),"unaltered CSD prepended to first IDR");
  check(Arrays.equals(separate.accept(vcl,pts+66666,1).bytes,expected.toByteArray()),"unaltered CSD repeated on later IDR");
  final AvcAccessUnits partial=new AvcAccessUnits();int cut=raw.length/2;
  check(partial.accept(Arrays.copyOfRange(raw,0,cut),pts,9)==null&&partial.hasPartial(),"partial buffer is not an AU");
  AvcAccessUnits.Au joined=partial.accept(Arrays.copyOfRange(raw,cut,raw.length),pts,0);
  check(!partial.hasPartial()&&Arrays.equals(joined.bytes,raw)&&joined.flags==1,"partial buffers produce exactly one complete AU");
  ByteArrayOutputStream avcc=new ByteArrayOutputStream();DataOutputStream lengths=new DataOutputStream(avcc);
  for(byte[] n:AvcAccessUnits.split(csd)){lengths.writeInt(n.length);lengths.write(n);}
  check(Arrays.equals(AvcAccessUnits.normalize(avcc.toByteArray()),csd),"length-prefixed CSD converted without NAL edits");
  byte[] configSps=AvcAccessUnits.split(csd).get(0);check((configSps[0]&31)==7&&(configSps[1]&255)==66&&(configSps[3]&255)==31,"independent fixture is Baseline/Level31");
  rejects(new Throwing(){public void run()throws Exception{new AvcAccessUnits().accept(vcl,1,1);}},"IDR without SPS/PPS");
  rejects(new Throwing(){public void run()throws Exception{new AvcAccessUnits().accept(new byte[]{0,0,0,1,0x41,1},1,0);}},"P frame before IDR");
  rejects(new Throwing(){public void run()throws Exception{AvcAccessUnits x=new AvcAccessUnits();x.accept(Arrays.copyOfRange(raw,0,10),1,8);x.accept(Arrays.copyOfRange(raw,10,raw.length),2,0);}},"partial PTS mismatch");
  rejects(new Throwing(){public void run()throws Exception{new AvcAccessUnits().accept(new byte[AvcAccessUnits.MAX_AU+1],1,0);}},"bounded AU");
  rejects(new Throwing(){public void run()throws Exception{AvcAccessUnits.normalize(new byte[]{0,0,0,100,0x65});}},"truncated AVCC length");
  AvcAccessUnits largeAdapter=new AvcAccessUnits();largeAdapter.accept(raw,pts,1);
  byte[] sceneChange=new byte[550784];Arrays.fill(sceneChange,(byte)0x55);System.arraycopy(new byte[]{0,0,0,1,0x41,(byte)0xb8},0,sceneChange,0,6);
  AvcAccessUnits.Au large=largeAdapter.accept(sceneChange,pts+33333,0);
  check(large!=null&&!large.idr&&Arrays.equals(large.bytes,sceneChange),"runtime-sized550784-byte P survives unchanged after IDR");
  byte[] maximum=Arrays.copyOf(sceneChange,AvcAccessUnits.MAX_AU);Arrays.fill(maximum,sceneChange.length,maximum.length,(byte)0x55);
  check(largeAdapter.accept(maximum,pts+66666,0).bytes.length==AvcAccessUnits.MAX_AU,"single2MiB complete AU accepted");
  AvcAccessUnits assembled=new AvcAccessUnits();assembled.accept(raw,pts,1);int split=1100000;
  check(assembled.accept(Arrays.copyOfRange(maximum,0,split),pts+99999,8)==null,"large partial AU waits for complete boundary");
  check(Arrays.equals(assembled.accept(Arrays.copyOfRange(maximum,split,maximum.length),pts+99999,0).bytes,maximum),"large partial AU assembled byte-identically within total bound");
  rejects(()->{AvcAccessUnits x=new AvcAccessUnits();x.accept(new byte[AvcAccessUnits.MAX_AU],1,8);x.accept(new byte[1],1,0);},"partial aggregate over2MiB");
  System.out.println("PASS "+checks+" AVC AU tests using independent libx264 Baseline31 fixture");
 }
}
