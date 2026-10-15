package org.carlife.hulab;

import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

/** Android player events, independent of PCM amplitude and CarLife video state. */
final class PlaybackActivity {
    interface Listener {void changed(long epoch,boolean newMediaStart);void degraded(long epoch,String detail);}
    interface Observer {void update(Snapshot value);void failed(String detail);}
    interface Source {void start(Observer observer)throws Exception;void close();}
    static final class Snapshot {
        final Set<Integer> output,media;
        Snapshot(Set<Integer> output,Set<Integer> media){this.output=new HashSet<Integer>(output);this.media=new HashSet<Integer>(media);}
    }
    private final Source source;private final Listener listener;
    private boolean started,known,disabled;private long epoch;
    private volatile boolean outputActive=true;
    private Set<Integer> output=Collections.emptySet(),media=Collections.emptySet();
    PlaybackActivity(Source source,Listener listener){this.source=source;this.listener=listener;}
    synchronized void start(){
        if(started||disabled)return;started=true;outputActive=false;final long token=++epoch;
        try{source.start(new Observer(){public void update(Snapshot value){accept(token,value);}public void failed(String detail){fail(token,detail);}});}
        catch(Exception e){fail(token,e.toString());}catch(LinkageError e){fail(token,e.toString());}
    }
    private void accept(long token,Snapshot value){
        boolean newMedia=false;
        synchronized(this){if(!started||token!=epoch)return;
            if(known)for(Integer id:value.media)if(!media.contains(id)){newMedia=true;break;}
            known=true;output=value.output;media=value.media;outputActive=!output.isEmpty();
        }
        listener.changed(token,newMedia);
    }
    private void fail(long token,String detail){
        final long failedEpoch;
        synchronized(this){if(!started||token!=epoch)return;disabled=true;known=false;started=false;failedEpoch=++epoch;output=Collections.emptySet();media=Collections.emptySet();outputActive=true;try{source.close();}catch(RuntimeException ignored){}}
        listener.degraded(failedEpoch,detail);
    }
    // When this optional platform API is unavailable, basic PCM remains usable.
    boolean outputActive(){return outputActive;}
    synchronized boolean known(){return known;}
    synchronized boolean current(long token){return started&&token==epoch;}
    synchronized boolean sameEpoch(long token){return token==epoch;}
    // Re-register before HU pause: callbacks already queued for the old player state
    // retain their old epoch, and the new initial snapshot is a baseline, not Play.
    synchronized void rebase(){if(!started)return;close();start();}
    synchronized void close(){started=false;known=false;disabled=false;epoch++;output=Collections.emptySet();media=Collections.emptySet();outputActive=true;try{source.close();}catch(RuntimeException ignored){}}

    /** The existing privileged app_process uses platform playback callbacks, with no APK or policy. */
    static final class AndroidSource implements Source {
        private AudioManager manager;private HandlerThread thread;private AudioManager.AudioPlaybackCallback callback;
        private Method active,playerId;
        public synchronized void start(final Observer observer)throws Exception{
            Class<?> config=Class.forName("android.media.AudioPlaybackConfiguration");
            active=config.getMethod("isActive");playerId=config.getMethod("getPlayerInterfaceId");
            // This no-Context platform constructor is sufficient for these AudioService methods.
            manager=(AudioManager)AudioManager.class.getConstructor().newInstance();
            thread=new HandlerThread("carlife-playback-events");thread.start();
            callback=new AudioManager.AudioPlaybackCallback(){public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs){
                // Preserve a rapid PAUSED -> STARTED pair queued for the same player.
                try{observer.update(snapshot(configs));}catch(Exception e){observer.failed(e.toString());}
            }};
            final Handler handler=new Handler(thread.getLooper());
            manager.registerAudioPlaybackCallback(callback,handler);
            handler.post(new Runnable(){public void run(){try{Snapshot value=read();if(value!=null)observer.update(value);}catch(Exception e){observer.failed(e.toString());}}});
        }
        private synchronized Snapshot read()throws Exception{
            if(manager==null)return null;
            return snapshot(manager.getActivePlaybackConfigurations());
        }
        private Snapshot snapshot(List<AudioPlaybackConfiguration> configs)throws Exception{
            Set<Integer> output=new HashSet<Integer>(),media=new HashSet<Integer>();
            for(AudioPlaybackConfiguration config:configs){
                if(!((Boolean)active.invoke(config)).booleanValue())continue;
                int usage=config.getAudioAttributes().getUsage();
                boolean music=usage==0||usage==1||usage==14;
                if(!music&&usage!=12&&usage!=16)continue;
                int id=((Integer)playerId.invoke(config)).intValue();
                if(id<=0)throw new IOException("playback player identity unavailable");
                output.add(id);if(music)media.add(id);
            }
            return new Snapshot(output,media);
        }
        public synchronized void close(){
            AudioManager old=manager;AudioManager.AudioPlaybackCallback cb=callback;HandlerThread worker=thread;
            manager=null;callback=null;thread=null;
            try{if(old!=null&&cb!=null)old.unregisterAudioPlaybackCallback(cb);}finally{if(worker!=null)worker.quitSafely();}
        }
    }
}
