package org.carlife.hulab;
import java.util.Collection;

/** Small diagnostic snapshots only; never changes queue admission, ordering or timeout policy. */
final class PipelineProgress {
    static final long PERIOD_MS=2000;
    static final class Queue {
        final int items;final long wireBytes;final Long oldestAgeMs;
        Queue(int n,long bytes,Long age){items=n;wireBytes=bytes;oldestAgeMs=age;}
    }
    static Queue queue(Collection<MdMain.Packet> packets,long now){
        int count=0;long bytes=0,oldest=Long.MAX_VALUE;
        // The two ArrayBlockingQueues copy at most8 or32 references under their own short lock.
        // No logging or transport I/O occurs under that lock.
        for(Object value:packets.toArray()){MdMain.Packet packet=(MdMain.Packet)value;count++;bytes+=packet.data.length;oldest=Math.min(oldest,packet.queued);}
        return new Queue(count,bytes,count==0?null:Math.max(0,now-oldest));
    }
    static Long seen(long time){return time==0?null:time;}
    static Long age(long now,long time){return time==0?null:Math.max(0,now-time);}
}
