package org.carlife.hulab;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One live Android main looper for the already privileged app_process runtime. */
final class AndroidRuntimeContext {
    private static CountDownLatch ready=new CountDownLatch(1);
    private static boolean started;
    private static Context context;
    private static Throwable failure;
    static synchronized void launch(){
        if(started){
            if(context!=null||ready.getCount()!=0)return;
            Looper loop=Looper.getMainLooper();
            if(loop==null||!loop.getThread().isAlive()){failure=new UnsupportedOperationException("Android main looper unavailable");return;}
            ready=new CountDownLatch(1);new Handler(loop).post(new Runnable(){public void run(){initialize();}});return;
        }
        started=true;
        Looper existing=Looper.getMainLooper();
        if(existing!=null){
            if(!existing.getThread().isAlive()){failure=new IllegalStateException("existing Android main looper has stopped");ready.countDown();return;}
            new Handler(existing).post(new Runnable(){public void run(){initialize();}});return;
        }
        Thread t=new Thread(new Runnable(){public void run(){
            try{Looper.prepareMainLooper();initialize();Looper.loop();}
            catch(Throwable e){failure=e;ready.countDown();}
        }},"carlife-services-main");t.setDaemon(true);t.start();
    }
    private static void initialize(){
        try {
            failure=null;
            Class<?> at=Class.forName("android.app.ActivityThread");
            Object thread=at.getMethod("currentActivityThread").invoke(null);
            if(thread==null)thread=at.getMethod("systemMain").invoke(null);
            context=(Context)at.getMethod("getSystemContext").invoke(thread);
            // ContextImpl uses the calling process UID/PID. Never substitute a package UID.
            if(android.os.Build.VERSION.SDK_INT>=31&&context.getAttributionSource().getUid()!=android.os.Process.myUid())
                throw new SecurityException("runtime attribution UID does not match process");
        }catch(Throwable e){context=null;failure=e;}finally{ready.countDown();}
    }

    static Context get()throws Exception {
        CountDownLatch attempt;synchronized(AndroidRuntimeContext.class){launch();attempt=ready;}if(!attempt.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Android context bootstrap timeout");
        if(failure!=null)throw new IllegalStateException("Android context unavailable",failure);return context;
    }
    static Handler mainHandler()throws Exception {get();return new Handler(Looper.getMainLooper());}
    private AndroidRuntimeContext(){}
}
