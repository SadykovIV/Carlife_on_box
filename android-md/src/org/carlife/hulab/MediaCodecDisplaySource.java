package org.carlife.hulab;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.view.Surface;
import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real display0 -> system virtual-display mirror -> AVC Surface encoder. No generated input frames. */
final class MediaCodecDisplaySource implements Runnable {
    static final String NAME="MediaCodecDisplaySource";
    static final String WIRE_MARKER="DEV_STREAM_OVER_ADB;source=MediaCodecDisplaySource;video=ANNEXB_AU;timestamp=ENCODER_PTS_MS";
    static final int COLOR_SURFACE=0x7f000789;
    static final int PROFILE=MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
    static final int LEVEL=MediaCodecInfo.CodecProfileLevel.AVCLevel31;
    static final int BITRATE=4000000, FPS=30, I_INTERVAL=2;
    static final long REPEAT_US=33333;
    static final String DISPLAY_API="android.hardware.display.DisplayManager.createVirtualDisplay(String,int,int,int,android.view.Surface)";
    static final int DIAGNOSTIC_FIRST_AUS=3;
    // Configuration is set once by the native boot entry before source creation.
    // The shared/DEV entry retains its existing full trace by default.
    static boolean fullVideoTraceDefault=true;
    static void configureVideoDiagnostics(boolean full){fullVideoTraceDefault=full;}
    static boolean retainAuDiagnostic(boolean full,long number,boolean idr){return full||number<=DIAGNOSTIC_FIRST_AUS||idr;}
    static boolean retainBufferDiagnostic(boolean full,long number,int flags){return full||number<=DIAGNOSTIC_FIRST_AUS||(flags&(MediaCodec.BUFFER_FLAG_CODEC_CONFIG|MediaCodec.BUFFER_FLAG_END_OF_STREAM))!=0;}
    final boolean fullVideoTrace;
    final MdMain.Session session;
    final Selection selection;
    final long generation;
    volatile boolean stopRequested;
    final AtomicBoolean syncFrameRequested=new AtomicBoolean();
    volatile Thread thread;
    MediaCodec codec;
    Surface inputSurface;
    Object virtualDisplay;
    volatile EglDisplayPipe eglPipe;
    volatile EglDisplayPipe progressPipe;
    boolean codecStarted,eosObserved;
    volatile String stage="new",api="",terminalError;
    volatile long lastCodecOutputUptime,lastAuUptime,lastOutputPtsUs;
    volatile long buffers,aus,idrs,bytes,encodedAuPayloadBytes,firstPts=Long.MIN_VALUE,lastPts=Long.MIN_VALUE;

    static final class Selection {
        final String name,repeatKey,maxFpsKey;
        final int width,height,fps;
        final boolean realtimePriority;
        final ProjectionGeometry geometry;
        Selection(String n,String r,String f,int w,int h,int rate){this(n,r,f,w,h,rate,false);}
        Selection(String n,String r,String f,int w,int h,int rate,boolean realtime){name=n;repeatKey=r;maxFpsKey=f;width=w;height=h;fps=rate;realtimePriority=realtime;geometry=new ProjectionGeometry(MdMain.displayWidth,MdMain.displayHeight,w,h,0);}
        MediaFormat format(){
            MediaFormat f=MediaFormat.createVideoFormat("video/avc",width,height);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT,COLOR_SURFACE);
            f.setInteger(MediaFormat.KEY_BIT_RATE,BITRATE);
            f.setInteger(MediaFormat.KEY_FRAME_RATE,fps);
            if(realtimePriority)f.setInteger(MediaFormat.KEY_PRIORITY,0);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,I_INTERVAL);
            f.setInteger("profile",PROFILE);f.setInteger("level",LEVEL);
            if(MdMain.videoPath.equals("direct"))f.setLong(repeatKey,REPEAT_US);
            f.setFloat(maxFpsKey,(float)fps);
            return f;
        }
    }

    static Selection select(int width,int height,int fps)throws IOException {
        return select(width,height,fps,false);
    }
    static Selection select(int width,int height,int fps,boolean realtimePriority)throws IOException {
        long macroblocks=((width+15)/16)*((height+15)/16);
        if(width<320||height<180||width>1920||height>1080||fps<1||fps>60||macroblocks>3600||macroblocks*fps>108000)throw new IOException("requested mode exceeds bounded AVC Level31 frame/MBPS limits");
        try {
            String repeat=(String)MediaFormat.class.getField("KEY_REPEAT_PREVIOUS_FRAME_AFTER").get(null);
            if(!"repeat-previous-frame-after".equals(repeat))throw new IOException("unexpected framework repeat-frame key");
            String maxFps=(String)MediaFormat.class.getField("KEY_MAX_FPS_TO_ENCODER").get(null);
            if(!"max-fps-to-encoder".equals(maxFps))throw new IOException("unexpected framework max-FPS key");
            List<MediaCodecInfo> candidates=new ArrayList<MediaCodecInfo>();
            int allCodecs=MediaCodecList.class.getField("ALL_CODECS").getInt(null);
            Object list=MediaCodecList.class.getConstructor(int.class).newInstance(allCodecs);
            MediaCodecInfo[] inventory=(MediaCodecInfo[])MediaCodecList.class.getMethod("getCodecInfos").invoke(list);
            for(MediaCodecInfo info:inventory){
                if(!info.isEncoder()||!info.getName().equals(MdMain.encoderName))continue;
                for(String type:info.getSupportedTypes())if("video/avc".equalsIgnoreCase(type)){candidates.add(info);break;}
            }
            MdMain.logFields("codec_inventory_selection","list_mode","ALL_CODECS","requested_name",MdMain.encoderName,"matching_avc_encoders",candidates.size(),"automatic_fallback",false);
            Collections.sort(candidates,new Comparator<MediaCodecInfo>(){public int compare(MediaCodecInfo a,MediaCodecInfo b){return rank(a.getName())-rank(b.getName());}});
            for(MediaCodecInfo info:candidates){
                MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType("video/avc");
                boolean surface=false,profile=false;
                StringBuilder profiles=new StringBuilder();
                for(int color:caps.colorFormats)surface|=color==COLOR_SURFACE;
                for(MediaCodecInfo.CodecProfileLevel p:caps.profileLevels){if(profiles.length()>0)profiles.append(',');profiles.append(p.profile).append(':').append(p.level);profile|=p.profile==PROFILE&&p.level>=LEVEL;}
                Selection selection=new Selection(info.getName(),repeat,maxFps,width,height,fps,realtimePriority);
                Object video=caps.getClass().getMethod("getVideoCapabilities").invoke(caps);
                boolean sizeRate=(Boolean)video.getClass().getMethod("areSizeAndRateSupported",int.class,int.class,double.class).invoke(video,width,height,(double)fps);
                boolean supported=(Boolean)caps.getClass().getMethod("isFormatSupported",MediaFormat.class).invoke(caps,selection.format());
                Object hw=info.getClass().getMethod("isHardwareAccelerated").invoke(info);
                MdMain.logFields("codec_candidate","name",info.getName(),"hardware_accelerated",hw,"profile_levels",profiles.toString(),"color_formats",Arrays.toString(caps.colorFormats),"surface",surface,"baseline_level31",profile,"size_rate_supported",sizeRate,"format_supported",supported,"bitrate_range",video.getClass().getMethod("getBitrateRange").invoke(video).toString());
                if(surface&&profile&&sizeRate&&supported){
                    int wa=(Integer)video.getClass().getMethod("getWidthAlignment").invoke(video),ha=(Integer)video.getClass().getMethod("getHeightAlignment").invoke(video);if(width%wa!=0||height%ha!=0)throw new IOException("requested size violates actual codec alignment");
                    if(MdMain.csdSource.equals("output-format-oem-guard"))verifyOemGuard();
                    MdMain.logFields("codec_selection","name",info.getName(),"width",width,"height",height,"fps_requested",fps,"max_fps_to_encoder_requested",(float)fps,"profile_requested",PROFILE,"level_requested",LEVEL,"bitrate_requested",BITRATE,"i_frame_interval_s",I_INTERVAL,"source_lab_limit_ms",MdMain.seconds*1000L,"repeat_previous_frame_after_us",MdMain.videoPath.equals("egl")?0:REPEAT_US,"video_path",MdMain.videoPath,"csd_source",MdMain.csdSource,"geometry",selection.geometry.toString(),"width_alignment",wa,"height_alignment",ha,"requested_format",selection.format().toString());
                    return selection;
                }
            }
            throw new IOException("requested encoder "+MdMain.encoderName+" lacks requested Surface AVC Baseline/Level31 mode; no automatic fallback");
        }catch(Exception e){throw new IOException("codec selection failed: "+cause(e),cause(e));}
    }
    static int rank(String n){if(n.equals("OMX.qcom.video.encoder.avc"))return 0;if(n.startsWith("c2.qti.")||n.startsWith("OMX.qcom."))return 1;return 2;}
    static volatile boolean guardVerified;
    static synchronized void verifyOemGuard()throws Exception{
        if(guardVerified)return;String path=android.os.Process.is64Bit()?"/system/lib64/libstagefright.so":"/system/lib/libstagefright.so";MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(path)){byte[]b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}String sha=hex(d.digest());
        if(!sha.equals("e1b2e80012ade83022614c36faf793d8c094a4421a5bb4818b9429ee1b81c1e6"))throw new IOException("OEM CSD guard is only proven for captured64bit library; runtimeSHA="+sha);
        MdMain.logFields("oem_csd_guard","library",path,"sha256",sha,"policy","original outputFormat CSD retained without byte edits; raw config differs only at proven level_idc store; conflicting inband parameter sets reject");guardVerified=true;
    }
    MediaCodecDisplaySource(MdMain.Session s,Selection plan,long g){session=s;selection=plan;generation=g;fullVideoTrace=fullVideoTraceDefault;}
    void start(){session.reportProgress("source_start",this);thread=new Thread(this,"mediacodec-display-source");thread.start();}
    void requestStop(){stopRequested=true;syncFrameRequested.set(false);}
    boolean requestKeyFrame(){if(stopRequested||!session.open||generation!=session.videoGeneration)return false;syncFrameRequested.set(true);return true;}
    boolean awaitStopped(long ms){Thread t=thread;if(t==null||t==Thread.currentThread())return true;try{t.join(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}return !t.isAlive();}

    public void run(){
        Throwable failure=null;
        int lastBufferIndex=-1,lastBufferSize=0,lastBufferOffset=0,lastBufferFlags=0;long lastBufferPtsUs=0;
        AvcAccessUnits units=new AvcAccessUnits(MdMain.csdSource.equals("output-format-oem-guard"));
        long stopAt=MdMain.deadline;
        try {
            stage="create_codec";api="MediaCodec.createByCodecName(String)";
            codec=MediaCodec.createByCodecName(selection.name);
            String actual=(String)codec.getClass().getMethod("getName").invoke(codec);
            MdMain.logFields("codec_created","source",NAME,"requested_name",selection.name,"actual_name",actual,"session_id",session.sessionId,"generation",generation,"video_diagnostics",fullVideoTrace?"full":"first3_idr_config_errors_progress","diagnostic_first_aus",DIAGNOSTIC_FIRST_AUS);
            stage="configure";api="MediaCodec.configure(MediaFormat,Surface,MediaCrypto,CONFIGURE_FLAG_ENCODE)";
            codec.configure(selection.format(),null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            stage="create_input_surface";api="MediaCodec.createInputSurface()";
            inputSurface=(Surface)codec.getClass().getMethod("createInputSurface").invoke(codec);
            stage="start_codec";api="MediaCodec.start()";codec.start();codecStarted=true;
            stage="mirror_display";api=DISPLAY_API;
            if(MdMain.videoPath.equals("egl")){eglPipe=new EglDisplayPipe(inputSurface,selection.geometry,selection.fps,MdMain.sourceDisplayId,session.sessionId,generation);progressPipe=eglPipe;eglPipe.start();}
            else {Class<?> dm=Class.forName("android.hardware.display.DisplayManager");Method mirror=dm.getDeclaredMethod("createVirtualDisplay",String.class,int.class,int.class,int.class,Surface.class);virtualDisplay=mirror.invoke(null,"CarLife-HULab-MediaCodec-"+generation,selection.width,selection.height,MdMain.sourceDisplayId,inputSurface);if(virtualDisplay==null)throw new IOException("display mirror API returned null");Object display=virtualDisplay.getClass().getMethod("getDisplay").invoke(virtualDisplay);Object displayId=display.getClass().getMethod("getDisplayId").invoke(display);MdMain.logFields("display_mirror_created","api",DISPLAY_API,"source_display_id",MdMain.sourceDisplayId,"virtual_display_id",displayId,"width",selection.width,"height",selection.height,"session_id",session.sessionId,"generation",generation,"input","real Android display mirror");}
            stage="drain";api="MediaCodec.dequeueOutputBuffer/getOutputBuffer/releaseOutputBuffer";
            Method getBuffer=codec.getClass().getMethod("getOutputBuffer",int.class);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(!stopRequested&&session.open&&generation==session.videoGeneration&&SystemClock.uptimeMillis()<stopAt){
                if(eglPipe!=null&&eglPipe.failure!=null)throw new IOException("EGL source failed",eglPipe.failure);
                if(syncFrameRequested.getAndSet(false)){
                    // Codec API remains owned by this thread; RESET never races codec release.
                    android.os.Bundle parameters=new android.os.Bundle();parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0);codec.setParameters(parameters);
                    MdMain.logFields("codec_sync_requested","session_id",session.sessionId,"generation",generation,"reason","native HU VIDEO_ENCODER_RESET");
                }
                int index=codec.dequeueOutputBuffer(info,50000);
                if(index==MediaCodec.INFO_TRY_AGAIN_LATER)continue;
                if(index==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)continue;
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat output=codec.getOutputFormat();
                    MdMain.logFields("codec_output_format","name",actual,"format",output.toString(),"session_id",session.sessionId,"generation",generation);
                    if(output.getInteger(MediaFormat.KEY_WIDTH)!=selection.width||output.getInteger(MediaFormat.KEY_HEIGHT)!=selection.height)throw new IOException("codec output dimensions differ from accepted mode");
                    for(int i=0;i<4;i++){String key="csd-"+i;if(!output.containsKey(key))continue;ByteBuffer csd=output.getByteBuffer(key).duplicate();byte[] raw=new byte[csd.remaining()];csd.get(raw);units.addFormatCsd(raw);MdMain.logFields("codec_csd","key",key,"bytes",raw.length,"hex",hex(raw),"session_id",session.sessionId,"generation",generation);}
                    continue;
                }
                if(index<0)throw new IOException("unknown dequeue status "+index);
                lastBufferIndex=index;lastBufferSize=info.size;lastBufferOffset=info.offset;lastBufferFlags=info.flags;lastBufferPtsUs=info.presentationTimeUs;
                AvcAccessUnits.Au au=null;
                try{
                    buffers++;lastCodecOutputUptime=SystemClock.uptimeMillis();lastOutputPtsUs=info.presentationTimeUs;
                    if(info.size<0||info.size>AvcAccessUnits.MAX_AU||info.offset<0)throw new IOException("invalid/oversized codec BufferInfo");
                    ByteBuffer buffer=(ByteBuffer)getBuffer.invoke(codec,index);
                    if(buffer==null&&info.size!=0)throw new IOException("null nonempty codec output buffer");
                    byte[] data=new byte[info.size];
                    if(info.size>0){ByteBuffer view=buffer.duplicate();if((long)info.offset+info.size>view.capacity())throw new IOException("BufferInfo outside buffer");view.position(info.offset);view.limit(info.offset+info.size);view.get(data);}
                    if(retainBufferDiagnostic(fullVideoTrace,buffers,info.flags))MdMain.logFields("codec_buffer","buffer_index",index,"buffer_number",buffers,"pts_us",info.presentationTimeUs,"flags",info.flags,"size",info.size,"offset",info.offset,"session_id",session.sessionId,"generation",generation);
                    if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0)MdMain.logFields("codec_csd","key","BUFFER_FLAG_CODEC_CONFIG","bytes",data.length,"hex",hex(data),"session_id",session.sessionId,"generation",generation);
                    au=units.accept(data,info.presentationTimeUs,info.flags);
                    if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0&&MdMain.csdSource.equals("output-format-oem-guard"))MdMain.logFields("codec_csd_guard_accepted","raw_config_sha256",hex(MessageDigest.getInstance("SHA-256").digest(data)),"guarded_configs",units.guardedConfigs(),"selected","unaltered outputFormat CSD","session_id",session.sessionId,"generation",generation);
                }finally{codec.releaseOutputBuffer(index,false);}
                if(au!=null){
                    if(firstPts==Long.MIN_VALUE)firstPts=au.ptsUs;
                    if(lastPts!=Long.MIN_VALUE&&au.ptsUs<=lastPts)throw new IOException("non-increasing encoder AU PTS");
                    long delta=lastPts==Long.MIN_VALUE?-1:au.ptsUs-lastPts;lastPts=au.ptsUs;lastAuUptime=SystemClock.uptimeMillis();aus++;encodedAuPayloadBytes+=au.bytes.length;if(au.idr)idrs++;
                    if(retainAuDiagnostic(fullVideoTrace,aus,au.idr))MdMain.logFields("codec_au","au_number",aus,"pts_us",au.ptsUs,"pts_delta_us",delta,"flags",au.flags,"is_idr",au.idr,"nal_types",au.nalTypes,"bytes",au.bytes.length,"payload_sha256",hex(MessageDigest.getInstance("SHA-256").digest(au.bytes)),"wire_timestamp_ms",(au.ptsUs/1000L)&0xffffffffL,"session_id",session.sessionId,"generation",generation);
                    stage="queue_au";api="Session.sendWithTimestamp bounded queue offer";session.sendWithTimestamp(MdMain.VIDEO,0x20001,au.bytes,generation,(int)(au.ptsUs/1000L));session.bytes+=au.bytes.length;session.chunks++;bytes+=au.bytes.length;stage="drain";api="MediaCodec.dequeueOutputBuffer/getOutputBuffer/releaseOutputBuffer";
                }
                if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0){eosObserved=true;break;}
            }
            if(units.hasPartial()&&!stopRequested)throw new IOException("source ended with partial AU");
        }catch(Throwable e){
            if(!stopRequested&&session.open){failure=cause(e);StringWriter trace=new StringWriter();failure.printStackTrace(new PrintWriter(trace));MdMain.logFields("codec_source_error","source",NAME,"stage",stage,"api",api,"exception_class",failure.getClass().getName(),"message",String.valueOf(failure.getMessage()),"stack",trace.toString(),"session_id",session.sessionId,"generation",generation,"last_buffer_index",lastBufferIndex,"last_buffer_size",lastBufferSize,"last_buffer_offset",lastBufferOffset,"last_buffer_flags",lastBufferFlags,"last_buffer_pts_us",lastBufferPtsUs,"rights","existing process rights unchanged; no bypass/fallback");}
        }finally{
            Throwable cleanupFailure=release();if(cleanupFailure!=null){if(failure==null)failure=cleanupFailure;else failure.addSuppressed(cleanupFailure);}
            boolean leaseReleased=MdMain.CAPTURE_LEASE.release(this,cleanupFailure==null);MdMain.logFields("capture_lease_released","source",NAME,"session_id",session.sessionId,"generation",generation,"cleanup_confirmed",cleanupFailure==null,"released",leaseReleased,"lease_state",MdMain.CAPTURE_LEASE.state());
            MdMain.logFields("codec_source_stopped","source",NAME,"intentional",stopRequested||!session.open,"source_lab_limit_ms",MdMain.seconds*1000L,"eos_observed",eosObserved,"drained_to_eos",eosObserved,"unflushed_output_count",null,"tail_discard_possible",!eosObserved,"buffers",buffers,"access_units",aus,"idr_count",idrs,"bytes",bytes,"first_pts_us",firstPts,"last_pts_us",lastPts,"session_id",session.sessionId,"generation",generation,"error",failure==null?null:failure.toString());
            terminalError=failure==null?null:failure.toString();stage="stopped";session.reportProgress("source_stopped",this);session.mediaSourceEnded(this);
            if(failure!=null)session.close();
        }
    }
    void appendProgress(List<Object> fields,long now){
        Collections.addAll(fields,"codec_stage",stage,"source_terminal_error",terminalError,"codec_stop_requested",stopRequested,"codec_buffers",buffers,"encoded_aus",aus,"encoded_idrs",idrs,"encoded_au_payload_bytes",encodedAuPayloadBytes,"codec_output_last_uptime_ms",PipelineProgress.seen(lastCodecOutputUptime),"codec_output_age_ms",PipelineProgress.age(now,lastCodecOutputUptime),"codec_output_last_pts_us",lastCodecOutputUptime==0?null:lastOutputPtsUs,"au_last_uptime_ms",PipelineProgress.seen(lastAuUptime),"au_age_ms",PipelineProgress.age(now,lastAuUptime),"au_last_pts_us",lastPts==Long.MIN_VALUE?null:lastPts);
        EglDisplayPipe pipe=progressPipe;if(pipe!=null)pipe.appendProgress(fields,now);
    }
    Throwable release(){
        Throwable failure=null;
        if(eglPipe!=null){
            try {
                final MediaCodec.BufferInfo tailInfo=new MediaCodec.BufferInfo();
                MdMain.logFields("egl_stop_begin","owner_stage",eglPipe.stage,"session_id",session.sessionId,"generation",generation);
                DrainUntilStopped.Result result=DrainUntilStopped.stop(eglPipe,new DrainUntilStopped.Consumer(){public int discardOne(long timeoutUs)throws Exception{
                    if(codec==null||!codecStarted){Thread.sleep(Math.max(1,timeoutUs/1000));return 0;}
                    int index=codec.dequeueOutputBuffer(tailInfo,timeoutUs);
                    if(index>=0){try{if((tailInfo.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)eosObserved=true;}finally{codec.releaseOutputBuffer(index,false);}return 1;}
                    if(index==MediaCodec.INFO_TRY_AGAIN_LATER||index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED||index==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)return 0;
                    throw new IOException("unexpected stop-drain dequeue status "+index);
                }},2000);
                MdMain.logFields("egl_stop_drain","owner_stage",eglPipe.stage,"producer_stopped",result.stopped,"discarded_tail_buffers",result.discarded,"polls",result.polls,"elapsed_ms",result.elapsedMs,"session_id",session.sessionId,"generation",generation);
                if(!result.stopped)return cleanupFailure(null,"egl_pipe",new IOException("EGL owner not stopped after concurrent output drain; retain codec/surface and globallease until process exit"));
            }catch(Exception e){return cleanupFailure(null,"egl_stop_drain",e);}
            if(eglPipe.failure!=null)failure=cleanupFailure(failure,"egl_pipe",eglPipe.failure);eglPipe=null;
        }
        if(virtualDisplay!=null){try{virtualDisplay.getClass().getMethod("release").invoke(virtualDisplay);MdMain.logFields("display_mirror_released","session_id",session.sessionId,"generation",generation);}catch(Exception e){failure=cleanupFailure(failure,"virtual_display",e);}virtualDisplay=null;}
        if(codec!=null&&codecStarted&&inputSurface!=null){try{codec.signalEndOfInputStream();long end=SystemClock.uptimeMillis()+600;int tail=0;MediaCodec.BufferInfo i=new MediaCodec.BufferInfo();while(SystemClock.uptimeMillis()<end){int n=codec.dequeueOutputBuffer(i,50000);if(n>=0){tail++;codec.releaseOutputBuffer(n,false);if((i.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0){eosObserved=true;break;}}}MdMain.logFields("codec_stop_drain","session_id",session.sessionId,"generation",generation,"discarded_tail_buffers",tail,"eos_observed",eosObserved);}catch(Exception e){failure=cleanupFailure(failure,"signal_drain_eos",e);}}
        if(inputSurface!=null){try{inputSurface.release();MdMain.logFields("input_surface_released","session_id",session.sessionId,"generation",generation);}catch(Exception e){failure=cleanupFailure(failure,"input_surface",e);}inputSurface=null;}
        if(codec!=null){if(codecStarted)try{codec.stop();MdMain.logFields("codec_stopped","session_id",session.sessionId,"generation",generation);}catch(Exception e){failure=cleanupFailure(failure,"codec_stop",e);}try{codec.release();MdMain.logFields("codec_released","session_id",session.sessionId,"generation",generation);}catch(Exception e){failure=cleanupFailure(failure,"codec_release",e);}codec=null;codecStarted=false;}
        return failure;
    }
    Throwable cleanupFailure(Throwable first,String resource,Throwable error){Throwable actual=cause(error);MdMain.logFields("codec_cleanup_error","resource",resource,"error",actual.toString(),"session_id",session.sessionId,"generation",generation);if(first==null)return actual;first.addSuppressed(actual);return first;}
    static Throwable cause(Throwable e){while(e instanceof InvocationTargetException&&((InvocationTargetException)e).getCause()!=null)e=((InvocationTargetException)e).getCause();return e;}
    static String hex(byte[] b){StringBuilder s=new StringBuilder(b.length*2);for(byte x:b){s.append(Character.forDigit((x>>>4)&15,16));s.append(Character.forDigit(x&15,16));}return s.toString();}
}
