package org.carlife.hulab;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Routing lifecycle and actual common PCM worker/wire tests. Android mixing needs hardware. */
public final class SplitAudioSourceTest {
    static int checks;
    static void check(boolean condition,String why){checks++;if(!condition)throw new AssertionError(why);}
    static void await(Callable<Boolean> condition,String why)throws Exception{long end=System.nanoTime()+3000000000L;while(!condition.call()){if(System.nanoTime()>end)throw new AssertionError(why);Thread.sleep(2);}checks++;}
    static class Record implements PcmAudioSession.Source {
        final CarLifeAudio.Lane lane;int readBytes,closes;boolean failClose;
        Record(CarLifeAudio.Lane lane){this.lane=lane;}
        public void start(){}
        public int read(byte[] data,int offset,int size){if(readBytes>=lane.chunkBytes*2)return 0;int n=Math.min(111,Math.min(size,lane.chunkBytes*2-readBytes));Arrays.fill(data,offset,offset+n,(byte)(lane==CarLifeAudio.Lane.MEDIA?17:34));readBytes+=n;return n;}
        public void close()throws IOException{closes++;if(failClose)throw new IOException("record release failure");}
    }
    static class Platform implements AudioPolicySources.Platform {
        int registers,unregisters;boolean failRegister,failUnregister,failSpeech,failSpeechStart,failSpeechRead;String order="";
        final Map<CarLifeAudio.Lane,Record> records=new EnumMap<CarLifeAudio.Lane,Record>(CarLifeAudio.Lane.class);
        public void register()throws Exception{registers++;order+="register;";if(failRegister)throw new IOException("registration denied");}
        public PcmAudioSession.Source open(final CarLifeAudio.Lane lane)throws Exception{order+="open-"+lane+";";if(failSpeech&&lane==CarLifeAudio.Lane.TTS)throw new IOException("speech route unavailable");Record r=new Record(lane){
            public void start(){if(failSpeechStart&&lane==CarLifeAudio.Lane.TTS)throw new IllegalStateException("speech actual format mismatch");}
            public int read(byte[] data,int offset,int size){if(failSpeechRead&&lane==CarLifeAudio.Lane.TTS)throw new IllegalStateException("speech read failed");return super.read(data,offset,size);}
        };records.put(lane,r);return r;}
        public void unregister()throws Exception{unregisters++;order+="unregister;";if(failUnregister)throw new IOException("unregistration unconfirmed");}
    }
    static AudioPolicySources owner(final Platform p,SourceLease global){return new AudioPolicySources(new AudioPolicySources.PlatformFactory(){public AudioPolicySources.Platform create(){return p;}},global);}
    static PcmAudioSession worker(AudioPolicySources p,CarLifeAudio.Lane lane){return new PcmAudioSession(p.factory(lane),new PcmAudioSession.Events(){public long now(){return 1000;}public void wakeWriter(){}public void state(String s,String d){}},lane,p.lease(lane));}
    static class Players implements PlaybackActivity.Source {
        PlaybackActivity.Observer observer;public void start(PlaybackActivity.Observer o){observer=o;}public void close(){}
        void emit(Integer[] output,Integer[] media){observer.update(new PlaybackActivity.Snapshot(new HashSet<Integer>(Arrays.asList(output)),new HashSet<Integer>(Arrays.asList(media))));}
    }
    public static void main(String[] args)throws Exception{
        for(int usage:new int[]{0,1,14})check(AudioUsage.isMedia(usage)&&!AudioUsage.isSpeech(usage),"media usage separated "+usage);
        for(int usage:new int[]{12,16})check(AudioUsage.isSpeech(usage)&&!AudioUsage.isMedia(usage),"speech usage separated "+usage);
        for(int usage:new int[]{2,3,4,5,6,7,10,11,13,15})check(!AudioUsage.isMedia(usage)&&!AudioUsage.isSpeech(usage),"calls/notifications/virtual source not rerouted "+usage);
        int[] roles=AudioUsage.forLane(CarLifeAudio.Lane.MEDIA);roles[0]=16;check(AudioUsage.forLane(CarLifeAudio.Lane.MEDIA)[0]==0,"route arrays cannot be mutated by caller");
        Map<Integer,Object> ttsInit=MdMain.Proto.parse(CarLifeAudio.Lane.TTS.init());check(MdMain.Proto.number(ttsInit,1,0)==16000&&MdMain.Proto.number(ttsInit,2,0)==1&&MdMain.Proto.number(ttsInit,3,0)==16,"TTS wire actual format");
        final Platform p=new Platform();final AudioPolicySources sources=owner(p,PcmAudioSession.LEASE);
        check(sources.retired()&&p.registers==0,"creating split owner does not register idle audio policy");
        final PcmAudioSession media=worker(sources,CarLifeAudio.Lane.MEDIA),speech=worker(sources,CarLifeAudio.Lane.TTS);
        check(media.start()&&speech.start(),"both workers admitted");
        await(new Callable<Boolean>(){public Boolean call(){return media.queue.size()==3&&speech.queue.size()==3;}},"two independently formatted PCM lanes captured");
        check(p.registers==1&&PcmAudioSession.LEASE.isHeld(),"one policy registers once under global mixed/split fence");
        PcmAudioSession mixed=new PcmAudioSession(new PcmAudioSession.Factory(){public PcmAudioSession.Source create(){return new Record(CarLifeAudio.Lane.MEDIA);}},new PcmAudioSession.Events(){public long now(){return 1000;}public void state(String s,String d){}public void wakeWriter(){}});
        check(!mixed.start(),"address-zero mixed capture cannot coexist with split policy");
        CarLifeAudio.Item mi=media.queue.poll(1000),ti=speech.queue.poll(1000),md=media.queue.poll(1000),td=speech.queue.poll(1000),td2=speech.queue.poll(1000);
        check(mi.channel==3&&mi.id==0x30001&&ti.channel==4&&ti.id==0x40001,"independent lane INIT precedes data");
        check(md.id==0x30006&&md.payload.length==3840&&md.payload[0]==17,"MEDIA carries its source and format");
        check(td.id==0x40003&&td.payload.length==640&&td.payload[0]==34,"TTS carries different source and actual mono PCM");
        check(td.timestamp==1000&&td2.timestamp==1020,"TTS sample count advances exactly 20ms");
        check(!media.queue.current(ti)&&!speech.queue.current(mi),"equal generation does not admit other lane packet");
        media.stop();await(new Callable<Boolean>(){public Boolean call(){return media.retired();}},"media releases on radio pause");
        check(speech.active()&&p.unregisters==0&&PcmAudioSession.LEASE.isHeld(),"radio media stop preserves speech route and shared policy");
        CarLifeAudio.Item ms=media.queue.poll(1000);check(ms.id==0x30002&&ms.channel==3,"MEDIA stop targets only channel3");
        speech.stop();await(new Callable<Boolean>(){public Boolean call(){return speech.retired();}},"speech releases");
        check(sources.retired()&&p.unregisters==1&&!PcmAudioSession.LEASE.isHeld(),"last source synchronously unregisters routes and releases global fence");
        check(speech.queue.poll(1000).id==0x40002,"TTS stop targets its own wire message");
        check(p.order.startsWith("register;open-")&&p.order.endsWith("unregister;"),"registration/open/removal ordering");
        check(mixed.start(),"mixed source available only after complete split teardown");mixed.stop();await(new Callable<Boolean>(){public Boolean call(){return mixed.retired();}},"mixed cleanup");
        final Platform oneFailed=new Platform();oneFailed.failSpeech=true;final AudioPolicySources isolated=owner(oneFailed,new SourceLease());final PcmAudioSession continuing=worker(isolated,CarLifeAudio.Lane.MEDIA),broken=worker(isolated,CarLifeAudio.Lane.TTS);
        continuing.start();await(new Callable<Boolean>(){public Boolean call(){return continuing.active();}},"media established before speech failure");broken.start();await(new Callable<Boolean>(){public Boolean call(){return broken.retired();}},"failed speech retires its worker");
        check(broken.error().contains("speech route unavailable")&&continuing.active()&&oneFailed.unregisters==0,"one lane failure does not destroy another active lane");continuing.stop();await(new Callable<Boolean>(){public Boolean call(){return continuing.retired();}},"remaining lane cleanup");check(isolated.retired(),"isolated failure cleanup completes");
        for(boolean readFailure:new boolean[]{false,true}){
            final Platform failure=new Platform();failure.failSpeechStart=!readFailure;failure.failSpeechRead=readFailure;
            final AudioPolicySources failureOwner=owner(failure,new SourceLease());final PcmAudioSession music=worker(failureOwner,CarLifeAudio.Lane.MEDIA),tts=worker(failureOwner,CarLifeAudio.Lane.TTS);
            music.start();await(new Callable<Boolean>(){public Boolean call(){return music.active();}},"media established before source error");tts.start();await(new Callable<Boolean>(){public Boolean call(){return tts.retired();}},"speech start/read failure retires lane");
            check(tts.error().contains(readFailure?"speech read failed":"speech actual format mismatch")&&music.active()&&failure.unregisters==0,"actual record failure is isolated to speech and diagnosed");
            CarLifeAudio.Item tail=tts.queue.poll(1000);check(readFailure?tail!=null&&tail.id==CarLifeAudio.Lane.TTS.stopId:tail==null,"failed start emits no INIT; failed read clears queued audio and sends STOP");
            check(failure.records.get(CarLifeAudio.Lane.TTS).closes==1,"failed record released exactly once");music.stop();await(new Callable<Boolean>(){public Boolean call(){return music.retired();}},"remaining media cleanup after source failure");check(failureOwner.retired(),"all failed capture routes removed");
        }
        final Platform closeFailure=new Platform();final SourceLease closeGuard=new SourceLease();final AudioPolicySources closeOwner=owner(closeFailure,closeGuard);final PcmAudioSession lastRecord=worker(closeOwner,CarLifeAudio.Lane.TTS);lastRecord.start();await(new Callable<Boolean>(){public Boolean call(){return lastRecord.active();}},"record active before release error");closeFailure.records.get(CarLifeAudio.Lane.TTS).failClose=true;lastRecord.stop();await(new Callable<Boolean>(){public Boolean call(){return lastRecord.retired();}},"synchronous unregister invalidates last captor even when release throws");
        check(closeOwner.retired()&&!closeGuard.isHeld()&&closeFailure.unregisters==1&&closeOwner.diagnostics().contains("failures=1"),"confirmed policy cleanup releases fence but preserves release-failure diagnostic");
        final Platform denied=new Platform();denied.failRegister=true;SourceLease deniedGuard=new SourceLease();final AudioPolicySources deniedOwner=owner(denied,deniedGuard);final PcmAudioSession deniedWorker=worker(deniedOwner,CarLifeAudio.Lane.TTS);deniedWorker.start();await(new Callable<Boolean>(){public Boolean call(){return deniedWorker.retired();}},"registration denial retires worker");
        check(deniedWorker.error().contains("registration denied")&&denied.unregisters==1&&!deniedGuard.isHeld()&&deniedOwner.retired(),"failed registration attempts cleanup before allowing other sources");check(deniedWorker.queue.size()==0,"failed registration emits no INIT/speech success");
        final Platform sticky=new Platform();sticky.failUnregister=true;SourceLease stickyGuard=new SourceLease();final AudioPolicySources stickyOwner=owner(sticky,stickyGuard);final PcmAudioSession stickyWorker=worker(stickyOwner,CarLifeAudio.Lane.TTS);stickyWorker.start();await(new Callable<Boolean>(){public Boolean call(){return stickyWorker.active();}},"sticky source active");stickyWorker.stop();await(new Callable<Boolean>(){public Boolean call(){return stickyWorker.error()!=null;}},"unregister error reported");
        check(!stickyWorker.retired()&&!stickyOwner.retired()&&stickyGuard.isHeld(),"unconfirmed unregistration retains both ownership fences");check(!stickyWorker.start(),"poisoned route cannot restart behind failed cleanup");
        CarLifeAudio.Queue tq=new CarLifeAudio.Queue(CarLifeAudio.Lane.TTS);long epoch=tq.epoch();tq.start(epoch,1000);for(int i=0;i<15;i++)tq.data(epoch,new byte[640],1000+i*20,1000);check(tq.size()==11&&tq.dropped==5,"TTS queue keeps bounded 200ms PCM");
        tq.poll(1000);check(tq.poll(1201)==null&&tq.dropped==15,"stale TTS PCM discarded");tq.clear();tq.data(epoch,new byte[640],1000,1000);check(tq.size()==0,"old TTS generation cannot write after logical reset");
        Players players=new Players();final boolean[] resumed={false};PlaybackActivity activity=new PlaybackActivity(players,new PlaybackActivity.Listener(){public void changed(long e,boolean play){resumed[0]=play;}public void degraded(long e,String why){}});activity.start();
        check(!activity.known()&&!activity.mediaActive()&&!activity.speechActive(),"initial async observer cannot grab either lane");players.emit(new Integer[]{1,2},new Integer[]{1});check(activity.mediaActive()&&activity.speechActive()&&!resumed[0],"initial snapshot exposes both roles without synthetic Play");
        players.emit(new Integer[]{2},new Integer[]{});check(!activity.mediaActive()&&activity.speechActive(),"media pause leaves spoken prompt active");players.emit(new Integer[]{1,2},new Integer[]{1});check(activity.mediaActive()&&activity.speechActive()&&resumed[0],"real media restart independent of speech");
        players.observer.failed("callback unavailable");check(!activity.known()&&activity.mediaActive()&&!activity.speechActive(),"degraded observer never invents active TTS");activity.close();
        System.out.println("PASS "+checks+" split audio worker/wire/lifecycle checks; Android route delivery requires device");
    }
}
