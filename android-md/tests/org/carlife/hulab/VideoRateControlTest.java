package org.carlife.hulab;
public final class VideoRateControlTest {
    static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static VideoQueue.Snapshot queue(long age,long pressure){return new VideoQueue.Snapshot(1,100,age,pressure,0,pressure,1,false);}
    public static void main(String[] args){
        VideoRateControl c=new VideoRateControl();
        c.onWrite(10,1000,0);c.onWrite(10,1000,1000);check(c.drainBytesPerSecond()==0,"tiny static frames cannot invent capacity");
        c.onWrite(100000,100000000,1100);c.onWrite(0,100000000,1200);c.onWrite(100000,100000000,2200);
        check(Math.abs(c.drainBytesPerSecond()-200000/0.3)<1,"audio/control busy time contributes to video capacity");
        check(c.bitrateFor(queue(200,0),2300)==3200000,"congestion reduces bitrate");
        check(c.bitrateFor(queue(220,1),2400)==0,"rate changes are bounded in time");
        for(long t=3400;t<14000;t+=1000)c.bitrateFor(queue(200,t),t);
        check(c.requestedBitrate()==1000000,"sustained pressure respects floor");
        c.bitrateFor(queue(0,13400),15000);check(c.bitrateFor(queue(0,13400),20000)==1250000,"five quiet seconds recover slowly with capacity evidence");
        check(c.bitrateFor(queue(0,13400),21000)==0,"no rapid upward oscillation");
        VideoRateControl idle=new VideoRateControl();idle.bitrateFor(queue(200,1),0);idle.bitrateFor(queue(0,1),1000);check(idle.bitrateFor(queue(0,1),10000)==0,"no capacity estimate means no speculative idle upscale");
        VideoRateControl cooldown=new VideoRateControl();cooldown.bitrateFor(queue(200,1),0);
        check(cooldown.bitrateFor(queue(0,2),500)==0,"transient recovery pressure respects cooldown");
        check(cooldown.bitrateFor(queue(0,2),1000)==2560000,"pressure observed during cooldown is not lost after queue drains");
        VideoRateControl reset=new VideoRateControl(7);
        check(reset.bitrateFor(queue(0,7),1000)==0,"old-generation cumulative drops do not reduce fresh source bitrate");
        check(reset.bitrateFor(queue(0,6),2000)==0&&reset.bitrateFor(queue(0,7),3000)==0,"out-of-order snapshots cannot replay an observed pressure event");
        System.out.println("PASS "+checks+" video bitrate throughput/hysteresis checks");
    }
}
