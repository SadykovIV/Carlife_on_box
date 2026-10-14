package org.carlife.hulab;
import android.os.SystemClock;
import java.util.concurrent.ArrayBlockingQueue;

/** Verifies observable queue evidence without mutating queue contents or changing their policy. */
public final class PipelineProgressTest {
    public static void main(String[] args)throws Exception {
        ArrayBlockingQueue<MdMain.Packet> queue=new ArrayBlockingQueue<MdMain.Packet>(8);
        SystemClock.fixedTime=100;MdMain.Packet first=new MdMain.Packet(MdMain.packet(2,0x20001,new byte[100],123),3);queue.add(first);
        SystemClock.fixedTime=200;MdMain.Packet second=new MdMain.Packet(MdMain.packet(2,0x20001,new byte[20],456),3);queue.add(second);
        try {
            PipelineProgress.Queue q=PipelineProgress.queue(queue,1000);
            if(q.items!=2||q.wireBytes!=160||q.oldestAgeMs!=900)throw new AssertionError("full wire bytes/oldest queue age incorrect");
            if(queue.size()!=2||queue.peek()!=first)throw new AssertionError("snapshot consumed/reordered packets");
            queue.remove();q=PipelineProgress.queue(queue,1100);
            if(q.items!=1||q.wireBytes!=40||q.oldestAgeMs!=900)throw new AssertionError("dequeue progress not reflected");
            queue.clear();q=PipelineProgress.queue(queue,1200);
            if(q.items!=0||q.wireBytes!=0||q.oldestAgeMs!=null)throw new AssertionError("empty queue age fabricated");
            if(PipelineProgress.seen(0)!=null||PipelineProgress.age(9999,0)!=null||PipelineProgress.age(1100,100)!=1000||PipelineProgress.age(100,200)!=0)throw new AssertionError("never-seen progress or boundary race age incorrect");
            for(int i=0;i<8;i++)queue.add(first);q=PipelineProgress.queue(queue,1200);
            if(q.items!=8||q.wireBytes!=960||queue.remainingCapacity()!=0)throw new AssertionError("capacity snapshot or policy changed");
            if(PipelineProgress.PERIOD_MS!=2000)throw new AssertionError("unexpected diagnostic cadence");
            System.out.println("PASS pipeline snapshot regression: full wire byte count, ages/nulls, non-consuming bounded snapshots; actual writer counters also exercised by RESET regression");
        }finally{SystemClock.fixedTime=-1;}
    }
}
