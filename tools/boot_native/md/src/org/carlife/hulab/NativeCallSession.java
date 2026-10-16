package org.carlife.hulab;

import java.io.IOException;

/** One call owner. Android I/O stays on this worker; the existing writer owns all USB. */
final class NativeCallSession implements AutoCloseable {
    interface Host {
        long now();
        boolean prepare(NativeCallSession owner);
        void release(NativeCallSession owner,boolean clean);
        void wake();
        void event(String state,String detail,NativeCallSession owner);
        void fatal(NativeCallSession owner,String detail);
    }
    final CarLifeAudio.Queue queue=new CarLifeAudio.Queue(CarLifeAudio.Lane.MEDIA);
    private final NativeCallAudio endpoints;
    private final Host host;
    private final NativeMicrophone.PcmQueue microphone=new NativeMicrophone.PcmQueue();
    private volatile Thread worker;
    private volatile boolean closed,holding,streaming,ending,wireStopped=true,microphoneReady;
    private volatile long token;
    private volatile int family;
    private volatile long downlinkBytes,uplinkBytes,ignoredMicrophonePackets;
    private volatile boolean clean=true;
    // These are changed only by the single transport writer.
    boolean voiceAnnounced,microphoneAnnounced;
    NativeCallSession(NativeCallAudio audio,Host host){if(audio==null||host==null)throw new NullPointerException();endpoints=audio;this.host=host;}
    synchronized void start(){if(closed||worker!=null)return;worker=new Thread(new Runnable(){public void run(){pump();}},"carlife-usb-call");worker.setDaemon(true);worker.start();}
    boolean holding(){return holding;}
    boolean streaming(){return streaming;}
    int family(){return family;}
    long generation(){return token;}
    long downlinkBytes(){return downlinkBytes;}
    long uplinkBytes(){return uplinkBytes;}
    long droppedMicrophoneBytes(){return microphone.droppedBytes();}
    boolean retired(){Thread t=worker;return (t==null||!t.isAlive())&&clean;}
    boolean current(CarLifeAudio.Item item){return item.owner==queue&&queue.current(item);}
    void onMic(byte[] pcm,int timestamp){long epoch=token;if(closed||!microphoneReady||epoch==0){ignoredMicrophonePackets++;return;}microphone.offer(epoch,pcm,host.now());}
    void initWritten(){if(!closed&&streaming){microphoneReady=true;host.wake();}}
    void stopWritten(){microphoneReady=false;wireStopped=true;host.wake();}
    private void event(String state,String detail){host.event(state,detail,this);}
    private void pump(){
        byte[] down=new byte[NativeCallAudio.DOWNLINK_CHUNK_BYTES];int used=0;long frames=0,origin=0,retryAt=0,reportAt=0;
        NativeMicrophone.Chunk pending=null;int offset=0;
        try{
            event("observer_ready","existing AudioManager call mode only; USB MEDIA downlink and MIC uplink; no setMode");
            while(!closed){
                long now=host.now();int next;
                try{next=endpoints.currentFamily();}
                catch(IOException failure){next=0;if(now>=retryAt){event("mode_unavailable",failure.toString());retryAt=now+5000;}}
                if(streaming&&(next!=family||!endpoints.active())){
                    endStream();used=0;frames=0;pending=null;offset=0;
                }
                if(holding&&!streaming&&wireStopped&&ending){
                    clean=!endpoints.poisoned();host.release(this,clean);holding=false;family=0;ending=false;
                    event("stopped",clean?"both endpoints released; normal audio may resume":"endpoint cleanup unconfirmed; ownership fenced");
                }
                if(!streaming&&!holding&&next!=0&&now>=retryAt&&!endpoints.poisoned()){
                    holding=true;family=next;event("waiting_for_sources","retire normal playback, app microphone and assistant before opening call pair");
                }
                if(holding&&!streaming&&!ending&&next!=0)family=next;
                if(holding&&!streaming&&!ending&&wireStopped&&next!=0&&family==next&&now>=retryAt&&!endpoints.poisoned()){
                    // prepare() is idempotent. No call endpoint can open before every
                    // predecessor has retired and its STOP completed on the wire.
                    if(host.prepare(this))try{
                        long opened=endpoints.openForCurrentMode();
                        if(opened!=0&&!closed){
                            synchronized(this){
                                if(closed)break;
                                token=opened;microphone.reset(opened);queue.clear();wireStopped=false;streaming=true;
                                queue.start(queue.epoch(),now);
                            }
                            origin=now;used=0;frames=0;pending=null;offset=0;reportAt=now;
                            event("capturing","paired Android endpoints; MEDIA48000/stereo16, MIC16000/mono16");host.wake();
                        }else{endpoints.stop();host.release(this,!endpoints.poisoned());holding=false;family=0;}
                    }catch(IOException failure){
                        endpoints.stop();retryAt=now+5000;host.release(this,!endpoints.poisoned());holding=false;family=0;event("failed",failure.toString());
                    }
                }
                if(holding&&!streaming&&wireStopped&&next==0){host.release(this,!endpoints.poisoned());holding=false;family=0;}
                if(streaming)try{
                    long epoch=token;int n=endpoints.readDownlink(epoch,down,used,down.length-used);
                    if(!endpoints.active()){endStream();continue;}
                    used+=n;downlinkBytes+=n;
                    if(used==down.length){queue.data(queue.epoch(),down,(int)(origin+frames*1000L/NativeCallAudio.DOWNLINK_RATE),now);frames+=used/4;used=0;host.wake();}
                    for(int writes=0;writes<4;writes++){
                        if(pending!=null&&(pending.epoch!=epoch||now-pending.created>NativeMicrophone.PcmQueue.MAX_AGE_MS)){microphone.discardPending(pending.data.length-offset);pending=null;offset=0;}
                        if(pending==null){pending=microphone.poll(epoch,now);offset=0;if(pending==null)break;}
                        n=endpoints.writeUplink(epoch,pending.data,offset,Math.min(NativeCallAudio.UPLINK_CHUNK_BYTES,pending.data.length-offset));
                        if(n==0)break;uplinkBytes+=n;offset+=n;if(offset==pending.data.length){pending=null;offset=0;}
                    }
                    if(now-reportAt>=1000){reportAt=now;event("active","raw_audio_recorded=false");}
                }catch(IOException failure){event("failed",failure.toString());retryAt=now+5000;endStream();used=0;pending=null;offset=0;}
                Thread.sleep(streaming||holding?5:100);
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(RuntimeException|LinkageError failure){event("failed",failure.toString());host.fatal(this,failure.toString());}
        finally{
            microphoneReady=false;token=0;microphone.reset(0);endpoints.close();clean=!endpoints.poisoned();streaming=false;queue.clear();
            if(holding)host.release(this,clean);holding=false;family=0;host.wake();event("closed",clean?"cleanup confirmed":"cleanup unconfirmed");
        }
    }
    private void endStream(){
        streaming=false;ending=true;microphoneReady=false;token=0;microphone.reset(0);endpoints.stop();
        queue.stop(queue.epoch(),host.now());host.wake();
    }
    public synchronized void close(){closed=true;microphoneReady=false;queue.clear();Thread t=worker;if(t!=null)t.interrupt();}
}
