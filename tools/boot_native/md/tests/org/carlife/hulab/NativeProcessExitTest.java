package org.carlife.hulab;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real forked JVM exit, including an unrelated non-daemon framework-like thread. */
public final class NativeProcessExitTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        if(args.length!=0){child(args[0]);return;}
        run("normal",0,false,false);
        run("controller-failure",1,false,false);
        run("retained-lease",1,false,false);
        run("cleanup-failure",1,false,true);
        run("dev",0,true,false);
        System.out.println("PASS "+checks+" forked JVM owner exit/cleanup/error/non-daemon/DEV checks; no device I/O");
    }
    private static void child(String mode)throws Exception{
        final CountDownLatch forever=new CountDownLatch(1);
        Thread framework=new Thread(new Runnable(){public void run(){try{forever.await();}catch(InterruptedException done){}}},"fixture-framework-non-daemon");
        framework.start();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){public void run(){System.out.println("fixture_shutdown_hook");}},"fixture-shutdown"));
        MdMain.bootNative=!mode.equals("dev");
        if(mode.equals("retained-lease"))MdMain.CAPTURE_LEASE.acquire(new Object(),"fixture pending capture cleanup");
        if(mode.equals("cleanup-failure"))MdMain.server=new ServerSocket(){public void close(){throw new IllegalStateException("fixture cleanup failure");}};
        MdMain.completeMain(!mode.equals("controller-failure"));
        System.out.println("fixture_returned");
        forever.countDown();framework.join(1000);
    }
    private static void run(String mode,int expected,boolean returns,boolean cleanupError)throws Exception{
        String java=new File(new File(System.getProperty("java.home"),"bin"),"java").getPath();
        Process p=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),NativeProcessExitTest.class.getName(),mode).redirectErrorStream(true).start();
        if(!p.waitFor(5,TimeUnit.SECONDS)){p.destroyForcibly();p.waitFor(2,TimeUnit.SECONDS);throw new AssertionError(mode+" kept process alive after owner cleanup");}
        ByteArrayOutputStream data=new ByteArrayOutputStream();InputStream input=p.getInputStream();byte[] chunk=new byte[2048];int n;while((n=input.read(chunk))!=-1)data.write(chunk,0,n);
        String text=data.toString("UTF-8");
        check(p.exitValue()==expected,mode+" exit code: "+p.exitValue()+" output="+text);
        check(text.contains("fixture_returned")==returns,mode+" boot exits and DEV returns");
        check(text.contains("fixture_shutdown_hook"),mode+" runs shutdown hook rather than abrupt halt");
        if(cleanupError)check(text.contains("fixture cleanup failure"),"cleanup failure remains on stderr");
        else check(text.contains("\"event\":\"stopped\""),mode+" existing cleanup precedes exit");
    }
}
