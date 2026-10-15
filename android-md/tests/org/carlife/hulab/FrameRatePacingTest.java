package org.carlife.hulab;
public final class FrameRatePacingTest {
    public static void main(String[] args){
        long last=1000000000L;
        for(int fps:new int[]{30,15,30,1,30}){long next=FrameRatePacing.changedDeadline(last+100,fps==1?last-1:last,fps);if(next<=last||next-last<FrameRatePacing.period(fps)-1)throw new AssertionError("PTS/rate "+fps);last=next;}
        if(FrameRatePacing.changedDeadline(5000000000L,1000,15)!=5000000000L)throw new AssertionError("no catchup backlog");
        for(int fps:new int[]{0,-1,61})try{FrameRatePacing.period(fps);throw new AssertionError("invalid fps accepted");}catch(IllegalArgumentException expected){}
        FrameRatePacing.Request old30=new FrameRatePacing.Request(30),pending15=new FrameRatePacing.Request(15),new30=new FrameRatePacing.Request(30);
        if(FrameRatePacing.confirmedFps(new30,old30)!=0||FrameRatePacing.confirmedFps(new30,pending15)!=0||FrameRatePacing.confirmedFps(new30,new30)!=30)throw new AssertionError("rapid30/15/30 must acknowledge latest request identity, not an old equal numericFPS");
        System.out.println("PASS dynamic EGL pacing: 30/15/30/1fps, monotonic PTS, no catchup backlog, bounds, rapid ABA request acknowledgement");
    }
}
