package org.carlife.hulab;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Bounded process path using the current JVM as child; no Android/provider/device calls. */
public final class NativeVideoSettingsCommandTest {
    static int checks;
    interface Task{void run()throws Exception;}
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void rejects(Task task,String text)throws Exception{
        try{task.run();throw new AssertionError("accepted "+text);}catch(IOException e){check(e.getMessage().contains(text),e.toString());}
    }
    static String command(String operation,String value)throws Exception{
        String java=new File(System.getProperty("java.home"),"bin/java"+(System.getProperty("os.name").startsWith("Windows")?".exe":"")).getPath();
        return NativeVideoSettings.command(java,"-cp",System.getProperty("java.class.path"),NativeVideoSettingsCommandTest.class.getName(),operation,value);
    }
    public static void main(String[] args)throws Exception{
        if(args.length>0){
            if(args[0].equals("emit")){System.out.print(args[1]);return;}
            if(args[0].equals("fail")){System.err.print("denied");System.exit(7);}
            if(args[0].equals("large")){for(int i=0;i<5000;i++)System.out.print('x');return;}
            if(args[0].equals("wait")){Thread.sleep(10000);return;}
            throw new IllegalArgumentException("unknown child action");
        }
        String value="1920x1080 @ 30 FPS; $(not executed)";
        check(command("emit",value).equals(value),"argv passes status literally");
        check(command("emit","v1:1920x1080:30\n").trim().equals("v1:1920x1080:30"),"read retains setting");
        rejects(()->command("fail",""),"exit=7");
        rejects(()->command("large",""),"exceeded");
        long start=System.nanoTime();
        rejects(()->command("wait",""),"timeout");
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<3000,"command timeout bounded below3s");
        System.out.println("PASS "+checks+" settings argv/output/exit/timeout checks; no Android calls");
    }
}
