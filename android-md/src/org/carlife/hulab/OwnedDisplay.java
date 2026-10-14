package org.carlife.hulab;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.view.Surface;
import java.lang.reflect.*;
import java.util.concurrent.Executor;

/** Process-owned logical display for isolated DPI experiments. Never changes the primary display. */
final class OwnedDisplay {
    Object manager,callback;Class<?> callbackType;ImageReader consumer;HandlerThread thread;int id=-1;long discarded;
    OwnedDisplay(int width,int height,int density)throws Exception{
        try{
            if(android.os.Process.myUid()!=0)throw new SecurityException("isolated lab display expects the already-existing UID0; no rights elevation");
            consumer=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);thread=new HandlerThread("owned-display-consumer");thread.start();consumer.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener(){public void onImageAvailable(ImageReader r){Image i=null;try{i=r.acquireLatestImage();if(i!=null)discarded++;}finally{if(i!=null)i.close();}}},new Handler(thread.getLooper()));
            Class<?> service=Class.forName("android.os.ServiceManager");IBinder binder=(IBinder)service.getMethod("getService",String.class).invoke(null,"display");Class<?> iface=Class.forName("android.hardware.display.IDisplayManager");manager=Class.forName("android.hardware.display.IDisplayManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
            Class<?> builder=Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");Object b=builder.getConstructor(String.class,int.class,int.class,int.class).newInstance("CarLife-HULab-Isolated",width,height,density);builder.getMethod("setSurface",Surface.class).invoke(b,consumer.getSurface());builder.getMethod("setFlags",int.class).invoke(b,1|2|8|64);Object config=builder.getMethod("build").invoke(b);
            callbackType=Class.forName("android.hardware.display.IVirtualDisplayCallback");callback=Class.forName("android.hardware.display.DisplayManagerGlobal$VirtualDisplayCallback").getConstructor(android.hardware.display.VirtualDisplay.Callback.class,Executor.class).newInstance(null,null);
            id=(Integer)iface.getMethod("createVirtualDisplay",config.getClass(),callbackType,Class.forName("android.media.projection.IMediaProjection"),String.class).invoke(manager,config,callback,null,null);if(id<=0)throw new IllegalStateException("isolated display creation returned"+id);
            MdMain.logFields("owned_display_created","display_id",id,"width",width,"height",height,"density_dpi",density,"density_provenance","explicit isolated laboratory setting","flags",75,"primary_display_changed",false,"consumer","ImageReader discard; no image planes read or saved");
        }catch(Exception e){close();throw e;}
    }
    synchronized void close(){if(callback!=null&&manager!=null){try{Class.forName("android.hardware.display.IDisplayManager").getMethod("releaseVirtualDisplay",callbackType).invoke(manager,callback);MdMain.logFields("owned_display_released","display_id",id,"discarded_buffers",discarded);}catch(Exception e){MdMain.logFields("owned_display_cleanup_error","display_id",id,"exception",MediaCodecDisplaySource.cause(e).toString());}callback=null;}
        if(consumer!=null){consumer.setOnImageAvailableListener(null,null);consumer.close();consumer=null;}if(thread!=null){thread.quitSafely();try{thread.join(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}thread=null;}}
}
