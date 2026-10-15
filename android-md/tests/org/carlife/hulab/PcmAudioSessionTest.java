package org.carlife.hulab;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
public final class PcmAudioSessionTest {
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static void await(Callable<Boolean> condition,String message)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!condition.call()){if(System.nanoTime()>until)throw new AssertionError(message);Thread.sleep(2);}checks++;}
 static class Events implements PcmAudioSession.Events {public long now(){return 1000;}public void wakeWriter(){}public void state(String s,String d){}}
 static class Fake implements PcmAudioSession.Source {
  int reads,closed;volatile boolean started;boolean fail;final CountDownLatch two=new CountDownLatch(1);
  public void start(){started=true;}
  public int read(byte[] b,int offset,int size)throws Exception{if(fail)throw new IOException("capture denied");if(reads>=4){two.countDown();return 0;}int n=Math.min(size,1920);Arrays.fill(b,offset,offset+n,(byte)1);reads++;return n;}
  public void close(){closed++;}
 }
 static PcmAudioSession worker(final Fake source){return new PcmAudioSession(new PcmAudioSession.Factory(){public PcmAudioSession.Source create(){return source;}},new Events());}
 public static void main(String[] args)throws Exception{
  final Fake fake=new Fake();final PcmAudioSession a=worker(fake);check(a.start(),"start");check(fake.two.await(2,TimeUnit.SECONDS),"partial reads assembled");
  check(a.queue.poll(1000).id==CarLifeAudio.INIT,"INIT after successful capture");CarLifeAudio.Item x=a.queue.poll(1000),y=a.queue.poll(1000);check(x.payload.length==3840&&y.payload.length==3840&&x.timestamp==1000&&y.timestamp==1020,"20ms sample-count clock from partial reads");
  a.stop();await(new Callable<Boolean>(){public Boolean call(){return a.retired();}},"source retirement");check(fake.closed==1&&!PcmAudioSession.LEASE.isHeld(),"release exactly once; lease free");check(a.queue.poll(1000).id==CarLifeAudio.STOP&&a.queue.size()==0,"one STOP no tail");
  final Fake failing=new Fake();failing.fail=true;final PcmAudioSession b=worker(failing);b.start();await(new Callable<Boolean>(){public Boolean call(){return b.retired();}},"failure retires");check(b.error().contains("capture denied")&&failing.closed==1&&!PcmAudioSession.LEASE.isHeld(),"failure recorded with cleanup");
  final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  final PcmAudioSession c=new PcmAudioSession(new PcmAudioSession.Factory(){public PcmAudioSession.Source create(){return new Fake(){public void start(){entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException e){}}};}},new Events());
  c.start();check(entered.await(2,TimeUnit.SECONDS),"slow start entered");c.stop();c.queue.clear();check(!c.retired(),"stop alone not cleanup proof");final PcmAudioSession d=worker(new Fake());check(!d.start(),"new capture fenced while native start has not returned");release.countDown();await(new Callable<Boolean>(){public Boolean call(){return c.retired();}},"slow start cleanup");check(c.queue.size()==0&&!PcmAudioSession.LEASE.isHeld(),"no INIT/STOP leaked after logical reset");
  final Fake callbackFake=new Fake();callbackFake.fail=true;
  final PcmAudioSession callbacks=new PcmAudioSession(new PcmAudioSession.Factory(){public PcmAudioSession.Source create(){return callbackFake;}},new Events(){public long now(){throw new IllegalStateException("clock failed");}public void state(String s,String d){throw new IllegalStateException("logger failed");}public void wakeWriter(){throw new IllegalStateException("wakeup failed");}});
  callbacks.start();await(new Callable<Boolean>(){public Boolean call(){return callbacks.retired();}},"bad diagnostics still retire source");check(callbackFake.closed==1&&!PcmAudioSession.LEASE.isHeld(),"diagnostic failures cannot retain capture lease");
  System.out.println("PASS "+checks+" audio source lifecycle checks");
 }
}
