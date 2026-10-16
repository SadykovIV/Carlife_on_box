package org.carlife.hulab;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

/**
 * Android call endpoint pair, owned by the USB call session worker.
 * A session worker owns this object; USB callbacks must enqueue rather than call it.
 * It observes an existing call mode and never creates a call or changes that mode.
 */
final class NativeCallAudio implements AutoCloseable {
    static final int DOWNLINK_RATE=48000, DOWNLINK_CHANNELS=2, DOWNLINK_CHUNK_BYTES=3840;
    static final int UPLINK_RATE=16000, UPLINK_CHANNELS=1, UPLINK_CHUNK_BYTES=640;
    private static final SourceLease PROCESS_LEASE=new SourceLease();
    interface Downlink extends AutoCloseable {
        void start()throws Exception;
        int read(byte[] pcm,int offset,int count)throws Exception;
        void close()throws Exception;
    }
    interface Uplink extends AutoCloseable {
        void start()throws Exception;
        int write(byte[] pcm,int offset,int count)throws Exception;
        /** Drop audio queued before a mute transition. */
        void flush()throws Exception;
        void close()throws Exception;
    }
    interface Platform {
        int mode()throws Exception;
        boolean microphoneMuted()throws Exception;
        Downlink openDownlink()throws Exception;
        Uplink openUplink()throws Exception;
    }
    private final Platform platform;
    private final SourceLease lease;
    private final byte[] silence=new byte[UPLINK_CHUNK_BYTES];
    private Downlink downlink;
    private Uplink uplink;
    private long epoch;
    private int callFamily;
    private boolean leased,active,closed,poisoned,muted,requestedMute;
    private String failure;

    NativeCallAudio(){this(new AndroidPlatform(),PROCESS_LEASE);}
    NativeCallAudio(Platform platform,SourceLease lease){
        if(platform==null||lease==null)throw new NullPointerException("call platform/lease");
        this.platform=platform;this.lease=lease;
    }
    /** 0 means no active audio call; mode RINGTONE does not invent a conversation. */
    static int family(int mode){return mode==2||mode==4||mode==5?1:mode==3||mode==6?2:0;}
    synchronized int currentFamily()throws IOException {try{return family(platform.mode());}catch(Exception|LinkageError e){throw io(e);}}
    synchronized long epoch(){return active&&!closed?epoch:0;}
    synchronized boolean active(){return active&&!closed;}
    synchronized boolean poisoned(){return poisoned;}
    synchronized String failure(){return failure;}

    /** Open both directions transactionally; caller must already own the chosen HU lane. */
    synchronized long openForCurrentMode()throws IOException {
        if(closed)throw new IOException("call endpoint closed");
        if(poisoned)throw new IOException("call endpoint cleanup unconfirmed");
        try{
            int next=family(platform.mode());
            if(active&&next==callFamily){syncMute();return epoch;}
            if(leased)retire();
            if(poisoned)throw new IOException("call endpoint cleanup unconfirmed");
            if(next==0)return 0;
            lease.acquire(this,"native USB call endpoint pair");leased=true;
            ++epoch;callFamily=next;
            downlink=platform.openDownlink();
            if(downlink==null)throw new IOException("null call downlink");
            requireCurrentMode(next);
            uplink=platform.openUplink();
            if(uplink==null)throw new IOException("null call uplink");
            requireCurrentMode(next);
            downlink.start();uplink.start();
            requireCurrentMode(next);
            muted=platform.microphoneMuted()||requestedMute;
            active=true;failure=null;return epoch;
        }catch(Exception|LinkageError e){failure=describe(e);if(e instanceof CleanupUnconfirmedException)poisoned=true;retire();throw io(e);}
    }
    private void requireCurrentMode(int expected)throws Exception {
        if(expected==0||family(platform.mode())!=expected)throw new IOException("call mode changed while opening");
    }
    /** Reconcile a mode/mute callback using current platform state, never its queued value. */
    synchronized boolean refresh()throws IOException {
        if(!active||closed)return false;
        try{
            if(family(platform.mode())!=callFamily){retire();return false;}
            syncMute();return true;
        }catch(Exception|LinkageError e){failure=describe(e);retire();throw io(e);}
    }
    synchronized void setMuted(boolean value)throws IOException {
        requestedMute=value;if(active)refresh();
    }
    private void syncMute()throws Exception {
        boolean next=platform.microphoneMuted()||requestedMute;
        if(next!=muted){uplink.flush();muted=next;}
    }
    /** Nonblocking PCM reads; stale generations have no access to the next call. */
    synchronized int readDownlink(long token,byte[] pcm,int offset,int count)throws IOException {
        checkBuffer(pcm,offset,count,4,DOWNLINK_CHUNK_BYTES);
        if(token==0||token!=epoch||!active||closed)return 0;
        if(!refresh())return 0;
        try{
            int n=downlink.read(pcm,offset,count);
            if(n<0||n>count||n%4!=0)throw new IOException("invalid call downlink read result: "+n);
            return n;
        }catch(Exception|LinkageError e){failure=describe(e);retire();throw io(e);}
    }
    /** HU PCM -> active call uplink. Mute sends silence without modifying caller buffers. */
    synchronized int writeUplink(long token,byte[] pcm,int offset,int count)throws IOException {
        checkBuffer(pcm,offset,count,2,UPLINK_CHUNK_BYTES);
        if(token==0||token!=epoch||!active||closed)return 0;
        if(!refresh())return 0;
        try{
            int n=uplink.write(muted?silence:pcm,muted?0:offset,count);
            if(n<0||n>count||n%2!=0)throw new IOException("invalid call uplink write result: "+n);
            return n;
        }catch(Exception|LinkageError e){failure=describe(e);retire();throw io(e);}
    }
    private static void checkBuffer(byte[] pcm,int offset,int count,int frame,int max){
        if(pcm==null||offset<0||count<0||offset>pcm.length-count||count>max||count%frame!=0)
            throw new IllegalArgumentException("call PCM bounds/alignment");
    }
    /** Reusable teardown for USB loss, call exit, or a session owner change. */
    synchronized void stop(){retire();}
    private void retire(){
        active=false;++epoch;callFamily=0;
        Uplink oldUp=uplink;uplink=null;Downlink oldDown=downlink;downlink=null;
        boolean clean=!poisoned;
        if(oldUp!=null)try{oldUp.close();}catch(Exception|LinkageError e){clean=false;failure=describe(e);}
        if(oldDown!=null)try{oldDown.close();}catch(Exception|LinkageError e){clean=false;failure=describe(e);}
        if(leased){if(!lease.release(this,clean))poisoned=true;else leased=false;}
        if(!clean)poisoned=true;
    }
    public synchronized void close(){if(closed)return;closed=true;retire();}
    private static String describe(Throwable e){
        Throwable cause=e;while(cause instanceof InvocationTargetException&&cause.getCause()!=null)cause=cause.getCause();
        return cause.getClass().getSimpleName()+": "+String.valueOf(cause.getMessage());
    }
    private static IOException io(Throwable e){return e instanceof IOException?(IOException)e:new IOException(describe(e),e);}
    static final class CleanupUnconfirmedException extends IOException {
        CleanupUnconfirmedException(Throwable cause){super("platform endpoint release failed",cause);}
    }

    /** Reflection keeps hidden SystemApi calls out of the public Android SDK dependency. */
    static final class AndroidPlatform implements Platform {
        private AudioManager manager;
        private AudioManager manager()throws Exception {
            if(manager==null)manager=AudioManager.class.getConstructor().newInstance();return manager;
        }
        public int mode()throws Exception{return manager().getMode();}
        public boolean microphoneMuted()throws Exception{return manager().isMicrophoneMute();}
        public Downlink openDownlink()throws Exception {
            AudioFormat format=new AudioFormat.Builder().setSampleRate(DOWNLINK_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
            final AudioRecord record=(AudioRecord)AudioManager.class
                .getMethod("getCallDownlinkExtractionAudioRecord",AudioFormat.class).invoke(manager(),format);
            if(record==null)throw new IOException("platform returned null call downlink");
            try{
                if(record.getState()!=AudioRecord.STATE_INITIALIZED||record.getSampleRate()!=DOWNLINK_RATE
                    ||record.getChannelCount()!=DOWNLINK_CHANNELS||record.getAudioFormat()!=AudioFormat.ENCODING_PCM_16BIT)
                    throw new IOException("platform call downlink format/state mismatch");
            }catch(Exception|LinkageError e){try{record.release();}catch(RuntimeException|LinkageError release){throw new CleanupUnconfirmedException(release);}throw e;}
            return new Downlink(){
                public void start(){record.startRecording();if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("call downlink did not start");}
                public int read(byte[] p,int o,int n){return record.read(p,o,n,AudioRecord.READ_NON_BLOCKING);}
                public void close(){record.release();}
            };
        }
        public Uplink openUplink()throws Exception {
            AudioFormat format=new AudioFormat.Builder().setSampleRate(UPLINK_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
            final AudioTrack track=(AudioTrack)AudioManager.class
                .getMethod("getCallUplinkInjectionAudioTrack",AudioFormat.class).invoke(manager(),format);
            if(track==null)throw new IOException("platform returned null call uplink");
            try{
                if(track.getState()!=AudioTrack.STATE_INITIALIZED||track.getSampleRate()!=UPLINK_RATE
                    ||track.getChannelCount()!=UPLINK_CHANNELS||track.getAudioFormat()!=AudioFormat.ENCODING_PCM_16BIT)
                    throw new IOException("platform call uplink format/state mismatch");
            }catch(Exception|LinkageError e){try{track.release();}catch(RuntimeException|LinkageError release){throw new CleanupUnconfirmedException(release);}throw e;}
            return new Uplink(){
                public void start(){track.play();if(track.getPlayState()!=AudioTrack.PLAYSTATE_PLAYING)throw new IllegalStateException("call uplink did not start");}
                public int write(byte[] p,int o,int n){return track.write(p,o,n,AudioTrack.WRITE_NON_BLOCKING);}
                public void flush(){track.pause();track.flush();track.play();}
                public void close(){track.release();}
            };
        }
    }
}
