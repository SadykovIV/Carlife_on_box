package org.carlife.hulab;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
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
 static final class WarmEvents extends Events {final AtomicLong clock=new AtomicLong(1000);public long now(){return clock.get();}}
 static final class Feed implements PcmAudioSession.Source {
  final WarmEvents events;final ArrayDeque<byte[]> parts=new ArrayDeque<byte[]>();final ArrayDeque<Long> times=new ArrayDeque<Long>();volatile int reads,settledReads,starts,closed;
  Feed(WarmEvents e){events=e;}
  public void start(){starts++;}
  synchronized void offer(int value,int length,long time){byte[] part=new byte[length];Arrays.fill(part,(byte)value);parts.add(part);times.add(time);}
  public synchronized int read(byte[] target,int offset,int size){if(parts.isEmpty()){settledReads=reads;return 0;}byte[] part=parts.remove();if(part.length>size)throw new AssertionError("test read size");System.arraycopy(part,0,target,offset,part.length);events.clock.set(times.remove());reads++;return part.length;}
  public void close(){closed++;}
 }
 static PcmAudioSession warmWorker(final Feed source,Events events,SourceLease lease){return new PcmAudioSession(new PcmAudioSession.Factory(){public PcmAudioSession.Source create(){return source;}},events,CarLifeAudio.Lane.MEDIA,lease);}
 static void feed(final Feed source,int value,int length,long time)throws Exception{final int count=source.reads+1;source.offer(value,length,time);await(new Callable<Boolean>(){public Boolean call(){return source.settledReads==count;}},"capture consumes PCM");}
 static void warmReady(final PcmAudioSession session)throws Exception{check(session.startWarm(),"warm worker starts");await(new Callable<Boolean>(){public Boolean call(){return session.active();}},"warm capture ready");}
 static void retire(final PcmAudioSession session)throws Exception{session.stop();await(new Callable<Boolean>(){public Boolean call(){return session.retired();}},"warm source retires");}
 static List<CarLifeAudio.Item> drain(PcmAudioSession session,long time){List<CarLifeAudio.Item> out=new ArrayList<CarLifeAudio.Item>();CarLifeAudio.Item item;while((item=session.queue.poll(time))!=null)out.add(item);return out;}
 static void warmChecks()throws Exception{
  final WarmEvents e=new WarmEvents();final Feed source=new Feed(e);final SourceLease lease=new SourceLease();final PcmAudioSession session=warmWorker(source,e,lease);warmReady(session);
  for(int i=0;i<6;i++)feed(source,i+1,CarLifeAudio.CHUNK_BYTES,1000+i*20);
  check(session.queue.size()==0,"warm idle emits neither INIT nor silent/nonzero PCM");
  session.setPlaybackActive(true);
  await(new Callable<Boolean>(){public Boolean call(){return session.queue.size()==6;}},"playback opens INIT and bounded preroll");
  List<CarLifeAudio.Item> first=drain(session,1100);check(first.get(0).id==CarLifeAudio.INIT,"INIT is before preroll");
  for(int i=1;i<first.size();i++){CarLifeAudio.Item item=first.get(i);check(item.id==CarLifeAudio.DATA&&item.payload[0]==i+1&&item.timestamp==1000+i*20&&item.queued==1000+i*20,"five recent chunks keep PCM, original timestamp and capture age");}
  session.setPlaybackActive(true);check(session.queue.size()==0,"same active signal does not duplicate INIT");
  feed(source,7,CarLifeAudio.CHUNK_BYTES,1120);await(new Callable<Boolean>(){public Boolean call(){return session.queue.size()==1;}},"active PCM immediately offered without preroll delay");
  check(session.queue.poll(1120).payload[0]==7,"active latest chunk unchanged");
  session.setPlaybackActive(false);check(session.queue.poll(1120).id==CarLifeAudio.STOP&&session.queue.size()==0,"idle ends wire immediately");
  check(source.starts==1&&source.closed==0&&session.active()&&lease.isHeld(),"idle preserves same capture and lease");
  feed(source,0,CarLifeAudio.CHUNK_BYTES,1140);feed(source,9,CarLifeAudio.CHUNK_BYTES,1160);
  check(session.queue.size()==0,"nonzero idle PCM cannot reopen HU source");
  session.setPlaybackActive(true);await(new Callable<Boolean>(){public Boolean call(){return session.queue.size()==3;}},"second playback reuses capture");
  List<CarLifeAudio.Item> second=drain(session,1160);check(second.get(0).id==CarLifeAudio.INIT&&second.get(1).payload[0]==0&&second.get(2).payload[0]==9,"new playback receives only post-idle PCM, no previous segment");
  retire(session);check(source.closed==1&&!lease.isHeld(),"hard stop releases warm source once");
  check(session.queue.poll(1160).id==CarLifeAudio.STOP&&session.queue.size()==0,"hard stop emits one STOP");
  session.setPlaybackActive(true);check(session.queue.size()==0&&!session.active(),"stopped capture cannot revive via playback callback");

  final WarmEvents staleEvents=new WarmEvents();final Feed staleSource=new Feed(staleEvents);final PcmAudioSession stale=warmWorker(staleSource,staleEvents,new SourceLease());warmReady(stale);
  feed(staleSource,42,CarLifeAudio.CHUNK_BYTES,1000);staleEvents.clock.set(1101);stale.setPlaybackActive(true);
  await(new Callable<Boolean>(){public Boolean call(){return stale.queue.size()==1;}},"expired preroll opens INIT only");
  check(stale.queue.poll(1101).id==CarLifeAudio.INIT&&stale.queue.size()==0,"PCM older than 100ms never replayed");
  feed(staleSource,43,CarLifeAudio.CHUNK_BYTES,1120);await(new Callable<Boolean>(){public Boolean call(){return stale.queue.size()==1;}},"fresh PCM flows after old preroll discard");
  check(stale.queue.poll(1120).payload[0]==43,"TTL discard does not lose next new frame");retire(stale);

  final WarmEvents partialEvents=new WarmEvents();final Feed partialSource=new Feed(partialEvents);final PcmAudioSession partial=warmWorker(partialSource,partialEvents,new SourceLease());warmReady(partial);partial.setPlaybackActive(true);
  await(new Callable<Boolean>(){public Boolean call(){return partial.queue.size()==1;}},"partial test INIT");drain(partial,1000);
  feed(partialSource,55,CarLifeAudio.CHUNK_BYTES/2,1000);partial.setPlaybackActive(false);partial.setPlaybackActive(true);
  await(new Callable<Boolean>(){public Boolean call(){return partial.queue.size()==2;}},"STOP precedes next INIT");
  List<CarLifeAudio.Item> edge=drain(partial,1000);check(edge.get(0).id==CarLifeAudio.STOP&&edge.get(1).id==CarLifeAudio.INIT,"pause/resume order preserved");
  feed(partialSource,66,CarLifeAudio.CHUNK_BYTES/2,1010);check(partial.queue.size()==0,"in-flight chunk crossing pause is discarded");
  feed(partialSource,77,CarLifeAudio.CHUNK_BYTES,1020);await(new Callable<Boolean>(){public Boolean call(){return partial.queue.size()==1;}},"fresh whole chunk after pause");
  CarLifeAudio.Item clean=partial.queue.poll(1020);check(clean.payload[0]==77&&clean.timestamp==1020,"pause discard keeps sample clock and no old samples");retire(partial);

  final WarmEvents idleEvents=new WarmEvents();final Feed idleSource=new Feed(idleEvents);final PcmAudioSession idle=warmWorker(idleSource,idleEvents,new SourceLease());warmReady(idle);feed(idleSource,88,CarLifeAudio.CHUNK_BYTES,1000);retire(idle);
  check(idle.queue.size()==0,"warm capture stopped before playback emits no STOP or INIT");
  check(idle.startWarm(),"retired warm worker restarts");await(new Callable<Boolean>(){public Boolean call(){return idle.active();}},"restarted warm ready");idle.setPlaybackActive(true);
  await(new Callable<Boolean>(){public Boolean call(){return idle.queue.size()==1;}},"restart INIT only");check(idle.queue.poll(1000).id==CarLifeAudio.INIT&&idle.queue.size()==0,"hard stop clears preroll across restart");retire(idle);

  final WarmEvents epochEvents=new WarmEvents();final Feed epochSource=new Feed(epochEvents);final PcmAudioSession epoch=warmWorker(epochSource,epochEvents,new SourceLease());warmReady(epoch);feed(epochSource,99,CarLifeAudio.CHUNK_BYTES,1000);
  epoch.queue.clear();epoch.setPlaybackActive(true);await(new Callable<Boolean>(){public Boolean call(){return epoch.retired();}},"stale queue epoch retires warm worker");
  check(epoch.queue.size()==0&&epochSource.closed==1,"logical reset fences delayed warm INIT and PCM");

  boolean rejected=false;try{new PcmAudioSession(new PcmAudioSession.Factory(){public PcmAudioSession.Source create(){return new Fake();}},new Events(),CarLifeAudio.Lane.VR,new SourceLease()).startWarm();}catch(IllegalStateException expected){rejected=true;}
  check(rejected,"warm mode cannot be enabled on VR");
 }
 public static void main(String[] args)throws Exception{
  final Fake fake=new Fake();final PcmAudioSession a=worker(fake);check(a.start(),"start");check(fake.two.await(2,TimeUnit.SECONDS),"partial reads assembled");
  check(a.queue.poll(1000).id==CarLifeAudio.INIT,"INIT after successful capture");CarLifeAudio.Item x=a.queue.poll(1000),y=a.queue.poll(1000);check(x.payload.length==3840&&y.payload.length==3840&&x.timestamp==1000&&y.timestamp==1020,"20ms sample-count clock from partial reads");
  a.stop();await(new Callable<Boolean>(){public Boolean call(){return a.retired();}},"source retirement");check(fake.closed==1&&!PcmAudioSession.LEASE.isHeld(),"release exactly once; lease free");check(a.queue.poll(1000).id==CarLifeAudio.STOP&&a.queue.size()==0,"one STOP no tail");
  final long[] startupClock={1000};final Fake delayed=new Fake(){public void start(){super.start();startupClock[0]=7000;}};
  final PcmAudioSession delayedSession=new PcmAudioSession(new PcmAudioSession.Factory(){public PcmAudioSession.Source create(){return delayed;}},new Events(){public long now(){return startupClock[0];}});
  delayedSession.start();check(delayed.two.await(2,TimeUnit.SECONDS),"delayed source produces PCM");delayedSession.queue.poll(7000);
  CarLifeAudio.Item delayedFirst=delayedSession.queue.poll(7000),delayedSecond=delayedSession.queue.poll(7000);
  check(delayedFirst.timestamp==7000&&delayedSecond.timestamp==7020,"source startup delay is excluded from sample-count timestamp origin");
  delayedSession.stop();await(new Callable<Boolean>(){public Boolean call(){return delayedSession.retired();}},"delayed source retires");
  final Fake failing=new Fake();failing.fail=true;final PcmAudioSession b=worker(failing);b.start();await(new Callable<Boolean>(){public Boolean call(){return b.retired();}},"failure retires");check(b.error().contains("capture denied")&&failing.closed==1&&!PcmAudioSession.LEASE.isHeld(),"failure recorded with cleanup");
  final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  final PcmAudioSession c=new PcmAudioSession(new PcmAudioSession.Factory(){public PcmAudioSession.Source create(){return new Fake(){public void start(){entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException e){}}};}},new Events());
  c.start();check(entered.await(2,TimeUnit.SECONDS),"slow start entered");c.stop();c.queue.clear();check(!c.retired(),"stop alone not cleanup proof");final PcmAudioSession d=worker(new Fake());check(!d.start(),"new capture fenced while native start has not returned");release.countDown();await(new Callable<Boolean>(){public Boolean call(){return c.retired();}},"slow start cleanup");check(c.queue.size()==0&&!PcmAudioSession.LEASE.isHeld(),"no INIT/STOP leaked after logical reset");
  final Fake callbackFake=new Fake();callbackFake.fail=true;
  final PcmAudioSession callbacks=new PcmAudioSession(new PcmAudioSession.Factory(){public PcmAudioSession.Source create(){return callbackFake;}},new Events(){public long now(){throw new IllegalStateException("clock failed");}public void state(String s,String d){throw new IllegalStateException("logger failed");}public void wakeWriter(){throw new IllegalStateException("wakeup failed");}});
  callbacks.start();await(new Callable<Boolean>(){public Boolean call(){return callbacks.retired();}},"bad diagnostics still retire source");check(callbackFake.closed==1&&!PcmAudioSession.LEASE.isHeld(),"diagnostic failures cannot retain capture lease");
  warmChecks();System.out.println("PASS "+checks+" audio source lifecycle checks");
 }
}
