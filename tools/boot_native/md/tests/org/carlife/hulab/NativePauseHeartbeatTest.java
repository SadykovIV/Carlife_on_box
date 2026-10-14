package org.carlife.hulab;
import java.io.*;
import java.util.*;
public final class NativePauseHeartbeatTest {
 static int checks;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static final class Memory implements MdTransport {
  final boolean nativeUsb; Memory(boolean n){nativeUsb=n;}
  public InputStream input(){return new ByteArrayInputStream(new byte[0]);}
  public OutputStream output(){return new ByteArrayOutputStream();}
  public String name(){return nativeUsb?"DIRECT_USB_AOA":"DEV_STREAM_OVER_ADB";}
  public boolean isNativeUsb(){return nativeUsb;}public void close(){}public boolean awaitStopped(long ms){return true;}
 }
 static MdMain.Session session(boolean nativeUsb,int major)throws Exception {
  MdMain.Session s=new MdMain.Session(new Memory(nativeUsb));s.version=true;s.initialized=true;s.huMajor=major;
  return s;
 }
 public static void main(String[]args)throws Exception {
  android.os.SystemClock.fixedTime=1000;MdMain.videoSource="mediacodec";
  MdMain.Session s=session(true,2);s.dispatch(1,0x1800a,new byte[0]);long generation=s.videoGeneration;
  check(s.nativePauseHeartbeatDue==3000,"2s starts from actual PAUSE");
  s.maybeSendNativePauseHeartbeat(2999);check(s.videos.isEmpty(),"no early heartbeat");
  android.os.SystemClock.fixedTime=3000;s.nativeProgress();check(s.videos.size()==1&&s.videoGeneration==generation&&s.mediaSource==null&&s.capture==null,"heartbeat retains generation and creates no source");
  MdMain.Packet p=s.videos.remove();DataInputStream wire=new DataInputStream(new ByteArrayInputStream(p.data));
  check(p.data.length==20&&wire.readInt()==2&&wire.readInt()==12&&wire.readInt()==0&&wire.readInt()==3000&&wire.readInt()==0x20001&&wire.available()==0,"fixed empty VIDEO_DATA wire header, timestamp distinct from encoded AU");
  s.maybeSendNativePauseHeartbeat(4999);check(s.videos.isEmpty(),"no heartbeat flood between periods");
  s.maybeSendNativePauseHeartbeat(5000);check(s.videos.size()==1,"second period");s.videos.clear();
  s.maybeSendNativePauseHeartbeat(25000);check(s.videos.size()==1&&s.nativePauseHeartbeatDue==27000,"long delay emits one heartbeat, not catch-up flood");s.videos.clear();
  s.mediaSource=new MediaCodecDisplaySource(s,new MediaCodecDisplaySource.Selection("TEST","repeat","fps",1280,720,30),generation);
  s.maybeSendNativePauseHeartbeat(27000);check(s.videos.isEmpty(),"wait for old source cleanup");s.mediaSource=null;
  s.paused=false;s.maybeSendNativePauseHeartbeat(27000);check(s.videos.isEmpty(),"no keepalive during normal stream");s.paused=true;
  s.initialized=false;s.maybeSendNativePauseHeartbeat(27000);check(s.videos.isEmpty(),"never imitate media before INIT");s.initialized=true;
  s.dispatch(1,0x18001,MdMain.Proto.ints(1,2,2,0));s.maybeSendNativePauseHeartbeat(27000);check(s.nativePauseHeartbeatDue==0&&s.videos.isEmpty()&&!s.initialized,"fresh VERSION clears heartbeat timer");s.close();
  MdMain.Session v1=session(true,1);v1.dispatch(1,0x1800a,new byte[0]);v1.maybeSendNativePauseHeartbeat(Long.MAX_VALUE);check(v1.videos.isEmpty()&&v1.nativePauseHeartbeatDue==0,"native SELF1 unchanged");v1.close();
  MdMain.Session dev=session(false,2);dev.dispatch(1,0x1800a,new byte[0]);dev.maybeSendNativePauseHeartbeat(Long.MAX_VALUE);check(dev.videos.isEmpty()&&dev.nativePauseHeartbeatDue==0,"development transport unchanged");dev.close();
  MdMain.Session closed=session(true,2);closed.dispatch(1,0x1800a,new byte[0]);closed.close();closed.maybeSendNativePauseHeartbeat(Long.MAX_VALUE);check(closed.videos.isEmpty(),"closed owner sends nothing");
  android.os.SystemClock.fixedTime=-1;
  System.out.println("PASS "+checks+" pause heartbeat checks; no device I/O");
 }
}
