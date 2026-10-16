package org.carlife.hulab;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** HU PCM -> one actual application recorder. Independent of playback capture policies. */
final class NativeMicrophone implements AutoCloseable {
    /** Invoked on our worker. The caller owns/coalesces MIC_RECORD_START/END with NativeVoice. */
    interface Demand { void changed(boolean wanted); }
    /** Verified application route only; no PCM, Binder calls or USB work here. */
    interface RouteListener { void changed(Recorder recorder);default void communicationChanged(boolean active){} }
    private static final int RATE=16000, REMOTE_SUBMIX=25;
    private static final long ROUTE_GRACE_MS=2500, RETRY_MS=5000;
    private static final SourceLease ROUTE_LEASE=new SourceLease();
    private final Demand demand;
    private final RouteListener routeListener;
    private volatile Recorder verifiedRecorder;
    private final PcmQueue queue=new PcmQueue();
    private HandlerThread thread;
    private Handler worker;
    private AudioManager manager;
    private AudioManager.AudioRecordingCallback callback;
    private AudioManager.OnModeChangedListener modeCallback;private boolean modeRegistered;private int audioMode;
    private Recorder suppressed;private boolean communicationActive;
    private volatile boolean closed,callHold;private volatile boolean callRetired=true;
    private volatile long ingestGeneration;
    private Route route;
    private Chunk writing;
    private int writingOffset;
    private long generation, retryAfter, writtenBytes, receivedPackets, ignoredPackets, writeZeroes;
    private long lastReport, firstPcm, lastPcm;
    private long pcmSamples;private double pcmEnergy;private int pcmPeak;
    private boolean wanted, callbackRegistered, poisoned;
    private int initializationFailures;
    private volatile List<Recorder> recorders=Collections.emptyList();
    private final Runnable initializeTask=new Runnable(){public void run(){initialize();}};
    private final Runnable refreshTask=new Runnable(){public void run(){refresh();}};
    private final Runnable pumpTask=new Runnable(){public void run(){pump();}};

    NativeMicrophone(Demand demand){this(demand,null);}
    NativeMicrophone(Demand demand,RouteListener listener){if(demand==null)throw new NullPointerException("microphone demand");this.demand=demand;this.routeListener=listener;}

    synchronized void start(){
        if(thread!=null||closed)return;
        thread=new HandlerThread("carlife-native-microphone");thread.start();
        worker=new Handler(thread.getLooper());worker.post(initializeTask);
    }

    /** USB ingress only copies bounded data; it never writes AudioTrack or makes Binder calls. */
    void onMic(byte[] pcm,int timestamp){
        long epoch=ingestGeneration;
        synchronized(this){receivedPackets++;if(closed||callHold||epoch==0){ignoredPackets++;return;}}
        queue.offer(epoch,pcm,SystemClock.uptimeMillis());
    }

    /** Fence ingress before posting cleanup; only the microphone worker can confirm retirement. */
    synchronized void callHold(final boolean value){
        if(closed||callHold==value)return;callHold=value;callRetired=false;
        Handler h=worker;if(h==null){callRetired=!poisoned&&!ROUTE_LEASE.isHeld();return;}
        h.post(new Runnable(){public void run(){if(closed)return;if(callHold){stopRoute();callRetired=!poisoned&&!ROUTE_LEASE.isHeld();}else{callRetired=true;refresh();}}});
    }
    boolean callRetired(){return callHold&&callRetired&&!poisoned;}
    boolean active(){return !closed&&!callHold&&ingestGeneration!=0;}
    Recorder verifiedRecorder(){return !closed&&ingestGeneration!=0?verifiedRecorder:null;}
    List<Recorder> observedRecorders(){return new ArrayList<Recorder>(recorders);}
    /** Cancel only the actual recorder opened by a HU-owned voice request. */
    void suppressOwnedRecorder(final Recorder target){
        Handler h=worker;if(closed||h==null||target==null)return;
        h.post(new Runnable(){public void run(){if(closed||find(recorders,target)==null)return;suppressed=target;if(route!=null&&route.target.same(target))stopRoute();reconcile();log("hu_owned_recorder_suppressed",target.description());}});
    }
    void resumeOwnedRecorder(){Handler h=worker;if(!closed&&h!=null)h.post(new Runnable(){public void run(){if(closed)return;suppressed=null;refresh();}});}
    private void routeChanged(Recorder recorder){verifiedRecorder=recorder;if(routeListener!=null)try{routeListener.changed(recorder);}catch(RuntimeException e){log("route_callback_error",cause(e));}}

    /** Useful after an explicit app/assistant launch; no microphone command is sent directly. */
    void refreshRecorders(){Handler h=worker;if(!closed&&h!=null)h.post(refreshTask);}

    private void initialize(){
        if(closed)return;
        try{
            android.os.IBinder binder=(android.os.IBinder)Class.forName("android.os.ServiceManager").getMethod("checkService",String.class).invoke(null,"audio");
            if(binder==null||!binder.isBinderAlive())throw new IOException("audio service not ready");
            manager=AudioManager.class.getConstructor().newInstance();
            callback=new AudioManager.AudioRecordingCallback(){
                public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs){
                    if(!closed&&NativeMicrophone.this.callback==this){recorders=convert(configs);reconcile();}
                }
            };
            manager.registerAudioRecordingCallback(callback,worker);callbackRegistered=true;
            modeCallback=new AudioManager.OnModeChangedListener(){public void onModeChanged(int mode){
                if(closed||NativeMicrophone.this.modeCallback!=this||manager==null)return;
                // The callback may wait behind a route cleanup: use the current mode,
                // not a queued NORMAL event from before a newer incoming call.
                try{audioMode=manager.getMode();}
                catch(RuntimeException e){if(shouldYieldToCall(mode,null))audioMode=mode;log("mode_snapshot_error",cause(e));}
                reconcile();
            }};
            manager.addOnModeChangedListener(new java.util.concurrent.Executor(){public void execute(Runnable task){Handler h=worker;if(!closed&&h!=null)h.post(task);}},modeCallback);modeRegistered=true;
            // Register before snapshot so a call starting during initialization is seen.
            audioMode=manager.getMode();
            log("observer_ready","recording callback and initial snapshot; exact UID/source/session; PCM16 mono16000");
            refresh();
        }catch(Exception|LinkageError e){
            log("observer_error",cause(e));
            // Framework registration can add its local callback before the Binder call fails.
            if(manager!=null&&callback!=null)try{manager.unregisterAudioRecordingCallback(callback);}catch(RuntimeException cleanup){log("observer_retry_cleanup_error",cause(cleanup));}
            if(manager!=null&&modeCallback!=null)try{manager.removeOnModeChangedListener(modeCallback);}catch(RuntimeException cleanup){log("mode_observer_retry_cleanup_error",cause(cleanup));}
            modeRegistered=false;modeCallback=null;callbackRegistered=false;callback=null;manager=null;
            if(!closed)worker.postDelayed(initializeTask,Math.min(30000,1000L<<Math.min(5,initializationFailures++)));
        }
    }

    private void refresh(){
        if(closed||manager==null)return;
        try{recorders=convert(manager.getActiveRecordingConfigurations());reconcile();}
        catch(RuntimeException e){log("snapshot_error",cause(e));if(!closed)worker.postDelayed(refreshTask,1000);}
    }

    private List<Recorder> convert(List<AudioRecordingConfiguration> configs){
        ArrayList<Recorder> result=new ArrayList<Recorder>();
        if(configs==null)return result;
        for(AudioRecordingConfiguration c:configs){
            try{
                int uid=((Integer)call(c,"getClientUid",new Class<?>[0])).intValue();
                AudioDeviceInfo d=c.getAudioDevice();
                String pkg=String.valueOf(call(c,"getClientPackageName",new Class<?>[0]));
                result.add(new Recorder(uid,c.getClientAudioSource(),c.getClientAudioSessionId(),c.isClientSilenced(),d==null?-1:d.getType(),d==null?"":d.getAddress(),pkg));
            }catch(Exception e){log("recorder_inventory_error",cause(e));}
        }
        return result;
    }

    private void reconcile(){
        if(closed||poisoned)return;
        long now=SystemClock.uptimeMillis();
        if(suppressed!=null&&find(recorders,suppressed)==null)suppressed=null;
        boolean communication=shouldYieldToCall(audioMode,null)||hasScoRecorder(recorders);
        if(communication!=communicationActive){communicationActive=communication;if(routeListener!=null)try{routeListener.communicationChanged(communication);}catch(RuntimeException e){log("call_mode_callback_error",cause(e));}log("call_audio_priority",Boolean.toString(communication));}
        if(communication||callHold){if(route!=null)stopRoute();if(callHold)callRetired=!poisoned&&!ROUTE_LEASE.isHeld();return;}
        Recorder selected=select(recorders,suppressed,audioMode);
        if(route!=null&&selected!=null&&!selected.same(route.target)){
            log("recorder_preempted",route.target.description()+" -> "+selected.description());
            stopRoute();if(poisoned||closed)return;
        }
        if(route!=null){
            Recorder same=find(recorders,route.target);
            if(same!=null&&same.eligible()){
                // A routing callback can change only the device; preserve the route and queue.
                if(same.onRoute(route.address)){
                    if(!route.verified){synchronized(this){if(closed)return;route.verified=true;queue.reset(route.epoch);ingestGeneration=route.epoch;}routeChanged(same);setDemand(true);log("route_verified",same.description()+" address="+route.address);worker.post(pumpTask);}
                    return;
                }
                if(now-route.created<ROUTE_GRACE_MS){scheduleRefresh(100);return;}
                log("route_not_applied",same.description()+" expected_address="+route.address);
                stopRoute();retryAfter=now+RETRY_MS;scheduleRefresh(RETRY_MS);return;
            }
            // During initial rerouting, AudioRecord can disappear while restoring its input.
            if(same==null&&now-route.created<ROUTE_GRACE_MS){scheduleRefresh(100);return;}
            stopRoute();
            if(poisoned||closed)return;
        }
        if(selected==null)return;
        if(now<retryAfter){scheduleRefresh(retryAfter-now);return;}
        try{
            Route next=new Route(selected,++generation,now);route=next;next.open();
            log("route_created",selected.description()+" address="+next.address+" track_buffer_frames="+next.bufferFrames+" track_capacity_frames="+next.bufferCapacityFrames+"; awaiting actual recorder route");
            // Starting the injector makes its input device available. Android may restore an
            // already-open AudioRecord; only its resulting device/address confirms rerouting.
            scheduleRefresh(50);
        }catch(Exception|LinkageError e){log("route_open_error",cause(e));stopRoute();retryAfter=now+RETRY_MS;scheduleRefresh(RETRY_MS);}
    }

    private void scheduleRefresh(long delay){if(!closed){worker.removeCallbacks(refreshTask);worker.postDelayed(refreshTask,Math.max(1,delay));}}

    private synchronized void setDemand(boolean value){
        if(value&&closed)return;
        if(wanted==value)return;wanted=value;
        try{demand.changed(value);}catch(RuntimeException e){log("demand_callback_error",cause(e));}
        log("mic_demand",Boolean.toString(value));
    }

    private void pump(){
        if(closed||route==null||!route.verified)return;
        final Route current=route;long now=SystemClock.uptimeMillis();
        try{
            for(int attempts=0;attempts<4;attempts++){
                if(writing!=null&&(writing.epoch!=current.epoch||now-writing.created>PcmQueue.MAX_AGE_MS)){queue.discardPending(writing.data.length-writingOffset);writing=null;writingOffset=0;}
                if(writing==null){writing=queue.poll(current.epoch,now);writingOffset=0;if(writing==null)break;}
                int count=Math.min(640,writing.data.length-writingOffset);
                int n=current.track.write(writing.data,writingOffset,count,AudioTrack.WRITE_NON_BLOCKING);
                if(n<0||(n&1)!=0)throw new IOException("AudioTrack.write="+n);
                if(n==0){writeZeroes++;break;}
                for(int i=writingOffset;i<writingOffset+n;i+=2){int sample=(short)((writing.data[i]&255)|((writing.data[i+1]&255)<<8));pcmPeak=Math.max(pcmPeak,Math.abs(sample));pcmEnergy+=(double)sample*sample;pcmSamples++;}
                writingOffset+=n;writtenBytes+=n;lastPcm=now;if(firstPcm==0)firstPcm=now;
                if(writingOffset==writing.data.length){writing=null;writingOffset=0;}
            }
            if(now-lastReport>=1000){lastReport=now;report("active");}
            if(!closed&&route==current)worker.postDelayed(pumpTask,5);
        }catch(Exception e){log("pcm_write_error",cause(e));stopRoute();retryAfter=now+RETRY_MS;scheduleRefresh(RETRY_MS);}
    }

    private void stopRoute(){
        ingestGeneration=0;routeChanged(null);queue.reset(0);writing=null;writingOffset=0;
        if(worker!=null)worker.removeCallbacks(pumpTask);setDemand(false);
        Route old=route;route=null;
        if(old!=null){boolean clean=old.close();if(!clean)poisoned=true;log("route_closed",old.target.description()+" cleanup="+clean);report("route_closed");}
    }

    private void report(String state){
        long received,ignored;synchronized(this){received=receivedPackets;ignored=ignoredPackets;}
        MdMain.logFields("native_microphone","state",state,"generation",ingestGeneration,"received_packets",received,"ignored_packets",ignored,"written_pcm_bytes",writtenBytes,"write_zeroes",writeZeroes,"queued_pcm_bytes",queue.bytes(),"dropped_pcm_bytes",queue.droppedBytes(),"invalid_packets",queue.invalidPackets(),"pcm_peak",pcmPeak,"pcm_rms",pcmSamples==0?0:Math.sqrt(pcmEnergy/pcmSamples),"first_pcm_uptime_ms",firstPcm,"last_pcm_uptime_ms",lastPcm,"raw_audio_recorded",false);
    }

    public void close(){
        final Handler h;final HandlerThread t;
        synchronized(this){if(closed)return;closed=true;ingestGeneration=0;h=worker;t=thread;}
        queue.reset(0);if(h==null)return;
        final CountDownLatch retired=new CountDownLatch(1);
        h.removeCallbacksAndMessages(null);h.post(new Runnable(){public void run(){try{
            stopRoute();
            if(manager!=null&&callbackRegistered)try{manager.unregisterAudioRecordingCallback(callback);}catch(RuntimeException e){log("observer_close_error",cause(e));}
            if(manager!=null&&modeRegistered&&modeCallback!=null)try{manager.removeOnModeChangedListener(modeCallback);}catch(RuntimeException e){log("mode_observer_close_error",cause(e));}
            modeRegistered=false;modeCallback=null;callbackRegistered=false;manager=null;recorders=Collections.emptyList();suppressed=null;
        }finally{retired.countDown();if(t!=null)t.quitSafely();}}});
        if(Looper.myLooper()!=h.getLooper())try{if(!retired.await(3,TimeUnit.SECONDS))log("cleanup_timeout","microphone worker did not finish cleanup within3s");}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }

    static final class Recorder {
        final int uid,source,session,deviceType;final boolean silenced;final String address,pkg;
        Recorder(int uid,int source,int session,boolean silenced,int deviceType,String address,String pkg){this.uid=uid;this.source=source;this.session=session;this.silenced=silenced;this.deviceType=deviceType;this.address=address==null?"":address;this.pkg=pkg==null?"":pkg;}
        boolean eligible(){return uid>=1000&&session>0&&!silenced&&(source==1||source==6||source==7||source==9);}
        boolean same(Recorder b){return b!=null&&uid==b.uid&&source==b.source&&session==b.session;}
        boolean onRoute(String wanted){return deviceType==REMOTE_SUBMIX&&wanted!=null&&!wanted.isEmpty()&&wanted.equals(address);}
        String description(){return "uid="+uid+" source="+source+" session="+session+" package="+pkg+" device_type="+deviceType+" silenced="+silenced;}
    }
    static Recorder find(List<Recorder> list,Recorder target){for(Recorder r:list)if(r.same(target))return r;return null;}
    static boolean shouldYieldToCall(int mode,Recorder recorder){return mode>=2&&mode<=6||recorder!=null&&recorder.deviceType==7;}
    private static boolean hasScoRecorder(List<Recorder> list){for(Recorder r:list)if(!r.silenced&&shouldYieldToCall(0,r))return true;return false;}
    static Recorder select(List<Recorder> list){return select(list,null,0);}
    static Recorder select(List<Recorder> list,Recorder suppressed,int mode){
        if(shouldYieldToCall(mode,null)||hasScoRecorder(list))return null;
        Recorder newest=null;for(Recorder r:list)if(r.eligible()&&!r.same(suppressed)&&(newest==null||r.session>newest.session))newest=r;return newest;
    }
    static final class Chunk {final long epoch,created;final byte[] data;Chunk(long epoch,long created,byte[] data){this.epoch=epoch;this.created=created;this.data=data;}}
    static final class PcmQueue {
        static final int MAX_BYTES=6400;static final long MAX_AGE_MS=150;
        private final ArrayDeque<Chunk> chunks=new ArrayDeque<Chunk>();private int bytes;private long epoch,dropped,invalid;
        synchronized void reset(long next){dropped+=bytes;chunks.clear();bytes=0;epoch=next;}
        synchronized void offer(long expected,byte[] pcm,long now){
            if(expected==0||expected!=epoch)return;
            if(pcm==null||pcm.length==0)return;
            if((pcm.length&1)!=0||pcm.length>32000){invalid++;return;}
            expire(now);
            int offset=Math.max(0,pcm.length-MAX_BYTES),length=pcm.length-offset;
            dropped+=offset;
            while(bytes+length>MAX_BYTES&&!chunks.isEmpty())dropFirst();
            byte[] copy=new byte[length];System.arraycopy(pcm,offset,copy,0,length);chunks.addLast(new Chunk(expected,now,copy));bytes+=length;
        }
        synchronized Chunk poll(long expected,long now){expire(now);while(!chunks.isEmpty()){Chunk c=chunks.removeFirst();bytes-=c.data.length;if(c.epoch==expected)return c;dropped+=c.data.length;}return null;}
        private void expire(long now){while(!chunks.isEmpty()&&now-chunks.peekFirst().created>MAX_AGE_MS)dropFirst();}
        private void dropFirst(){Chunk c=chunks.removeFirst();bytes-=c.data.length;dropped+=c.data.length;}
        synchronized void discardPending(int count){dropped+=Math.max(0,count);}
        synchronized int bytes(){return bytes;}synchronized long droppedBytes(){return dropped;}synchronized long invalidPackets(){return invalid;}
    }

    private final class Route {
        final Recorder target;final long epoch,created;Object policy,mix;Class<?> policyType;AudioTrack track;String address="";int bufferFrames,bufferCapacityFrames;boolean attempted,verified,leased;
        Route(Recorder target,long epoch,long created){this.target=target;this.epoch=epoch;this.created=created;}
        void open()throws Exception{
            ROUTE_LEASE.acquire(this,"native microphone recorder injection");leased=true;
            policyType=Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> pb=Class.forName("android.media.audiopolicy.AudioPolicy$Builder"),mixType=Class.forName("android.media.audiopolicy.AudioMix"),mb=Class.forName("android.media.audiopolicy.AudioMix$Builder"),ruleType=Class.forName("android.media.audiopolicy.AudioMixingRule"),rb=Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Object rules=rb.getConstructor().newInstance();call(rules,"setTargetMixRole",new Class<?>[]{int.class},ruleType.getField("MIX_ROLE_INJECTOR").getInt(null));
            AudioAttributes.Builder attributes=new AudioAttributes.Builder();call(attributes,"setInternalCapturePreset",new Class<?>[]{int.class},target.source);
            call(rules,"addRule",new Class<?>[]{AudioAttributes.class,int.class},attributes.build(),ruleType.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null));
            call(rules,"addMixRule",new Class<?>[]{int.class,Object.class},ruleType.getField("RULE_MATCH_UID").getInt(null),Integer.valueOf(target.uid));
            call(rules,"addMixRule",new Class<?>[]{int.class,Object.class},ruleType.getField("RULE_MATCH_AUDIO_SESSION_ID").getInt(null),Integer.valueOf(target.session));
            Object rule=call(rules,"build",new Class<?>[0]),builder=mb.getConstructor(ruleType).newInstance(rule);
            AudioFormat format=new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            call(builder,"setFormat",new Class<?>[]{AudioFormat.class},format);call(builder,"setRouteFlags",new Class<?>[]{int.class},mixType.getField("ROUTE_FLAG_LOOP_BACK").getInt(null));mix=call(builder,"build",new Class<?>[0]);
            Object policyBuilder=pb.getConstructor(Context.class).newInstance(new Object[]{null});call(policyBuilder,"setLooper",new Class<?>[]{Looper.class},worker.getLooper());call(policyBuilder,"addMix",new Class<?>[]{mixType},mix);policy=call(policyBuilder,"build",new Class<?>[0]);
            attempted=true;int status=((Integer)call(manager,"registerAudioPolicy",new Class<?>[]{policyType},policy)).intValue();if(status!=0)throw new IOException("registerAudioPolicy="+status);
            address=String.valueOf(call(mix,"getRegistration",new Class<?>[0]));
            track=(AudioTrack)call(policy,"createAudioTrackSource",new Class<?>[]{mixType},mix);if(track==null||track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("microphone AudioTrack not initialized");
            if(track.getSampleRate()!=RATE||track.getChannelCount()!=1||track.getAudioFormat()!=AudioFormat.ENCODING_PCM_16BIT)throw new IOException("microphone AudioTrack format mismatch");
            // Keep the framework buffer: the OEM submix mixes 1024 frames per cycle.
            // A 320-frame buffer only supplies 20 ms each 64 ms cycle and loses speech.
            bufferFrames=track.getBufferSizeInFrames();bufferCapacityFrames=track.getBufferCapacityInFrames();
            track.play();
        }
        boolean close(){
            boolean clean=true;
            if(track!=null){try{track.stop();track.flush();}catch(RuntimeException e){log("track_stop_error",cause(e));}try{track.release();}catch(RuntimeException e){clean=false;log("track_release_error",cause(e));}track=null;}
            if(attempted&&policy!=null)try{call(manager,"unregisterAudioPolicy",new Class<?>[]{policyType},policy);}catch(Exception e){clean=false;log("policy_close_error",cause(e));}
            if(leased){ROUTE_LEASE.release(this,clean);if(clean)leased=false;}
            attempted=false;policy=null;return clean;
        }
    }
    private static Object call(Object target,String name,Class<?>[] signature,Object...args)throws Exception{
        try{return target.getClass().getMethod(name,signature).invoke(target,args);}catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IOException("microphone API "+name,cause);}
    }
    private static String cause(Throwable e){return NativeServices.causeChain(e);}
    private static void log(String state,String detail){MdMain.logFields("native_microphone","state",state,"detail",detail);}
}
