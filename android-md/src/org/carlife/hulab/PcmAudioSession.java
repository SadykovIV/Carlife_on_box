package org.carlife.hulab;

import java.io.*;
import java.util.ArrayDeque;

/** Independent source worker; failures disable audio only, never the video source. */
final class PcmAudioSession {
    interface Source {void start()throws Exception;int read(byte[] p,int offset,int size)throws Exception;void close()throws Exception;}
    interface Factory {Source create();}
    interface Events {long now();void state(String state,String detail);void wakeWriter();}
    static final SourceLease LEASE=new SourceLease();
    final CarLifeAudio.Queue queue;final Factory factory;final Events events;final CarLifeAudio.Lane lane;final SourceLease leaseGuard;
    private volatile Thread worker;private volatile boolean stop;private volatile boolean sourceActive;
    private volatile boolean cleanupConfirmed=true;private String error;
    // Native MEDIA may keep its capture ready without claiming the HU audio source.
    // Only a real playback signal opens the wire; PCM amplitude is never a trigger.
    static final int WARM_PREROLL_CHUNKS=5;static final long WARM_PREROLL_MS=100;
    private final ArrayDeque<WarmChunk> preroll=new ArrayDeque<WarmChunk>();
    private boolean warm,playbackActive,wireOpen;private long gateGeneration,activeToken;
    private static final class WarmChunk {final byte[] pcm;final int timestamp;final long captured;WarmChunk(byte[] p,int t,long c){pcm=p.clone();timestamp=t;captured=c;}}
    PcmAudioSession(Factory f,Events e){this(f,e,CarLifeAudio.Lane.MEDIA,LEASE);}
    PcmAudioSession(Factory f,Events e,CarLifeAudio.Lane lane,SourceLease lease){if(f==null||e==null||lane==null||lease==null)throw new NullPointerException("audio source contract");factory=f;events=e;this.lane=lane;leaseGuard=lease;queue=new CarLifeAudio.Queue(lane);}
    private long now(){try{return events.now();}catch(RuntimeException e){return System.nanoTime()/1000000L;}}
    private void state(String state,String detail){try{events.state(state,detail);}catch(RuntimeException ignored){}}
    private void wakeWriter(){try{events.wakeWriter();}catch(RuntimeException ignored){}}
    synchronized boolean start(){return start(false);}
    synchronized boolean startWarm(){
        if(lane!=CarLifeAudio.Lane.MEDIA)throw new IllegalStateException("warm capture is MEDIA only");
        return start(true);
    }
    private boolean start(boolean requestedWarm){
        if(worker!=null&&worker.isAlive())return !stop;
        if(!cleanupConfirmed||leaseGuard.isHeld()){state("blocked","previous audio source has not retired");return false;}
        stop=false;error=null;warm=requestedWarm;playbackActive=false;wireOpen=false;preroll.clear();gateGeneration++;
        final long token=queue.epoch();activeToken=token;worker=new Thread(new Runnable(){public void run(){capture(token);}},"carlife-output-"+lane.name().toLowerCase(java.util.Locale.ROOT));worker.setDaemon(true);worker.start();return true;
    }
    synchronized void setPlaybackActive(boolean active){
        if(!warm||stop||playbackActive==active)return;
        playbackActive=active;
        if(!active){
            // A completed old chunk or an in-flight partial chunk cannot be replayed
            // after pause, even when another playback begins immediately.
            gateGeneration++;preroll.clear();wireOpen=false;queue.stop(activeToken,now());wakeWriter();
        }
    }
    private boolean openWire(long token,long time)throws IOException{
        if(wireOpen)return true;
        if(!queue.start(token,time))return false;
        wireOpen=true;
        int restored=0;
        while(!preroll.isEmpty()){
            WarmChunk chunk=preroll.remove();long age=time-chunk.captured;
            if(age>=0&&age<=WARM_PREROLL_MS){queue.data(token,chunk.pcm,chunk.timestamp,chunk.captured);restored++;}
        }
        wakeWriter();
        if(warm)state("wire_started","native MEDIA playback; preroll_chunks="+restored+"; capture already ready");
        return true;
    }
    private void capture(long token){
        Source source=null;boolean lease=false,started=false;
        try{
            if(stop)return;
            leaseGuard.acquire(this,"Android output audio "+lane);lease=true;cleanupConfirmed=false;
            if(stop)return;
            source=factory.create();if(source==null)throw new IOException("audio source factory returned null");
            if(stop)return;
            source.start();long origin=now();
            synchronized(this){if(stop||(!warm||playbackActive)&&!openWire(token,now()))return;sourceActive=true;started=true;}
            state(warm?"warm_ready":"capturing",lane.rate+"Hz "+lane.channels+"ch PCM16LE; Android output; not microphone; sample-count timestamps");
            byte[] chunk=new byte[lane.chunkBytes];int used=0;long frames=0,chunkGeneration=0;
            while(!stop){
                synchronized(this){
                    if(stop)break;
                    if(warm&&playbackActive&&!openWire(token,now()))break;
                    if(used==0)chunkGeneration=gateGeneration;
                }
                int n=source.read(chunk,used,chunk.length-used);
                if(n<0||n>chunk.length-used)throw new IOException("invalid PCM source read "+n);
                if(n==0){Thread.sleep(5);continue;}used+=n;
                if(used==chunk.length){
                    int timestamp=(int)(origin+frames*1000L/lane.rate);
                    boolean offered=false;
                    synchronized(this){
                        if(stop)break;
                        if(!warm){queue.data(token,chunk,timestamp,now());offered=true;}
                        else if(chunkGeneration==gateGeneration){
                            long time=now();
                            if(playbackActive){if(!openWire(token,time))break;queue.data(token,chunk,timestamp,time);offered=true;}
                            else {while(preroll.size()>=WARM_PREROLL_CHUNKS)preroll.remove();preroll.add(new WarmChunk(chunk,timestamp,time));}
                        }
                    }
                    frames+=used/lane.frameBytes;used=0;if(offered)wakeWriter();
                }
            }
        }catch(InterruptedException e){if(!stop)failed(e);Thread.currentThread().interrupt();}
        catch(Exception e){failed(e);}
        catch(LinkageError e){failed(new IOException("audio platform API unavailable",e));}
        finally{
            boolean released=source==null;
            if(source!=null)try{source.close();released=true;}catch(Exception e){failed(e);}catch(LinkageError e){failed(new IOException("audio source cleanup API unavailable",e));}
            synchronized(this){sourceActive=false;if(started)queue.stop(token,now());wireOpen=false;preroll.clear();gateGeneration++;cleanupConfirmed=released;}
            if(lease)leaseGuard.release(this,released);
            wakeWriter();state("stopped",cleanupConfirmed?"capture released; partial final chunk discarded":"capture release unconfirmed");
        }
    }
    private void failed(Exception e){synchronized(this){error=e.getClass().getSimpleName()+": "+e.getMessage();}state("failed",error);}
    synchronized void stop(){stop=true;playbackActive=false;wireOpen=false;preroll.clear();gateGeneration++;queue.stop(queue.epoch(),now());wakeWriter();Thread t=worker;if(t!=null)t.interrupt();}
    boolean active(){return sourceActive;}
    boolean retired(){Thread t=worker;return (t==null||!t.isAlive())&&cleanupConfirmed;}
    synchronized String error(){return error;}
}
