package org.carlife.hulab;
import java.io.IOException;
import java.util.concurrent.*;
/** Cross-session safety: a timed-out source must block a new encoder globally. */
public final class SourceLeaseTest {
 public static void main(String[] ignored)throws Exception {
  final SourceLease lease=new SourceLease();final Object oldSession=new Object(),nextSession=new Object();
  lease.acquire(oldSession,"old session source");
  final CountDownLatch tried=new CountDownLatch(1);final Throwable[] failure={null};
  Thread next=new Thread(new Runnable(){public void run(){try{lease.acquire(nextSession,"new session");failure[0]=new AssertionError("second encoder acquired while first alive");}catch(IOException expected){}finally{tried.countDown();}}});next.start();
  if(!tried.await(2,TimeUnit.SECONDS))throw new AssertionError("next session blocked thread instead of rejecting");next.join();if(failure[0]!=null)throw new AssertionError(failure[0]);
  if(lease.release(nextSession,true))throw new AssertionError("different session released old owner");
  if(lease.release(oldSession,false)||!lease.isHeld())throw new AssertionError("cleanup timeout freed the lease");
  try{lease.acquire(nextSession,"new after timeout");throw new AssertionError("poisoned lease admitted source");}catch(IOException expected){}
  if(!lease.release(oldSession,true)||lease.isHeld())throw new AssertionError("confirmed late cleanup did not free lease");
  lease.acquire(nextSession,"new after confirmed cleanup");if(!lease.release(nextSession,true))throw new AssertionError("new owner cleanup failed");
  System.out.println("PASS cross-session source lease: contention rejected, foreign release denied, unconfirmed cleanup retains ownership, confirmed cleanup admits next source");
 }
}
