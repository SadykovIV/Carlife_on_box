package org.carlife.hulab;

/** Pure ticket identity; RESET/EOF discard pending work, never acknowledge a stale probe. */
final class PendingVideoInit {
    final int width,height,fps;
    final long generation;
    PendingVideoInit(int w,int h,int f,long g){width=w;height=h;fps=f;generation=g;}
    boolean current(PendingVideoInit pending,long actualGeneration,boolean open){
        return open&&pending==this&&actualGeneration==generation;
    }
}
