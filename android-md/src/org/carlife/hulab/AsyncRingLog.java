package org.carlife.hulab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Logging must never perform disk/console I/O while a session/media lock is held. */
final class AsyncRingLog {
    static final int QUEUE_LINES=256, HEAD_QUEUE_LINES=64, LINE_BYTES=8192;
    static final int TAIL_BYTES=1024*1024, HEAD_BYTES=64*1024, PREVIOUS_HEADERS=3;
    interface Sink {void open()throws IOException;boolean head(byte[] line)throws IOException;void tail(byte[] line)throws IOException;void close()throws IOException;}
    private final ConcurrentLinkedQueue<byte[]> tail=new ConcurrentLinkedQueue<byte[]>(),head=new ConcurrentLinkedQueue<byte[]>();
    private final AtomicInteger tailSize=new AtomicInteger(),headSize=new AtomicInteger();
    private final AtomicInteger producers=new AtomicInteger();
    private final ConcurrentHashMap<String,AtomicInteger> headEvents=new ConcurrentHashMap<String,AtomicInteger>();
    final AtomicLong offered=new AtomicLong(),dropped=new AtomicLong(),oversize=new AtomicLong(),headDropped=new AtomicLong(),written=new AtomicLong(),ioErrors=new AtomicLong();
    private final Sink sink;
    private final Thread worker;
    private final int capacity;
    private volatile boolean accepting=true,stopped;
    volatile String lastIoError;

    AsyncRingLog(Sink target){this(target,QUEUE_LINES);}
    AsyncRingLog(Sink target,int queueCapacity){
        if(queueCapacity<1||queueCapacity>QUEUE_LINES)throw new IllegalArgumentException("log queue bounds");
        sink=target;capacity=queueCapacity;
        worker=new Thread(new Runnable(){public void run(){drain();}},"md-log-writer");worker.setDaemon(true);worker.start();
    }
    static boolean reserve(AtomicInteger size,int cap){for(;;){int n=size.get();if(n>=cap)return false;if(size.compareAndSet(n,n+1))return true;}}
    static int headerLimit(String event){
        if(event.equals("rx")||event.equals("tx"))return 8;
        if(event.equals("wire_header"))return 16;
        if(event.equals("native_touch_mapping"))return 32;
        if(event.equals("native_touch_ingress"))return 32;
        if(event.equals("native_touch_recovered"))return 16;
        if(event.equals("native_feature_config"))return 2;
        if(event.equals("native_touch_wire"))return 8;
        if(event.equals("native_touch_unknown"))return 8;
        if(event.equals("native_output_mode")||event.equals("write_error")||event.equals("backpressure")||event.equals("peer_eof")||event.equals("transport_owner_stopped")||event.equals("session_closed")||event.equals("native_owner_retire"))return 4;
        if(event.equals("protocol_version")||event.equals("session_error")||event.equals("native_statistic_handshake")||event.equals("native_fps_negotiation"))return 4;
        if(event.equals("codec_csd"))return 4;
        if(event.equals("native_boot_identity")||event.equals("log_started")||event.equals("primary_display_observed")||event.equals("source_display_state")||event.equals("projection_policy")||event.equals("transport_controller_ready")||event.equals("connected")||event.equals("video_initialized")||event.equals("codec_selection")||event.equals("codec_created")||event.equals("codec_output_format")||event.equals("oem_csd_guard")||event.equals("codec_csd_guard_accepted"))return 1;
        return 0;
    }
    boolean reserveHeader(String event){
        int limit=headerLimit(event);if(limit==0)return false;
        AtomicInteger n=headEvents.get(event);if(n==null){AtomicInteger created=new AtomicInteger();AtomicInteger previous=headEvents.putIfAbsent(event,created);n=previous==null?created:previous;}
        return reserve(n,limit);
    }
    /** Nonblocking bounded enqueue: no sink call, monitor, wait, or console here. */
    void offer(String event,String json){
        producers.incrementAndGet();try{
        offered.incrementAndGet();if(!accepting){dropped.incrementAndGet();return;}
        byte[] bytes=(json+"\n").getBytes(StandardCharsets.UTF_8);
        if(bytes.length>LINE_BYTES){oversize.incrementAndGet();bytes=("{\"event\":\"log_line_oversize\",\"original_bytes\":"+bytes.length+",\"original_record_omitted\":true}\n").getBytes(StandardCharsets.UTF_8);}
        if(reserveHeader(event)){if(reserve(headSize,HEAD_QUEUE_LINES))head.offer(bytes);else headDropped.incrementAndGet();}
        if(reserve(tailSize,capacity))tail.offer(bytes);else dropped.incrementAndGet();
        }finally{producers.decrementAndGet();}
    }
    byte[] poll(ConcurrentLinkedQueue<byte[]> queue,AtomicInteger size){byte[] b=queue.poll();if(b!=null)size.decrementAndGet();return b;}
    String stats(String event){return "{\"event\":\""+event+"\",\"monotonic_ns\":"+System.nanoTime()+",\"offered\":"+offered.get()+",\"tail_written\":"+written.get()+",\"queue_lines\":"+tailSize.get()+",\"queue_capacity\":"+capacity+",\"dropped\":"+dropped.get()+",\"oversize\":"+oversize.get()+",\"header_dropped\":"+headDropped.get()+",\"io_errors\":"+ioErrors.get()+"}\n";}
    void ioError(IOException error){
        ioErrors.incrementAndGet();String message=String.valueOf(error.getMessage());if(message.length()>256)message=message.substring(0,256);
        lastIoError=error.getClass().getSimpleName()+": "+message;
        // Only the logger worker reaches stderr. A broken/blocked error sink can
        // strand this daemon, never a video/USB caller; closeWithin stays bounded.
        System.err.println("{\"event\":\"log_io_error\",\"io_errors\":"+ioErrors.get()+",\"message\":\""+lastIoError.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"}");
    }
    void drain(){
        long reported=-1,lastReport=0;
        try{
            sink.open();
            while(accepting||producers.get()>0||headSize.get()>0||tailSize.get()>0){
                byte[] h=poll(head,headSize);if(h!=null&&!sink.head(h))headDropped.incrementAndGet();
                byte[] t=poll(tail,tailSize);if(t!=null){sink.tail(t);written.incrementAndGet();}
                long now=System.nanoTime(),drops=dropped.get()+oversize.get()+headDropped.get();
                if(drops!=reported&&(now-lastReport>=1000000000L||!accepting)){sink.tail(stats("log_progress").getBytes(StandardCharsets.UTF_8));reported=drops;lastReport=now;}
                if(h==null&&t==null)try{Thread.sleep(10);}catch(InterruptedException ignored){}
            }
            sink.tail(stats("log_stopped").getBytes(StandardCharsets.UTF_8));
        }catch(IOException e){accepting=false;ioError(e);}
        finally{try{sink.close();}catch(IOException e){ioError(e);}stopped=true;}
    }
    /** Bound applies even when a filesystem write/flush does not return. */
    boolean closeWithin(long timeoutMs){
        accepting=false;
        if(Thread.currentThread()==worker)return false;
        try{worker.join(Math.max(1,timeoutMs));}catch(InterruptedException e){Thread.currentThread().interrupt();}
        return stopped;
    }
    int queued(){return tailSize.get();}
    static Sink console(final PrintStream console){return new Sink(){
        public void open(){}public boolean head(byte[] b){return true;}
        public void tail(byte[] b)throws IOException{console.write(b,0,b.length);console.flush();if(console.checkError())throw new IOException("native log console error");}
        public void close()throws IOException{console.flush();if(console.checkError())throw new IOException("native log console close error");}
    };}
    static final class Files implements Sink {
        final File dir,current,previous,header;final File[] archivedHeaders=new File[PREVIOUS_HEADERS];final int tailLimit,headLimit;
        OutputStream tailOut,headOut;long tailBytes,headBytes;
        Files(File directory)throws IOException{this(directory,TAIL_BYTES,HEAD_BYTES);}
        Files(File directory,int tailBound,int headBound)throws IOException{
            if(tailBound<LINE_BYTES||headBound<LINE_BYTES)throw new IllegalArgumentException("file bounds");
            dir=directory;tailLimit=tailBound;headLimit=headBound;
            if(!dir.isAbsolute()||!dir.isDirectory()||!dir.getCanonicalFile().equals(dir.getAbsoluteFile()))throw new IOException("log directory must preexist and have no symlink/relative segments");
            current=new File(dir,"md.jsonl");previous=new File(dir,"md.1.jsonl");header=new File(dir,"md.head.jsonl");
            for(int i=0;i<archivedHeaders.length;i++)archivedHeaders[i]=new File(dir,"md.head."+(i+1)+".jsonl");
            for(File f:new File[]{current,previous})validate(f,tailLimit);
            validate(header,headLimit);for(File f:archivedHeaders)validate(f,headLimit);
        }
        static void validate(File f,int bound)throws IOException{
            if(java.nio.file.Files.isSymbolicLink(f.toPath())||(f.exists()&&(!f.isFile()||!f.getCanonicalFile().equals(f.getAbsoluteFile()))))throw new IOException("foreign log path: "+f.getName());
            if(f.exists()&&f.length()>bound)throw new IOException("existing log exceeds owned bound: "+f.getName());
        }
        static OutputStream create(File f)throws IOException{OutputStream out=new FileOutputStream(f,false);if(!f.setReadable(false,false)||!f.setWritable(false,false)||!f.setReadable(true,true)||!f.setWritable(true,true)){try{out.close();}catch(IOException ignored){}throw new IOException("private log permissions: "+f.getName());}return new BufferedOutputStream(out,16384);}
        void rotate()throws IOException{
            if(tailOut!=null){tailOut.close();tailOut=null;}
            if(previous.exists()&&!previous.delete())throw new IOException("previous log unlink");
            if(current.exists()&&!current.renameTo(previous))throw new IOException("log rotate rename");
            tailOut=create(current);tailBytes=0;
        }
        void rotateHeader()throws IOException{
            // Preserve the last three process headers across boot/open; tail rotation
            // never touches these files. Each header carries its own boot identity.
            validate(header,headLimit);for(File f:archivedHeaders)validate(f,headLimit);
            if(!header.exists())return;
            File oldest=archivedHeaders[archivedHeaders.length-1];
            if(oldest.exists()&&!oldest.delete())throw new IOException("previous header unlink");
            for(int i=archivedHeaders.length-1;i>0;i--)
                if(archivedHeaders[i-1].exists()&&!archivedHeaders[i-1].renameTo(archivedHeaders[i]))throw new IOException("previous header rotate rename");
            if(!header.renameTo(archivedHeaders[0]))throw new IOException("header archive rename");
        }
        public void open()throws IOException{rotateHeader();rotate();headOut=create(header);headBytes=0;}
        public boolean head(byte[] b)throws IOException{if(headBytes+b.length>headLimit)return false;headOut.write(b);headOut.flush();headBytes+=b.length;return true;}
        public void tail(byte[] b)throws IOException{if(tailBytes+b.length>tailLimit)rotate();tailOut.write(b);tailOut.flush();tailBytes+=b.length;}
        public void close()throws IOException{IOException error=null;if(headOut!=null)try{headOut.close();}catch(IOException e){error=e;}if(tailOut!=null)try{tailOut.close();}catch(IOException e){error=e;}if(error!=null)throw error;}
    }
}
