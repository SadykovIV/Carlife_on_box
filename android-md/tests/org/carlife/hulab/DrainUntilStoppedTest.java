package org.carlife.hulab;

import java.io.IOException;
import java.util.concurrent.*;

/** Deterministic bounded BufferQueue analogue: producer's in-flight swap requires consumer drain. */
public final class DrainUntilStoppedTest {
    static final class SurfaceProducer implements DrainUntilStopped.Producer,Runnable {
        final ArrayBlockingQueue<Integer> output=new ArrayBlockingQueue<Integer>(1);
        final CountDownLatch entered=new CountDownLatch(1),done=new CountDownLatch(1);
        volatile boolean stopping;volatile Throwable error;
        final Thread worker=new Thread(this,"test-in-flight-surface-producer");
        SurfaceProducer(){output.add(1);}
        public void requestStop(){stopping=true;}
        public boolean isStopped(){return done.getCount()==0;}
        public void run(){try{entered.countDown();output.put(2);if(!stopping)throw new AssertionError("producer released before stop request");}catch(Throwable e){error=e;}finally{done.countDown();}}
    }
    public static void main(String[] args)throws Exception {
        final SurfaceProducer surface=new SurfaceProducer();surface.worker.start();
        if(!surface.entered.await(1,TimeUnit.SECONDS))throw new AssertionError("producer not entered");
        surface.requestStop();
        // Negative control reproduces the old ordering: joining while no one drains cannot finish.
        if(surface.done.await(80,TimeUnit.MILLISECONDS))throw new AssertionError("negative control failed: full consumer should block swap");
        DrainUntilStopped.Result result;
        try {
            result=DrainUntilStopped.stop(surface,new DrainUntilStopped.Consumer(){public int discardOne(long timeoutUs)throws Exception{return surface.output.poll(timeoutUs,TimeUnit.MICROSECONDS)==null?0:1;}},500);
        } finally {
            // Never leave a host test blocked even if the implementation regresses.
            if(!surface.isStopped())surface.worker.interrupt();surface.worker.join(1000);
        }
        if(!result.stopped||result.discarded<1||surface.error!=null||surface.worker.isAlive())throw new AssertionError("concurrent drain did not unblock stopped producer");
        final DrainUntilStopped.Producer wedged=new DrainUntilStopped.Producer(){public void requestStop(){}public boolean isStopped(){return false;}};
        final SourceLease lease=new SourceLease();Object owner=new Object();lease.acquire(owner,"test stalled producer");
        DrainUntilStopped.Result timeout=DrainUntilStopped.stop(wedged,new DrainUntilStopped.Consumer(){public int discardOne(long us)throws Exception{TimeUnit.MICROSECONDS.sleep(us);return 0;}},80);
        if(timeout.stopped||timeout.elapsedMs>500||timeout.elapsedMs<60)throw new AssertionError("unresponsive producer did not remain bounded");
        if(lease.release(owner,timeout.stopped))throw new AssertionError("unconfirmed cleanup released lease");
        try{lease.acquire(new Object(),"second producer");throw new AssertionError("second producer admitted after timeout");}catch(IOException expected){}
        boolean propagated=false;
        try{DrainUntilStopped.stop(wedged,new DrainUntilStopped.Consumer(){public int discardOne(long us)throws Exception{throw new IOException("native dequeue failed");}},80);}catch(IOException expected){propagated=true;}
        if(!propagated)throw new AssertionError("drain failure hidden as cleanup success");
        final int[] calls={0};DrainUntilStopped.Result already=DrainUntilStopped.stop(new DrainUntilStopped.Producer(){public void requestStop(){}public boolean isStopped(){return true;}},new DrainUntilStopped.Consumer(){public int discardOne(long us){calls[0]++;return 0;}},80);
        if(!already.stopped||calls[0]!=0)throw new AssertionError("unnecessary consume after producer stopped");
        System.out.println("PASS old join-before-drain negative control; production stop/drain barrier unblocks in-flight producer; timeout retains lease; drain failure propagates; already stopped does not dequeue");
    }
}
