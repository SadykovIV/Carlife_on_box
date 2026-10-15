package org.carlife.hulab;

/** Pure ticket identity; RESET/EOF discard pending work, never acknowledge a stale probe. */
final class PendingVideoInit {
    final int width,height,fps,hintWidth,hintHeight,hintFps;
    final long generation;
    PendingVideoInit(int w,int h,int f,long g){this(w,h,f,g,w,h,f);}
    PendingVideoInit(int w,int h,int f,long g,int hw,int hh,int hf){width=w;height=h;fps=f;generation=g;hintWidth=hw;hintHeight=hh;hintFps=hf;}
    boolean current(PendingVideoInit pending,long actualGeneration,boolean open){
        return open&&pending==this&&actualGeneration==generation;
    }
}
