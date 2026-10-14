package org.carlife.hulab;
import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
/** Exercise the actual MD writer across a deterministically stalled in-flight old VIDEO packet. */
public final class MdWriterRaceTest {
 static final class BarrierOutput extends OutputStream {
  final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),newVideo=new CountDownLatch(1);
  final List<byte[]> written=Collections.synchronizedList(new ArrayList<byte[]>());
  public void write(int x)throws IOException{throw new IOException("unexpected byte write");}
  public void write(byte[] b,int off,int len)throws IOException{
   if(written.isEmpty()){started.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new IOException("test barrier timeout");}catch(InterruptedException e){throw new IOException(e);}}
   byte[] frame=Arrays.copyOfRange(b,off,off+len);written.add(frame);
   MdMain.Frame f=MdMain.readFrame(new DataInputStream(new ByteArrayInputStream(frame)));
   if(f.channel==2&&f.payload.length==1&&f.payload[0]==(byte)0xbb)newVideo.countDown();
  }
 }
 static final class MemorySocket extends Socket {
  final BarrierOutput out;MemorySocket(BarrierOutput o){out=o;}
  public void setTcpNoDelay(boolean x){}public void setSoTimeout(int x){}public void setSendBufferSize(int x){}
  public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]);}
  public OutputStream getOutputStream(){return out;}public void close(){}
 }
 public static void main(String[] ignored)throws Exception{
  BarrierOutput out=new BarrierOutput();final MdMain.Session s=new MdMain.Session(new MemorySocket(out));s.videoGeneration=7;
  s.videos.add(new MdMain.Packet(MdWireTest.hex("000000020000000d000000010000000000020001aa"),7));
  s.writer=new Thread(new Runnable(){public void run(){s.writeLoop();}},"race-test-writer");s.writer.start();
  if(!out.started.await(2,TimeUnit.SECONDS))throw new AssertionError("old frame never in flight");
  s.stopCapture(); // Actual stop path invalidates generation before destroying/draining the source.
  s.send(1,0x10008,MdMain.Proto.ints(1,1280,2,720,3,30));
  // Model a source that passed the enqueue guard before RESET and resumed late.
  s.videos.add(new MdMain.Packet(MdWireTest.hex("000000020000000d000000010000000000020001cc"),7));
  s.videos.add(new MdMain.Packet(MdWireTest.hex("000000020000000d000000010000000000020001bb"),s.videoGeneration));
  out.release.countDown();
  if(!out.newVideo.await(2,TimeUnit.SECONDS))throw new AssertionError("new generation never delivered");
  s.close();s.writer.join(2000);
  if(s.writer.isAlive())throw new AssertionError("writer remained running");
  if(s.socketWrittenPackets!=3||s.socketWrittenVideoPackets!=2||s.staleVideoWriteDrops.get()!=1||s.writingPacket!=null||s.writingSince!=0)throw new AssertionError("writer progress counters/state inconsistent after actual RESET race");
  if(out.written.size()!=3)throw new AssertionError("old-generation packet crossed barrier: "+out.written.size());
  MdMain.Frame a=MdMain.readFrame(new DataInputStream(new ByteArrayInputStream(out.written.get(0))));
  MdMain.Frame b=MdMain.readFrame(new DataInputStream(new ByteArrayInputStream(out.written.get(1))));
  MdMain.Frame c=MdMain.readFrame(new DataInputStream(new ByteArrayInputStream(out.written.get(2))));
  if(a.channel!=2||a.payload[0]!=(byte)0xaa||b.id!=0x10008||c.channel!=2||c.payload[0]!=(byte)0xbb)throw new AssertionError("incorrect reset writer order");
  System.out.println("PASS actual MD writer RESET barrier: old in-flight VIDEO -> INIT_DONE -> new VIDEO; late stale source dropped; writer stopped");
 }
}
