package org.carlife.hulab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Bounded platform settings CLI calls, only on the readiness worker.
 * app_process has no registered package/application thread for SettingsProvider's
 * Context API. The existing platform command uses its supported shell Binder path.
 */
final class NativeVideoSettings {
    static final int OUTPUT_LIMIT=4096;
    static final long COMMAND_TIMEOUT_MS=2000;
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
