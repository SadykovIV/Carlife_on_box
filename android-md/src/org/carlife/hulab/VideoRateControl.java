package org.carlife.hulab;

/** Small writer-measured controller; static tiny AUs never estimate USB capacity. */
final class VideoRateControl {
    static final int FLOOR_BPS=1000000, CEILING_BPS=4000000;
    private long windowStart=-1,videoBytes,busyNs,lastChange=-1,quietSince=-1,lastPressure;
    private double bytesPerSecond;
    private int requested=CEILING_BPS;
    private boolean pendingPressure;
    VideoRateControl(){this(0);}
    VideoRateControl(long initialPressure){lastPressure=initialPressure;}
    synchronized void onWrite(int videoPayloadBytes,long allPacketWriteNs,long now){
        if(videoPayloadBytes<0||allPacketWriteNs<0)throw new IllegalArgumentException("negative writer sample");
        if(windowStart<0)windowStart=now;
        videoBytes+=videoPayloadBytes;busyNs+=allPacketWriteNs;
        if(now-windowStart<1000)return;
        // The denominator includes audio/control writes sharing this same writer.
        if(videoBytes>=32*1024&&busyNs>=50_000_000L){double sample=videoBytes*1_000_000_000.0/busyNs;bytesPerSecond=bytesPerSecond==0?sample:bytesPerSecond*.75+sample*.25;}
        windowStart=now;videoBytes=0;busyNs=0;
    }
    synchronized int bitrateFor(VideoQueue.Snapshot queue,long now){
        // Concurrent producers may hand in an older snapshot after a newer one.
        boolean pressure=queue.pressureEvents>lastPressure||queue.oldestAgeMs>=150;
        lastPressure=Math.max(lastPressure,queue.pressureEvents);
        if(pressure){pendingPressure=true;quietSince=-1;}else if(quietSince<0)quietSince=now;
        if(lastChange>=0&&now-lastChange<1000)return 0;
        int next=requested;
        if(pendingPressure){
            pendingPressure=false;
            next=Math.max(FLOOR_BPS,requested*4/5);
            if(bytesPerSecond>0)next=Math.max(FLOOR_BPS,Math.min(next,(int)Math.min(CEILING_BPS,bytesPerSecond*8*.75)));
        }else if(quietSince>=0&&now-quietSince>=5000&&queue.oldestAgeMs<50&&bytesPerSecond>requested/8.0*1.4){
            next=Math.min(CEILING_BPS,requested+250000);quietSince=now;
        }
        if(next==requested)return 0;
        requested=next;lastChange=now;return next;
    }
    synchronized double drainBytesPerSecond(){return bytesPerSecond;}
    synchronized int requestedBitrate(){return requested;}
}
