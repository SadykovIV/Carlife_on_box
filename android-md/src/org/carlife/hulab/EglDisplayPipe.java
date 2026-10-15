package org.carlife.hulab;
import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.*;
import android.view.Surface;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One GPU texture draw per scheduled input frame. Never reads pixels to CPU or repeats encoded AUs. */
final class EglDisplayPipe implements Runnable,DrainUntilStopped.Producer {
    final Surface encoderSurface;final ProjectionGeometry geometry;final int fps,sourceDisplay;final long session,generation;
    volatile int appliedFps;
    volatile FrameRatePacing.Request requestedRate,appliedRateRequest;
    final CountDownLatch ready=new CountDownLatch(1),done=new CountDownLatch(1);final AtomicLong arrivals=new AtomicLong();
    volatile boolean stop;volatile String stage="new";volatile Throwable failure;volatile Thread worker;volatile long draws,textureUpdates,missedDeadlines,lastCallbackUptime,lastTextureUpdateUptime,lastDrawUptime,lastSwapStartUptime,lastSwapCompletedUptime,lastPresentationNs,lastSourceTextureTimestampNs;
    EGLDisplay egl=EGL14.EGL_NO_DISPLAY;EGLContext context=EGL14.EGL_NO_CONTEXT;EGLSurface window=EGL14.EGL_NO_SURFACE;
    SurfaceTexture texture;Surface mirrorSurface;Object mirror;HandlerThread callbacks;int program,textureName;
    EglDisplayPipe(Surface s,ProjectionGeometry g,int rate,int display,long sid,long gen){encoderSurface=s;geometry=g;fps=rate;requestedRate=new FrameRatePacing.Request(rate);sourceDisplay=display;session=sid;generation=gen;}
    void requestFrameRate(FrameRatePacing.Request rate){if(rate.fps<1||rate.fps>fps)throw new IllegalArgumentException("FPS exceeds accepted encoder plan");requestedRate=rate;Thread owner=worker;if(owner!=null)owner.interrupt();}
    int appliedFrameRate(){return appliedFps;}
    void start()throws Exception{worker=new Thread(this,"display-egl-pacer");worker.start();if(!ready.await(7,TimeUnit.SECONDS))throw new IOException("EGL initialization timeout");if(failure!=null)throw new IOException("EGL initialization failed",failure);}
    public void requestStop(){stop=true;Thread t=worker;if(t!=null)t.interrupt();}
    public boolean isStopped(){return done.getCount()==0;}
    void fields(String event,Object...args){Object[] x=new Object[args.length+4];System.arraycopy(args,0,x,0,args.length);x[args.length]="session_id";x[args.length+1]=session;x[args.length+2]="generation";x[args.length+3]=generation;MdMain.logFields(event,x);}
    public void run(){try{stage="initialize";
        egl=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);if(egl==EGL14.EGL_NO_DISPLAY)throw new IOException("eglGetDisplay");int[]ver=new int[2];if(!EGL14.eglInitialize(egl,ver,0,ver,1))throw new IOException("eglInitialize");
        int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE};EGLConfig[]configs=new EGLConfig[1];int[]n=new int[1];
        if(!EGL14.eglChooseConfig(egl,attrs,0,configs,0,1,n,0)||n[0]!=1)throw new IOException("recordable EGL config unavailable");
        context=EGL14.eglCreateContext(egl,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);window=EGL14.eglCreateWindowSurface(egl,configs[0],encoderSurface,new int[]{EGL14.EGL_NONE},0);
        if(context==EGL14.EGL_NO_CONTEXT||window==EGL14.EGL_NO_SURFACE||!EGL14.eglMakeCurrent(egl,window,window,context))throw new IOException("EGL context/window current");
        int[]names=new int[1];GLES20.glGenTextures(1,names,0);textureName=names[0];GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureName);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        texture=new SurfaceTexture(textureName);texture.setDefaultBufferSize(geometry.sourceWidth,geometry.sourceHeight);callbacks=new HandlerThread("display-texture-events");callbacks.start();texture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener(){public void onFrameAvailable(SurfaceTexture t){arrivals.incrementAndGet();lastCallbackUptime=SystemClock.uptimeMillis();}},new Handler(callbacks.getLooper()));mirrorSurface=new Surface(texture);
        Class<?> dm=Class.forName("android.hardware.display.DisplayManager");Method create=dm.getDeclaredMethod("createVirtualDisplay",String.class,int.class,int.class,int.class,Surface.class);
        mirror=create.invoke(null,"CarLife-HULab-EGL-"+session+"-"+generation,geometry.sourceWidth,geometry.sourceHeight,sourceDisplay,mirrorSurface);if(mirror==null)throw new IOException("mirror returned null");Object d=mirror.getClass().getMethod("getDisplay").invoke(mirror);
        fields("display_mirror_created","path","egl","source_display_id",sourceDisplay,"virtual_display_id",d.getClass().getMethod("getDisplayId").invoke(d),"geometry",geometry.toString(),"cpu_pixel_readback",false,"gpu_texture_draws_per_input",1);
        program=GLES20.glCreateProgram();GLES20.glAttachShader(program,shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 p; attribute vec2 t; uniform mat4 matrix; varying vec2 uv; void main(){gl_Position=vec4(p,0.0,1.0);uv=(matrix*vec4(t,0.0,1.0)).xy;}"));GLES20.glAttachShader(program,shader(GLES20.GL_FRAGMENT_SHADER,"#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES frame; varying vec2 uv; void main(){gl_FragColor=texture2D(frame,uv);}"));GLES20.glLinkProgram(program);int[]ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);if(ok[0]!=1)throw new IOException("GL link: "+GLES20.glGetProgramInfoLog(program));
        FloatBuffer vertices=floats(new float[]{-1,-1,1,-1,-1,1,1,1}),coords=floats(new float[]{0,0,1,0,0,1,1,1});int p=GLES20.glGetAttribLocation(program,"p"),t=GLES20.glGetAttribLocation(program,"t"),matrix=GLES20.glGetUniformLocation(program,"matrix");
        FrameRatePacing.Request initial=requestedRate;float[]m=new float[16];long consumed=0,period=FrameRatePacing.period(initial.fps),next=System.nanoTime(),report=next,firstDeadline=next+5_000_000_000L;boolean hasFrame=false;appliedFps=initial.fps;appliedRateRequest=initial;ready.countDown();
        while(!stop){
            stage="pace_wait";long now=System.nanoTime();FrameRatePacing.Request requested=requestedRate;if(requested!=appliedRateRequest){period=FrameRatePacing.period(requested.fps);next=FrameRatePacing.changedDeadline(now,lastPresentationNs,requested.fps);appliedFps=requested.fps;appliedRateRequest=requested;fields("egl_frame_rate_applied","fps",appliedFps,"next_presentation_ns",next);}
            if(now<next){long wait=next-now;try{Thread.sleep(wait/1_000_000L,(int)(wait%1_000_000L));}catch(InterruptedException e){if(stop)break;}continue;}
            long available=arrivals.get();if(available!=consumed){stage="update_texture";texture.updateTexImage();texture.getTransformMatrix(m);consumed=available;textureUpdates++;lastTextureUpdateUptime=SystemClock.uptimeMillis();lastSourceTextureTimestampNs=texture.getTimestamp();hasFrame=true;}
            if(!hasFrame){if(now>firstDeadline)throw new IOException("no real display texture within5s");next=now+5_000_000L;continue;}
            stage="draw";GLES20.glViewport(0,0,geometry.videoWidth,geometry.videoHeight);GLES20.glClearColor(0,0,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);GLES20.glViewport(geometry.left,geometry.videoHeight-geometry.top-geometry.contentHeight,geometry.contentWidth,geometry.contentHeight);
            GLES20.glUseProgram(program);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureName);GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"frame"),0);GLES20.glUniformMatrix4fv(matrix,1,false,m,0);GLES20.glEnableVertexAttribArray(p);GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,0,vertices);GLES20.glEnableVertexAttribArray(t);GLES20.glVertexAttribPointer(t,2,GLES20.GL_FLOAT,false,0,coords);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);int error=GLES20.glGetError();if(error!=GLES20.GL_NO_ERROR)throw new IOException("GL error0x"+Integer.toHexString(error));
            lastDrawUptime=SystemClock.uptimeMillis();stage="swap_buffers";lastSwapStartUptime=lastDrawUptime;if(!EGLExt.eglPresentationTimeANDROID(egl,window,next))throw new IOException("eglPresentationTimeANDROID failed");if(!EGL14.eglSwapBuffers(egl,window))throw new IOException("eglSwapBuffers failed0x"+Integer.toHexString(EGL14.eglGetError()));draws++;lastSwapCompletedUptime=SystemClock.uptimeMillis();lastPresentationNs=next;
            next+=period;now=System.nanoTime();if(next<now-period){long missed=(now-next)/period;missedDeadlines+=missed;next+=missed*period;}
            if(now-report>=1_000_000_000L){fields("egl_pacing","submitted",draws,"texture_updates",textureUpdates,"surface_callbacks",arrivals.get(),"repeated_texture_submissions",draws-textureUpdates,"missed_deadlines",missedDeadlines,"target_fps",appliedFps,"last_source_texture_timestamp_ns",texture.getTimestamp());report=now;}
        }
    }catch(Throwable e){failure=MediaCodecDisplaySource.cause(e);fields("egl_error","stage",stage,"exception",failure.toString());}finally{ready.countDown();stage="release";release();fields("egl_stopped","submitted",draws,"texture_updates",textureUpdates,"missed_deadlines",missedDeadlines,"error",failure==null?null:failure.toString());stage="stopped";done.countDown();}}
    void appendProgress(List<Object> f,long now){
        Collections.addAll(f,"egl_stage",stage,"egl_stop_requested",stop,"surface_callbacks",arrivals.get(),"texture_updates",textureUpdates,"encoder_surface_submissions",draws,"egl_missed_deadlines",missedDeadlines,
            "source_callback_last_uptime_ms",PipelineProgress.seen(lastCallbackUptime),"source_callback_age_ms",PipelineProgress.age(now,lastCallbackUptime),"texture_update_last_uptime_ms",PipelineProgress.seen(lastTextureUpdateUptime),"texture_update_age_ms",PipelineProgress.age(now,lastTextureUpdateUptime),
            "gpu_draw_submitted_last_uptime_ms",PipelineProgress.seen(lastDrawUptime),"gpu_draw_progress_age_ms",PipelineProgress.age(now,lastDrawUptime),"encoder_swap_started_last_uptime_ms",PipelineProgress.seen(lastSwapStartUptime),"encoder_swap_completed_last_uptime_ms",PipelineProgress.seen(lastSwapCompletedUptime),"encoder_swap_progress_age_ms",PipelineProgress.age(now,lastSwapCompletedUptime),"encoder_last_presentation_ns",lastSwapCompletedUptime==0?null:lastPresentationNs,"source_texture_timestamp_ns",lastTextureUpdateUptime==0?null:lastSourceTextureTimestampNs,"egl_failure",failure==null?null:failure.toString());
    }
    int shader(int type,String code)throws IOException{int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,code);GLES20.glCompileShader(s);int[]ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]!=1)throw new IOException("GL shader: "+GLES20.glGetShaderInfoLog(s));return s;}
    static FloatBuffer floats(float[]v){FloatBuffer b=ByteBuffer.allocateDirect(v.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(v).position(0);return b;}
    void release(){Thread.interrupted();try{if(mirror!=null){mirror.getClass().getMethod("release").invoke(mirror);mirror=null;fields("display_mirror_released","path","egl");}}catch(Throwable e){record(e);}
        try{if(texture!=null)texture.setOnFrameAvailableListener(null);if(mirrorSurface!=null)mirrorSurface.release();if(texture!=null)texture.release();}catch(Throwable e){record(e);}
        if(callbacks!=null){callbacks.quitSafely();try{callbacks.join(1500);if(callbacks.isAlive())record(new IOException("texture callback thread not stopped"));}catch(InterruptedException e){record(e);}}
        if(egl!=EGL14.EGL_NO_DISPLAY){if(program!=0)GLES20.glDeleteProgram(program);if(textureName!=0)GLES20.glDeleteTextures(1,new int[]{textureName},0);EGL14.eglMakeCurrent(egl,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(window!=EGL14.EGL_NO_SURFACE&&!EGL14.eglDestroySurface(egl,window))record(new IOException("eglDestroySurface failed"));if(context!=EGL14.EGL_NO_CONTEXT&&!EGL14.eglDestroyContext(egl,context))record(new IOException("eglDestroyContext failed"));EGL14.eglReleaseThread();if(!EGL14.eglTerminate(egl))record(new IOException("eglTerminate failed"));}
    }
    void record(Throwable e){Throwable x=MediaCodecDisplaySource.cause(e);if(failure==null)failure=x;else failure.addSuppressed(x);fields("egl_cleanup_error","exception",x.toString());}
}
