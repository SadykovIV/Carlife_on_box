package org.carlife.hulab;

import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.*;
import java.net.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One CarLife session/media/input implementation over loopback development or native USB I/O. */
public final class MdMain {
    static final int MAX_BODY=1024*1024, CMD=1, VIDEO=2, TOUCH=6;
    static volatile boolean running=true;
    static volatile Session active;
    static ServerSocket server;
    static String transportMode="dev-stream";
    static String audioSource="off";
    static String nativeVideoMode="720p";
    static boolean nativeServicesEnabled;
    static String voiceRecognizer;
    static boolean bootNative;
    static String bootStateFile,bootNonce;
    static BootNativeReadiness bootReadiness;
    static int displayWidth=1920, displayHeight=1080, seconds=120, port=7080;
    static String videoSource="bootstrap",videoPath="direct",csdSource="buffer",touchProfile="legacy-single";
    static int sourceDisplayId=0,sourceDensity=0,isolatedWidth=0,isolatedHeight=0,isolatedDensity=0;
    static OwnedDisplay ownedDisplay;
    static String encoderName="OMX.qcom.video.encoder.avc";
    static long sessionSequence;
    static final SourceLease CAPTURE_LEASE=new SourceLease();
    static long deadline;
    static volatile AsyncRingLog asyncLog;
    static final Object CONSOLE_LOG_LOCK=new Object();
    static String logDirectory;
    static void emitLog(String event,String json){
        AsyncRingLog logger=asyncLog;if(logger!=null){logger.offer(event,json);return;}
        synchronized(CONSOLE_LOG_LOCK){System.out.println(json);System.out.flush();}
    }
    static void log(String event,String detail) {
        emitLog(event,"{\"uptime_ms\":"+SystemClock.uptimeMillis()+",\"event\":\""+escape(event)+"\",\"detail\":\""+escape(detail)+"\"}");
    }
    static String escape(String s) {
        StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'||c=='\\'){b.append('\\').append(c);}else if(c<32){String h=Integer.toHexString(c);b.append("\\u");for(int n=h.length();n<4;n++)b.append('0');b.append(h);}else b.append(c);}return b.toString();
    }
    static void logFields(String event,Object...fields){
        StringBuilder b=new StringBuilder("{\"uptime_ms\":").append(SystemClock.uptimeMillis()).append(",\"event\":\"").append(escape(event)).append('"');
        for(int i=0;i<fields.length;i+=2){b.append(",\"").append(escape((String)fields[i])).append("\":");Object v=fields[i+1];if(v==null)b.append("null");else if(v instanceof Number||v instanceof Boolean)b.append(v);else b.append('"').append(escape(v.toString())).append('"');}b.append('}');emitLog(event,b.toString());
    }
    public static void main(String[] args) throws Exception {
        boolean explicitTouchProfile=false;
        String videoTrace="auto";
        for(int i=0;i<args.length;i+=2) {
            if(i+1==args.length) throw new IllegalArgumentException("option requires value");
            if(args[i].equals("--boot-native")){bootNative=choice(args[i+1],"true","false").equals("true");continue;}
            if(args[i].equals("--boot-state-file")){bootStateFile=args[i+1];continue;}
            if(args[i].equals("--boot-nonce")){bootNonce=args[i+1];continue;}
            if(args[i].equals("--transport")){transportMode=choice(args[i+1],"dev-stream","native-usb");continue;}
            if(args[i].equals("--audio-source")){audioSource=choice(args[i+1],"off","remote-submix","split");continue;}
            if(args[i].equals("--video-mode")){nativeVideoMode=args[i+1];VideoMode.requested(nativeVideoMode,1280,720,30);continue;}
            if(args[i].equals("--native-services")){nativeServicesEnabled=choice(args[i+1],"off","on").equals("on");continue;}
            if(args[i].equals("--voice-recognizer")){voiceRecognizer=args[i+1];continue;}
            if(args[i].equals("--log-dir")){logDirectory=args[i+1];continue;}
            if(args[i].equals("--video-source")){videoSource=args[i+1];if(!videoSource.equals("bootstrap")&&!videoSource.equals("mediacodec"))throw new IllegalArgumentException("video source must be bootstrap or mediacodec");continue;}
            if(args[i].equals("--encoder")){encoderName=args[i+1];if(!encoderName.equals("OMX.qcom.video.encoder.avc")&&!encoderName.equals("c2.qti.avc.encoder")&&!encoderName.equals("c2.android.avc.encoder"))throw new IllegalArgumentException("encoder outside laboratory whitelist");continue;}
            if(args[i].equals("--video-trace")){videoTrace=choice(args[i+1],"auto","full");continue;}
            if(args[i].equals("--video-path")){videoPath=choice(args[i+1],"direct","egl");continue;}
            if(args[i].equals("--csd-source")){csdSource=choice(args[i+1],"buffer","output-format-oem-guard");continue;}
            if(args[i].equals("--touch-profile")){touchProfile=choice(args[i+1],"legacy-single","legacy-dual","action3-multi","all-lab","recognized");explicitTouchProfile=true;continue;}
            int n=Integer.parseInt(args[i+1]);
            if(args[i].equals("--port"))port=n;
            else if(args[i].equals("--seconds"))seconds=n;
            else if(args[i].equals("--display-width"))displayWidth=n;
            else if(args[i].equals("--display-height"))displayHeight=n;
            else if(args[i].equals("--source-display"))sourceDisplayId=n;
            else if(args[i].equals("--source-density"))sourceDensity=n;
            else if(args[i].equals("--isolated-width"))isolatedWidth=n;
            else if(args[i].equals("--isolated-height"))isolatedHeight=n;
            else if(args[i].equals("--isolated-density"))isolatedDensity=n;
            else throw new IllegalArgumentException("unknown option "+args[i]);
        }
        boolean nativeUsb=transportMode.equals("native-usb");
        if(bootNative&&(!nativeUsb||!videoSource.equals("mediacodec")||sourceDisplayId!=0))throw new IllegalArgumentException("boot-native requires native USB/MediaCodec/primary display");
        if(!bootNative&&(bootStateFile!=null||bootNonce!=null))throw new IllegalArgumentException("boot state options require --boot-native true");
        if(nativeUsb&&!explicitTouchProfile)touchProfile="recognized";
        // Shared DEV entry never calls this API and keeps full diagnostics.
        MediaCodecDisplaySource.configureVideoDiagnostics(!bootNative||videoTrace.equals("full"));
        if(seconds<(nativeUsb?0:1)||seconds>4200||port<1024||port>65535||!validDisplayRequest(nativeUsb,displayWidth,displayHeight))throw new IllegalArgumentException("bounds");
        if(sourceDisplayId<0||sourceDensity<0||sourceDensity>640)throw new IllegalArgumentException("source display/density bounds");
        if(isolatedWidth!=0||isolatedHeight!=0||isolatedDensity!=0){if(isolatedWidth<320||isolatedWidth>1920||isolatedHeight<180||isolatedHeight>1080||isolatedDensity<120||isolatedDensity>640)throw new IllegalArgumentException("isolated display bounds; all three parameters required");}
        if(nativeUsb&&(sourceDisplayId!=0||isolatedWidth!=0))throw new IllegalArgumentException("native pilot requires actual primary display0");
        if(logDirectory!=null&&!nativeUsb)throw new IllegalArgumentException("--log-dir is native-usb only; development logging unchanged");
        if(nativeUsb){
            if(logDirectory!=null)verifyLogDirectory(logDirectory);
            asyncLog=new AsyncRingLog(logDirectory==null?AsyncRingLog.console(System.out):new AsyncRingLog.Files(new File(logDirectory)));
            logFields("log_started","backend",logDirectory==null?"async_console":"async_disk_ring","queue_lines",AsyncRingLog.QUEUE_LINES,"line_bytes",AsyncRingLog.LINE_BYTES,"tail_files",2,"tail_file_bytes",AsyncRingLog.TAIL_BYTES,"header_bytes",AsyncRingLog.HEAD_BYTES,"overflow","nonblocking drop with counters","header_scope","first handshake/codec metadata of this process","shutdown_flush_bound_ms",1500);
            logNativeBootIdentity();
        }
        deadline=seconds==0?Long.MAX_VALUE:SystemClock.uptimeMillis()+seconds*1000L;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){public void run(){shutdown();closeLog();}},"md-cleanup"));
        if(isolatedWidth>0){ownedDisplay=new OwnedDisplay(isolatedWidth,isolatedHeight,isolatedDensity);sourceDisplayId=ownedDisplay.id;displayWidth=isolatedWidth;displayHeight=isolatedHeight;sourceDensity=isolatedDensity;}
        if(bootNative){bootReadiness=new BootNativeReadiness(bootStateFile,bootNonce);bootReadiness.start();}
        else verifyDisplay();
        logFields("projection_policy","video_source",videoSource,"video_path",videoPath,"csd_source",csdSource,"touch_profile",touchProfile,"source_display_id",sourceDisplayId,"source_width",displayWidth,"source_height",displayHeight,"source_density_dpi",sourceDensity,"density_provenance",sourceDensity==0?"unknown":"runtime/manual/owned display declared by launcher","compatibility","SELF explicit lab profile");
        if(!nativeUsb){server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),1);server.setSoTimeout(1000);}
        logFields("transport_controller_ready","transport",transportMode,"endpoint",nativeUsb?AndroidAccessoryIo.PATH:"127.0.0.1:"+port,"controller_deadline_uptime_ms",seconds==0?null:deadline,"persistent_controller",seconds==0,"audio_source_requested",audioSource,"audio_capture_verified",false,"phone_verification","v2_checksum","legacy_rsa_provider_available",false,"wire","CarLife 8-byte channel/length then unchanged message header");
        Thread watchdog=new Thread(new Runnable(){public void run(){
            while(running){
                if(SystemClock.uptimeMillis()>deadline){log("deadline","server time limit");shutdown();break;}
                Session s=active;
                if(s!=null)s.checkInputTimeout();
                if(s!=null){s.maybeReportProgress();s.closeIfWriteStalled();}
                try{Thread.sleep(100);}catch(InterruptedException e){break;}
            }
        }},"md-watchdog"); watchdog.setDaemon(true);watchdog.start();
        long lastAccessoryWaitLog=0;String lastAccessoryWaitError=null;
        try {
            while(running&&SystemClock.uptimeMillis()<deadline){
                MdTransport transport=null;
                try {
                    if(nativeUsb){transport=new AccessoryTransport(new AndroidAccessoryIo());log("accessory_opened","O_RDWR|O_NONBLOCK|O_CLOEXEC; 16384-byte kernel reads; bounded reader queue; attach/handshake pending");}
                    else transport=new SocketMdTransport(server.accept());
                    active=new Session(transport);active.run();
                }
                catch(SocketTimeoutException e) { }
                catch(AndroidAccessoryIo.OpenFailure e){long now=SystemClock.uptimeMillis();String detail=e.toString();if(running&&(!detail.equals(lastAccessoryWaitError)||now-lastAccessoryWaitLog>=5000)){logFields("accessory_open_wait","retryable",e.retryable,"detail",detail);lastAccessoryWaitError=detail;lastAccessoryWaitLog=now;}if(!e.retryable)throw e;}
                catch(IOException e) { if(active!=null)active.noteTerminal("session_io_error");if(running)logFields("session_error","session_id",active==null?null:active.sessionId,"error",e.toString()); }
                finally {
                    if(bootReadiness!=null)bootReadiness.commandReady(false);
                    try{finishSessionOwner(active,transport);}finally{active=null;}
                }
                if(nativeUsb&&running)Thread.sleep(250);
            }
        } finally { shutdown();log("stopped","transport controller stopped; owned session cleanup requested");closeLog(); }
    }
    static void finishSessionOwner(Session session,MdTransport transport)throws IOException {
        // fd close/open alone does not reset old f_accessory endpoint requests.
        // After an accepted boot-native session ends, only the owning controller
        // may publish fresh MTP and await a new genuine AOA START.
        boolean retire=bootNative&&session!=null&&session.transport.isNativeUsb()&&session.everNegotiated;
        if(retire)running=false;
        if(session!=null)session.close();
        if(transport!=null){try{transport.close();}catch(IOException e){log("transport_close_error",e.toString());}
            boolean stopped=transport.awaitStopped(2500);
            logFields("transport_owner_stopped","transport",transport.name(),"session_id",session==null?null:session.sessionId,"ever_negotiated",session!=null&&session.everNegotiated,"quiescent",stopped);
            if(!stopped){running=false;throw new IOException("transport owner did not stop; refusing another accessory fd");}}
        if(retire){
            boolean cleanup=session.awaitTerminalCleanup(3500);
            logFields("native_owner_retire","session_id",session.sessionId,"logical_session",session.nativeLogicalSession,"hu_major",session.huMajor,"hu_minor",session.huMinor,"terminal_reason",session.terminalReason,"cleanup_confirmed",cleanup,"source_lease",CAPTURE_LEASE.state(),"next_action","exit MD; controller must rearm owned MTP before new AOA; no fd reopen");
            if(!cleanup)throw new IOException("negotiated native owner cleanup unconfirmed; no successful recovery exit");
        }
    }
    static void closeLog(){AsyncRingLog logger=asyncLog;if(logger!=null)logger.closeWithin(1500);}
    static void logNativeBootIdentity(){
        try {String boot=BootNativeReadiness.readLine("/proc/sys/kernel/random/boot_id");if(!boot.matches("[0-9a-f-]{36}"))throw new IOException("invalid boot ID format");logFields("native_boot_identity","boot_id",boot,"pid",android.os.Process.myPid(),"provenance","current /proc/sys/kernel/random/boot_id");}
        catch(Exception e){logFields("native_boot_identity","boot_id",null,"identity_read_error",e.getClass().getSimpleName(),"admission_affected",false);}
    }
    static void verifyLogDirectory(String path)throws Exception{
        File directory=new File(path);if(!directory.isAbsolute()||!directory.getCanonicalFile().equals(directory.getAbsoluteFile()))throw new IOException("log directory must be canonical absolute path");
        android.system.StructStat st=android.system.Os.lstat(path);
        if(!android.system.OsConstants.S_ISDIR(st.st_mode)||st.st_uid!=android.os.Process.myUid()||(st.st_mode&0777)!=0700)throw new IOException("log directory must be precreated by existing UID with mode0700");
        for(String name:new String[]{"md.jsonl","md.1.jsonl","md.head.jsonl","md.head.1.jsonl","md.head.2.jsonl","md.head.3.jsonl"}){File f=new File(directory,name);try{st=android.system.Os.lstat(f.getPath());}catch(android.system.ErrnoException e){if(e.errno==android.system.OsConstants.ENOENT)continue;throw e;}if(!android.system.OsConstants.S_ISREG(st.st_mode)||st.st_uid!=android.os.Process.myUid()||(st.st_mode&0777)!=0600)throw new IOException("foreign log file ownership/mode "+name);}
    }
    static boolean validDisplayRequest(boolean nativeUsb,int width,int height){return nativeUsb&&width==0&&height==0||width>=320&&width<=4096&&height>=180&&height<=4096;}
    static void verifyDisplay()throws Exception{
        boolean observePrimary=transportMode.equals("native-usb")&&sourceDisplayId==0&&displayWidth==0&&displayHeight==0;
        Class<?> global=Class.forName("android.hardware.display.DisplayManagerGlobal");Object manager=global.getMethod("getInstance").invoke(null);android.view.Display d=null;long waitUntil=SystemClock.uptimeMillis()+(observePrimary?60000:0),lastWait=0;
        do{d=(android.view.Display)global.getMethod("getRealDisplay",int.class).invoke(manager,sourceDisplayId);if(d!=null)break;if(!observePrimary||!running||SystemClock.uptimeMillis()>=waitUntil)break;if(SystemClock.uptimeMillis()-lastWait>=5000){log("primary_display_wait","waiting for real display0; 60s maximum; no USB opened yet");lastWait=SystemClock.uptimeMillis();}Thread.sleep(250);}while(true);
        if(d==null)throw new IOException("source display does not exist within bounded observation");android.graphics.Point size=new android.graphics.Point();d.getRealSize(size);android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();d.getRealMetrics(metrics);
        logFields("source_display_state","display_id",sourceDisplayId,"logical_width",size.x,"logical_height",size.y,"density_dpi",metrics.densityDpi,"rotation",d.getRotation(),"provenance","DisplayManagerGlobal.getRealDisplay / Display.getRealSize/getRealMetrics");
        if(observePrimary){if(!validDisplayRequest(false,size.x,size.y))throw new IOException("observed primary dimensions outside existing bounds");displayWidth=size.x;displayHeight=size.y;logFields("primary_display_observed","width",size.x,"height",size.y,"density_dpi",metrics.densityDpi,"rotation",d.getRotation(),"policy","OBSERVED_PRIMARY_NOT_CHANGED");}
        if(d.getRotation()!=0)throw new IOException("live source rotations other than0 require explicit adapter; no guessed input transform");if(size.x!=displayWidth||size.y!=displayHeight)throw new IOException("actual source geometry differs from launcher declaration");if(sourceDensity!=0&&sourceDensity!=metrics.densityDpi)throw new IOException("declared source density differs from actual display; use owned isolated display to change layout");sourceDensity=metrics.densityDpi;
    }
    static String choice(String value,String...allowed){for(String s:allowed)if(s.equals(value))return value;throw new IllegalArgumentException("unsupported explicit laboratory option "+value);}
    static void shutdown(){running=false;if(bootReadiness!=null)bootReadiness.stop();Session s=active;if(s!=null)s.close();try{if(server!=null)server.close();}catch(IOException e){}OwnedDisplay d=ownedDisplay;if(d!=null)d.close();}
    static final class Packet {
        final byte[] data;final long queued=SystemClock.uptimeMillis();final long videoGeneration;
        final boolean recoveryPoint,initialStatus;
        Packet(byte[] d,long generation){this(d,generation,false);}
        Packet(byte[] d,long generation,boolean recovery){this(d,generation,recovery,false);}
        Packet(byte[] d,long generation,boolean recovery,boolean initial){data=d;videoGeneration=generation;recoveryPoint=recovery;initialStatus=initial;}
    }
    static boolean sameVideoGeneration(long packetGeneration,long currentGeneration){return packetGeneration==currentGeneration;}
    static byte[] packet(int channel,int id,byte[] payload,int timestamp)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream(payload.length+20);DataOutputStream d=new DataOutputStream(b);
        int h=(channel==CMD||channel==TOUCH)?8:12;
        d.writeInt(channel);d.writeInt(payload.length+h);
        if(h==8){if(payload.length>65535)throw new IOException("command too large");d.writeShort(payload.length);d.writeShort(0);d.writeInt(id);}
        else{d.writeInt(payload.length);d.writeInt(timestamp);d.writeInt(id);}
        d.write(payload);return b.toByteArray();
    }
    static final class Frame {
        final int channel,id,timestamp;final byte[] payload;final CarLifeFrameReader.TouchWire touchWire;
        Frame(int c,int i,byte[] p){this(c,i,p,null,0);}
        Frame(int c,int i,byte[] p,CarLifeFrameReader.TouchWire wire){this(c,i,p,wire,0);}
        Frame(int c,int i,byte[] p,CarLifeFrameReader.TouchWire wire,int time){channel=c;id=i;payload=p;touchWire=wire;timestamp=time;}
    }
    static Frame readFrame(DataInputStream in)throws IOException {
        int first;try{first=in.read();}catch(SocketTimeoutException idle){return null;}
        if(first<0)throw new EOFException("at frame boundary");
        // Any failure after this first byte is fatal; a partial header is never resynchronized.
        int channel=(first<<24)|(in.readUnsignedByte()<<16)|(in.readUnsignedByte()<<8)|in.readUnsignedByte();
        int length=in.readInt();int header=(channel==CMD||channel==TOUCH)?8:12;
        if(channel<1||channel>6||length<header||length>MAX_BODY)throw new IOException("invalid outer header");
        byte[] body=new byte[length];in.readFully(body);DataInputStream d=new DataInputStream(new ByteArrayInputStream(body));
        int declared,reserved=0,id,timestamp=0;if(header==8){declared=d.readUnsignedShort();reserved=d.readUnsignedShort();id=d.readInt();}else{declared=d.readInt();timestamp=d.readInt();id=d.readInt();}
        if(declared<0||length!=header+declared||reserved!=0)throw new IOException("inner length/reserved mismatch");
        if((id>>>16)!=channel)throw new IOException("message ID / channel mismatch");
        byte[] payload=new byte[declared];d.readFully(payload);return new Frame(channel,id,payload,null,timestamp);
    }
    static int versionMatchStatus(Map<Integer,Object> fields)throws IOException {
        Proto.requireInts(fields,1,2);
        return Proto.number(fields,1,-1)==1&&Proto.number(fields,2,-1)==0?1:2;
    }
    interface AuthProvider { void requireAuthentication(int messageId)throws IOException; }
    static final AuthProvider AUTH_UNAVAILABLE=new AuthProvider(){public void requireAuthentication(int messageId)throws IOException {
        throw new IOException("AUTH_UNSUPPORTED: no authorized AuthProvider for mandatory message 0x"+Integer.toHexString(messageId));
    }};
    static void rejectMandatoryAuth(int id)throws IOException {
        if(id==0x18048||id==0x1804a||id==0x1804c)AUTH_UNAVAILABLE.requireAuthentication(id);
        if(id==0x1806b||id==0x1806d||id==0x1806f)throw new IOException("ENCRYPTION_UNSUPPORTED: no authorized encryption provider for message 0x"+Integer.toHexString(id));
    }
    static int androidKeyCodeForCarLife(int key){return NativeSessionProtocol.androidKeyCode(key);}
    static MediaCodecDisplaySource.Selection selectOutput(int w,int h,int fps,boolean realtime)throws IOException{
        try{return MediaCodecDisplaySource.select(w,h,fps,realtime);}
        catch(IOException rejected){
            if(!nativeVideoMode.equals("auto")||(w==1280&&h==720))throw rejected;
            logFields("native_mode_fallback","requested_width",w,"requested_height",h,"reason",rejected.toString(),"accepted_width",1280,"accepted_height",720);
            return MediaCodecDisplaySource.select(1280,720,fps,realtime);
        }
    }
    static VideoMode requestedOutput(int w,int h,int fps)throws IOException{
        try{return VideoMode.requested(nativeVideoMode,w,h,fps);}
        catch(IOException invalid){if(!nativeVideoMode.equals("auto"))throw invalid;logFields("native_mode_hint_invalid","width",w,"height",h,"reason",invalid.toString(),"fallback","720p");return VideoMode.of(1280,720,fps);}
    }
    static class Session {
        final long sessionId=++sessionSequence;
        final MdTransport transport; final DataInputStream in;final OutputStream out;
        final ArrayBlockingQueue<Packet> controls=new ArrayBlockingQueue<Packet>(32),videos=new ArrayBlockingQueue<Packet>(8);
        // Encoded native AUs use byte/age limits and decoder-chain recovery.
        // The existing queue remains for SELF/bootstrap and empty PAUSE keepalives.
        final VideoQueue<Packet> nativeVideos=new VideoQueue<Packet>(new VideoQueue.Adapter<Packet>(){
            public int bytes(Packet p){return p.data.length;}
            public long generation(Packet p){return p.videoGeneration;}
            public long queuedAt(Packet p){return p.queued;}
            public boolean recoveryPoint(Packet p){return p.recoveryPoint;}
            public boolean heartbeat(Packet p){return p.data.length==20;}
        });
        volatile VideoRateControl videoRate=new VideoRateControl();
        final Object writerSignal=new Object();
        final PcmAudioSession audio,speech;
        final AudioPolicySources audioPolicy;
        final boolean splitAudio;
        final PlaybackActivity playback;
        final boolean audioRequested=!audioSource.equals("off");
        boolean audioNegotiated,audioAttempted,speechAttempted,preferSpeech;
        volatile boolean audioHuMuted,audioBluetooth;
        volatile boolean audioWireActive;
        volatile boolean speechWireActive,naviWireActive,routeNavigationActive;
        volatile NativeServices services;
        static final class MusicStatus {final long epoch;final boolean active;MusicStatus(long e,boolean a){epoch=e;active=a;}}
        volatile MusicStatus pendingMusicStatus;
        volatile MusicStatus pendingNaviStatus;
        volatile boolean open=true,everNegotiated,closeComplete;volatile String terminalReason;volatile long writingSince;volatile long videoGeneration=0;volatile Process capture;
        volatile MediaCodecDisplaySource mediaSource;
        volatile Packet writingPacket;
        volatile long lastSnapshotUptime,lastVideoEnqueueUptime,lastControlEnqueueUptime,lastSocketWriteUptime,lastSocketWriteDurationMs,socketWrittenPackets,socketWrittenBytes,socketWrittenVideoPackets;
        final AtomicLong snapshotSequence=new AtomicLong(),videoEnqueuedPackets=new AtomicLong(),videoEnqueuedBytes=new AtomicLong(),controlEnqueuedPackets=new AtomicLong(),controlEnqueuedBytes=new AtomicLong(),staleVideoEnqueueDrops=new AtomicLong(),staleVideoWriteDrops=new AtomicLong();
        MediaCodecDisplaySource.Selection codecSelection;ProjectionGeometry geometry;
        volatile PendingVideoInit pendingInit;
        Thread writer,captureThread;boolean version=false,paused=false;volatile boolean initialized=false;int width=1280,height=720;long bytes=0,chunks=0,nativeLogicalSession=1;long touchCount=0,touchRecoveries=0,nativeTouchIngressUptime;int touchDiagnosticSamples=0,unknownTouchDiagnosticSamples=0,touchOrdinaryIngress=0,touchPriorityIngress=0,lastTouchShape=-1;
        NativePhoneVerification phoneVerification;
        int huMajor,huMinor,acceptedFps=30;boolean statisticReplied,moduleStatusSent,featureConfigRecorded;long moduleStatusDue;
        int pendingFps;long pendingFpsGeneration=-1;
        volatile long nativeStartDue;
        long nativePauseHeartbeatDue;
        final InputSink sink;
        Session(Socket s)throws IOException{this(new SocketMdTransport(s));}
        Session(MdTransport t)throws IOException{this(t,new InputSink());}
        static final class AudioInputs {
            final PcmAudioSession.Factory media,speech;final SourceLease mediaLease,speechLease;final AudioPolicySources policy;
            AudioInputs(PcmAudioSession.Factory media){this(media,null,PcmAudioSession.LEASE,null,null);}
            AudioInputs(PcmAudioSession.Factory media,PcmAudioSession.Factory speech,SourceLease ml,SourceLease sl,AudioPolicySources p){this.media=media;this.speech=speech;mediaLease=ml;speechLease=sl;policy=p;}
            static AudioInputs production(){
                if(audioSource.equals("split")){AudioPolicySources p=new AudioPolicySources();return new AudioInputs(p.factory(CarLifeAudio.Lane.MEDIA),p.factory(CarLifeAudio.Lane.TTS),p.lease(CarLifeAudio.Lane.MEDIA),p.lease(CarLifeAudio.Lane.TTS),p);}
                return new AudioInputs(new PcmAudioSession.Factory(){public PcmAudioSession.Source create(){return new RemoteSubmixSource();}});
            }
        }
        Session(MdTransport t,InputSink input)throws IOException{this(t,input,AudioInputs.production(),new PlaybackActivity.AndroidSource());}
        Session(MdTransport t,InputSink input,PcmAudioSession.Factory audioFactory)throws IOException{
            this(t,input,audioFactory,new PlaybackActivity.AndroidSource());
        }
        Session(MdTransport t,InputSink input,PcmAudioSession.Factory audioFactory,PlaybackActivity.Source playbackSource)throws IOException{
            this(t,input,new AudioInputs(audioFactory),playbackSource);
        }
        Session(MdTransport t,InputSink input,AudioInputs sources,PlaybackActivity.Source playbackSource)throws IOException{
            transport=t;in=new DataInputStream(t.input());out=t.output();sink=input;sink.configureNativeTouch(t.isNativeUsb());
            audioPolicy=sources.policy;splitAudio=sources.speech!=null;
            playback=new PlaybackActivity(playbackSource,new PlaybackActivity.Listener(){
                public void changed(long epoch,boolean newMediaStart){playbackChanged(epoch,newMediaStart);}
                public void degraded(long epoch,String detail){if(!open||!playback.sameEpoch(epoch))return;logFields("audio_playback_monitor","session_id",sessionId,"state","degraded","detail",detail,"fallback","basic PCM; resume by explicit MEDIA_PLAY hardkey");maybeStartAudio();musicStatusReply();}
            });
            audio=new PcmAudioSession(sources.media,new PcmAudioSession.Events(){
                public long now(){return SystemClock.uptimeMillis();}
                public void wakeWriter(){wake();}
                public void state(String state,String detail){logFields("audio_source","session_id",sessionId,"state",state,"detail",detail,"source",audioSource);}
            },CarLifeAudio.Lane.MEDIA,sources.mediaLease);
            speech=!splitAudio?null:new PcmAudioSession(sources.speech,new PcmAudioSession.Events(){
                public long now(){return SystemClock.uptimeMillis();}
                public void wakeWriter(){wake();}
                public void state(String state,String detail){logFields("speech_source","session_id",sessionId,"state",state,"detail",detail,"source","split");}
            },CarLifeAudio.Lane.TTS,sources.speechLease);
        }
        String wireMarker(){return transport.isNativeUsb()?(videoSource.equals("mediacodec")?"DIRECT_USB_AOA;source=MediaCodecDisplaySource;video=ANNEXB_AU;timestamp=ENCODER_PTS_MS":"DIRECT_USB_AOA"):(videoSource.equals("mediacodec")?MediaCodecDisplaySource.WIRE_MARKER:"DEV_STREAM_OVER_ADB");}
        void run()throws IOException {
            logFields("connected","session_id",sessionId,"transport",transport.name(),"audio_source_requested",audioSource,"phone_verification","v2_checksum; legacy_RSA_unsupported","mic_encryption","unsupported","wire_marker",wireMarker());
            writer=new Thread(new Runnable(){public void run(){writeLoop();}},"md-writer");writer.start();
            if(bootReadiness!=null)bootReadiness.commandReady(true);
            CarLifeFrameReader nativeReader=transport.isNativeUsb()?new CarLifeFrameReader(in,new CarLifeFrameReader.Diagnostics(){public void accept(CarLifeFrameReader.Header h){
                logFields("wire_header","session_id",sessionId,"reason",h.reason,"channel",h.channel,"outer_length",h.outerLength,"header_length",h.headerLength,"declared_length",h.declaredLength,"reserved",h.reserved,"message_id",h.messageId,"available_length",h.availableLength,"header_hex",h.headerHex);
            }}):null;
            while(open&&running){
                nativeProgress();
                Frame f;
                try{if(nativeReader==null)f=readFrame(in);else{CarLifeFrameReader.Frame n=nativeReader.readNext();f=n==null?null:new Frame(n.channel,n.id,n.payload,n.touchWire,n.timestamp);}}catch(EOFException e){noteTerminal("peer_eof");logFields("peer_eof","session_id",sessionId,"logical_session",nativeLogicalSession,"ever_negotiated",everNegotiated,"detail","disconnect or truncated frame: "+e.getMessage());break;}
                if(f==null)continue;
                log("rx","channel="+f.channel+" id=0x"+Integer.toHexString(f.id)+" bytes="+f.payload.length);
                dispatch(f.channel,f.id,f.payload,f.touchWire,f.timestamp);
            }
        }
        boolean nativeCompatibility(){return transport.isNativeUsb()&&huMajor>=2;}
        void closeServices(){NativeServices old;synchronized(audio.queue){old=services;services=null;routeNavigationActive=false;}if(old!=null)old.close();}
        void updateRouteStatus(){NativeServices s=services;boolean active=s!=null&&s.routeNaviWanted();synchronized(audio.queue){if(s!=services||routeNavigationActive==active)return;routeNavigationActive=active;pendingNaviStatus=new MusicStatus(audio.queue.epoch(),speechWireActive||active);}wake();}
        void nativeProgress()throws IOException {
            maybeStartAudio();
            updateRouteStatus();
            if(!nativeCompatibility()||!version||!open)return;
            long now=SystemClock.uptimeMillis();
            adaptVideo(now);
            completeFrameRateChange();
            if(!moduleStatusSent&&moduleStatusDue>0&&now>=moduleStatusDue){sendWithTimestamp(CMD,0x10026,currentModules(),-1,0,true);moduleStatusSent=true;}
            // Some v2 HUs omit START (HeadSwap's observed VW flow). Read-loop ownership
            // plus the generation/RESET cancellation prevents a second concurrent source.
            maybeSendNativePauseHeartbeat(now);
            synchronized(this){if(nativeFallbackReady(now)){nativeStartDue=0;log("native_start_fallback","no VIDEO_START after INIT_DONE; reference-compatible 1500ms fallback");startCapture();}}
        }
        synchronized void maybeSendNativePauseHeartbeat(long now)throws IOException {
            if(!open||!version||!nativeCompatibility()||!initialized||!paused||pendingInit!=null
                ||capture!=null||mediaSource!=null||nativePauseHeartbeatDue<=0||now<nativePauseHeartbeatDue)return;
            // Protocol heartbeat, not an encoded AU. No source/lease/PTS/decoder generation change.
            sendWithTimestamp(VIDEO,0x20001,NativeSessionProtocol.EMPTY,videoGeneration,(int)now);
            nativePauseHeartbeatDue=now+2000;
            logFields("native_pause_keepalive","session_id",sessionId,"generation",videoGeneration,
                "payload_bytes",0,"encoded_access_unit",false,"timestamp_kind","uptime_ms_heartbeat");
        }
        boolean nativeFallbackReady(long now){return open&&nativeCompatibility()&&nativeStartDue>0&&now>=nativeStartDue&&initialized&&!paused&&capture==null&&mediaSource==null;}
        void afterNativeInit()throws IOException {
            if(nativeCompatibility()){send(CMD,0x1001b,NativeSessionProtocol.EMPTY);nativeStartDue=SystemClock.uptimeMillis()+1500;}
        }
        synchronized boolean repeatNativeInit()throws IOException {
            if(!nativeCompatibility())return false;
            if(initialized){send(CMD,0x10008,Proto.ints(1,width,2,height,3,acceptedFps));log("native_init_repeat","ACK existing accepted mode; source and fallback deadline unchanged");return true;}
            if(pendingInit!=null){log("native_init_repeat","retain original pending capability probe; no premature ACK");return true;}
            return false;
        }
        void wake(){synchronized(writerSignal){writerSignal.notifyAll();}}
        void clearVideoQueues(){videos.clear();nativeVideos.clear();}
        void adaptVideo(long now){
            MediaCodecDisplaySource source=mediaSource;
            if(source==null||source.generation!=videoGeneration||!nativeCompatibility())return;
            if(nativeVideos.consumeSyncRequest(now))source.requestKeyFrame();
            int bitrate=videoRate.bitrateFor(nativeVideos.snapshot(now),now);
            if(bitrate!=0)source.requestBitrate(bitrate);
        }
        boolean requestSourceFrameRate(int fps){MediaCodecDisplaySource source=mediaSource;return source!=null&&source.requestFrameRate(fps);}
        int sourceFrameRate(){MediaCodecDisplaySource source=mediaSource;return source==null?0:source.appliedFrameRate();}
        synchronized void requestFrameRateChange(int fps)throws IOException{
            if(!nativeCompatibility()||!initialized||fps<1||fps>30||!requestSourceFrameRate(fps)){
                logFields("native_fps_change_unavailable","session_id",sessionId,"requested_fps",fps,"source_running",mediaSource!=null,"ack_sent",false);return;
            }
            pendingFps=fps;pendingFpsGeneration=videoGeneration;
            completeFrameRateChange();
        }
        synchronized void completeFrameRateChange()throws IOException{
            if(pendingFps==0)return;
            if(!open||pendingFpsGeneration!=videoGeneration){pendingFps=0;return;}
            if(sourceFrameRate()!=pendingFps)return;
            int applied=pendingFps;pendingFps=0;acceptedFps=applied;
            send(CMD,0x1000d,Proto.ints(1,applied));
            logFields("native_fps_changed","session_id",sessionId,"generation",videoGeneration,"applied_fps",applied,"ack_after_egl_apply",true);
        }
        void sendAccessUnit(AvcAccessUnits.Au au,long generation)throws IOException{
            if(!nativeCompatibility()){sendWithTimestamp(VIDEO,0x20001,au.bytes,generation,(int)(au.ptsUs/1000));return;}
            if(!open||generation!=videoGeneration){staleVideoEnqueueDrops.incrementAndGet();return;}
            Packet p=new Packet(packet(VIDEO,0x20001,au.bytes,(int)(au.ptsUs/1000)),generation,au.idr);
            long now=SystemClock.uptimeMillis();
            if(nativeVideos.offer(p,videoGeneration,now)){
                lastVideoEnqueueUptime=now;videoEnqueuedPackets.incrementAndGet();videoEnqueuedBytes.addAndGet(p.data.length);wake();
            }
            adaptVideo(now);
        }
        synchronized void maybeStartAudio(){
            if(!open||!version||!audioRequested||!audioNegotiated)return;
            playback.start();
            if(speech!=null){
                if(!playback.speechActive()){if(speechAttempted||speech.active()){speech.stop();speechAttempted=false;}}
                else if(!speechAttempted&&speech.retired())speechAttempted=speech.start();
            }
            // Wait for the first snapshot; a known idle player must not seize
            // the HU media source with INIT/silent PCM. An unavailable optional
            // observer explicitly reports outputActive=true for basic fallback.
            if(!mediaPlaybackActive()){
                if(audioAttempted||audio.active()){audio.stop();audioAttempted=false;}
                return;
            }
            if(audioHuMuted||audioBluetooth||audioAttempted||!audio.retired())return;
            audioAttempted=audio.start();
        }
        boolean mediaPlaybackActive(){return splitAudio?playback.mediaActive():playback.outputActive();}
        synchronized void playbackChanged(long epoch,boolean newMediaStart){
            if(!open||!playback.current(epoch))return;
            if(audioHuMuted&&newMediaStart){audioHuMuted=false;audioAttempted=false;log("audio_player_resume","new Android media player start after HU mute; PCM level is not a resume signal");}
            maybeStartAudio();
            musicStatusReply();
        }
        synchronized void explicitMediaPlay(){
            // A real Play key may resume; MUSIC=1 is only a status request.
            audioHuMuted=false;audioAttempted=false;maybeStartAudio();musicStatusReply();
        }
        synchronized void explicitMediaPause(){pauseAudio(false);}
        synchronized void muteAudio(){pauseAudio(true);}
        private void pauseAudio(boolean injectPause){
            boolean first=!audioHuMuted;audioHuMuted=true;audio.stop();
            if(first){playback.rebase();if(injectPause&&audioRequested&&audioNegotiated){NativeServices s=services;if(s==null||!s.onHardkey(0x20))sink.mediaPause();}}
            musicStatusReply();
        }
        synchronized void resetAudio(){
            playback.close();audio.stop();if(speech!=null){speech.stop();speech.queue.clear();}
            synchronized(audio.queue){audio.queue.clear();audioWireActive=false;pendingMusicStatus=null;pendingNaviStatus=null;}
            audioNegotiated=false;audioAttempted=false;speechAttempted=false;audioHuMuted=false;audioBluetooth=false;speechWireActive=false;naviWireActive=false;routeNavigationActive=false;
        }
        void musicStatusReply(){synchronized(audio.queue){pendingMusicStatus=new MusicStatus(audio.queue.epoch(),audioWireActive&&!audioHuMuted&&!audioBluetooth&&mediaPlaybackActive());}wake();}
        void naviStatusReply(){synchronized(audio.queue){pendingNaviStatus=new MusicStatus(audio.queue.epoch(),speechWireActive||routeNavigationActive);}wake();}
        PcmAudioSession audioOwner(CarLifeAudio.Item item){return item.channel==CarLifeAudio.Lane.TTS.channel?speech:audio;}
        CarLifeAudio.Item pollAudio(long now){
            CarLifeAudio.Item item=null;
            if(preferSpeech&&speech!=null)item=speech.queue.poll(now);
            if(item==null)item=audio.queue.poll(now);
            if(item==null&&speech!=null)item=speech.queue.poll(now);
            if(item!=null)preferSpeech=item.channel!=CarLifeAudio.Lane.TTS.channel;
            return item;
        }
        boolean audioQueuesEmpty(){return audio.queue.size()==0&&(speech==null||speech.queue.size()==0);}
        static byte[] moduleStatus(int module,boolean active)throws IOException{
            return NativeSessionProtocol.join(Proto.ints(1,1),nestedModule(module,active));
        }
        static byte[] nestedModule(int module,boolean active)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();NativeSessionProtocol.bytes(b,2,Proto.ints(1,module,2,active?1:0));return b.toByteArray();}
        void audioWritten(CarLifeAudio.Item sound){
            if(sound.channel==CarLifeAudio.Lane.TTS.channel){
                synchronized(audio.queue){synchronized(speech.queue){if(!speech.queue.current(sound))return;speech.queue.wrote();if(sound.id!=CarLifeAudio.Lane.TTS.initId&&sound.id!=CarLifeAudio.Lane.TTS.stopId)return;speechWireActive=sound.id==CarLifeAudio.Lane.TTS.initId;
                    pendingNaviStatus=new MusicStatus(audio.queue.epoch(),speechWireActive||routeNavigationActive);}}
                wake();logFields("speech_wire_state","session_id",sessionId,"active",speechWireActive,"message_id",sound.id);return;
            }
            synchronized(audio.queue){if(!audio.queue.current(sound))return;
                audio.queue.wrote();if(sound.id!=CarLifeAudio.INIT&&sound.id!=CarLifeAudio.STOP)return;
                audioWireActive=sound.id==CarLifeAudio.INIT;
                pendingMusicStatus=new MusicStatus(sound.epoch,audioWireActive&&!audioHuMuted&&!audioBluetooth&&mediaPlaybackActive());
            }
            logFields("audio_wire_state","session_id",sessionId,"active",sound.id==CarLifeAudio.INIT,"message_id",sound.id,"pcm_format","48000/stereo/s16le");
        }
        void audioModuleControl(Map<Integer,Object> fields)throws IOException{
            Proto.requireInts(fields,1,2);int module=Proto.number(fields,1,-1),status=Proto.number(fields,2,-1);
            if(module==2&&(status==0||status==1)){naviStatusReply();return;}
            if(module!=3||!(status==0||status==1)){log("audio_module_control","unsupported module/status "+module+"/"+status);return;}
            if(status==0)muteAudio(); // status=1 asks for actual state, not autoplay.
            musicStatusReply();
            logFields("audio_module_control","session_id",sessionId,"music_requested",status==1,"source_enabled",audioRequested,"bluetooth_audio_selected",audioBluetooth);
        }
        void audioFeatureConfig(byte[] payload){
            try{Integer mode=CarLifeAudio.transmissionMode(payload);if(mode==null)return;
                if(mode!=0&&mode!=1)logFields("audio_transport_unknown","session_id",sessionId,"mode",mode,"policy","only 1 selects Bluetooth; other values use USB as in HeadSwap");
                boolean wasBluetooth=audioBluetooth;audioBluetooth=mode==1;
                if(audioBluetooth)audio.stop();else if(wasBluetooth){audioAttempted=false;maybeStartAudio();}
                musicStatusReply();
                logFields("audio_transport_selected","session_id",sessionId,"mode",audioBluetooth?"bluetooth_no_usb_pcm":"usb_pcm","source_enabled",audioRequested);
            }catch(IOException e){logFields("audio_feature_error","session_id",sessionId,"error",e.toString());}
        }
        byte[] currentModules()throws IOException{NativeServices s=services;return NativeSessionProtocol.initialModules(speechWireActive||routeNavigationActive,audioWireActive&&!audioHuMuted&&!audioBluetooth&&mediaPlaybackActive(),s!=null&&s.voiceActive());}
        void writeLoop(){boolean preferAudio=true;CarLifeAudio.Item deferredSpeech=null;try{while(open){
            updateRouteStatus();NativeServices serviceOwner=null;
            Packet p=controls.poll();boolean video=false;CarLifeAudio.Item sound=null;
            MusicStatus music=null,navi=null;
            if(p!=null&&p.initialStatus){synchronized(audio.queue){p=new Packet(packet(CMD,0x10026,currentModules(),0),-1);navi=new MusicStatus(audio.queue.epoch(),speechWireActive||routeNavigationActive);}}
            if(p==null){synchronized(audio.queue){music=pendingMusicStatus;pendingMusicStatus=null;}
                if(music!=null){if(music.epoch!=audio.queue.epoch())continue;p=new Packet(packet(CMD,0x10026,CarLifeAudio.musicStatus(music.active),0),-1);}}
            if(p==null){synchronized(audio.queue){navi=pendingNaviStatus;pendingNaviStatus=null;}
                if(navi!=null){if(navi.epoch!=audio.queue.epoch())continue;p=new Packet(packet(CMD,0x10026,moduleStatus(2,navi.active),0),-1);}}
            if(p==null){serviceOwner=services;NativeServices.Output update=serviceOwner==null?null:serviceOwner.poll();if(update!=null)p=new Packet(packet(CMD,update.id,update.payload,0),-1);else serviceOwner=null;}
            if(p==null){
                if(deferredSpeech!=null){sound=deferredSpeech;deferredSpeech=null;}
                else if(preferAudio)sound=pollAudio(SystemClock.uptimeMillis());
                if(sound==null){p=videos.poll();if(p==null)p=nativeVideos.poll(videoGeneration,SystemClock.uptimeMillis());video=p!=null;adaptVideo(SystemClock.uptimeMillis());}
                if(p==null&&sound==null)sound=pollAudio(SystemClock.uptimeMillis());
                if(sound!=null){
                    if(!audioOwner(sound).queue.current(sound))continue;
                    if(sound.channel==4&&sound.id==CarLifeAudio.Lane.TTS.initId&&!naviWireActive){
                        // NAVI activation is on the same writer before TTS INIT,
                        // even if the source callback races the queue wakeup.
                        deferredSpeech=sound;sound=null;navi=new MusicStatus(audio.queue.epoch(),true);
                        p=new Packet(packet(CMD,0x10026,moduleStatus(2,true),0),-1);
                    }else p=new Packet(packet(sound.channel,sound.id,sound.payload,sound.timestamp),-1);
                    preferAudio=false;
                }
                else if(video)preferAudio=true;
            }
            if(p==null){synchronized(writerSignal){if(open&&controls.isEmpty()&&videos.isEmpty()&&nativeVideos.isEmpty()&&audioQueuesEmpty()&&pendingMusicStatus==null&&pendingNaviStatus==null)writerSignal.wait(50);}continue;}
            if(sound!=null&&!audioOwner(sound).queue.current(sound))continue;
            if(music!=null&&music.epoch!=audio.queue.epoch())continue;
            if(navi!=null&&navi.epoch!=audio.queue.epoch())continue;
            if(serviceOwner!=null&&serviceOwner!=services)continue;
            if(video&&!sameVideoGeneration(p.videoGeneration,videoGeneration)){staleVideoWriteDrops.incrementAndGet();log("video_epoch_drop","discard stale source generation before write");continue;}
            if(video&&SystemClock.uptimeMillis()-p.queued>2000)throw new IOException("video queue latency >2s; reconnect for IDR");
            writingPacket=p;writingSince=SystemClock.uptimeMillis();VideoRateControl rateAtWrite=videoRate;long writeStartedNs=System.nanoTime();transport.writePacket(p.data);
            rateAtWrite.onWrite(video?Math.max(0,p.data.length-20):0,Math.max(0,System.nanoTime()-writeStartedNs),SystemClock.uptimeMillis());
            long ended=SystemClock.uptimeMillis();lastSocketWriteDurationMs=ended-writingSince;lastSocketWriteUptime=ended;socketWrittenPackets++;socketWrittenBytes+=p.data.length;if(video)socketWrittenVideoPackets++;writingSince=0;writingPacket=null;
            if(sound!=null)audioWritten(sound);
            if(navi!=null&&navi.epoch==audio.queue.epoch())naviWireActive=navi.active;
            adaptVideo(ended);
        }}catch(Exception e){if(open)logFields("write_error","session_id",sessionId,"logical_session",nativeLogicalSession,"ever_negotiated",everNegotiated,"error",e.toString());close("write_error");}}
        void maybeReportProgress(){long now=SystemClock.uptimeMillis();if(now-lastSnapshotUptime>=PipelineProgress.PERIOD_MS){lastSnapshotUptime=now;reportProgress("periodic",mediaSource);}}
        void reportProgress(String reason,MediaCodecDisplaySource source){
            long now=SystemClock.uptimeMillis(),writeStarted=writingSince;Packet inFlight=writingPacket;
            PipelineProgress.Queue v=PipelineProgress.queue(videos,now),c=PipelineProgress.queue(controls,now);
            VideoQueue.Snapshot nv=nativeVideos.snapshot(now);
            if(nativeCompatibility()&&videoSource.equals("mediacodec"))v=new PipelineProgress.Queue(v.items+nv.items,v.wireBytes+nv.bytes,v.items+nv.items==0?null:Math.max(v.oldestAgeMs==null?0:v.oldestAgeMs,nv.oldestAgeMs));
            ArrayList<Object> f=new ArrayList<Object>(96);
            Collections.addAll(f,"session_id",sessionId,"active_generation",videoGeneration,"source_generation",source==null?null:source.generation,"reason",reason,"snapshot_sequence",snapshotSequence.incrementAndGet(),"sample_uptime_ms",now,"snapshot_consistency","best_effort_cross_thread; queue bytes include complete wire headers","session_open",open,
                "video_queue_items",v.items,"video_queue_wire_bytes",v.wireBytes,"video_queue_oldest_age_ms",v.oldestAgeMs,"control_queue_items",c.items,"control_queue_wire_bytes",c.wireBytes,"control_queue_oldest_age_ms",c.oldestAgeMs,
                "video_enqueued_packets",videoEnqueuedPackets.get(),"video_enqueued_wire_bytes",videoEnqueuedBytes.get(),"control_enqueued_packets",controlEnqueuedPackets.get(),"control_enqueued_wire_bytes",controlEnqueuedBytes.get(),"video_enqueue_last_uptime_ms",PipelineProgress.seen(lastVideoEnqueueUptime),"video_enqueue_age_ms",PipelineProgress.age(now,lastVideoEnqueueUptime),
                "socket_written_packets",socketWrittenPackets,"socket_written_video_packets",socketWrittenVideoPackets,"socket_written_wire_bytes",socketWrittenBytes,"socket_write_last_uptime_ms",PipelineProgress.seen(lastSocketWriteUptime),"socket_write_progress_age_ms",PipelineProgress.age(now,lastSocketWriteUptime),"socket_write_last_duration_ms",lastSocketWriteDurationMs,
                "socket_inflight_started_uptime_ms",PipelineProgress.seen(writeStarted),"socket_inflight_age_ms",PipelineProgress.age(now,writeStarted),"socket_inflight_wire_bytes",inFlight==null?0:inFlight.data.length,"socket_inflight_generation",inFlight==null?null:inFlight.videoGeneration,"stale_enqueue_drops",staleVideoEnqueueDrops.get(),"stale_write_drops",staleVideoWriteDrops.get(),
                "video_queue_capacity",8,"control_queue_capacity",32,"video_queue_age_budget_ms",nativeCompatibility()?VideoQueue.MAX_AGE_MS:2000,"socket_write_stall_budget_ms",3000,
                "native_video_queue_bytes_budget",VideoQueue.MAX_BYTES,"native_video_drops",nv.dropped,"native_video_waiting_idr",nv.awaitingIdr,"native_video_pressure_events",nv.pressureEvents,"native_video_target_bitrate",videoRate.requestedBitrate(),"native_video_measured_drain_bytes_per_second",videoRate.drainBytesPerSecond());
            appendAccessoryProgress(f);
            audio.queue.appendProgress(f);Collections.addAll(f,"audio_source_active",audio.active(),"audio_wire_active",audioWireActive,"audio_source_error",audio.error(),"audio_playback_known",playback.known(),"audio_output_active",playback.outputActive());
            if(speech!=null){speech.queue.appendProgress(f,"speech");Collections.addAll(f,"speech_source_active",speech.active(),"speech_wire_active",speechWireActive,"speech_source_error",speech.error(),"speech_playback_active",playback.speechActive(),"audio_policy",audioPolicy==null?"injected":audioPolicy.diagnostics());}
            if(source!=null)source.appendProgress(f,now);
            logFields("md_progress",f.toArray(new Object[0]));
        }
        void appendAccessoryProgress(List<Object> fields){
            if(!(transport instanceof AccessoryTransport))return;
            AccessoryTransport accessory=(AccessoryTransport)transport;
            Collections.addAll(fields,"accessory_kernel_read_calls",accessory.kernelReadCalls(),
                "accessory_kernel_read_inflight",accessory.kernelReadInFlight(),
                "accessory_reader_failure",accessory.readerFailure());
        }
        boolean closeIfWriteStalled(){
            // Completion clears this volatile value. Never subtract a second read that may be zero.
            final long observedSince=writingSince;
            if(observedSince>0&&SystemClock.uptimeMillis()-observedSince>3000&&writingSince==observedSince){
                logFields("backpressure","session_id",sessionId,"write_started_uptime_ms",observedSince,"detail","same write stalled >3s; closing session");close("write_stall");return true;
            }
            return false;
        }
        void send(int ch,int id,byte[] data)throws IOException{send(ch,id,data,-1);}
        void send(int ch,int id,byte[] data,long generation)throws IOException{sendWithTimestamp(ch,id,data,generation,(int)SystemClock.uptimeMillis());}
        void sendWithTimestamp(int ch,int id,byte[] data,long generation,int timestamp)throws IOException{sendWithTimestamp(ch,id,data,generation,timestamp,false);}
        void sendWithTimestamp(int ch,int id,byte[] data,long generation,int timestamp,boolean initialStatus)throws IOException{try{
            if(ch==VIDEO&&!sameVideoGeneration(generation,videoGeneration)){staleVideoEnqueueDrops.incrementAndGet();log("video_epoch_drop","discard stale source generation before enqueue");return;}
            Packet p=new Packet(packet(ch,id,data,timestamp),generation,false,initialStatus);
            if(!(ch==VIDEO?videos:controls).offer(p,1000,TimeUnit.MILLISECONDS))throw new IOException("bounded send queue exhausted");
            wake();
            long enqueuedAt=SystemClock.uptimeMillis();
            if(ch==VIDEO){lastVideoEnqueueUptime=enqueuedAt;videoEnqueuedPackets.incrementAndGet();videoEnqueuedBytes.addAndGet(p.data.length);}else{lastControlEnqueueUptime=enqueuedAt;controlEnqueuedPackets.incrementAndGet();controlEnqueuedBytes.addAndGet(p.data.length);}
            if(ch!=VIDEO)log("tx","channel="+ch+" id=0x"+Integer.toHexString(id)+" bytes="+data.length);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
        void dispatch(int ch,int id,byte[] p)throws IOException {dispatch(ch,id,p,null);}
        void dispatch(int ch,int id,byte[] p,CarLifeFrameReader.TouchWire wire)throws IOException {
            dispatch(ch,id,p,wire,0);
        }
        void dispatch(int ch,int id,byte[] p,CarLifeFrameReader.TouchWire wire,int timestamp)throws IOException {
            if(ch==5&&id==0x58001){NativeServices s=services;if(version&&s!=null)s.onMic(p,timestamp);return;}
            // A malformed/unsupported touch or Android injection refusal is local to input.
            // Framing, command negotiation and media/transport failures remain terminal.
            if(ch==TOUCH&&transport.isNativeUsb()){
                nativeTouchIngressUptime=SystemClock.uptimeMillis();
                ++touchCount;
                if(wire!=null)recordTouchWire(id,wire);
                Map<Integer,Object> inputFields=Collections.<Integer,Object>emptyMap();boolean ingressRecorded=false;
                try{inputFields=id==0x6800e?inputFields:Proto.parse(p);recordTouchIngress(id,p,inputFields);ingressRecorded=true;dispatchTouch(id,p,inputFields);}
                catch(IOException|RuntimeException e){if(!ingressRecorded)recordTouchIngress(id,p,inputFields);sink.recoverNative();recordTouchRecovery(id,p,inputFields,"cancel_wait_down",e.getClass().getSimpleName()+": "+e.getMessage());}
                return;
            }
            if(ch==CMD&&id==0x18052&&transport.isNativeUsb()&&!featureConfigRecorded){featureConfigRecorded=true;logFields("native_feature_config","session_id",sessionId,"logical_session",nativeLogicalSession,"message_id",id,"payload_length",p.length,"captured_bytes",Math.min(p.length,2048),"payload_hex",Session.hexPrefix(p,2048),"truncated",p.length>2048,"validation","raw HU_FEATURE_CONFIG_RESPONSE before protobuf validation; decode offline");}
            Map<Integer,Object> fields=(ch==CMD||(ch==TOUCH&&id!=0x6800e))?Proto.parse(p):Collections.<Integer,Object>emptyMap();
            if(ch==CMD){
                if(transport.isNativeUsb()&&id==0x18048){
                    if(!version||phoneVerification==null)throw new IOException("phone verification requires sent MD_INFO");
                    send(CMD,0x10049,phoneVerification.response(p));
                    logFields("phone_verification_response","session_id",sessionId,"logical_session",nativeLogicalSession,"branch","2.0_checksum","identity","exact_sent_MD_INFO","hu_acceptance","await_result");return;
                }
                if(transport.isNativeUsb()&&id==0x1804a){Proto.requireInts(fields,1);int result=Proto.number(fields,1,-1);logFields("hu_authen_result","session_id",sessionId,"value",result,"cryptographic_verification_performed_by_md",false);if(result!=1)throw new IOException("HU_AUTHEN_RESULT reported rejection: "+result);return;}
                rejectMandatoryAuth(id);
                if(id==0x18001){
                    if(version){
                        if(nativeCompatibility()&&!paused&&Proto.number(fields,1,-1)==huMajor&&Proto.number(fields,2,-1)==huMinor){log("version_repeat","same native HU version; retain negotiation, source and pending INIT; no second MATCH");return;}
                        if(!transport.isNativeUsb()||(!paused&&!(bootNative&&!initialized)))throw new IOException("duplicate version outside paused native session boundary");
                        if(capture!=null||mediaSource!=null)throw new IOException("native session boundary before source cleanup");
                        // Closing a host libusb claim is not a physical device detach. A fresh
                        // genuine HU_VERSION after PAUSE starts negotiation on the same fd.
                        // One writer preserves old in-flight VIDEO -> VERSION_MATCH ordering.
                        stopCapture();closeServices();resetAudio();sink.cancel();controls.clear();clearVideoQueues();
                        version=false;initialized=false;paused=false;codecSelection=null;geometry=null;nativeLogicalSession++;
                        logFields("native_session_boundary","session_id",sessionId,"logical_session",nativeLogicalSession,"generation",videoGeneration,"reason","fresh HU_VERSION after source-stopped VIDEO_PAUSE");
                    }
                    int match=transport.isNativeUsb()?NativeSessionProtocol.versionStatus(fields):versionMatchStatus(fields);
                    huMajor=Proto.number(fields,1,0);huMinor=Proto.number(fields,2,0);nativePauseHeartbeatDue=0;statisticReplied=false;moduleStatusSent=false;moduleStatusDue=0;
                    send(CMD,0x10002,Proto.ints(1,match));version=match==1;if(version)everNegotiated=true;
                    logFields("protocol_version","session_id",sessionId,"major",huMajor,"minor",huMinor,"match_status",match,"profile",nativeCompatibility()?"NATIVE_REFERENCE_COMPAT":"SELF_V1");
                    phoneVerification=null;
                    if(version&&nativeCompatibility()){
                        byte[] mdInfo=NativeSessionProtocol.deviceInfo(wireMarker());
                        send(CMD,0x1001b,NativeSessionProtocol.EMPTY);send(CMD,0x10018,NativeSessionProtocol.EMPTY);send(CMD,0x10004,mdInfo);
                        phoneVerification=new NativePhoneVerification(mdInfo);moduleStatusDue=SystemClock.uptimeMillis()+500;
                        if(nativeServicesEnabled){services=new NativeServices(voiceRecognizer);services.start();}
                    }
                    if(!version)log("version_rejected","invalid version or unsupported development profile");return;
                }
                if(!version)throw new IOException("version required");
                NativeServices nativeServices=services;
                if(nativeServices!=null){
                    if(id==0x18003)nativeServices.onHuInfo(p);
                    try{if(nativeServices.onCommand(id,p))return;}
                    catch(IOException|RuntimeException e){logFields("native_service_command_error","message_id",id,"error",e.toString());if(id==0x18043||id==0x18045||id==0x18046)return;}
                }
                if(id==0x18028){audioModuleControl(fields);return;}
                if(id==0x1800e){muteAudio();log("audio_pause_media","HU paused USB PCM; Android MEDIA_PAUSE requested");return;}
                if(id==0x18052){audioFeatureConfig(p);log("native_optional_info","feature config received");return;}
                if(id==0x18003){if(nativeCompatibility()){log("hu_info","received; MD_INFO already sent with VERSION");return;}send(CMD,0x10004,Proto.strings(1,"Android",7,"hulab-md",14,"CarLife MD video/input pilot",15,wireMarker()));return;}
                if(nativeCompatibility()&&id==0x18027){if(!statisticReplied){
                    // MD accepts the HU without a challenge of its own. This is not an
                    // answer to HU_AUTHEN_REQUEST; that distinct exchange stays explicit.
                    send(CMD,0x1004b,Proto.ints(1,1));send(CMD,0x10051,NativeSessionProtocol.multitouchFeatureRequest());send(CMD,0x10031,NativeSessionProtocol.EMPTY);statisticReplied=true;
                    log("native_statistic_handshake","HU accepted by MD; MULTI_TOUCH=1 feature request/car-data requests sent; no cryptographic proof claimed");}return;}
                if(nativeCompatibility()&&id==0x18043){send(CMD,0x10044,NativeSessionProtocol.subscribeDone(p));log("native_metadata_subscription","responded; navigation/media metadata unavailable, video/input unaffected");return;}
                if(nativeCompatibility()&&(id==0x18052||id==0x18045||id==0x18046)){log("native_optional_info","received command 0x"+Integer.toHexString(id));return;}
                if(id==0x18007){if(repeatNativeInit())return;if(nativeCompatibility())Proto.requireInts(fields,1,2);else Proto.requireInts(fields,1,2,3);if(initialized||pendingInit!=null)throw new IOException("duplicate init; reset first");int w=Proto.number(fields,1,0),h=Proto.number(fields,2,0),fps=Proto.number(fields,3,0);
                    if(nativeCompatibility()){int requested=fps,requestedWidth=w,requestedHeight=h;fps=NativeSessionProtocol.fps(fps);VideoMode selectedMode=requestedOutput(w,h,fps);w=selectedMode.width;h=selectedMode.height;logFields("native_fps_negotiation","requested",requested,"accepted",fps,"policy","HeadSwap automatic 1..30 else30");logFields("native_output_mode","session_id",sessionId,"requested_width",requestedWidth,"requested_height",requestedHeight,"accepted_width",w,"accepted_height",h,"source_width",displayWidth,"source_height",displayHeight,"policy",nativeVideoMode+"; capability and actual SPS validated; source density unchanged");}
                    if(nativeCompatibility())VideoMode.of(w,h,fps);else if(w<320||w>1920||h<180||h>1080||(w&1)!=0||(h&1)!=0||fps<1||fps>60)throw new IOException("unsupported video dimensions/fps hint");
                    if(bootNative){pendingInit=new PendingVideoInit(w,h,fps,videoGeneration);logFields("boot_media_init_pending","session_id",sessionId,"generation",videoGeneration,"width",w,"height",h,"fps",fps,"wire_ack_sent",false);return;}
                    if(videoSource.equals("mediacodec")){codecSelection=selectOutput(w,h,fps,false);w=codecSelection.width;h=codecSelection.height;}
                    geometry=new ProjectionGeometry(displayWidth,displayHeight,w,h,0);sink.cancel();width=w;height=h;acceptedFps=fps;initialized=true;send(CMD,0x10008,Proto.ints(1,w,2,h,3,fps));afterNativeInit();log("video_initialized",videoSource.equals("mediacodec")?"MediaCodecDisplaySource; mode-specific AVC level; actual PTS/flags/CSD in codec logs":"BootstrapDisplaySource; fps is negotiation hint, not controlled encoder rate; PTS unavailable");return;}
                if(id==0x18009){if(!initialized)throw new IOException("init required");nativeStartDue=0;nativePauseHeartbeatDue=0;if(capture!=null||mediaSource!=null){if(nativeCompatibility()&&!paused){log("native_start_repeat","source already running; preserve source lease");return;}throw new IOException("duplicate start or previous source not stopped");}paused=false;startCapture();return;}
                if(id==0x1800a){sink.cancel();stopCapture();paused=true;nativePauseHeartbeatDue=nativeCompatibility()?SystemClock.uptimeMillis()+2000:0;log("paused","capture stop requested; next START only after owned source stopped");return;}
                if(id==0x1800b){
                    if(nativeCompatibility()&&initialized){MediaCodecDisplaySource source=mediaSource;boolean requested=source!=null&&source.requestKeyFrame();logFields("native_keyframe_reset","session_id",sessionId,"generation",videoGeneration,"requested",requested,"source_preserved",true);return;}
                    stopCapture();initialized=false;paused=false;nativePauseHeartbeatDue=0;sink.cancel();log("reset","video state reset; input canceled");return;}
                if(id==0x1800c){Proto.requireInts(fields,1);requestFrameRateChange(Proto.number(fields,1,0));return;}
                log("unsupported","command0x"+Integer.toHexString(id)+" no fabricated success ACK");return;
            }
            if(ch==TOUCH){dispatchTouch(id,p,fields);return;}
            log("unsupported","media/audio/VR channel="+ch+" payload ignored; no playback or injection claimed");
        }
        void recordTouchWire(int id,CarLifeFrameReader.TouchWire w){
            int reserved=((w.innerHeader[2]&255)<<8)|(w.innerHeader[3]&255);
            logFields("native_touch_wire","session_id",sessionId,"logical_session",nativeLogicalSession,"input_count",touchCount,"message_id",id,"inner_header_hex",Session.hexPrefix(w.innerHeader,8),"reserved_u16",reserved,"reserved_byte2",w.innerHeader[2]&255,"reserved_byte3",w.innerHeader[3]&255,"declared_length",w.declaredLength,"available_length",w.availableLength,"delivered_length",w.deliveredLength,"available_payload_hex",Session.hexPrefix(w.availableBodyPrefix,CarLifeFrameReader.TouchWire.PREFIX_BYTES),"captured_bytes",w.availableBodyPrefix.length,"truncated",w.availableBodyPrefix.length<w.availableLength,"inner_sha256",w.innerSha256,"interpretation","opaque inner header; available body before min-length delivery; before touch protobuf/action validation");
        }
        void checkInputTimeout(){if(!transport.isNativeUsb())sink.cancelIfIdle();}
        void dispatchTouch(int id,byte[] p,Map<Integer,Object> fields)throws IOException {
            if(!transport.isNativeUsb())++touchCount;
            if(!version||!initialized)throw new IOException("touch requires initialized session");
            if(!transport.isNativeUsb()&&touchCount>200000)throw new IOException("input count limit");
            if(id==0x68001||id==0x6800e||(transport.isNativeUsb()&&TouchFrame.isLegacyStream(id))){sink.wire(id,p,fields,geometry,videoGeneration,this);return;}
            if(id==0x68008){Proto.requireInts(fields,1);int key=Proto.number(fields,1,-1);NativeServices s=services;boolean handled=s!=null&&s.onHardkey(key);if(!handled&&androidKeyCodeForCarLife(key)>=0){sink.hardkey(key);handled=true;}if(handled){if(key==0x1f)explicitMediaPlay();else if(key==0x20)explicitMediaPause();}else log("unsupported","hardkey="+key);return;}
            if(transport.isNativeUsb()){if(unknownTouchDiagnosticSamples<8){unknownTouchDiagnosticSamples++;logFields("native_touch_unknown","session_id",sessionId,"sample",unknownTouchDiagnosticSamples,"limit",8,"message_id",id,"payload_length",p.length);}}else log("unsupported","touchid0x"+Integer.toHexString(id));
        }
        static String touchHex(byte[] p){return hexPrefix(p,64);}
        static String hexPrefix(byte[] p,int cap){StringBuilder b=new StringBuilder();final char[] h="0123456789abcdef".toCharArray();for(int i=0;i<Math.min(p.length,cap);i++){if(i>0)b.append(' ');b.append(h[(p[i]&255)>>>4]).append(h[p[i]&15]);}return b.toString();}
        static int rawTouchAction(int id,byte[] p,Map<Integer,Object> fields){
            if(id==0x6800e)return p.length<4?-1:((p[0]&255)<<24)|((p[1]&255)<<16)|((p[2]&255)<<8)|(p[3]&255);
            if(TouchFrame.isLegacyStream(id))try{return TouchFrame.legacyStreamAction(id,fields);}catch(IOException invalid){return -1;}
            return Proto.number(fields,1,-1);
        }
        static String touchFields(Map<Integer,Object> fields){StringBuilder b=new StringBuilder();for(int k=1;k<=5;k++){if(k>1)b.append(',');b.append(k).append('=');Object v=fields.get(k);b.append(v==null?"absent":v instanceof Long?v:"wrong_type");}return b.toString();}
        void recordTouchIngress(int id,byte[] p,Map<Integer,Object> fields){
            if(!TouchFrame.isLegacyStream(id)&&id!=0x6800e)return;
            int action=rawTouchAction(id,p,fields),n=id==0x6800e&&p.length>=4?(p.length-4)/5:0;
            int second=id==0x68001?4:3;
            int shape=(id==0x6800e?4:id==0x68001?0:8)|(fields.containsKey(second)?1:0)|(fields.containsKey(second+1)?2:0);
            boolean priority=(action&255)==5||(action&255)==6||n>1||(lastTouchShape>=0&&lastTouchShape!=shape);lastTouchShape=shape;
            boolean retainHeader=priority?touchPriorityIngress<24:touchOrdinaryIngress<8;
            if(retainHeader){if(priority)touchPriorityIngress++;else touchOrdinaryIngress++;}
            // Every packet reaches the bounded async tail. Ordinary MOVE cannot
            // consume the independent header samples reserved for pointer changes.
            logFields(retainHeader?"native_touch_ingress":"native_touch_ingress_tail","session_id",sessionId,"logical_session",nativeLogicalSession,"input_count",touchCount,"sample",retainHeader?touchOrdinaryIngress+touchPriorityIngress:0,"priority",priority,"message_id",id,"full_action",action,"fields",touchFields(fields),"payload_length",p.length,"payload_hex",touchHex(p),"prefix_limit",64,"action3_count_from_length",n,"validation","not_yet_validated");
        }
        void recordTouchRecovery(int id,byte[] p,Map<Integer,Object> fields,String policy,String reason){
            long count=++touchRecoveries;boolean detail=count<=16;
            logFields("native_touch_recovered","session_id",sessionId,"logical_session",nativeLogicalSession,"generation",videoGeneration,"recovery_count",count,"input_count",touchCount,"message_id",id,"full_action",rawTouchAction(id,p,fields),"policy",policy,"reason",reason==null?null:reason.substring(0,Math.min(reason.length(),256)),"payload_length",p.length,"fields",detail?touchFields(fields):null,"payload_hex",detail?touchHex(p):null,"prefix_limit",64,"detail_limit",16,"session_preserved",open);
        }
        void recordTouchMapping(int id,TouchFrame raw,TouchFrame mapped,ProjectionGeometry g){
            if(!transport.isNativeUsb()||touchDiagnosticSamples>=32)return;
            touchDiagnosticSamples++;
            logFields("native_touch_mapping","session_id",sessionId,"logical_session",nativeLogicalSession,"sample",touchDiagnosticSamples,"limit",32,"message_id",id,"action",raw.action,"action_index",raw.actionIndex,"ids",Arrays.toString(raw.ids),"raw_xs",Arrays.toString(raw.xs),"raw_ys",Arrays.toString(raw.ys),"mapped_xs",mapped==null?null:Arrays.toString(mapped.xs),"mapped_ys",mapped==null?null:Arrays.toString(mapped.ys),"inside_content",mapped!=null,"geometry",g.toString(),"generation",raw.generation,"coordinate_units","raw=accepted video pixels; mapped=source display pixels");
        }
        synchronized void completeBootInit(PendingVideoInit ticket,MediaCodecDisplaySource.Selection selection)throws IOException{
            if(!ticket.current(pendingInit,videoGeneration,open)){log("boot_stale_init_discarded","RESET/EOF/new VERSION canceled capability probe");return;}
            if(selection==null)throw new IOException("missing validated MediaCodec selection");
            geometry=selection.geometry;
            codecSelection=selection;sink.cancel();width=selection.width;height=selection.height;acceptedFps=selection.fps;initialized=true;pendingInit=null;
            send(CMD,0x10008,Proto.ints(1,width,2,height,3,acceptedFps));
            afterNativeInit();
            logFields("video_initialized","session_id",sessionId,"generation",videoGeneration,"boot_native",true,"width",width,"height",height,"fps",ticket.fps,"initialization","display and encoder capabilities validated; source resources begin on VIDEO_START");
        }
        synchronized void startCapture()throws IOException{
            audioNegotiated=true;maybeStartAudio();
            final long generation=++videoGeneration;videoRate=new VideoRateControl(nativeVideos.snapshot(SystemClock.uptimeMillis()).pressureEvents);pendingFps=0;
            if(videoSource.equals("mediacodec")){if(codecSelection==null)throw new IOException("MediaCodec plan missing");MediaCodecDisplaySource source=new MediaCodecDisplaySource(this,codecSelection,generation);CAPTURE_LEASE.acquire(source,"mediacodec session="+sessionId+" generation="+generation);mediaSource=source;if(acceptedFps!=codecSelection.fps)source.requestFrameRate(acceptedFps);try{source.start();}catch(RuntimeException e){mediaSource=null;CAPTURE_LEASE.release(source,true);throw new IOException("source thread did not start",e);}return;}
            final Object leaseOwner=new Object();CAPTURE_LEASE.acquire(leaseOwner,"bootstrap session="+sessionId+" generation="+generation);
            final Process child;try{child=new ProcessBuilder("/system/bin/screenrecord","--output-format=h264","--size",width+"x"+height,"--bit-rate","4000000","--time-limit","60","-").start();}catch(IOException e){CAPTURE_LEASE.release(leaseOwner,true);throw e;}capture=child;
            log("capture_start","/system/bin/screenrecord; Android display -> H264 -> MD packets; 60s max; no output file");
            Thread stderr=new Thread(new Runnable(){public void run(){try{BufferedReader r=new BufferedReader(new InputStreamReader(child.getErrorStream()));String l;int n=0;while((l=r.readLine())!=null&&n++<64)log("screenrecord_stderr",l.substring(0,Math.min(512,l.length())));}catch(IOException e){}}},"capture-stderr");stderr.setDaemon(true);stderr.start();
            captureThread=new Thread(new Runnable(){public void run(){try{byte[] b=new byte[65536];int n;while(open&&capture==child&&(n=child.getInputStream().read(b))!=-1){if(n==0)continue;send(VIDEO,0x20001,Arrays.copyOf(b,n),generation);bytes+=n;chunks++;}log(open&&capture==child?"capture_eof":"capture_stopped","bytes="+bytes+" chunks="+chunks+" NOTframeCount");}catch(IOException e){log(open&&capture==child?"capture_error":"capture_stopped",e.toString());}finally{boolean stopped=terminateChild(child);CAPTURE_LEASE.release(leaseOwner,stopped);logFields("capture_lease_released","source","bootstrap","session_id",sessionId,"cleanup_confirmed",stopped,"lease_state",CAPTURE_LEASE.state());synchronized(Session.this){if(capture==child)capture=null;}}}},"capture-reader");captureThread.start();
        }
        void stopCapture(){Process p;Thread t;MediaCodecDisplaySource m;synchronized(this){videoGeneration++;pendingInit=null;nativeStartDue=0;p=capture;capture=null;t=captureThread;captureThread=null;m=mediaSource;}reportProgress("generation_invalidated",m);if(m!=null){m.requestStop();boolean done=m.awaitStopped(3000);if(done){synchronized(this){if(mediaSource==m)mediaSource=null;}}else logFields("codec_stop_timeout","session_id",sessionId,"generation",videoGeneration,"new_start_blocked",true);}if(p!=null){p.destroy();try{p.getInputStream().close();p.getErrorStream().close();}catch(IOException e){}try{if(!p.waitFor(2000,TimeUnit.MILLISECONDS))p.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();}}if(t!=null&&t!=Thread.currentThread())try{t.join(2500);}catch(InterruptedException e){Thread.currentThread().interrupt();}clearVideoQueues();}
        synchronized void mediaSourceEnded(MediaCodecDisplaySource source){if(mediaSource==source)mediaSource=null;}
        synchronized void noteTerminal(String reason){if(terminalReason==null)terminalReason=reason;}
        boolean awaitTerminalCleanup(long timeoutMs){
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while(true){
                Thread w=writer;
                if(closeComplete&&(w==null||!w.isAlive())&&capture==null&&captureThread==null&&mediaSource==null&&!CAPTURE_LEASE.isHeld()&&audio.retired()&&(speech==null||speech.retired())&&(audioPolicy==null||audioPolicy.retired()))return true;
                if(System.nanoTime()>=end)return false;
                try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
            }
        }
        void close(){close("session_close");}
        void close(String reason){synchronized(this){if(!open)return;noteTerminal(reason);open=false;}closeServices();playback.close();audio.stop();audio.queue.clear();if(speech!=null){speech.stop();speech.queue.clear();}wake();try{try{transport.close();}catch(IOException e){log("transport_close_error",e.toString());}stopCapture();sink.cancel();controls.clear();clearVideoQueues();if(writer!=null&&writer!=Thread.currentThread())writer.interrupt();reportProgress("session_closed",mediaSource);logFields("session_closed","session_id",sessionId,"logical_session",nativeLogicalSession,"ever_negotiated",everNegotiated,"terminal_reason",terminalReason,"bytes",bytes,"chunks",chunks,"touches",touchCount);}finally{closeComplete=true;}}
    }
    static boolean terminateChild(Process child){
        child.destroy();try{if(child.waitFor(2000,TimeUnit.MILLISECONDS))return true;child.destroyForcibly();return child.waitFor(2000,TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return !child.isAlive();}
    }
    static class InputSink {
        static final int ASYNC=0,WAIT_FOR_FINISH=2;
        Object manager;Method inject,setDisplay;final TouchFrame.State state=new TouchFrame.State();final TouchFrame.State rawState=new TouchFrame.State();boolean ignoreUntilDown;TouchFrame lastAttempt;
        int touchInjectionMode=WAIT_FOR_FINISH;long nativeIngressUptime,nativeInputCount;
        InputSink(){if(!bootNative)initialize();}
        synchronized void configureNativeTouch(boolean nativeUsb){touchInjectionMode=nativeUsb?ASYNC:WAIT_FOR_FINISH;}
        synchronized void initialize(){if(inject!=null)return;for(String c:new String[]{"android.hardware.input.InputManagerGlobal","android.hardware.input.InputManager"})try{Class<?> k=Class.forName(c);manager=k.getDeclaredMethod("getInstance").invoke(null);inject=k.getMethod("injectInputEvent",InputEvent.class,int.class);setDisplay=InputEvent.class.getMethod("setDisplayId",int.class);log("input_backend",c+".injectInputEvent existing process rights");break;}catch(Exception e){log("input_probe",c+": "+e.getClass().getSimpleName());}}
        // Hard keys and the DEV baseline retain their existing completion wait.
        boolean event(InputEvent e,int display){return event(e,display,WAIT_FOR_FINISH);}
        boolean event(InputEvent e,int display,int mode){if(bootNative&&inject==null)initialize();if(inject==null){log("input_unavailable","no reflection injector; no shell fallback");return false;}try{if(setDisplay!=null)setDisplay.invoke(e,display);else if(display!=0)throw new IOException("no explicit display routing API");boolean ok=(Boolean)inject.invoke(manager,e,mode);log("input_injected","accepted="+ok+" type="+e.getClass().getSimpleName()+" display="+display+" injection_mode="+mode+" acceptance="+(mode==ASYNC?"asynchronous_admission_not_app_handled":"wait_for_finish_result"));return ok;}catch(Exception ex){log("input_error",MediaCodecDisplaySource.cause(ex).toString());return false;}}
        synchronized void wire(int id,byte[] raw,Map<Integer,Object> fields,ProjectionGeometry geometry,long generation,Session owner)throws IOException{
            if(owner.transport.isNativeUsb()){nativeIngressUptime=owner.nativeTouchIngressUptime;nativeInputCount=owner.touchCount;}
            boolean multi=id==0x6800e,dual=false,compatibleLegacy=owner.transport.isNativeUsb()&&touchProfile.equals("recognized")&&TouchFrame.isLegacyStream(id);int action;
            TouchFrame frame=null;
            if(multi){if(!touchProfile.equals("action3-multi")&&!touchProfile.equals("all-lab")&&!touchProfile.equals("recognized"))throw new IOException("ACTION3 not enabled by explicit profile");frame=TouchFrame.action3(raw,sourceDisplayId,generation,SystemClock.uptimeMillis());action=frame.action;}
            else if(compatibleLegacy)action=TouchFrame.legacyStreamAction(id,fields)&255;
            else {if(id!=0x68001||touchProfile.equals("action3-multi"))throw new IOException("legacy opcode not enabled by explicit profile");Proto.requireInts(fields,1,2,3);action=Proto.number(fields,1,-1)&255;dual=touchProfile.equals("legacy-dual")||((touchProfile.equals("all-lab")||touchProfile.equals("recognized"))&&fields.containsKey(4)&&fields.containsKey(5));if(touchProfile.equals("legacy-single")&&(fields.containsKey(4)||fields.containsKey(5)))throw new IOException("dual fields outside explicit single profile");if(touchProfile.equals("recognized")&&fields.containsKey(4)!=fields.containsKey(5))throw new IOException("incomplete recognized dual touch coordinate pair");}
            if(ignoreUntilDown&&action!=0){log("touch_ignored","gesture canceled; waiting new DOWN");return;}if(action==0)ignoreUntilDown=false;
            if(frame==null)frame=compatibleLegacy?TouchFrame.legacyCompatible(id,fields,rawState,sourceDisplayId,generation,SystemClock.uptimeMillis()):TouchFrame.legacy(fields,dual,state,sourceDisplayId,generation,SystemClock.uptimeMillis());
            // A fresh native DOWN is a new gesture even when the HU changes its
            // negotiated wire format. The existing CANCEL-before-DOWN path below
            // retires the old contacts; a mid-gesture format change still cancels.
            if(state.last!=null&&!(owner.transport.isNativeUsb()&&frame.action==0)&&!(owner.transport.isNativeUsb()?TouchFrame.sameWireFamily(state.last.source,frame.source):state.last.source.equals(frame.source)))throw new IOException("touch wire profile changed during gesture");
            TouchFrame mapped=frame.mapped(geometry);owner.recordTouchMapping(id,frame,mapped,geometry);if(mapped==null){cancel();ignoreUntilDown=true;logFields("touch_outside_viewport","action",action,"policy","reject outside DOWN; CANCEL active gesture; ignore until fresh DOWN","geometry",geometry.toString());return;}
            if(owner.transport.isNativeUsb()&&mapped.action==0&&state.last!=null){
                if(mapped.repeatsDown(state.last)){owner.recordTouchRecovery(id,raw,fields,"duplicate_down_ignored","same active DOWN/contact/coordinates; Android downTime preserved");return;}
                cancel();state.clear();owner.recordTouchRecovery(id,raw,fields,"restarted_down","fresh DOWN cancels previous contact before starting new gesture");
            }
            apply(mapped,owner.transport.isNativeUsb());
            if(compatibleLegacy)rawState.accept(frame);
        }
        synchronized void apply(TouchFrame f)throws IOException{apply(f,false);}
        synchronized void apply(TouchFrame f,boolean nativeInput)throws IOException{
            state.accept(f);lastAttempt=f;long injectStarted=SystemClock.uptimeMillis();boolean ok=injectTouchFrame(f);long injectCompleted=SystemClock.uptimeMillis();
            logFields("touch_frame","action",f.action,"action_index",f.actionIndex,"pointer_count",f.ids.length,"ids",Arrays.toString(f.ids),"xs",Arrays.toString(f.xs),"ys",Arrays.toString(f.ys),"display_id",f.displayId,"generation",f.generation,"source",f.source,"event_time_ms",f.timeMs,"accepted",ok,"input_count",nativeInput?nativeInputCount:null,"ingress_uptime_ms",nativeInput?nativeIngressUptime:null,"inject_started_uptime_ms",injectStarted,"inject_completed_uptime_ms",injectCompleted,"inject_call_duration_ms",injectCompleted-injectStarted,"ingress_to_inject_started_ms",nativeInput?injectStarted-nativeIngressUptime:null,"injection_mode",touchInjectionMode,"acceptance_semantics",touchInjectionMode==ASYNC?"asynchronous_admission_only; app_handling_and_visible_response_not_confirmed":"wait_for_finish_result");
            if(!ok){if(!nativeInput)cancel();throw new IOException("Android InputManager rejected touchscreen event");}
            lastAttempt=null;
        }
        // One injectable platform boundary, shared by normal frames and cancellation.
        boolean injectTouchFrame(TouchFrame f){return injectTouchFrameWithMode(f,touchInjectionMode);}
        boolean injectTouchFrameWithMode(TouchFrame f,int mode){
            int n=f.ids.length;MotionEvent.PointerProperties[]pp=new MotionEvent.PointerProperties[n];MotionEvent.PointerCoords[]pc=new MotionEvent.PointerCoords[n];
            for(int i=0;i<n;i++){pp[i]=new MotionEvent.PointerProperties();pp[i].id=f.ids[i];pp[i].toolType=MotionEvent.TOOL_TYPE_FINGER;pc[i]=new MotionEvent.PointerCoords();pc[i].x=f.xs[i];pc[i].y=f.ys[i];pc[i].pressure=f.action==1||f.action==3?0:1;pc[i].size=1;}
            MotionEvent e=MotionEvent.obtain(state.downTime,f.timeMs,f.fullAction(),n,pp,pc,0,0,1,1,0,0,0x1002,0);try{return event(e,f.displayId,mode);}finally{e.recycle();}
        }
        synchronized void cancel(){try{TouchFrame f=state.cancel(SystemClock.uptimeMillis());if(f!=null){state.accept(f);boolean ok=injectTouchFrame(f);logFields("touch_cancel","pointer_count",f.ids.length,"ids",Arrays.toString(f.ids),"display_id",f.displayId,"generation",f.generation,"accepted",ok,"injection_mode",touchInjectionMode,"acceptance_semantics",touchInjectionMode==ASYNC?"asynchronous_admission_only; app_handling_not_confirmed":"wait_for_finish_result");}}catch(Exception e){log("input_cancel_error",e.toString());}finally{rawState.clear();}}
        synchronized void recoverNative(){
            // UP clears canonical state before Android answers. A rejected UP still
            // needs an explicit CANCEL using the attempted contact, not an empty state.
            TouchFrame attempted=lastAttempt;
            try{if(attempted!=null){TouchFrame c=new TouchFrame(3,attempted.ids,attempted.xs,attempted.ys,attempted.displayId,attempted.generation,SystemClock.uptimeMillis(),attempted.source);boolean ok=injectTouchFrame(c);logFields("touch_cancel","pointer_count",c.ids.length,"ids",Arrays.toString(c.ids),"display_id",c.displayId,"generation",c.generation,"accepted",ok,"reason","rejected attempted input","injection_mode",touchInjectionMode,"acceptance_semantics",touchInjectionMode==ASYNC?"asynchronous_admission_only; app_handling_not_confirmed":"wait_for_finish_result");}else if(state.last!=null)cancel();}
            catch(Exception e){log("input_cancel_error",e.toString());}
            finally{state.clear();rawState.clear();lastAttempt=null;ignoreUntilDown=true;}
        }
        synchronized void cancelIfIdle(){if(state.last!=null&&SystemClock.uptimeMillis()-state.last.timeMs>3000){log("touch_timeout","no input update for3s; CANCEL all pointers");cancel();ignoreUntilDown=true;}}
        synchronized void back(){hardkey(14);}
        synchronized void mediaPause(){
            try{long t=SystemClock.uptimeMillis();boolean down=event(new KeyEvent(t,t,KeyEvent.ACTION_DOWN,127,0),sourceDisplayId);boolean up=event(new KeyEvent(t,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,127,0),sourceDisplayId);logFields("audio_player_pause","android_key",127,"accepted",down&&up);}
            catch(RuntimeException e){logFields("audio_player_pause","android_key",127,"accepted",false,"error",e.toString());}
        }
        synchronized void hardkey(int key){cancel();int androidKey=androidKeyCodeForCarLife(key);if(androidKey<0)return;long t=SystemClock.uptimeMillis();boolean down=event(new KeyEvent(t,t,KeyEvent.ACTION_DOWN,androidKey,0),sourceDisplayId);boolean up=event(new KeyEvent(t,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,androidKey,0),sourceDisplayId);logFields("hardkey","carlife_key",key,"android_key",androidKey,"display_id",sourceDisplayId,"accepted",down&&up);}
    }
    /** Independent small proto2 wire implementation; only known unsigned-int/string fields. */
    static final class Proto {
        static long varint(ByteArrayInputStream in)throws IOException{long v=0;for(int i=0;i<10;i++){int b=in.read();if(b<0)throw new EOFException("protobuf varint");if(i==9&&(b&254)!=0)throw new IOException("varint overflow");v|=(long)(b&127)<<(i*7);if((b&128)==0)return v;}throw new IOException("varint overflow");}
        static Map<Integer,Object> parse(byte[] data)throws IOException{Map<Integer,Object> m=new HashMap<Integer,Object>();ByteArrayInputStream in=new ByteArrayInputStream(data);int count=0;while(in.available()>0){if(++count>128)throw new IOException("too many protobuf fields");long key=varint(in);int f=(int)(key>>>3),type=(int)(key&7);if(f==0)throw new IOException("field zero");Object val;if(type==0)val=varint(in);else if(type==2){long n=varint(in);if(n<0||n>in.available()||n>65535)throw new IOException("invalid protobuf length");byte[] s=new byte[(int)n];in.read(s);val=s;}else if(type==1||type==5){int n=type==1?8:4;if(in.skip(n)!=n)throw new EOFException("fixed protobuf");continue;}else throw new IOException("unsupported protobuf wire type");m.put(f,val);}return m;}
        static void requireInts(Map<Integer,Object> f,int...keys)throws IOException{for(int k:keys)if(!(f.get(k) instanceof Long))throw new IOException("missing or wrong-type required protobuf int field "+k);}
        static int number(Map<Integer,Object> f,int key,int def){Object o=f.get(key);return o instanceof Long?((Long)o).intValue():def;}
        static void varint(ByteArrayOutputStream b,long n){while((n&~127L)!=0){b.write((int)n&127|128);n>>>=7;}b.write((int)n);}
        static byte[] ints(int... pairs){ByteArrayOutputStream b=new ByteArrayOutputStream();for(int i=0;i<pairs.length;i+=2){varint(b,pairs[i]<<3);varint(b,pairs[i+1]);}return b.toByteArray();}
        static byte[] strings(Object... pairs)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(int i=0;i<pairs.length;i+=2){byte[] s=((String)pairs[i+1]).getBytes("UTF-8");varint(b,((Integer)pairs[i]<<3)|2);varint(b,s.length);b.write(s);}return b.toByteArray();}
    }
}
