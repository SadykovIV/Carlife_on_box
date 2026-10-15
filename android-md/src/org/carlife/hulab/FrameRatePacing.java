package org.carlife.hulab;
/** Pure pacing arithmetic shared with the EGL worker and host tests. */
final class FrameRatePacing {
    static final class Request {
        final int fps;
        Request(int fps){period(fps);this.fps=fps;}
    }
    static int confirmedFps(Request latest,Request applied){return latest!=null&&latest==applied?latest.fps:0;}
    static long period(int fps){if(fps<1||fps>60)throw new IllegalArgumentException("fps");return 1_000_000_000L/fps;}
    static long changedDeadline(long now,long lastPresentation,int fps){return Math.max(now,lastPresentation+period(fps));}
}
