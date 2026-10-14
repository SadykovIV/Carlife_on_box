package org.carlife.hulab;

import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Standalone bounded cancellation preflight. Opens the existing accessory node,
 * starts only an idle read, then uses the same cancellation path as the pilot.
 * Does not send USB data or change configuration; never run alongside the MD.
 */
public final class AccessoryIoProbe {
    public static void main(String[] args){
        if(args.length!=0){System.err.println("No arguments accepted");System.exit(64);return;}
        final AccessoryTransport transport;
        try{transport=new AccessoryTransport(new AndroidAccessoryIo());}
        catch(Exception e){MdMain.logFields("accessory_probe_result","status","OPEN_FAILED","exception",e.toString());System.exit(65);return;}
        MdMain.logFields("accessory_probe_opened","path",AndroidAccessoryIo.PATH,"transport","DIRECT_USB_AOA","mode_writes",0,"data_writes",0);
        try{Thread.sleep(300);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        final long readCalls=transport.kernelReadCalls();final boolean readInFlight=transport.kernelReadInFlight();final String readerFailure=transport.readerFailure();
        final CountDownLatch closeReturned=new CountDownLatch(1);
        final Throwable[] failure=new Throwable[1];
        Thread close=new Thread(new Runnable(){public void run(){try{transport.close();}catch(Throwable e){failure[0]=e;}finally{closeReturned.countDown();}}},"md-accessory-probe-close");
        close.setDaemon(true);long start=System.nanoTime();close.start();
        boolean returned=false;try{returned=closeReturned.await(1500,TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        boolean stopped=returned&&failure[0]==null&&transport.awaitStopped(1500);
        boolean exercised=readCalls>0&&readInFlight&&readerFailure==null;
        MdMain.logFields("accessory_probe_result","status",!stopped?"CLOSE_NOT_QUIESCENT":exercised?"CLOSE_QUIESCENT":"CLOSE_QUIESCENT_READ_NOT_EXERCISED","close_returned",returned,"io_stopped",stopped,"kernel_read_calls_before_close",readCalls,"kernel_read_inflight_before_close",readInFlight,"reader_failure_before_close",readerFailure,"close_elapsed_ms",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),"exception",failure[0]==null?null:failure[0].toString(),"scope","idle read cancellation only; attached bulk/write cancellation requires separate live acceptance");
        // This dedicated preflight process is the final containment boundary if the
        // installed driver ignores cancellation; it never hands a live fd to MD.
        System.exit(stopped&&exercised?0:73);
    }
}
