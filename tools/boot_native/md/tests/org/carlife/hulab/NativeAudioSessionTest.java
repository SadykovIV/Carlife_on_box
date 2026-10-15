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
 static class Playback implements PlaybackActivity.Source {
  PlaybackActivity.Observer observer;int closes;int[] lastOutput=new int[0],lastMedia=new int[0];
  public void start(PlaybackActivity.Observer o){observer=o;emit(lastOutput,lastMedia);}public void close(){closes++;}
  void emit(int[] output,int[] media){lastOutput=output.clone();lastMedia=media.clone();Set<Integer> a=new HashSet<Integer>(),b=new HashSet<Integer>();for(int id:output)a.add(id);for(int id:media)b.add(id);observer.update(new PlaybackActivity.Snapshot(a,b));}
 }
 static final class Keys extends MdMain.InputSink {int pauses,lastKey=-1; synchronized void mediaPause(){pauses++;} synchronized void hardkey(int key){lastKey=key;}}
 static MdMain.Session session(Memory transport,PcmAudioSession.Factory f)throws Exception{MdMain.Session s=new MdMain.Session(transport,new MdMain.InputSink(),f);s.version=true;s.huMajor=2;s.initialized=true;return s;}
 public static void main(String[] args)throws Exception{
  MdMain.bootNative=true;MdMain.videoSource="mediacodec";android.os.SystemClock.fixedTime=1000;
  PcmAudioSession.Factory fake=new PcmAudioSession.Factory(){public PcmAudioSession.Source create(){return new PcmAudioSessionTest.Fake();}};
  MdMain.audioSource="off";MdMain.Session off=session(new Memory(),fake);off.audioNegotiated=true;off.maybeStartAudio();check(off.audio.retired()&&!off.audio.active(),"baseline defaults to audio off");off.close();
  MdMain.audioSource="remote-submix";final MdMain.Session retry=session(new Memory(),fake);retry.audioNegotiated=true;Object previousOwner=new Object();PcmAudioSession.LEASE.acquire(previousOwner,"test retiring source");
  try{retry.maybeStartAudio();check(!retry.audioAttempted&&retry.audio.retired(),"temporary source lease does not consume audio start attempt");}finally{PcmAudioSession.LEASE.release(previousOwner,true);}
  retry.maybeStartAudio();await(new Callable<Boolean>(){public Boolean call(){return retry.audio.active();}},"capture starts once previous lease retires");retry.close();check(retry.awaitTerminalCleanup(1000),"retried capture releases resources");
  MdMain.audioSource="remote-submix";final MdMain.Session s=session(new Memory(),fake);s.audioNegotiated=true;s.maybeStartAudio();await(new Callable<Boolean>(){public Boolean call(){return s.audio.active();}},"capture starts");
  s.dispatch(1,0x1800a,new byte[0]);check(s.audio.active(),"VIDEO_PAUSE does not pause music");s.dispatch(1,0x1800b,new byte[0]);check(s.audio.active(),"native video RESET preserves audio");
  s.dispatch(1,0x18028,MdMain.Proto.ints(1,3,2,0));await(new Callable<Boolean>(){public Boolean call(){return s.audio.retired();}},"HU radio stop retires source");s.maybeStartAudio();check(s.audio.retired()&&s.audioHuMuted,"no autoplay after HU music off");
  s.dispatch(1,0x18052,CarLifeAudioTest.feature(1));s.dispatch(1,0x18028,MdMain.Proto.ints(1,3,2,1));check(s.audioBluetooth&&s.audio.retired(),"Bluetooth mode suppresses USB PCM");
  s.dispatch(1,0x18052,CarLifeAudioTest.feature(2));check(s.audioHuMuted&&s.audio.retired(),"transport change and MUSIC status query do not undo selected radio");s.explicitMediaPlay();await(new Callable<Boolean>(){public Boolean call(){return s.audio.active();}},"explicit Play can resume non-Bluetooth USB output");
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
  MdMain.audioSource="remote-submix";Playback players=new Playback();Keys keys=new Keys();final MdMain.Session tracked=new MdMain.Session(new Memory(),keys,fake,players);tracked.version=true;tracked.huMajor=2;tracked.initialized=true;tracked.audioNegotiated=true;tracked.maybeStartAudio();
  check(!tracked.audioAttempted&&tracked.audio.retired()&&tracked.audio.queue.size()==0,"known idle creates no capture, MEDIA INIT or silent PCM");tracked.audioWireActive=true;tracked.musicStatusReply();check(!tracked.pendingMusicStatus.active,"empty submix does not advertise MUSIC playing");
  players.emit(new int[]{7},new int[]{7});await(new Callable<Boolean>(){public Boolean call(){return tracked.audio.active();}},"active playback starts capture after known idle");check(tracked.pendingMusicStatus.active,"actual Android output advertises MUSIC playing");
  PlaybackActivity.Observer beforeRadio=players.observer;tracked.dispatch(1,0x18028,MdMain.Proto.ints(1,3,2,0));await(new Callable<Boolean>(){public Boolean call(){return tracked.audio.retired();}},"radio retires capture");check(keys.pauses==1,"radio requests Android media pause");
  beforeRadio.update(new PlaybackActivity.Snapshot(new HashSet<Integer>(Arrays.asList(8)),new HashSet<Integer>(Arrays.asList(8))));check(tracked.audioHuMuted,"pre-radio queued callback cannot undo HU mute");
  players.emit(new int[]{7},new int[]{7});check(tracked.audioHuMuted&&tracked.audio.retired(),"still-playing old source cannot undo radio selection");
  players.emit(new int[]{9},new int[0]);check(tracked.audioHuMuted,"navigation prompt cannot resume radio-muted MUSIC");
  players.emit(new int[]{7,9},new int[]{7});await(new Callable<Boolean>(){public Boolean call(){return tracked.audio.active();}},"new player start resumes without another HU command");check(!tracked.audioHuMuted,"observed stopped-to-started player releases HU mute");
  PlaybackActivity.Observer stale=players.observer;tracked.resetAudio();await(new Callable<Boolean>(){public Boolean call(){return tracked.audio.retired();}},"version boundary retires monitored capture");check(players.closes==2&&!tracked.playback.known(),"version boundary unregisters monitor and clears snapshot");
  tracked.audioHuMuted=true;stale.update(new PlaybackActivity.Snapshot(new HashSet<Integer>(Arrays.asList(10)),new HashSet<Integer>(Arrays.asList(10))));check(tracked.audioHuMuted&&!tracked.playback.known(),"late callback from old logical session is ignored");tracked.close();
  final Playback racePlayers=new Playback();racePlayers.lastOutput=new int[]{5};racePlayers.lastMedia=new int[]{5};final MdMain.Session radioRace=new MdMain.Session(new Memory(),new Keys(),fake,racePlayers);radioRace.version=true;radioRace.audioNegotiated=true;radioRace.maybeStartAudio();await(new Callable<Boolean>(){public Boolean call(){return radioRace.audio.active();}},"race source starts");
  Thread event;
  synchronized(radioRace){
   event=new Thread(new Runnable(){public void run(){racePlayers.emit(new int[]{7},new int[]{7});}});event.start();final Thread waitingEvent=event;
   await(new Callable<Boolean>(){public Boolean call(){return waitingEvent.getState()==Thread.State.BLOCKED;}},"player event accepted before HU mute while listener waits");radioRace.muteAudio();
  }
  event.join(1000);await(new Callable<Boolean>(){public Boolean call(){return radioRace.audio.retired();}},"radio race capture retires");check(radioRace.audioHuMuted,"accepted pre-mute event cannot undo later HU mute");
  racePlayers.emit(new int[0],new int[0]);racePlayers.emit(new int[]{7},new int[]{7});await(new Callable<Boolean>(){public Boolean call(){return radioRace.audio.active();}},"rapid paused/playing snapshots of same player resume capture");radioRace.close();check(radioRace.awaitTerminalCleanup(1000),"event monitor session cleanup");
  Playback brokenPlayers=new Playback();brokenPlayers.lastOutput=new int[]{5};brokenPlayers.lastMedia=new int[]{5};Keys degradedKeys=new Keys();final MdMain.Session degraded=new MdMain.Session(new Memory(),degradedKeys,fake,brokenPlayers);degraded.version=true;degraded.initialized=true;degraded.audioNegotiated=true;degraded.maybeStartAudio();await(new Callable<Boolean>(){public Boolean call(){return degraded.audio.active();}},"fallback source starts");degraded.audioWireActive=true;degraded.musicStatusReply();check(degraded.pendingMusicStatus.active,"known active initial snapshot produces MUSIC state");
  brokenPlayers.observer.failed("test playback API unavailable");check(!degraded.playback.known()&&degraded.pendingMusicStatus.active&&degraded.audio.active(),"API failure keeps basic PCM and refreshes status");degraded.muteAudio();await(new Callable<Boolean>(){public Boolean call(){return degraded.audio.retired();}},"fallback radio mute retires");degraded.dispatch(1,0x18028,MdMain.Proto.ints(1,3,2,1));check(degraded.audioHuMuted&&degraded.audio.retired(),"MUSIC=1 reports actual paused status even in degraded mode");degraded.dispatch(6,0x68008,MdMain.Proto.ints(1,0x1f));check(degradedKeys.lastKey==0x1f,"CarLife MEDIA_PLAY reaches Android key sink");await(new Callable<Boolean>(){public Boolean call(){return degraded.audio.active();}},"wire MEDIA_PLAY works without optional playback API");
  int pauseCalls=degradedKeys.pauses;degraded.dispatch(6,0x68008,MdMain.Proto.ints(1,0x20));
  await(new Callable<Boolean>(){public Boolean call(){return degraded.audio.retired();}},"wire MEDIA_PAUSE retires PCM when playback observer is unavailable");
  check(degradedKeys.lastKey==0x20&&degradedKeys.pauses==pauseCalls,"explicit Pause key is delivered once without synthetic duplicate pause");
  check(degraded.audioHuMuted&&!degraded.pendingMusicStatus.active,"wire MEDIA_PAUSE reports MUSIC inactive in degraded mode");
  degraded.dispatch(1,0x18028,MdMain.Proto.ints(1,3,2,1));degraded.maybeStartAudio();check(degraded.audioHuMuted&&degraded.audio.retired()&&!degraded.pendingMusicStatus.active,"MUSIC=1 cannot resume explicit Pause without observer");
  degraded.close();check(degraded.awaitTerminalCleanup(1000),"fallback cleanup");
  final CountDownLatch opening=new CountDownLatch(1),releaseApi=new CountDownLatch(1),queueFree=new CountDownLatch(1);
  PlaybackActivity.Source unavailable=new PlaybackActivity.Source(){public void start(PlaybackActivity.Observer o)throws Exception{opening.countDown();releaseApi.await();throw new IOException("platform API denied");}public void close(){}};
  final MdMain.Session lockOrder=new MdMain.Session(new Memory(),new Keys(),fake,unavailable);lockOrder.version=true;lockOrder.audioNegotiated=true;long lockToken=lockOrder.audio.queue.epoch();lockOrder.audio.queue.start(lockToken,1000);final CarLifeAudio.Item lockInit=lockOrder.audio.queue.poll(1000);
  Thread startup=new Thread(new Runnable(){public void run(){lockOrder.maybeStartAudio();}});startup.setDaemon(true);startup.start();check(opening.await(1,TimeUnit.SECONDS),"platform startup holds playback monitor");
  final Thread completion=new Thread(new Runnable(){public void run(){lockOrder.audioWritten(lockInit);}});completion.setDaemon(true);completion.start();completion.join(1000);check(!completion.isAlive(),"writer reads activity without waiting for platform monitor lock");
  Thread queueProbe=new Thread(new Runnable(){public void run(){synchronized(lockOrder.audio.queue){queueFree.countDown();}}});queueProbe.setDaemon(true);queueProbe.start();boolean queueAvailable=queueFree.await(1,TimeUnit.SECONDS);releaseApi.countDown();check(queueAvailable,"writer never holds audio queue while waiting for playback monitor");startup.join(1000);completion.join(1000);check(!startup.isAlive()&&!completion.isAlive(),"API failure and writer completion cannot deadlock");lockOrder.close();check(lockOrder.awaitTerminalCleanup(1000),"lock-order regression cleanup");
  final Playback newestPlayers=new Playback();final MdMain.Session newest=new MdMain.Session(new Memory(),new Keys(),fake,newestPlayers);newest.version=true;newest.playback.start();newest.audio.queue.start(newest.audio.queue.epoch(),1000);final CarLifeAudio.Item newestInit=newest.audio.queue.poll(1000);Thread newestWriter;
  synchronized(newest.audio.queue){
   newestWriter=new Thread(new Runnable(){public void run(){newest.audioWritten(newestInit);}});newestWriter.setDaemon(true);newestWriter.start();final Thread waitingWriter=newestWriter;await(new Callable<Boolean>(){public Boolean call(){return waitingWriter.getState()==Thread.State.BLOCKED;}},"INIT completion waits for queue ownership");
   newestPlayers.emit(new int[]{11},new int[]{11});
  }
  newestWriter.join(1000);check(!newestWriter.isAlive()&&newest.pendingMusicStatus.active,"INIT uses newest activity and cannot overwrite a newer player callback with stale idle");newest.close();
  final Playback deferredPlayers=new Playback(){public void start(PlaybackActivity.Observer o){observer=o;}};
  final MdMain.Session deferred=new MdMain.Session(new Memory(),new Keys(),fake,deferredPlayers);deferred.version=true;deferred.audioNegotiated=true;deferred.maybeStartAudio();
  check(!deferred.playback.known()&&!deferred.playback.outputActive()&&!deferred.audioAttempted&&deferred.audio.retired()&&deferred.audio.queue.size()==0,"async initial player snapshot cannot leak an idle MEDIA INIT");
  deferredPlayers.emit(new int[]{17},new int[]{17});await(new Callable<Boolean>(){public Boolean call(){return deferred.audio.active();}},"initial async active snapshot starts audio without a prior inactive event");
  deferredPlayers.emit(new int[0],new int[0]);await(new Callable<Boolean>(){public Boolean call(){return deferred.audio.retired();}},"actual player stop releases output capture");
  check(!deferred.audioAttempted&&!PcmAudioSession.LEASE.isHeld(),"idle playback resets start attempt after source retirement");
  CarLifeAudio.Item idleStop=deferred.audio.queue.poll(1000);check(idleStop!=null&&idleStop.id==CarLifeAudio.STOP&&deferred.audio.queue.size()==0,"actual player stop drops PCM tail and sends one STOP");
  deferredPlayers.emit(new int[]{17},new int[]{17});await(new Callable<Boolean>(){public Boolean call(){return deferred.audio.active();}},"same player resumes after idle without restarting video");
  deferred.muteAudio();await(new Callable<Boolean>(){public Boolean call(){return deferred.audio.retired();}},"radio retires resumed source");
  for(int i=0;i<7;i++)deferred.dispatch(1,0x18028,MdMain.Proto.ints(1,3,2,1));
  check(deferred.audioHuMuted&&deferred.audio.retired()&&!deferred.pendingMusicStatus.active,"repeated HU MUSIC=1 status queries cannot restore silent PCM or autoplay");
  deferred.close();check(deferred.awaitTerminalCleanup(1000),"deferred player source cleanup");
  final Playback unavailableInitial=new Playback(){public void start(PlaybackActivity.Observer o){observer=o;}};
  final MdMain.Session delayedFallback=new MdMain.Session(new Memory(),new Keys(),fake,unavailableInitial);delayedFallback.version=true;delayedFallback.audioNegotiated=true;delayedFallback.maybeStartAudio();
  check(!delayedFallback.audioAttempted,"awaiting callback before degraded fallback");unavailableInitial.observer.failed("async unavailable API");
  await(new Callable<Boolean>(){public Boolean call(){return delayedFallback.audio.active();}},"asynchronous platform failure enables basic PCM fallback");delayedFallback.close();check(delayedFallback.awaitTerminalCleanup(1000),"asynchronous fallback cleanup");
  MdMain.audioSource="off";MdMain.bootNative=false;android.os.SystemClock.fixedTime=-1;
  System.out.println("PASS "+checks+" native audio integration checks");
 }
}
