package org.carlife.hulab;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.lang.reflect.Method;

/** Early command ownership, deferred capability admission. Never binds USB or alters selection. */
final class BootNativeReadiness implements Runnable {
    private final File stateFile;
    private final String nonce,bootId,startTime;
    private final int pid=android.os.Process.myPid(),uid=android.os.Process.myUid();
    private volatile boolean stopped,commandReady,mediaReady;
    private volatile String stage="BOOTSTRAP",failure="";
    private Thread worker;
    private long sequence,lastWaitingLog;
    private String lastPublished="",lastVideoStatus="";
    private long nextSettingsStatusAttempt;
    private MediaCodecDisplaySource.Selection defaultSelection;

    BootNativeReadiness(String path,String nonce)throws Exception {
        if(path==null||nonce==null||!nonce.matches("[0-9a-f]{32}"))throw new IOException("boot-native requires explicit owned state file and nonce32");
        stateFile=new File(path);
        if(!stateFile.isAbsolute()||!stateFile.getCanonicalFile().equals(stateFile.getAbsoluteFile()))throw new IOException("boot state path must be canonical absolute");
        if(!stateFile.getName().equals("md.boot-state"))throw new IOException("boot state filename must be md.boot-state");
        StructStat parent=Os.lstat(stateFile.getParent());
        if(!OsConstants.S_ISDIR(parent.st_mode)||parent.st_uid!=uid||(parent.st_mode&0777)!=0700)throw new IOException("boot state parent ownership/mode");
        checkExisting();
        this.nonce=nonce;
        bootId=readLine("/proc/sys/kernel/random/boot_id");
        if(!bootId.matches("[0-9a-f-]{36}"))throw new IOException("boot ID format");
        String stat=readLine("/proc/self/stat");String[] fields=stat.substring(stat.lastIndexOf(") ")+2).split(" +");
        if(fields.length<20||!fields[19].matches("[0-9]+"))throw new IOException("process start time unavailable");
        startTime=fields[19];publish();
    }
    static String readLine(String path)throws IOException {try(BufferedReader r=new BufferedReader(new FileReader(path))){String s=r.readLine();if(s==null||s.length()>8192)throw new IOException("missing/oversize file "+path);return s;}}
    void start(){worker=new Thread(this,"boot-native-media-readiness");worker.setDaemon(true);worker.start();}
    void commandReady(boolean ready){commandReady=ready;publishQuietly();}
    void stop(){stopped=true;commandReady=false;mediaReady=false;stage="STOPPED";if(worker!=null)worker.interrupt();publishQuietly();}
    static boolean servicePresent(String name)throws Exception {
        Class<?> s=Class.forName("android.os.ServiceManager");
        return s.getDeclaredMethod("checkService",String.class).invoke(null,name)!=null;
    }
    void mediaProbe()throws Exception {
        // Service existence is a prerequisite observation, not sys.boot_completed.
        for(String service:new String[]{"display","SurfaceFlinger","media.player","input"}){
            stage="WAIT_SERVICE_"+service.replace('.','_');
            if(!servicePresent(service))throw new NotReady(stage);
        }
        stage="WAIT_PRIMARY_DISPLAY";
        Class<?> global=Class.forName("android.hardware.display.DisplayManagerGlobal");Object manager=global.getMethod("getInstance").invoke(null);
        if(manager==null)throw new NotReady(stage);
        android.view.Display d=(android.view.Display)global.getMethod("getRealDisplay",int.class).invoke(manager,0);
        if(d==null)throw new NotReady(stage);
        android.graphics.Point size=new android.graphics.Point();d.getRealSize(size);
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();d.getRealMetrics(metrics);
        if(!MdMain.validDisplayRequest(false,size.x,size.y))throw new NotReady("WAIT_VALID_DISPLAY_GEOMETRY");
        if(d.getRotation()!=0)throw new IOException("unsupported source rotation; existing geometry adapter unchanged");
        if(MdMain.displayWidth==0&&MdMain.displayHeight==0){MdMain.displayWidth=size.x;MdMain.displayHeight=size.y;}
        if(size.x!=MdMain.displayWidth||size.y!=MdMain.displayHeight)throw new IOException("actual source differs from declared geometry");
        if(MdMain.sourceDensity!=0&&MdMain.sourceDensity!=metrics.densityDpi)throw new IOException("actual density differs from declared source");
        MdMain.sourceDensity=metrics.densityDpi;
        stage="WAIT_ENCODER_CAPABILITIES";
        defaultSelection=MediaCodecDisplaySource.select(1280,720,30,true);
        MdMain.logFields("boot_media_capabilities_ready","width",size.x,"height",size.y,"density_dpi",metrics.densityDpi,"rotation",d.getRotation(),"encoder",defaultSelection.name,"validation","real display + advertised codec mode; no encoder resources allocated","boot_id",bootId);
        if(stopped||!MdMain.running)return;
        mediaReady=true;failure="";stage="MEDIA_CAPABILITIES_READY";publish();
    }
    public void run(){
        long firstWait=SystemClock.uptimeMillis();boolean deadlineReported=false;
        while(!stopped&&MdMain.running){
            try {
                if(!mediaReady){
                    try{mediaProbe();}
                    catch(NotReady e){stage=e.getMessage();failure="";}
                    catch(Exception e){failure=MediaCodecDisplaySource.cause(e).toString();}
                    long now=SystemClock.uptimeMillis();
                    if(!mediaReady&&(now-lastWaitingLog>=5000||lastWaitingLog==0)){
                        lastWaitingLog=now;MdMain.logFields("boot_media_wait","boot_id",bootId,"stage",stage,"error",failure,"elapsed_ms",now-firstWait,"transport_kept_open",true,"sys_boot_completed_required",false);
                        publishQuietly();
                    }
                    if(!mediaReady&&!deadlineReported&&now-firstWait>=30000){deadlineReported=true;MdMain.logFields("boot_media_wait_deadline","stage",stage,"elapsed_ms",now-firstWait,"action","remain selected; bounded readiness samples only; no USB rebind or OEM fallback");}
                }
                MdMain.Session s=MdMain.active;
                PendingVideoInit ticket=s==null?null:s.pendingInit;
                if(mediaReady&&ticket!=null&&ticket.current(s.pendingInit,s.videoGeneration,s.open)){
                    try {
                        int width=ticket.width,height=ticket.height,fps=ticket.fps;
                        String mode=MdMain.nativeVideoMode;
                        if(s.transport.isNativeUsb()){
                            NativeVideoConfig config=s.cachedVideoConfig(ticket);
                            if(config==null){
                                NativeVideoConfig observed;
                                try{observed=NativeVideoSettings.read();}
                                catch(Exception unavailable){observed=NativeVideoConfig.DEFAULT;MdMain.logFields("video_settings_unavailable","error",unavailable.toString(),"fallback","auto:auto; HU hint and codec capability; next logical session retries");}
                                config=s.acceptVideoConfig(ticket,observed);
                            }
                            if(config==null)continue;
                            VideoMode requested=config.requested(ticket.hintWidth,ticket.hintHeight,ticket.hintFps);
                            width=requested.width;height=requested.height;fps=requested.fps;mode=config.mode;
                            MdMain.logFields("video_settings_snapshot","session_id",s.sessionId,"logical_session",s.nativeLogicalSession,"generation",ticket.generation,"configuration",config.encoded(),"provenance",config.provenance,"hu_width",ticket.hintWidth,"hu_height",ticket.hintHeight,"hu_fps",ticket.hintFps,"requested_width",width,"requested_height",height,"requested_fps",fps,"changes_apply","next logical VERSION session");
                        }
                        if(!ticket.current(s.pendingInit,s.videoGeneration,s.open))continue;
                        MediaCodecDisplaySource.Selection selected=width==1280&&height==720&&fps==30?defaultSelection:MdMain.selectOutput(width,height,fps,true,mode);
                        s.completeBootInit(ticket,selected);
                    }catch(Exception e){boolean current=s.failBootInitIfCurrent(ticket);MdMain.logFields("boot_media_init_failed","session_id",s.sessionId,"generation",ticket.generation,"error",e.toString(),"init_done_sent",false,"current_ticket",current);}
                }
                // Optional status must not spawn the platform settings CLI while media services are still starting.
                // A failed SettingsProvider status write never closes an otherwise working USB session.
                String status=s==null?"No active CarLife session":s.videoSettingsStatus;
                long now=SystemClock.uptimeMillis();
                if(mediaReady&&status!=null&&!status.equals(lastVideoStatus)&&now>=nextSettingsStatusAttempt){
                    try{NativeVideoSettings.publish(status);lastVideoStatus=status;}
                    catch(Exception unavailable){nextSettingsStatusAttempt=now+5000;MdMain.logFields("video_status_unavailable","error",unavailable.toString(),"session_affected",false);}
                }
                Thread.sleep(mediaReady?100:deadlineReported?1000:250);
            }catch(InterruptedException e){if(stopped)break;Thread.currentThread().interrupt();break;}
        }
    }
    static final class NotReady extends Exception{NotReady(String s){super(s);}}
    private void checkExisting()throws Exception {
        try{StructStat s=Os.lstat(stateFile.getPath());if(!OsConstants.S_ISREG(s.st_mode)||s.st_uid!=uid||(s.st_mode&0777)!=0600)throw new IOException("foreign boot state file");}
        catch(android.system.ErrnoException e){if(e.errno!=OsConstants.ENOENT)throw e;}
    }
    private synchronized void publish()throws Exception {
        if(stopped){stage="STOPPED";commandReady=false;mediaReady=false;}
        String state=stage+"|"+commandReady+"|"+mediaReady+"|"+failure;
        if(state.equals(lastPublished))return;
        checkExisting();
        File tmp=new File(stateFile.getParentFile(),".md.boot-state."+pid+"."+(sequence+1));
        if(!tmp.createNewFile())throw new IOException("boot state staging path already exists");
        boolean renamed=false;
        try{
            Os.chmod(tmp.getPath(),0600);
            String body="contract=BOOT_NATIVE_MD_V1\nboot_id="+bootId+"\nnonce="+nonce+"\npid="+pid+"\nstart="+startTime+"\nsequence="+(sequence+1)+"\nuptime_ms="+SystemClock.uptimeMillis()+"\ncommand_ready="+commandReady+"\nmedia_ready="+mediaReady+"\nstage="+stage+"\nerror_present="+(!failure.isEmpty())+"\nmedia_validation=DISPLAY_PLUS_CODEC_CAPABILITIES_NOT_ENCODER_START\nsource_display_id=0\nsource_width="+MdMain.displayWidth+"\nsource_height="+MdMain.displayHeight+"\nsource_density="+MdMain.sourceDensity+"\n";
            try(FileOutputStream f=new FileOutputStream(tmp)){f.write(body.getBytes("UTF-8"));}
            checkExisting();Os.rename(tmp.getPath(),stateFile.getPath());renamed=true;sequence++;lastPublished=state;
        }finally{if(!renamed&&!tmp.delete())MdMain.log("boot_state_cleanup_error",tmp.getName());}
    }
    private void publishQuietly(){try{publish();}catch(Exception e){MdMain.logFields("boot_state_write_error","error",e.toString(),"publication_not_confirmed",true);}}
}
