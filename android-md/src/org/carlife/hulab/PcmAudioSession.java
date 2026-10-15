package org.carlife.hulab;

import java.io.*;

/** Independent source worker; failures disable audio only, never the video source. */
final class PcmAudioSession {
    interface Source {void start()throws Exception;int read(byte[] p,int offset,int size)throws Exception;void close()throws Exception;}
    interface Factory {Source create();}
    interface Events {long now();void state(String state,String detail);void wakeWriter();}
    static final SourceLease LEASE=new SourceLease();
    final CarLifeAudio.Queue queue=new CarLifeAudio.Queue();final Factory factory;final Events events;
    private volatile Thread worker;private volatile boolean stop;private volatile boolean sourceActive;
    private volatile boolean cleanupConfirmed=true;private String error;
    PcmAudioSession(Factory f,Events e){factory=f;events=e;}
    private long now(){try{return events.now();}catch(RuntimeException e){return System.nanoTime()/1000000L;}}
    private void state(String state,String detail){try{events.state(state,detail);}catch(RuntimeException ignored){}}
    private void wakeWriter(){try{events.wakeWriter();}catch(RuntimeException ignored){}}
    synchronized boolean start(){
        if(worker!=null&&worker.isAlive())return !stop;
        if(!cleanupConfirmed||LEASE.isHeld()){state("blocked","previous audio source has not retired");return false;}
        stop=false;error=null;final long token=queue.epoch();worker=new Thread(new Runnable(){public void run(){capture(token);}},"carlife-output-audio");worker.setDaemon(true);worker.start();return true;
    }
    private void capture(long token){
        Source source=null;boolean lease=false,started=false;
        try{
            if(stop)return;
            LEASE.acquire(this,"Android output audio");lease=true;cleanupConfirmed=false;
            if(stop)return;
            source=factory.create();if(source==null)throw new IOException("audio source factory returned null");
            if(stop)return;
            source.start();long origin=now();
            synchronized(this){if(stop||!queue.start(token,now()))return;sourceActive=true;started=true;}
            wakeWriter();state("capturing","48000Hz stereo PCM16LE; Android output; not microphone; sample-count timestamps");
            byte[] chunk=new byte[CarLifeAudio.CHUNK_BYTES];int used=0;long frames=0;
            while(!stop){
                int n=source.read(chunk,used,chunk.length-used);
                if(n<0||n>chunk.length-used)throw new IOException("invalid PCM source read "+n);
                if(n==0){Thread.sleep(5);continue;}used+=n;
                if(used==chunk.length){
                    int timestamp=(int)(origin+frames*1000L/CarLifeAudio.RATE);
                    synchronized(this){if(stop)break;queue.data(token,chunk,timestamp,now());}
                    frames+=used/CarLifeAudio.FRAME_BYTES;used=0;wakeWriter();
                }
            }
        }catch(InterruptedException e){if(!stop)failed(e);Thread.currentThread().interrupt();}
        catch(Exception e){failed(e);}
        finally{
            boolean released=source==null;
            if(source!=null)try{source.close();released=true;}catch(Exception e){failed(e);}
            synchronized(this){sourceActive=false;if(started)queue.stop(token,now());cleanupConfirmed=released;}
            if(lease)LEASE.release(this,released);
            wakeWriter();state("stopped",cleanupConfirmed?"capture released; partial final chunk discarded":"capture release unconfirmed");
        }
    }
    private void failed(Exception e){synchronized(this){error=e.getClass().getSimpleName()+": "+e.getMessage();}state("failed",error);}
    synchronized void stop(){stop=true;queue.stop(queue.epoch(),now());wakeWriter();Thread t=worker;if(t!=null)t.interrupt();}
    boolean active(){return sourceActive;}
    boolean retired(){Thread t=worker;return (t==null||!t.isAlive())&&cleanupConfirmed;}
    synchronized String error(){return error;}
}
