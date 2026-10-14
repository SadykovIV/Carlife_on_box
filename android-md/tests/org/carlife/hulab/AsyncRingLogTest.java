package org.carlife.hulab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class AsyncRingLogTest {
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static class Memory implements AsyncRingLog.Sink {
        final List<String> heads=Collections.synchronizedList(new ArrayList<String>()),tails=Collections.synchronizedList(new ArrayList<String>());
        public void open()throws IOException{}public boolean head(byte[] b)throws IOException{heads.add(new String(b,StandardCharsets.UTF_8));return true;}
        public void tail(byte[] b)throws IOException{tails.add(new String(b,StandardCharsets.UTF_8));}public void close()throws IOException{}
    }
    static final class Slow extends Memory {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        public void open()throws IOException{entered.countDown();try{release.await();}catch(InterruptedException e){throw new IOException(e);}}
    }
    static String record(String event,int n){return "{\"event\":\""+event+"\",\"n\":"+n+"}";}
    static void slowDiskDoesNotBlockSession()throws Exception{
        Slow sink=new Slow();AsyncRingLog logger=new AsyncRingLog(sink,8);check(sink.entered.await(1,TimeUnit.SECONDS),"worker did not enter blocked sink");
        long start=System.nanoTime();
        MdMain.asyncLog=logger;
        try{for(int i=0;i<10000;i++)MdMain.emitLog("codec_au",record("codec_au",i));MdMain.emitLog("connected",record("connected",7));}
        finally{MdMain.asyncLog=null;}
        check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(2),"producer stalled on slow disk");
        check(logger.queued()==8&&logger.dropped.get()>=9993,"tail bound/drop evidence absent");
        start=System.nanoTime();check(!logger.closeWithin(50),"blocked worker falsely reported flushed");
        check(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(500),"close exceeded bounded wait");
        sink.release.countDown();check(logger.closeWithin(1000),"released logger did not drain");
        check(sink.heads.size()==1&&sink.heads.get(0).contains("connected"),"first handshake lost behind saturated tail");
        check(sink.tails.toString().contains("log_stopped")&&sink.tails.toString().contains("\"dropped\":9993"),"terminal counters missing");
    }
    static void rotationAndHeader()throws Exception{
        Path dir=java.nio.file.Files.createTempDirectory("carlife-log-test-").toRealPath();
        try{
            AsyncRingLog.Files sink=new AsyncRingLog.Files(dir.toFile(),16384,8192);sink.open();
            byte[] first=(record("connected",123)+"\n").getBytes(StandardCharsets.UTF_8);check(sink.head(first),"header not written");
            String filler=String.join("",Collections.nCopies(3000,"x"));
            for(int i=0;i<80;i++)sink.tail(("{\"n\":"+i+",\"padding\":\""+filler+"\"}\n").getBytes(StandardCharsets.UTF_8));
            sink.close();
            check(java.nio.file.Files.size(dir.resolve("md.jsonl"))<=16384&&java.nio.file.Files.size(dir.resolve("md.1.jsonl"))<=16384,"tail file bound exceeded");
            check(new String(java.nio.file.Files.readAllBytes(dir.resolve("md.head.jsonl")),StandardCharsets.UTF_8).contains("123"),"first header rotated away");
            check(new String(java.nio.file.Files.readAllBytes(dir.resolve("md.jsonl")),StandardCharsets.UTF_8).contains("\"n\":79"),"latest tail absent");
            for(Path p:java.nio.file.Files.newDirectoryStream(dir)){
                for(String line:java.nio.file.Files.readAllLines(p,StandardCharsets.UTF_8))check(line.startsWith("{")&&line.endsWith("}"),"partial JSON line");
                Set<java.nio.file.attribute.PosixFilePermission> permissions=java.nio.file.Files.getPosixFilePermissions(p);
                check(permissions.equals(EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)),"log mode not0600");
            }
            Path target=dir.resolve("target");java.nio.file.Files.write(target,new byte[]{1});java.nio.file.Files.delete(dir.resolve("md.jsonl"));java.nio.file.Files.createSymbolicLink(dir.resolve("md.jsonl"),target);
            try{new AsyncRingLog.Files(dir.toFile());throw new AssertionError("foreign symlink accepted");}catch(IOException expected){}
            java.nio.file.Files.delete(target);
            try{new AsyncRingLog.Files(dir.toFile());throw new AssertionError("dangling symlink accepted");}catch(IOException expected){}
            java.nio.file.Files.delete(dir.resolve("md.jsonl"));java.nio.file.Files.write(dir.resolve("md.jsonl"),new byte[AsyncRingLog.TAIL_BYTES+1]);
            try{new AsyncRingLog.Files(dir.toFile());throw new AssertionError("oversized previous file escaped ring bound");}catch(IOException expected){}
        }finally{for(Path p:java.nio.file.Files.newDirectoryStream(dir))java.nio.file.Files.delete(p);java.nio.file.Files.delete(dir);}
    }
    static void previousHeadersSurviveProcessAndTailRotation()throws Exception{
        Path dir=java.nio.file.Files.createTempDirectory("carlife-header-archive-test-").toRealPath();
        try{
            for(int process=0;process<7;process++){
                AsyncRingLog.Files sink=new AsyncRingLog.Files(dir.toFile(),16384,8192);sink.open();
                byte[] line=("{\"event\":\"native_boot_identity\",\"boot_id\":\"boot-"+process+"\"}\n").getBytes(StandardCharsets.UTF_8);
                sink.head(line);
                for(int n=0;n<8;n++)sink.tail(new byte[8192]);sink.close();
                for(int old=1;old<=Math.min(process,3);old++){
                    Path archive=dir.resolve("md.head."+old+".jsonl");
                    String expected="{\"event\":\"native_boot_identity\",\"boot_id\":\"boot-"+(process-old)+"\"}\n";
                    check(new String(java.nio.file.Files.readAllBytes(archive),StandardCharsets.UTF_8).equals(expected),"old header changed or lost on tail/process rotation");
                    check(java.nio.file.Files.size(archive)<=8192,"archived header bound");
                    check(java.nio.file.Files.getPosixFilePermissions(archive).equals(EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)),"archived header mode0600");
                }
                check(!java.nio.file.Files.exists(dir.resolve("md.head.4.jsonl")),"archive exceeded3 files");
            }
            byte[] retained=java.nio.file.Files.readAllBytes(dir.resolve("md.head.jsonl"));
            Path old=dir.resolve("md.head.2.jsonl");java.nio.file.Files.delete(old);java.nio.file.Files.createSymbolicLink(old,dir.resolve("absent-target"));
            try{new AsyncRingLog.Files(dir.toFile(),16384,8192);throw new AssertionError("foreign archived header accepted");}catch(IOException expected){}
            java.nio.file.Files.delete(old);java.nio.file.Files.write(old,new byte[8193]);
            try{new AsyncRingLog.Files(dir.toFile(),16384,8192);throw new AssertionError("oversized archived header accepted");}catch(IOException expected){}
            check(Arrays.equals(retained,java.nio.file.Files.readAllBytes(dir.resolve("md.head.jsonl"))),"refusal destroyed current header");
            java.nio.file.Files.delete(old);
            AsyncRingLog.Files delayed=new AsyncRingLog.Files(dir.toFile(),16384,8192);
            java.nio.file.Files.createSymbolicLink(old,dir.resolve("absent-target"));
            try{delayed.open();throw new AssertionError("late foreign archived header accepted");}catch(IOException expected){}
            check(Arrays.equals(retained,java.nio.file.Files.readAllBytes(dir.resolve("md.head.jsonl"))),"open refusal destroyed current header");
        }finally{for(Path p:java.nio.file.Files.newDirectoryStream(dir))java.nio.file.Files.delete(p);java.nio.file.Files.delete(dir);}
        Memory quotaSink=new Memory();AsyncRingLog quota=new AsyncRingLog(quotaSink);
        for(String event:new String[]{"native_touch_mapping","native_touch_ingress","native_touch_recovered","native_touch_unknown","native_output_mode","write_error","backpressure","peer_eof","transport_owner_stopped","session_closed"}){
            int expected=(event.equals("native_touch_mapping")||event.equals("native_touch_ingress"))?32:event.equals("native_touch_recovered")?16:event.equals("native_touch_unknown")?8:4;
            for(int i=0;i<expected;i++)check(quota.reserveHeader(event),"expected diagnostic header quota");
            check(!quota.reserveHeader(event),"diagnostic header quota exceeded");
        }
        check(quota.closeWithin(1000),"quota fixture cleanup");
        Path boundDir=java.nio.file.Files.createTempDirectory("carlife-header-bound-test-").toRealPath();
        try{
            AsyncRingLog.Files bounded=new AsyncRingLog.Files(boundDir.toFile());bounded.open();byte[] line=new byte[8192];
            for(int i=0;i<8;i++)check(bounded.head(line),"header budget ended early");
            check(!bounded.head(new byte[]{1}),"64KiB header limit escaped");bounded.close();
            check(java.nio.file.Files.size(boundDir.resolve("md.head.jsonl"))==65536,"header file exceeds64KiB");
        }finally{for(Path f:java.nio.file.Files.newDirectoryStream(boundDir))java.nio.file.Files.delete(f);java.nio.file.Files.delete(boundDir);}
        Memory memory=new Memory();AsyncRingLog logger=new AsyncRingLog(memory);
        logger.offer("native_boot_identity",record("native_boot_identity",1));logger.offer("native_boot_identity",record("native_boot_identity",2));
        check(logger.closeWithin(1000)&&memory.heads.size()==1&&memory.heads.get(0).contains("\"n\":1"),"boot identity not retained once in header");
    }
    static void oversizeAndIoFailure()throws Exception{
        Memory memory=new Memory();AsyncRingLog logger=new AsyncRingLog(memory);
        logger.offer("codec_csd",String.join("",Collections.nCopies(9000,"x")));check(logger.closeWithin(1000),"oversize logger stop");
        check(logger.oversize.get()==1&&memory.tails.toString().contains("log_line_oversize"),"oversize must become valid bounded evidence");
        for(String s:memory.tails)check(s.getBytes(StandardCharsets.UTF_8).length<=AsyncRingLog.LINE_BYTES,"line size exceeded");
        PrintStream original=System.err;ByteArrayOutputStream errors=new ByteArrayOutputStream();System.setErr(new PrintStream(errors));
        try{
            AsyncRingLog broken=new AsyncRingLog(new Memory(){public void open()throws IOException{throw new IOException("disk full");}});
            check(broken.closeWithin(1000),"failed sink did not stop");check(broken.ioErrors.get()==1&&broken.lastIoError.contains("disk full"),"IO exception not recorded");
            long start=System.nanoTime();for(int i=0;i<10000;i++)broken.offer("codec_au",record("codec_au",i));
            check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1)&&broken.queued()==0,"failed logger backpressures media");
            check(errors.toString("UTF-8").contains("log_io_error"),"worker-only IO error record missing");
        }finally{System.setErr(original);}
    }
    static void concurrentClose()throws Exception{
        for(int round=0;round<20;round++){
            Memory memory=new Memory();final AsyncRingLog logger=new AsyncRingLog(memory);
            Thread[] producers=new Thread[4];for(int t=0;t<producers.length;t++){producers[t]=new Thread(new Runnable(){public void run(){for(int i=0;i<1000;i++)logger.offer("codec_au",record("codec_au",i));}});producers[t].start();}
            logger.closeWithin(1000);for(Thread t:producers)t.join(1000);check(logger.closeWithin(1000),"close race stranded logger");check(logger.queued()==0,"enqueue raced after worker exit");
            check(logger.offered.get()==4000&&logger.written.get()+logger.dropped.get()==4000,"race lost unaccounted record");
        }
    }
    public static void main(String[] args)throws Exception{check(MdMain.validDisplayRequest(true,0,0),"native primary observation rejected");check(!MdMain.validDisplayRequest(false,0,0)&&!MdMain.validDisplayRequest(true,0,1080)&&!MdMain.validDisplayRequest(true,1920,0),"implicit geometry escaped explicit native0/0");check(MdMain.validDisplayRequest(false,1920,1080)&&!MdMain.validDisplayRequest(false,8192,1080),"explicit geometry bounds changed");slowDiskDoesNotBlockSession();rotationAndHeader();previousHeadersSurviveProcessAndTailRotation();oversizeAndIoFailure();concurrentClose();System.out.println("PASS async slow-log backpressure isolation, bounded flush negative control, independent first-handshake retention, real disk rotation/private modes, oversize/IO errors, concurrent close accounting and native-only observation admission");}
}
