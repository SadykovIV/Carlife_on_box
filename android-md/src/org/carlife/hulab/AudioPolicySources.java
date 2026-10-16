package org.carlife.hulab;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.HandlerThread;
import android.os.Looper;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.EnumMap;

/** One privileged Android policy, disjoint media/navigation/assistant sinks. No permission changes. */
final class AudioPolicySources {
    interface Platform {
        void register() throws Exception;
        PcmAudioSession.Source open(CarLifeAudio.Lane lane) throws Exception;
        void unregister() throws Exception;
    }
    interface PlatformFactory {Platform create() throws Exception;}
    private final PlatformFactory factory;
    private final SourceLease global;
    private final EnumMap<CarLifeAudio.Lane,OwnedSource> active=new EnumMap<CarLifeAudio.Lane,OwnedSource>(CarLifeAudio.Lane.class);
    private final EnumMap<CarLifeAudio.Lane,SourceLease> laneLeases=new EnumMap<CarLifeAudio.Lane,SourceLease>(CarLifeAudio.Lane.class);
    private Platform platform;
    private boolean leased,poisoned;
    private long registrations,releases,failures;private String lastFailure;
    AudioPolicySources(){this(new PlatformFactory(){public Platform create(){return new AndroidPlatform();}},PcmAudioSession.LEASE);}
    AudioPolicySources(PlatformFactory factory,SourceLease global){
        if(factory==null||global==null)throw new NullPointerException("audio policy owner");
        this.factory=factory;this.global=global;
        for(CarLifeAudio.Lane lane:CarLifeAudio.Lane.values())laneLeases.put(lane,new SourceLease());
    }
    SourceLease lease(CarLifeAudio.Lane lane){return laneLeases.get(lane);}
    PcmAudioSession.Factory factory(final CarLifeAudio.Lane lane){
        if(lane==null)throw new NullPointerException("audio lane");
        return new PcmAudioSession.Factory(){public PcmAudioSession.Source create(){return new OwnedSource(lane);}};
    }
    synchronized boolean retired(){return active.isEmpty()&&platform==null&&!leased;}
    synchronized String diagnostics(){return "routes="+active.keySet()+" registrations="+registrations+" releases="+releases+" cleanup_unconfirmed="+poisoned+" failures="+failures+" last_failure="+lastFailure;}
    private synchronized PcmAudioSession.Source attach(OwnedSource source)throws Exception{
        if(poisoned)throw new IOException("previous audio policy cleanup unconfirmed");
        if(active.containsKey(source.lane))throw new IOException("duplicate audio sink "+source.lane);
        if(platform==null){
            global.acquire(this,"split Android audio policy");leased=true;
            try{
                platform=factory.create();if(platform==null)throw new IOException("null audio policy platform");
                platform.register();registrations++;
            }catch(Exception|LinkageError error){
                Exception failure=exception(error);
                failures++;lastFailure=failure.toString();
                try{closePolicy();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
        try{
            PcmAudioSession.Source sink=platform.open(source.lane);
            if(sink==null)throw new IOException("audio policy returned null "+source.lane+" sink");
            active.put(source.lane,source);source.attached=true;return sink;
        }catch(Exception|LinkageError error){
                Exception failure=exception(error);
            failures++;lastFailure=failure.toString();
            // Failure opening a lane leaves an already-running other lane intact.
            if(active.isEmpty())try{closePolicy();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    private synchronized void detach(OwnedSource source,Exception captureFailure)throws Exception{
        if(!source.attached)return;
        source.attached=false;
        if(active.get(source.lane)!=source)throw new IOException("audio policy ownership mismatch");
        active.remove(source.lane);
        if(captureFailure!=null){poisoned=true;failures++;lastFailure=captureFailure.toString();}
        if(active.isEmpty()){
            try{closePolicy();}catch(Exception cleanup){if(captureFailure!=null)cleanup.addSuppressed(captureFailure);throw cleanup;}
            // Synchronous policy removal invalidates all its captors, including
            // a record whose individual release failed.
            return;
        }
        if(captureFailure!=null)throw captureFailure;
    }
    private void closePolicy()throws Exception{
        if(!leased)return;
        try{
            if(platform!=null)platform.unregister();
            platform=null;poisoned=false;leased=false;releases++;global.release(this,true);
        }catch(Exception|LinkageError error){
                Exception failure=exception(error);poisoned=true;failures++;lastFailure=failure.toString();global.release(this,false);throw failure;}
    }
    private static Exception exception(Throwable error){return error instanceof Exception?(Exception)error:new IOException("audio policy linkage failure",error);}
    private final class OwnedSource implements PcmAudioSession.Source {
        final CarLifeAudio.Lane lane;PcmAudioSession.Source sink;boolean attached;
        OwnedSource(CarLifeAudio.Lane lane){this.lane=lane;}
        public void start()throws Exception{sink=attach(this);sink.start();}
        public int read(byte[] data,int offset,int size)throws Exception{return sink.read(data,offset,size);}
        public void close()throws Exception{
            Exception failure=null;
            if(sink!=null)try{sink.close();}catch(Exception|LinkageError e){failure=exception(e);}finally{sink=null;}
            detach(this,failure);
        }
    }

    /** Hidden platform APIs are reflected; their normal registration permission checks remain. */
    static final class AndroidPlatform implements Platform {
        private Object policy,manager;private Class<?> policyType,mixType;
        private final EnumMap<CarLifeAudio.Lane,Object> mixes=new EnumMap<CarLifeAudio.Lane,Object>(CarLifeAudio.Lane.class);
        private HandlerThread thread;
        private boolean registrationAttempted;
        public void register()throws Exception{
            policyType=Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> policyBuilderType=Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            mixType=Class.forName("android.media.audiopolicy.AudioMix");
            Object builder=policyBuilderType.getConstructor(Context.class).newInstance(new Object[]{null});
            // Null context is explicitly supported by AOSP: caller attribution
            // is generated by the platform; permission checks use the caller PID/UID.
            thread=new HandlerThread("carlife-audio-policy");thread.start();
            invoke(builder,"setLooper",new Class<?>[]{Looper.class},thread.getLooper());
            for(CarLifeAudio.Lane lane:CarLifeAudio.Lane.values()){
                Object mix=buildMix(lane);mixes.put(lane,mix);
                invoke(builder,"addMix",new Class<?>[]{mixType},mix);
            }
            policy=invoke(builder,"build",new Class<?>[0]);
            manager=AudioManager.class.getConstructor().newInstance();
            registrationAttempted=true;
            int result=((Integer)invoke(manager,"registerAudioPolicy",new Class<?>[]{policyType},policy)).intValue();
            if(result!=0)throw new IOException("registerAudioPolicy failed: "+result+"; existing MODIFY_AUDIO_ROUTING required");
        }
        private Object buildMix(CarLifeAudio.Lane lane)throws Exception{
            Class<?> ruleType=Class.forName("android.media.audiopolicy.AudioMixingRule");
            Class<?> rbType=Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Class<?> mbType=Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Object rb=rbType.getConstructor().newInstance();
            int role=ruleType.getField("MIX_ROLE_PLAYERS").getInt(null);
            int match=ruleType.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null);
            invoke(rb,"setTargetMixRole",new Class<?>[]{int.class},role);
            for(int usage:AudioUsage.forLane(lane)){
                AudioAttributes attr=new AudioAttributes.Builder().setUsage(usage).build();
                invoke(rb,"addRule",new Class<?>[]{AudioAttributes.class,int.class},attr,match);
            }
            Object rule=invoke(rb,"build",new Class<?>[0]);
            Object mb=mbType.getConstructor(ruleType).newInstance(rule);
            // Output channel masks are required here; createAudioRecordSink converts them to input masks.
            AudioFormat format=new AudioFormat.Builder().setSampleRate(lane.rate)
                .setChannelMask(lane.channels==2?AudioFormat.CHANNEL_OUT_STEREO:AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            invoke(mb,"setFormat",new Class<?>[]{AudioFormat.class},format);
            invoke(mb,"setRouteFlags",new Class<?>[]{int.class},mixType.getField("ROUTE_FLAG_LOOP_BACK").getInt(null));
            return invoke(mb,"build",new Class<?>[0]);
        }
        public PcmAudioSession.Source open(CarLifeAudio.Lane lane)throws Exception{
            if(policy==null)throw new IOException("audio policy not registered");
            AudioRecord record=(AudioRecord)invoke(policy,"createAudioRecordSink",new Class<?>[]{mixType},mixes.get(lane));
            if(record==null)throw new IOException("createAudioRecordSink returned null for "+lane);
            return new PolicyRecord(record,lane);
        }
        public void unregister()throws Exception{
            // Synchronous API: do not release the ownership fence on an async request alone.
            if(registrationAttempted&&policy!=null&&manager!=null){
                invoke(manager,"unregisterAudioPolicy",new Class<?>[]{policyType},policy);
                registrationAttempted=false;
            }
            policy=null;manager=null;mixes.clear();
            if(thread!=null){HandlerThread t=thread;thread=null;t.quitSafely();boolean interrupted=Thread.interrupted();long deadline=System.nanoTime()+1000000000L;
                try{while(t.isAlive()&&System.nanoTime()<deadline){try{t.join(Math.max(1,(deadline-System.nanoTime())/1000000L));}catch(InterruptedException e){interrupted=true;}}}
                finally{if(interrupted)Thread.currentThread().interrupt();}
                if(t.isAlive())throw new IOException("audio policy callback thread did not retire");}
        }
        private static Object invoke(Object target,String name,Class<?>[] types,Object...args)throws Exception{
            Method method=target.getClass().getMethod(name,types);
            try{return method.invoke(target,args);}catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IOException("audio policy API "+name+" failed",cause);}
        }
    }
    private static final class PolicyRecord implements PcmAudioSession.Source {
        private AudioRecord record;private final CarLifeAudio.Lane lane;
        PolicyRecord(AudioRecord record,CarLifeAudio.Lane lane){this.record=record;this.lane=lane;}
        public void start()throws IOException{
            if(record.getState()!=AudioRecord.STATE_INITIALIZED||record.getSampleRate()!=lane.rate||record.getChannelCount()!=lane.channels||record.getAudioFormat()!=AudioFormat.ENCODING_PCM_16BIT)
                throw new IOException("AudioMix actual format/state mismatch for "+lane);
            record.startRecording();
            if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("AudioMix recording did not start for "+lane);
        }
        public int read(byte[] data,int offset,int size)throws IOException{
            int n=record.read(data,offset,size,AudioRecord.READ_NON_BLOCKING);
            if(n<0)throw new IOException("AudioMix read failed for "+lane+": "+n);return n;
        }
        public void close(){if(record!=null){record.release();record=null;}}
    }
}
