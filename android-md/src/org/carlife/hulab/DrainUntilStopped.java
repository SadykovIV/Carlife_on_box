package org.carlife.hulab;

/** A Surface producer may finish its current swap only while the codec consumer keeps draining. */
final class DrainUntilStopped {
    interface Producer {void requestStop();boolean isStopped();}
    interface Consumer {int discardOne(long timeoutUs)throws Exception;}
    static final class Result {
        final boolean stopped;final int polls,discarded;final long elapsedMs;
        Result(boolean s,int p,int d,long e){stopped=s;polls=p;discarded=d;elapsedMs=e;}
    }
    static Result stop(Producer producer,Consumer consumer,long timeoutMs)throws Exception {
        if(timeoutMs<1||timeoutMs>3000)throw new IllegalArgumentException("bounded producer stop timeout");
        final long started=System.nanoTime(),deadline=started+timeoutMs*1000000L;
        producer.requestStop();int polls=0,discarded=0;
        while(!producer.isStopped()){
            long remaining=deadline-System.nanoTime();if(remaining<=0)break;
            // No output goes to transport after the generation was invalidated. Each native dequeue
            // is bounded; returning codec buffers lets an already-entered EGL swap complete.
            discarded+=consumer.discardOne(Math.max(1,Math.min(50000,remaining/1000)));polls++;
        }
        return new Result(producer.isStopped(),polls,discarded,(System.nanoTime()-started)/1000000L);
    }
}
