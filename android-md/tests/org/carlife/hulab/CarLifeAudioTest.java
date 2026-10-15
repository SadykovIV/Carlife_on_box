package org.carlife.hulab;
import java.io.*;
import java.util.*;
public final class CarLifeAudioTest {
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static byte[] feature(int value)throws Exception{byte[] e=MdMain.Proto.strings(1,"AUDIO_TRANSMISSION_MODE");ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(e);out.write(MdMain.Proto.ints(2,value));e=out.toByteArray();out.reset();out.write(MdMain.Proto.ints(1,1));MdMain.Proto.varint(out,18);MdMain.Proto.varint(out,e.length);out.write(e);return out.toByteArray();}
 public static void main(String[] args)throws Exception{
  Map<Integer,Object> f=MdMain.Proto.parse(CarLifeAudio.init());check(MdMain.Proto.number(f,1,0)==48000&&MdMain.Proto.number(f,2,0)==2&&MdMain.Proto.number(f,3,0)==16,"sample rate/channel count/PCM bits");
  f=MdMain.Proto.parse(CarLifeAudio.musicStatus(true));check(MdMain.Proto.number(f,1,0)==1,"one module");f=MdMain.Proto.parse((byte[])f.get(2));check(MdMain.Proto.number(f,1,0)==3&&MdMain.Proto.number(f,2,0)==1,"music active");
  check(CarLifeAudio.transmissionMode(feature(1))==1&&CarLifeAudio.transmissionMode(feature(0))==0&&CarLifeAudio.transmissionMode(new byte[0])==null,"feature mode BT and USB");
  check(CarLifeAudio.transmissionMode(new byte[]{26,0})==null,"empty unknown protobuf field at EOF");
  ByteArrayOutputStream dup=new ByteArrayOutputStream();dup.write(feature(0));dup.write(feature(1));try{CarLifeAudio.transmissionMode(dup.toByteArray());throw new AssertionError("conflict accepted");}catch(IOException expected){checks++;}
  CarLifeAudio.Queue q=new CarLifeAudio.Queue();long token=q.epoch();q.start(token,100);byte[] pcm=new byte[CarLifeAudio.CHUNK_BYTES];pcm[0]=1;
  for(int i=0;i<12;i++)q.data(token,pcm,100+i*20,100+i*20);check(q.size()==11&&q.dropped==2,"bounded queue drops only old complete PCM chunks");
  check(q.poll(340).id==CarLifeAudio.INIT,"INIT survives overload/age");CarLifeAudio.Item item=q.poll(340);check(item.id==CarLifeAudio.DATA&&item.timestamp==140&&item.payload[0]==1,"oldest retained timestamp; no catchup rebasing");pcm[0]=99;check(item.payload[0]==1,"source buffer copied");
  q.stop(token,350);check(q.size()==1&&q.poll(350).id==CarLifeAudio.STOP,"STOP discards PCM tail");q.data(token,pcm,360,360);check(q.size()==0,"no PCM after STOP");
  q.start(token,400);q.poll(400);q.data(token,pcm,400,400);check(q.poll(601)==null,"age fence drops stale chunk");
  q.start(token,700);item=q.poll(700);q.clear();check(!q.current(item)&&!q.start(token,700),"logical epoch invalidates already polled INIT");q.data(token,pcm,700,700);q.stop(token,700);check(q.size()==0,"retiring source cannot publish to new epoch");
  token=q.epoch();q.start(token,800);try{q.data(token,new byte[3],800,800);throw new AssertionError("unaligned accepted");}catch(IOException expected){checks++;}
  q.stop(token,800);q.start(token,801);check(q.poll(801).id==CarLifeAudio.STOP&&q.poll(801).id==CarLifeAudio.INIT,"STOP survives fast restart before writer drains");
  q.clear();System.out.println("PASS "+checks+" audio wire/queue checks");
 }
}
