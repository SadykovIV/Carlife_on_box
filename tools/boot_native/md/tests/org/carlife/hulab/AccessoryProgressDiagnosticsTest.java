package org.carlife.hulab;
import java.io.*;import java.util.*;import java.util.concurrent.*;
public final class AccessoryProgressDiagnosticsTest {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static final class Io implements AccessoryTransport.Io {
  final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);volatile boolean closed,allowRead,failPoll;
  public boolean pollRead(int ms)throws IOException{if(failPoll)throw new IOException("fixture poll failure");if(allowRead)return true;try{Thread.sleep(5);}catch(InterruptedException e){throw new InterruptedIOException();}return false;}
  public boolean pollWrite(int ms){return false;}
  public int read(byte[]b)throws IOException{entered.countDown();try{release.await();}catch(InterruptedException e){throw new InterruptedIOException();}throw new EOFException("fixture canceled");}
  public int write(byte[]b,int o,int n){return 0;}public void close(){closed=true;release.countDown();}
 }
 static Map<Object,Object> fields(MdMain.Session s){List<Object>a=new ArrayList<Object>();s.appendAccessoryProgress(a);Map<Object,Object>m=new LinkedHashMap<Object,Object>();for(int i=0;i<a.size();i+=2)m.put(a.get(i),a.get(i+1));return m;}
 public static void main(String[]args)throws Exception {
  Io idle=new Io();AccessoryTransport t=new AccessoryTransport(idle);MdMain.Session s=new MdMain.Session(t);Map<Object,Object>m=fields(s);
  check(m.size()==3&&m.get("accessory_kernel_read_calls").equals(0L)&&m.get("accessory_kernel_read_inflight").equals(false)&&m.get("accessory_reader_failure")==null,"poll idle distinguished from entered kernel read");
  idle.allowRead=true;check(idle.entered.await(1,TimeUnit.SECONDS),"real adapter reader enters fixture");m=fields(s);
  check(m.get("accessory_kernel_read_calls").equals(1L)&&m.get("accessory_kernel_read_inflight").equals(true),"blocked read shown with call count");s.close();check(t.awaitStopped(1000),"old cancellation preserved");
  m=fields(s);check(m.get("accessory_kernel_read_inflight").equals(false)&&m.get("accessory_reader_failure")==null,"own close not misclassified as reader failure");
  Io fail=new Io();fail.failPoll=true;AccessoryTransport ft=new AccessoryTransport(fail);MdMain.Session fs=new MdMain.Session(ft);
  long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(ft.readerFailure()==null&&System.nanoTime()<until)Thread.sleep(1);
  m=fields(fs);check(m.get("accessory_kernel_read_calls").equals(0L)&&m.get("accessory_reader_failure").toString().contains("fixture poll failure"),"poll failure exposed before any kernel read");fs.close();check(ft.awaitStopped(1000),"failed reader cleanup unchanged");
  System.out.println("PASS "+checks+" real adapter progress checks; no device I/O");
 }
}
