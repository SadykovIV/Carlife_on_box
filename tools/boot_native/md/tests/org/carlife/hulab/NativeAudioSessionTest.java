package org.carlife.hulab;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
public final class NativeAudioSessionTest {
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static void await(Callable<Boolean> c,String why)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!c.call()){if(System.nanoTime()>end)throw new AssertionError(why);Thread.sleep(2);}checks++;}
 static final class Memory implements MdTransport {
  final List<byte[]> packets=Collections.synchronizedList(new ArrayList<byte[]>());
  public InputStream input(){return new ByteArrayInputStream(new byte[0]);}public OutputStream output(){return new ByteArrayOutputStream();}
  public void writePacket(byte[] p){packets.add(p.clone());}public String name(){return "MEMORY";}public boolean isNativeUsb(){return true;}public void close(){}public boolean awaitStopped(long ms){return true;}
 }
 static int channel(byte[] p)throws Exception{return new DataInputStream(new ByteArrayInputStream(p)).readInt();}
 static int id(byte[] p)throws Exception{DataInputStream in=new DataInputStream(new ByteArrayInputStream(p));int ch=in.readInt();in.skipBytes(ch==1?8:12);return in.readInt();}
 static MdMain.Session session(Memory transport,PcmAudioSession.Factory f)throws Exception{MdMain.Session s=new MdMain.Session(transport,new MdMain.InputSink(),f);s.version=true;s.huMajor=2;s.initialized=true;return s;}
 public static void main(String[] args)throws Exception{
  MdMain.bootNative=true;MdMain.videoSource="mediacodec";android.os.SystemClock.fixedTime=1000;
  PcmAudioSession.Factory fake=new PcmAudioSession.Factory(){public PcmAudioSession.Source create(){return new PcmAudioSessionTest.Fake();}};
  MdMain.audioSource="off";MdMain.Session off=session(new Memory(),fake);off.audioNegotiated=true;off.maybeStartAudio();check(off.audio.retired()&&!off.audio.active(),"baseline defaults to audio off");off.close();
  MdMain.audioSource="remote-submix";final MdMain.Session s=session(new Memory(),fake);s.audioNegotiated=true;s.maybeStartAudio();await(new Callable<Boolean>(){public Boolean call(){return s.audio.active();}},"capture starts");
  s.dispatch(1,0x1800a,new byte[0]);check(s.audio.active(),"VIDEO_PAUSE does not pause music");s.dispatch(1,0x1800b,new byte[0]);check(s.audio.active(),"native video RESET preserves audio");
  s.dispatch(1,0x18028,MdMain.Proto.ints(1,3,2,0));await(new Callable<Boolean>(){public Boolean call(){return s.audio.retired();}},"HU radio stop retires source");s.maybeStartAudio();check(s.audio.retired()&&s.audioHuMuted,"no autoplay after HU music off");
  s.dispatch(1,0x18052,CarLifeAudioTest.feature(1));s.dispatch(1,0x18028,MdMain.Proto.ints(1,3,2,1));check(s.audioBluetooth&&s.audio.retired(),"Bluetooth mode suppresses USB PCM");
  s.dispatch(1,0x18052,CarLifeAudioTest.feature(0));await(new Callable<Boolean>(){public Boolean call(){return s.audio.active();}},"explicit USB/music resumes capture");
  s.dispatch(1,0x18001,MdMain.Proto.ints(1,2,2,0));await(new Callable<Boolean>(){public Boolean call(){return s.audio.retired();}},"fresh version retires source");check(!s.audioNegotiated&&s.audio.queue.size()==0,"logical session boundary cannot leak PCM/STOP");s.close();check(s.awaitTerminalCleanup(1000),"terminal audio cleanup participates in ownership fence");
  MdMain.audioSource="off";final Memory wire=new Memory();final MdMain.Session w=session(wire,fake);w.send(1,0x10018,new byte[0]);w.send(2,0x20001,new byte[]{0,0,0,1,0x65},w.videoGeneration);
  long token=w.audio.queue.epoch();w.audio.queue.start(token,1000);w.audio.queue.data(token,new byte[3840],1000,1000);
  w.writer=new Thread(new Runnable(){public void run(){w.writeLoop();}});w.writer.start();await(new Callable<Boolean>(){public Boolean call(){return wire.packets.size()>=5;}},"writer sends control, INIT, status, video, PCM");
  w.close();w.writer.join(1000);check(w.awaitTerminalCleanup(1000),"writer terminates");
  check(channel(wire.packets.get(0))==1&&id(wire.packets.get(0))==0x10018,"command first");check(id(wire.packets.get(1))==CarLifeAudio.INIT,"audio INIT before DATA");check(id(wire.packets.get(2))==0x10026,"MUSIC active follows successful wire INIT");check(channel(wire.packets.get(3))==2&&id(wire.packets.get(4))==CarLifeAudio.DATA,"fair scheduling video then audio without starvation");
  final MdMain.Session fences=session(new Memory(),fake);long old=fences.audio.queue.epoch();fences.audio.queue.start(old,1000);CarLifeAudio.Item oldInit=fences.audio.queue.poll(1000);
  fences.resetAudio();fences.audioWritten(oldInit);check(!fences.audioWireActive&&fences.pendingMusicStatus==null,"stale INIT completion cannot send MUSIC active into fresh VERSION");
  long fresh=fences.audio.queue.epoch();fences.audio.queue.start(fresh,1000);CarLifeAudio.Item newInit=fences.audio.queue.poll(1000);
  for(int i=0;i<32;i++)fences.controls.add(new MdMain.Packet(MdMain.packet(1,0x10018,new byte[0],0),-1));
  fences.audioWritten(newInit);check(fences.open&&fences.controls.size()==32&&fences.pendingMusicStatus.active,"audio status never offers into full writer control queue");
  fences.dispatch(1,0x1800e,new byte[0]);check(fences.audioHuMuted&&!fences.pendingMusicStatus.active,"HU PAUSE_MEDIA suppresses PCM and replies idle");fences.close();
  final MdMain.Session disabled=session(new Memory(),fake);disabled.dispatch(1,0x18028,MdMain.Proto.ints(1,3,2,0));check(disabled.pendingMusicStatus!=null&&!disabled.pendingMusicStatus.active,"MUSIC off answered even without capture");disabled.audioWireActive=true;disabled.moduleStatusDue=1;disabled.nativeProgress();check(!disabled.pendingMusicStatus.active,"late idle-module report cannot reactivate HU-muted audio");disabled.close();
  MdMain.audioSource="off";MdMain.bootNative=false;android.os.SystemClock.fixedTime=-1;
  System.out.println("PASS "+checks+" native audio integration checks");
 }
}
