package org.carlife.hulab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.io.File;
import android.os.FileObserver;

/** Bounded platform settings CLI calls, only on the readiness worker.
 * app_process has no registered package/application thread for SettingsProvider's
 * Context API. The existing platform command uses its supported shell Binder path.
 */
final class NativeVideoSettings {
    static final int OUTPUT_LIMIT=4096;
    static final long COMMAND_TIMEOUT_MS=2000;
    /** Root app_process observes SettingsProvider's committed global file.
     * Watch the parent because AtomicFile replaces the file by rename. A change
     * only schedules one bounded CLI read: no polling subprocess, XML parsing,
     * duplicate state store, or Android ApplicationThread registration is needed.
     */
    static final class ChangeWatch implements AutoCloseable {
        static final String DIRECTORY="/data/system/users/0",FILENAME="settings_global.xml";
        private final AtomicLong revision=new AtomicLong();
        private FileObserver observer;
        private boolean closed;
        synchronized void start()throws Exception {
            if(closed||observer!=null)return;
            File directory=new File(DIRECTORY),global=new File(directory,FILENAME);
            if(!directory.isDirectory()||!directory.canRead()||!global.isFile()||!global.canRead())
                throw new IOException("global settings file unavailable for existing runtime UID");
            FileObserver next=new FileObserver(DIRECTORY,FileObserver.CLOSE_WRITE|FileObserver.MOVED_TO
                    |FileObserver.CREATE|FileObserver.DELETE|FileObserver.DELETE_SELF|FileObserver.MOVE_SELF){
                @Override public void onEvent(int event,String path){fileEvent(this,event,path);}
            };
            observer=next;
            try{next.startWatching();}
            catch(RuntimeException failure){observer=null;next.stopWatching();throw failure;}
            // Initial read after registration closes the read/register race.
            changed();
        }
        private synchronized void fileEvent(FileObserver source,int event,String path){
            if(closed||observer!=source)return;
            if((event&(FileObserver.DELETE_SELF|FileObserver.MOVE_SELF))!=0){
                observer=null;source.stopWatching();
                MdMain.logFields("video_settings_watch_invalidated","event",event,"retry",true);
            }else if(FILENAME.equals(path))changed();
        }
        synchronized boolean registered(){return observer!=null;}
        synchronized void changed(){if(!closed)revision.incrementAndGet();}
        long revision(){return revision.get();}
        public synchronized void close(){
            if(closed)return;closed=true;
            FileObserver old=observer;observer=null;if(old!=null)old.stopWatching();
        }
    }
    static NativeVideoConfig read()throws Exception {
        String value=command("/system/bin/settings","get","global",NativeVideoConfig.KEY).trim();
        return NativeVideoConfig.saved(value.equals("null")?null:value);
    }
    static void publish(String status)throws Exception {
        command("/system/bin/settings","put","global",NativeVideoConfig.STATUS_KEY,status);
    }
    static String command(String... argv)throws Exception {
        Process process=new ProcessBuilder(argv).redirectErrorStream(true).start();
        final InputStream stream=process.getInputStream();
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final boolean[] oversized={false};
        final IOException[] failure={null};
        Thread drain=new Thread(new Runnable(){public void run(){
            try{byte[] bytes=new byte[512];int count;while((count=stream.read(bytes))!=-1){
                int keep=Math.min(count,Math.max(0,OUTPUT_LIMIT-output.size()));
                if(keep>0)output.write(bytes,0,keep);if(keep<count)oversized[0]=true;
            }}catch(IOException e){failure[0]=e;}
        }},"video-settings-command-output");drain.setDaemon(true);drain.start();
        try{
            if(!process.waitFor(COMMAND_TIMEOUT_MS,TimeUnit.MILLISECONDS))throw new IOException("settings command timeout");
            drain.join(250);
            if(drain.isAlive())throw new IOException("settings command output did not close");
            if(failure[0]!=null)throw failure[0];
            if(oversized[0])throw new IOException("settings command output exceeded "+OUTPUT_LIMIT+" bytes");
            String result=new String(output.toByteArray(),StandardCharsets.UTF_8);
            if(process.exitValue()!=0)throw new IOException("settings command failed exit="+process.exitValue()+": "+result.trim());
            return result;
        }finally{
            if(process.isAlive())process.destroyForcibly();
            try{stream.close();}catch(IOException ignored){}
            process.getOutputStream().close();
        }
    }
    private NativeVideoSettings(){}
}
