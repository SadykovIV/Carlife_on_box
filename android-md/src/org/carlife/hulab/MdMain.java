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
        for(int i=0;i<args.length;i+=2) {
            if(i+1==args.length) throw new IllegalArgumentException("option requires value");
            if(args[i].equals("--transport")){transportMode=choice(args[i+1],"dev-stream","native-usb");continue;}
            if(args[i].equals("--log-dir")){logDirectory=args[i+1];continue;}
            if(args[i].equals("--video-source")){videoSource=args[i+1];if(!videoSource.equals("bootstrap")&&!videoSource.equals("mediacodec"))throw new IllegalArgumentException("video source must be bootstrap or mediacodec");continue;}
            if(args[i].equals("--encoder")){encoderName=args[i+1];if(!encoderName.equals("OMX.qcom.video.encoder.avc")&&!encoderName.equals("c2.qti.avc.encoder")&&!encoderName.equals("c2.android.avc.encoder"))throw new IllegalArgumentException("encoder outside laboratory whitelist");continue;}
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
        if(nativeUsb&&!explicitTouchProfile)touchProfile="recognized";
        if(seconds<(nativeUsb?0:1)||seconds>4200||port<1024||port>65535||!validDisplayRequest(nativeUsb,displayWidth,displayHeight))throw new IllegalArgumentException("bounds");
        if(sourceDisplayId<0||sourceDensity<0||sourceDensity>640)throw new IllegalArgumentException("source display/density bounds");
        if(isolatedWidth!=0||isolatedHeight!=0||isolatedDensity!=0){if(isolatedWidth<320||isolatedWidth>1920||isolatedHeight<180||isolatedHeight>1080||isolatedDensity<120||isolatedDensity>640)throw new IllegalArgumentException("isolated display bounds; all three parameters required");}
        if(nativeUsb&&(sourceDisplayId!=0||isolatedWidth!=0))throw new IllegalArgumentException("native pilot requires actual primary display0");
        if(logDirectory!=null&&!nativeUsb)throw new IllegalArgumentException("--log-dir is native-usb only; development logging unchanged");
        if(nativeUsb){
            if(logDirectory!=null)verifyLogDirectory(logDirectory);
            asyncLog=new AsyncRingLog(logDirectory==null?AsyncRingLog.console(System.out):new AsyncRingLog.Files(new File(logDirectory)));
            logFields("log_started","backend",logDirectory==null?"async_console":"async_disk_ring","queue_lines",AsyncRingLog.QUEUE_LINES,"line_bytes",AsyncRingLog.LINE_BYTES,"tail_files",2,"tail_file_bytes",AsyncRingLog.TAIL_BYTES,"header_bytes",AsyncRingLog.HEAD_BYTES,"overflow","nonblocking drop with counters","header_scope","first handshake/codec metadata of this process","shutdown_flush_bound_ms",1500);
        }
        deadline=seconds==0?Long.MAX_VALUE:SystemClock.uptimeMillis()+seconds*1000L;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){public void run(){shutdown();closeLog();}},"md-cleanup"));
        if(isolatedWidth>0){ownedDisplay=new OwnedDisplay(isolatedWidth,isolatedHeight,isolatedDensity);sourceDisplayId=ownedDisplay.id;displayWidth=isolatedWidth;displayHeight=isolatedHeight;sourceDensity=isolatedDensity;}
        verifyDisplay();
        logFields("projection_policy","video_source",videoSource,"video_path",videoPath,"csd_source",csdSource,"touch_profile",touchProfile,"source_display_id",sourceDisplayId,"source_width",displayWidth,"source_height",displayHeight,"source_density_dpi",sourceDensity,"density_provenance",sourceDensity==0?"unknown":"runtime/manual/owned display declared by launcher","compatibility","SELF explicit lab profile");
        if(!nativeUsb){server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),1);server.setSoTimeout(1000);}
        logFields("transport_controller_ready","transport",transportMode,"endpoint",nativeUsb?AndroidAccessoryIo.PATH:"127.0.0.1:"+port,"controller_deadline_uptime_ms",seconds==0?null:deadline,"persistent_controller",seconds==0,"audio_supported",false,"auth_provider_available",false,"wire","CarLife 8-byte channel/length then unchanged message header");
        Thread watchdog=new Thread(new Runnable(){public void run(){
            while(running){
                if(SystemClock.uptimeMillis()>deadline){log("deadline","server time limit");shutdown();break;}
                Session s=active;
                if(s!=null)s.sink.cancelIfIdle();
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
                catch(IOException e) { if(running)log("session_error",e.toString()); }
                finally {
                    if(active!=null)active.close();active=null;
                    if(transport!=null){try{transport.close();}catch(IOException e){log("transport_close_error",e.toString());}
                        boolean stopped=transport.awaitStopped(2500);logFields("transport_owner_stopped","transport",transport.name(),"quiescent",stopped);
                        if(!stopped){running=false;throw new IOException("transport owner did not stop; refusing another accessory fd");}}
                }
                if(nativeUsb&&running)Thread.sleep(250);
            }
        } finally { shutdown();log("stopped","transport controller stopped; owned session cleanup requested");closeLog(); }
    }
    static void closeLog(){AsyncRingLog logger=asyncLog;if(logger!=null)logger.closeWithin(1500);}
    static void verifyLogDirectory(String path)throws Exception{
        File directory=new File(path);if(!directory.isAbsolute()||!directory.getCanonicalFile().equals(directory.getAbsoluteFile()))throw new IOException("log directory must be canonical absolute path");
        android.system.StructStat st=android.system.Os.lstat(path);
        if(!android.system.OsConstants.S_ISDIR(st.st_mode)||st.st_uid!=android.os.Process.myUid()||(st.st_mode&0777)!=0700)throw new IOException("log directory must be precreated by existing UID with mode0700");
        for(String name:new String[]{"md.jsonl","md.1.jsonl","md.head.jsonl"}){File f=new File(directory,name);try{st=android.system.Os.lstat(f.getPath());}catch(android.system.ErrnoException e){if(e.errno==android.system.OsConstants.ENOENT)continue;throw e;}if(!android.system.OsConstants.S_ISREG(st.st_mode)||st.st_uid!=android.os.Process.myUid()||(st.st_mode&0777)!=0600)throw new IOException("foreign log file ownership/mode "+name);}
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
    static void shutdown(){running=false;Session s=active;if(s!=null)s.close();try{if(server!=null)server.close();}catch(IOException e){}OwnedDisplay d=ownedDisplay;if(d!=null)d.close();}
    static final class Packet { final byte[] data;final long queued=SystemClock.uptimeMillis();final long videoGeneration;Packet(byte[] d,long generation){data=d;videoGeneration=generation;} }
    static boolean sameVideoGeneration(long packetGeneration,long currentGeneration){return packetGeneration==currentGeneration;}
    static byte[] packet(int channel,int id,byte[] payload,int timestamp)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream(payload.length+20);DataOutputStream d=new DataOutputStream(b);
        int h=(channel==CMD||channel==TOUCH)?8:12;
        d.writeInt(channel);d.writeInt(payload.length+h);
        if(h==8){if(payload.length>65535)throw new IOException("command too large");d.writeShort(payload.length);d.writeShort(0);d.writeInt(id);}
        else{d.writeInt(payload.length);d.writeInt(timestamp);d.writeInt(id);}
        d.write(payload);return b.toByteArray();
    }
    static final class Frame { final int channel,id;final byte[] payload;Frame(int c,int i,byte[] p){channel=c;id=i;payload=p;} }
    static Frame readFrame(DataInputStream in)throws IOException {
        int first;try{first=in.read();}catch(SocketTimeoutException idle){return null;}
        if(first<0)throw new EOFException("at frame boundary");
        // Any failure after this first byte is fatal; a partial header is never resynchronized.
        int channel=(first<<24)|(in.readUnsignedByte()<<16)|(in.readUnsignedByte()<<8)|in.readUnsignedByte();
        int length=in.readInt();int header=(channel==CMD||channel==TOUCH)?8:12;
        if(channel<1||channel>6||length<header||length>MAX_BODY)throw new IOException("invalid outer header");
        byte[] body=new byte[length];in.readFully(body);DataInputStream d=new DataInputStream(new ByteArrayInputStream(body));
        int declared,reserved=0,id;if(header==8){declared=d.readUnsignedShort();reserved=d.readUnsignedShort();id=d.readInt();}else{declared=d.readInt();d.readInt();id=d.readInt();}
        if(declared<0||length!=header+declared||reserved!=0)throw new IOException("inner length/reserved mismatch");
        if((id>>>16)!=channel)throw new IOException("message ID / channel mismatch");
        byte[] payload=new byte[declared];d.readFully(payload);return new Frame(channel,id,payload);
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
    static int androidKeyCodeForCarLife(int key){return key==1?KeyEvent.KEYCODE_HOME:key==14?KeyEvent.KEYCODE_BACK:-1;}
    static final class Session {
        final long sessionId=++sessionSequence;
        final MdTransport transport; final DataInputStream in;final OutputStream out;
        final ArrayBlockingQueue<Packet> controls=new ArrayBlockingQueue<Packet>(32),videos=new ArrayBlockingQueue<Packet>(8);
        volatile boolean open=true;volatile long writingSince;volatile long videoGeneration=0;volatile Process capture;
        volatile MediaCodecDisplaySource mediaSource;
        volatile Packet writingPacket;
        volatile long lastSnapshotUptime,lastVideoEnqueueUptime,lastControlEnqueueUptime,lastSocketWriteUptime,lastSocketWriteDurationMs,socketWrittenPackets,socketWrittenBytes,socketWrittenVideoPackets;
        final AtomicLong snapshotSequence=new AtomicLong(),videoEnqueuedPackets=new AtomicLong(),videoEnqueuedBytes=new AtomicLong(),controlEnqueuedPackets=new AtomicLong(),controlEnqueuedBytes=new AtomicLong(),staleVideoEnqueueDrops=new AtomicLong(),staleVideoWriteDrops=new AtomicLong();
        MediaCodecDisplaySource.Selection codecSelection;ProjectionGeometry geometry;
        Thread writer,captureThread;boolean version=false,initialized=false,paused=false;int width=1280,height=720;long bytes=0,chunks=0,nativeLogicalSession=1;int touchCount=0;
        final InputSink sink=new InputSink();
        Session(Socket s)throws IOException{this(new SocketMdTransport(s));}
        Session(MdTransport t)throws IOException{transport=t;in=new DataInputStream(t.input());out=t.output();}
        String wireMarker(){return transport.isNativeUsb()?(videoSource.equals("mediacodec")?"DIRECT_USB_AOA;source=MediaCodecDisplaySource;video=ANNEXB_AU;timestamp=ENCODER_PTS_MS":"DIRECT_USB_AOA"):(videoSource.equals("mediacodec")?MediaCodecDisplaySource.WIRE_MARKER:"DEV_STREAM_OVER_ADB");}
        void run()throws IOException {
            logFields("connected","session_id",sessionId,"transport",transport.name(),"audio_mic_auth_encryption","unsupported","wire_marker",wireMarker());
            writer=new Thread(new Runnable(){public void run(){writeLoop();}},"md-writer");writer.start();
            while(open&&running){
                Frame f;
                try{f=readFrame(in);}catch(EOFException e){log("peer_eof","disconnect or truncated frame: "+e.getMessage());break;}
                if(f==null)continue;
                log("rx","channel="+f.channel+" id=0x"+Integer.toHexString(f.id)+" bytes="+f.payload.length);
                dispatch(f.channel,f.id,f.payload);
            }
        }
        void writeLoop(){try{while(open){Packet p=controls.poll();boolean video=false;if(p==null){p=videos.poll(50,TimeUnit.MILLISECONDS);video=true;}if(p==null)continue;
            if(video&&!sameVideoGeneration(p.videoGeneration,videoGeneration)){staleVideoWriteDrops.incrementAndGet();log("video_epoch_drop","discard stale source generation before write");continue;}
            if(video&&SystemClock.uptimeMillis()-p.queued>2000)throw new IOException("video queue latency >2s; reconnect for IDR");
            writingPacket=p;writingSince=SystemClock.uptimeMillis();out.write(p.data);out.flush();
            long ended=SystemClock.uptimeMillis();lastSocketWriteDurationMs=ended-writingSince;lastSocketWriteUptime=ended;socketWrittenPackets++;socketWrittenBytes+=p.data.length;if(video)socketWrittenVideoPackets++;writingSince=0;writingPacket=null;
        }}catch(Exception e){if(open)log("write_error",e.toString());close();}}
        void maybeReportProgress(){long now=SystemClock.uptimeMillis();if(now-lastSnapshotUptime>=PipelineProgress.PERIOD_MS){lastSnapshotUptime=now;reportProgress("periodic",mediaSource);}}
        void reportProgress(String reason,MediaCodecDisplaySource source){
            long now=SystemClock.uptimeMillis(),writeStarted=writingSince;Packet inFlight=writingPacket;
            PipelineProgress.Queue v=PipelineProgress.queue(videos,now),c=PipelineProgress.queue(controls,now);
            ArrayList<Object> f=new ArrayList<Object>(96);
            Collections.addAll(f,"session_id",sessionId,"active_generation",videoGeneration,"source_generation",source==null?null:source.generation,"reason",reason,"snapshot_sequence",snapshotSequence.incrementAndGet(),"sample_uptime_ms",now,"snapshot_consistency","best_effort_cross_thread; queue bytes include complete wire headers","session_open",open,
                "video_queue_items",v.items,"video_queue_wire_bytes",v.wireBytes,"video_queue_oldest_age_ms",v.oldestAgeMs,"control_queue_items",c.items,"control_queue_wire_bytes",c.wireBytes,"control_queue_oldest_age_ms",c.oldestAgeMs,
                "video_enqueued_packets",videoEnqueuedPackets.get(),"video_enqueued_wire_bytes",videoEnqueuedBytes.get(),"control_enqueued_packets",controlEnqueuedPackets.get(),"control_enqueued_wire_bytes",controlEnqueuedBytes.get(),"video_enqueue_last_uptime_ms",PipelineProgress.seen(lastVideoEnqueueUptime),"video_enqueue_age_ms",PipelineProgress.age(now,lastVideoEnqueueUptime),
                "socket_written_packets",socketWrittenPackets,"socket_written_video_packets",socketWrittenVideoPackets,"socket_written_wire_bytes",socketWrittenBytes,"socket_write_last_uptime_ms",PipelineProgress.seen(lastSocketWriteUptime),"socket_write_progress_age_ms",PipelineProgress.age(now,lastSocketWriteUptime),"socket_write_last_duration_ms",lastSocketWriteDurationMs,
                "socket_inflight_started_uptime_ms",PipelineProgress.seen(writeStarted),"socket_inflight_age_ms",PipelineProgress.age(now,writeStarted),"socket_inflight_wire_bytes",inFlight==null?0:inFlight.data.length,"socket_inflight_generation",inFlight==null?null:inFlight.videoGeneration,"stale_enqueue_drops",staleVideoEnqueueDrops.get(),"stale_write_drops",staleVideoWriteDrops.get(),
                "video_queue_capacity",8,"control_queue_capacity",32,"video_queue_age_budget_ms",2000,"socket_write_stall_budget_ms",3000);
            if(source!=null)source.appendProgress(f,now);
            logFields("md_progress",f.toArray(new Object[0]));
        }
        boolean closeIfWriteStalled(){
            // Completion clears this volatile value. Never subtract a second read that may be zero.
            final long observedSince=writingSince;
            if(observedSince>0&&SystemClock.uptimeMillis()-observedSince>3000&&writingSince==observedSince){
                logFields("backpressure","session_id",sessionId,"write_started_uptime_ms",observedSince,"detail","same write stalled >3s; closing session");close();return true;
            }
            return false;
        }
        void send(int ch,int id,byte[] data)throws IOException{send(ch,id,data,-1);}
        void send(int ch,int id,byte[] data,long generation)throws IOException{sendWithTimestamp(ch,id,data,generation,(int)SystemClock.uptimeMillis());}
        void sendWithTimestamp(int ch,int id,byte[] data,long generation,int timestamp)throws IOException{try{
            if(ch==VIDEO&&!sameVideoGeneration(generation,videoGeneration)){staleVideoEnqueueDrops.incrementAndGet();log("video_epoch_drop","discard stale source generation before enqueue");return;}
            Packet p=new Packet(packet(ch,id,data,timestamp),generation);
            if(!(ch==VIDEO?videos:controls).offer(p,1000,TimeUnit.MILLISECONDS))throw new IOException("bounded send queue exhausted");
            long enqueuedAt=SystemClock.uptimeMillis();
            if(ch==VIDEO){lastVideoEnqueueUptime=enqueuedAt;videoEnqueuedPackets.incrementAndGet();videoEnqueuedBytes.addAndGet(p.data.length);}else{lastControlEnqueueUptime=enqueuedAt;controlEnqueuedPackets.incrementAndGet();controlEnqueuedBytes.addAndGet(p.data.length);}
            if(ch!=VIDEO)log("tx","channel="+ch+" id=0x"+Integer.toHexString(id)+" bytes="+data.length);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
        void dispatch(int ch,int id,byte[] p)throws IOException {
            Map<Integer,Object> fields=(ch==CMD||(ch==TOUCH&&id!=0x6800e))?Proto.parse(p):Collections.<Integer,Object>emptyMap();
            if(ch==CMD){
                rejectMandatoryAuth(id);
                if(id==0x18001){
                    if(version){
                        if(!transport.isNativeUsb()||!paused)throw new IOException("duplicate version outside paused native session boundary");
                        if(capture!=null||mediaSource!=null)throw new IOException("native session boundary before source cleanup");
                        // Closing a host libusb claim is not a physical device detach. A fresh
                        // genuine HU_VERSION after PAUSE starts negotiation on the same fd.
                        // One writer preserves old in-flight VIDEO -> VERSION_MATCH ordering.
                        stopCapture();sink.cancel();controls.clear();videos.clear();
                        version=false;initialized=false;paused=false;codecSelection=null;geometry=null;nativeLogicalSession++;
                        logFields("native_session_boundary","session_id",sessionId,"logical_session",nativeLogicalSession,"generation",videoGeneration,"reason","fresh HU_VERSION after source-stopped VIDEO_PAUSE");
                    }
                    int match=versionMatchStatus(fields);send(CMD,0x10002,Proto.ints(1,match));version=match==1;if(!version)log("version_rejected","matchStatus=2; only laboratory v1.0 supported");return;
                }
                if(!version)throw new IOException("version required");
                if(id==0x18003){send(CMD,0x10004,Proto.strings(1,"Android",7,"hulab-md",14,"CarLife MD video/input pilot",15,wireMarker()));return;}
                if(id==0x18007){Proto.requireInts(fields,1,2,3);if(initialized)throw new IOException("duplicate init; reset first");int w=Proto.number(fields,1,0),h=Proto.number(fields,2,0),fps=Proto.number(fields,3,0);
                    if(w<320||w>1920||h<180||h>1080||(w&1)!=0||(h&1)!=0||fps<1||fps>60)throw new IOException("unsupported video dimensions/fps hint");
                    if(videoSource.equals("mediacodec"))codecSelection=MediaCodecDisplaySource.select(w,h,fps);
                    geometry=new ProjectionGeometry(displayWidth,displayHeight,w,h,0);sink.cancel();width=w;height=h;initialized=true;send(CMD,0x10008,Proto.ints(1,w,2,h,3,fps));log("video_initialized",videoSource.equals("mediacodec")?"MediaCodecDisplaySource; requested AVC Baseline31; actual PTS/flags/CSD in codec logs":"BootstrapDisplaySource; fps is negotiation hint, not controlled encoder rate; PTS unavailable");return;}
                if(id==0x18009){if(!initialized)throw new IOException("init required");if(capture!=null||mediaSource!=null)throw new IOException("duplicate start or previous source not stopped");paused=false;startCapture();return;}
                if(id==0x1800a){sink.cancel();stopCapture();paused=true;log("paused","capture stop requested; next START only after owned source stopped");return;}
                if(id==0x1800b){stopCapture();initialized=false;paused=false;sink.cancel();log("reset","video state reset; input canceled");return;}
                if(id==0x1800c){log("unsupported","runtime frame rate change unsupported by selected laboratory source; no success ACK");return;}
                log("unsupported","command0x"+Integer.toHexString(id)+" no fabricated success ACK");return;
            }
            if(ch==TOUCH){if(!version||!initialized)throw new IOException("touch requires initialized session");if(++touchCount>200000)throw new IOException("input count limit");
                if(id==0x68001||id==0x6800e){sink.wire(id,p,fields,geometry,videoGeneration);return;}
                if(id==0x68008){Proto.requireInts(fields,1);int key=Proto.number(fields,1,-1);if(androidKeyCodeForCarLife(key)>=0)sink.hardkey(key);else log("unsupported","hardkey="+key);return;}
                log("unsupported","touchid0x"+Integer.toHexString(id));return;
            }
            log("unsupported","media/audio/VR channel="+ch+" payload ignored; no playback or injection claimed");
        }
        synchronized void startCapture()throws IOException{
            final long generation=++videoGeneration;
            if(videoSource.equals("mediacodec")){if(codecSelection==null)throw new IOException("MediaCodec plan missing");MediaCodecDisplaySource source=new MediaCodecDisplaySource(this,codecSelection,generation);CAPTURE_LEASE.acquire(source,"mediacodec session="+sessionId+" generation="+generation);mediaSource=source;try{source.start();}catch(RuntimeException e){mediaSource=null;CAPTURE_LEASE.release(source,true);throw new IOException("source thread did not start",e);}return;}
            final Object leaseOwner=new Object();CAPTURE_LEASE.acquire(leaseOwner,"bootstrap session="+sessionId+" generation="+generation);
            final Process child;try{child=new ProcessBuilder("/system/bin/screenrecord","--output-format=h264","--size",width+"x"+height,"--bit-rate","4000000","--time-limit","60","-").start();}catch(IOException e){CAPTURE_LEASE.release(leaseOwner,true);throw e;}capture=child;
            log("capture_start","/system/bin/screenrecord; Android display -> H264 -> MD packets; 60s max; no output file");
            Thread stderr=new Thread(new Runnable(){public void run(){try{BufferedReader r=new BufferedReader(new InputStreamReader(child.getErrorStream()));String l;int n=0;while((l=r.readLine())!=null&&n++<64)log("screenrecord_stderr",l.substring(0,Math.min(512,l.length())));}catch(IOException e){}}},"capture-stderr");stderr.setDaemon(true);stderr.start();
            captureThread=new Thread(new Runnable(){public void run(){try{byte[] b=new byte[65536];int n;while(open&&capture==child&&(n=child.getInputStream().read(b))!=-1){if(n==0)continue;send(VIDEO,0x20001,Arrays.copyOf(b,n),generation);bytes+=n;chunks++;}log(open&&capture==child?"capture_eof":"capture_stopped","bytes="+bytes+" chunks="+chunks+" NOTframeCount");}catch(IOException e){log(open&&capture==child?"capture_error":"capture_stopped",e.toString());}finally{boolean stopped=terminateChild(child);CAPTURE_LEASE.release(leaseOwner,stopped);logFields("capture_lease_released","source","bootstrap","session_id",sessionId,"cleanup_confirmed",stopped,"lease_state",CAPTURE_LEASE.state());synchronized(Session.this){if(capture==child)capture=null;}}}},"capture-reader");captureThread.start();
        }
        void stopCapture(){Process p;Thread t;MediaCodecDisplaySource m;synchronized(this){videoGeneration++;p=capture;capture=null;t=captureThread;captureThread=null;m=mediaSource;}reportProgress("generation_invalidated",m);if(m!=null){m.requestStop();boolean done=m.awaitStopped(3000);if(done){synchronized(this){if(mediaSource==m)mediaSource=null;}}else logFields("codec_stop_timeout","session_id",sessionId,"generation",videoGeneration,"new_start_blocked",true);}if(p!=null){p.destroy();try{p.getInputStream().close();p.getErrorStream().close();}catch(IOException e){}try{if(!p.waitFor(2000,TimeUnit.MILLISECONDS))p.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();}}if(t!=null&&t!=Thread.currentThread())try{t.join(2500);}catch(InterruptedException e){Thread.currentThread().interrupt();}videos.clear();}
        synchronized void mediaSourceEnded(MediaCodecDisplaySource source){if(mediaSource==source)mediaSource=null;}
        void close(){synchronized(this){if(!open)return;open=false;}try{transport.close();}catch(IOException e){log("transport_close_error",e.toString());}stopCapture();sink.cancel();controls.clear();videos.clear();if(writer!=null&&writer!=Thread.currentThread())writer.interrupt();reportProgress("session_closed",mediaSource);log("session_closed","bytes="+bytes+" chunks="+chunks+" touches="+touchCount);}
    }
    static boolean terminateChild(Process child){
        child.destroy();try{if(child.waitFor(2000,TimeUnit.MILLISECONDS))return true;child.destroyForcibly();return child.waitFor(2000,TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return !child.isAlive();}
    }
    static final class InputSink {
        Object manager;Method inject,setDisplay;final TouchFrame.State state=new TouchFrame.State();boolean ignoreUntilDown;
        InputSink(){for(String c:new String[]{"android.hardware.input.InputManagerGlobal","android.hardware.input.InputManager"})try{Class<?> k=Class.forName(c);manager=k.getDeclaredMethod("getInstance").invoke(null);inject=k.getMethod("injectInputEvent",InputEvent.class,int.class);setDisplay=InputEvent.class.getMethod("setDisplayId",int.class);log("input_backend",c+".injectInputEvent existing process rights");break;}catch(Exception e){log("input_probe",c+": "+e.getClass().getSimpleName());}}
        boolean event(InputEvent e,int display){if(inject==null){log("input_unavailable","no reflection injector; no shell fallback");return false;}try{if(setDisplay!=null)setDisplay.invoke(e,display);else if(display!=0)throw new IOException("no explicit display routing API");boolean ok=(Boolean)inject.invoke(manager,e,2);log("input_injected","accepted="+ok+" type="+e.getClass().getSimpleName()+" display="+display);return ok;}catch(Exception ex){log("input_error",MediaCodecDisplaySource.cause(ex).toString());return false;}}
        synchronized void wire(int id,byte[] raw,Map<Integer,Object> fields,ProjectionGeometry geometry,long generation)throws IOException{
            boolean multi=id==0x6800e,dual=false;int action;
            TouchFrame frame=null;
            if(multi){if(!touchProfile.equals("action3-multi")&&!touchProfile.equals("all-lab")&&!touchProfile.equals("recognized"))throw new IOException("ACTION3 not enabled by explicit profile");frame=TouchFrame.action3(raw,sourceDisplayId,generation,SystemClock.uptimeMillis());action=frame.action;}
            else {if(touchProfile.equals("action3-multi"))throw new IOException("legacy opcode not enabled by explicit profile");Proto.requireInts(fields,1,2,3);action=Proto.number(fields,1,-1)&255;dual=touchProfile.equals("legacy-dual")||((touchProfile.equals("all-lab")||touchProfile.equals("recognized"))&&fields.containsKey(4)&&fields.containsKey(5));if(touchProfile.equals("legacy-single")&&(fields.containsKey(4)||fields.containsKey(5)))throw new IOException("dual fields outside explicit single profile");if(touchProfile.equals("recognized")&&fields.containsKey(4)!=fields.containsKey(5))throw new IOException("incomplete recognized dual touch coordinate pair");}
            if(ignoreUntilDown&&action!=0){log("touch_ignored","outside-viewport gesture canceled; waiting new DOWN");return;}if(action==0)ignoreUntilDown=false;
            if(frame==null)frame=TouchFrame.legacy(fields,dual,state,sourceDisplayId,generation,SystemClock.uptimeMillis());
            if(state.last!=null&&!state.last.source.equals(frame.source))throw new IOException("touch wire profile changed during gesture");
            TouchFrame mapped=frame.mapped(geometry);if(mapped==null){cancel();ignoreUntilDown=true;logFields("touch_outside_viewport","action",action,"policy","reject outside DOWN; CANCEL active gesture; ignore until fresh DOWN","geometry",geometry.toString());return;}
            apply(mapped);
        }
        synchronized void apply(TouchFrame f)throws IOException{
            state.accept(f);int n=f.ids.length;MotionEvent.PointerProperties[]pp=new MotionEvent.PointerProperties[n];MotionEvent.PointerCoords[]pc=new MotionEvent.PointerCoords[n];
            for(int i=0;i<n;i++){pp[i]=new MotionEvent.PointerProperties();pp[i].id=f.ids[i];pp[i].toolType=MotionEvent.TOOL_TYPE_FINGER;pc[i]=new MotionEvent.PointerCoords();pc[i].x=f.xs[i];pc[i].y=f.ys[i];pc[i].pressure=f.action==1||f.action==3?0:1;pc[i].size=1;}
            MotionEvent e=MotionEvent.obtain(state.downTime,f.timeMs,f.fullAction(),n,pp,pc,0,0,1,1,0,0,0x1002,0);boolean ok=event(e,f.displayId);e.recycle();
            logFields("touch_frame","action",f.action,"action_index",f.actionIndex,"pointer_count",n,"ids",Arrays.toString(f.ids),"xs",Arrays.toString(f.xs),"ys",Arrays.toString(f.ys),"display_id",f.displayId,"generation",f.generation,"source",f.source,"event_time_ms",f.timeMs,"accepted",ok);
            if(!ok){cancel();throw new IOException("Android InputManager rejected touchscreen event");}
        }
        synchronized void cancel(){try{TouchFrame f=state.cancel(SystemClock.uptimeMillis());if(f!=null){state.accept(f);int n=f.ids.length;MotionEvent.PointerProperties[]p=new MotionEvent.PointerProperties[n];MotionEvent.PointerCoords[]c=new MotionEvent.PointerCoords[n];for(int i=0;i<n;i++){p[i]=new MotionEvent.PointerProperties();p[i].id=f.ids[i];p[i].toolType=1;c[i]=new MotionEvent.PointerCoords();c[i].x=f.xs[i];c[i].y=f.ys[i];}MotionEvent e=MotionEvent.obtain(state.downTime,f.timeMs,3,n,p,c,0,0,1,1,0,0,0x1002,0);boolean ok=event(e,f.displayId);e.recycle();logFields("touch_cancel","pointer_count",n,"ids",Arrays.toString(f.ids),"display_id",f.displayId,"generation",f.generation,"accepted",ok);}}catch(Exception e){log("input_cancel_error",e.toString());}}
        synchronized void cancelIfIdle(){if(state.last!=null&&SystemClock.uptimeMillis()-state.last.timeMs>3000){log("touch_timeout","no input update for3s; CANCEL all pointers");cancel();ignoreUntilDown=true;}}
        synchronized void back(){hardkey(14);}
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
