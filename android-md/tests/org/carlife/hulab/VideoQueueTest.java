package org.carlife.hulab;
public final class VideoQueueTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static final class Frame {final int bytes;final long generation,time;final boolean idr,heartbeat;Frame(int b,long g,long t,boolean i,boolean h){bytes=b;generation=g;time=t;idr=i;heartbeat=h;}}
    static final VideoQueue.Adapter<Frame> ADAPTER=new VideoQueue.Adapter<Frame>(){
        public int bytes(Frame p){return p.bytes;}public long generation(Frame p){return p.generation;}public long queuedAt(Frame p){return p.time;}public boolean recoveryPoint(Frame p){return p.idr;}public boolean heartbeat(Frame p){return p.heartbeat;}
    };
    static Frame f(int bytes,long generation,long time,boolean idr){return new Frame(bytes,generation,time,idr,false);}
    public static void main(String[] args){
        VideoQueue<Frame> q=new VideoQueue<Frame>(ADAPTER);
        check(!q.offer(f(100,1,0,false),1,0),"P before first recovery rejected");
        check(q.consumeSyncRequest(0)&&!q.consumeSyncRequest(10)&&q.consumeSyncRequest(1500),"sync request throttled and retried while waiting");
        Frame idr=f(500000,1,1500,true);check(q.offer(idr,1,1500),"one large complete IDR admitted");
        check(q.snapshot(1500).pressureEvents==0,"one complete large IDR alone is not congestion");
        check(!q.offer(f(100,1,1501,false),1,1501),"P over large IDR byte budget refused");
        check(q.poll(1,1501)==idr,"new P cannot erase the only large IDR before writer sees it");
        check(!q.offer(f(100,1,1502,false),1,1502),"lost dependency chain still waits after old IDR sent");
        check(q.offer(f(100,1,1503,true),1,1503),"new recovery accepted");
        for(int i=0;i<7;i++)check(q.offer(f(100,1,1504+i,false),1,1504+i),"bounded P chain accepted");
        check(!q.offer(f(100,1,1511,false),1,1511)&&q.isEmpty(),"frame limit clears unsent dependency chain");
        check(!q.offer(f(100,1,1512,false),1,1512),"P following overflow remains suppressed");
        Frame fresh=f(100,2,1600,true);check(q.offer(fresh,2,1600),"new generation accepted");
        check(!q.offer(f(100,1,1601,true),1,1601),"producer with stale current generation cannot revert queue");
        check(!q.offer(f(100,1,1601,true),2,1601),"stale packet rejected against current generation");
        check(q.poll(1,1601)==null&&q.poll(2,1601)==fresh,"stale writer cannot clear or consume new queue");
        check(q.offer(f(100,2,1700,false),2,1700),"chain survives normal writer polling");
        check(q.poll(2,1951)==null&&q.snapshot(1951).awaitingIdr,"stale P chain expires before write");
        check(q.consumeSyncRequest(1951),"age recovery requests sync");
        Frame beat=new Frame(20,2,1952,false,true);check(q.offer(beat,2,1952)&&q.poll(2,1952)==beat&&q.snapshot(1952).awaitingIdr,"pause heartbeat does not pretend to recover decoder");
        check(q.offer(f(200000,2,1960,true),2,1960),"ordinary IDR");
        check(!q.offer(f(100000,2,1961,false),2,1961)&&q.snapshot(1961).bytes==0,"byte limit enforced below frame limit");
        check(!q.offer(f(VideoQueue.MAX_SINGLE_BYTES+1,2,1962,true),2,1962),"single frame bound enforced");
        q.clear();check(q.isEmpty()&&q.snapshot(2000).awaitingIdr&&!q.consumeSyncRequest(2000),"clear restores wait without scheduling spurious sync");
        q.offer(f(100,2,2000,true),2,2000);q.poll(2,2000);
        Frame largeP=f(550784+20,2,2001,false);
        check(q.offer(largeP,2,2001)&&q.snapshot(2001).items==1&&!q.snapshot(2001).awaitingIdr,"one1080 scene-change P fits alone with intact prior chain");
        check(q.poll(2,2001)==largeP,"large P reaches writer without silently losing reference");
        check(q.offer(f(100,2,2002,false),2,2002),"dependent P after normally written large P remains admissible");q.poll(2,2002);
        Frame maximumP=f(VideoQueue.MAX_SINGLE_BYTES,2,2003,false);
        check(q.offer(maximumP,2,2003),"one complete AU up to2MiB+headers allowed");
        check(!q.offer(f(100,2,2004,false),2,2004)&&q.snapshot(2004).awaitingIdr,"later dropped P marks dependency loss");
        check(q.poll(2,2004)==maximumP,"later congestion does not erase already admitted large P");
        check(!q.offer(f(100,2,2005,false),2,2005),"after a lost P only real IDR can restore dependency chain");
        q.clear();check(!q.offer(largeP,2,2006),"large P cannot bypass initial/recovery IDR requirement");
        // Continuous production into a stalled writer stays bounded and never waits.
        long started=System.nanoTime();for(int i=0;i<100000;i++){long now=2000+i;q.offer(f(10000,2,now,i%30==0),2,now);VideoQueue.Snapshot s=q.snapshot(now);if(s.bytes>VideoQueue.MAX_SINGLE_BYTES||s.items>8)throw new AssertionError("unbounded queue");}
        check((System.nanoTime()-started)/1000000<5000,"100k blocked-writer offers never wait for transport");
        System.out.println("PASS "+checks+" video queue dependency/byte/age/generation/recovery checks");
    }
}
