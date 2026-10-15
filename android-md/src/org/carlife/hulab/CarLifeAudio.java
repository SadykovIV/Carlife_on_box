package org.carlife.hulab;

import java.io.*;
import java.util.*;

/** Independent PCM wire contract from the public Baidu vehicle protocol definitions. */
final class CarLifeAudio {
    static final int MEDIA=3, INIT=0x30001, STOP=0x30002, PAUSE=0x30003,
        RESUME=0x30004, SEEK=0x30005, DATA=0x30006;
    static final int RATE=48000, CHANNELS=2, BITS=16, FRAME_BYTES=4;
    static final int CHUNK_FRAMES=960, CHUNK_BYTES=3840, MAX_QUEUED_DATA=10;
    static final long MAX_AGE_MS=200;
    static byte[] init(){return MdMain.Proto.ints(1,RATE,2,CHANNELS,3,BITS);}
    static byte[] musicStatus(boolean active)throws IOException{
        byte[] entry=MdMain.Proto.ints(1,3,2,active?1:0);
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(MdMain.Proto.ints(1,1));
        MdMain.Proto.varint(out,(2<<3)|2);MdMain.Proto.varint(out,entry.length);out.write(entry);return out.toByteArray();
    }
    // FeatureConfigList repeated nested protobuf; generic Proto.parse cannot retain repeats.
    static Integer transmissionMode(byte[] input)throws IOException{
        ByteArrayInputStream in=new ByteArrayInputStream(input);Integer result=null;int count=0;
        while(in.available()>0){if(++count>128)throw new IOException("feature field count");
            long tag=MdMain.Proto.varint(in);int field=(int)(tag>>>3),wire=(int)(tag&7);
            if(field==0)throw new IOException("feature field zero");
            if(wire==0){MdMain.Proto.varint(in);continue;}
            if(wire==1||wire==5){int n=wire==1?8:4;if(in.skip(n)!=n)throw new EOFException("feature fixed");continue;}
            if(wire!=2)throw new IOException("feature wire type");
            long n=MdMain.Proto.varint(in);if(n<0||n>in.available())throw new EOFException("feature length");
            byte[] body=new byte[(int)n];if(body.length>0&&in.read(body)!=body.length)throw new EOFException();
            if(field!=2)continue;Map<Integer,Object> f=MdMain.Proto.parse(body);Object name=f.get(1);
            if(name instanceof byte[]&&"AUDIO_TRANSMISSION_MODE".equals(new String((byte[])name,"UTF-8"))){
                MdMain.Proto.requireInts(f,2);int value=MdMain.Proto.number(f,2,-1);
                if(result!=null&&result.intValue()!=value)throw new IOException("conflicting audio transmission modes");result=value;
            }
        }return result;
    }
    static final class Item {
        final int id,timestamp;final byte[] payload;final long queued,epoch;
        Item(int id,byte[] p,int time,long now,long token){this.id=id;payload=p;timestamp=time;queued=now;epoch=token;}
    }
    /** Same FIFO for INIT/DATA/STOP; only complete independent PCM chunks may be shed. */
    static final class Queue {
        final ArrayDeque<Item> items=new ArrayDeque<Item>();long dropped,offered,written,nonzeroSamples,epoch;boolean initialized;
        synchronized long epoch(){return epoch;}
        synchronized boolean current(Item item){return item.epoch==epoch;}
        synchronized boolean start(long token,long now){if(token!=epoch)return false;Iterator<Item> old=items.iterator();while(old.hasNext())if(old.next().id!=STOP)old.remove();initialized=true;items.add(new Item(INIT,init(),0,now,token));return true;}
        synchronized void data(long token,byte[] p,int timestamp,long now)throws IOException{
            if(token!=epoch||!initialized)return;
            if(p.length==0||p.length>CHUNK_BYTES||p.length%FRAME_BYTES!=0)throw new IOException("PCM chunk alignment/size");
            int data=0;for(Item i:items)if(i.id==DATA)data++;
            if(data>=MAX_QUEUED_DATA){Iterator<Item> it=items.iterator();while(it.hasNext())if(it.next().id==DATA){it.remove();dropped++;break;}}
            items.add(new Item(DATA,p.clone(),timestamp,now,token));offered++;
            for(int i=0;i<p.length;i+=2)if(p[i]!=0||p[i+1]!=0)nonzeroSamples++;
        }
        synchronized void stop(long token,long now){
            if(token!=epoch||!initialized)return;
            // An INIT already in flight remains before STOP in the single transport writer.
            for(Item item:items)if(item.id==DATA)dropped++;items.clear();items.add(new Item(STOP,new byte[0],0,now,token));initialized=false;
        }
        synchronized Item poll(long now){
            while(!items.isEmpty()){Item i=items.remove();if(i.id==DATA&&now-i.queued>MAX_AGE_MS){dropped++;continue;}return i;}return null;
        }
        synchronized int size(){return items.size();}
        synchronized void wrote(){written++;}
        synchronized void appendProgress(List<Object> fields){Collections.addAll(fields,"audio_queue_items",items.size(),"audio_pcm_chunks_enqueued",offered,"audio_packets_written",written,"audio_pcm_chunks_dropped",dropped,"audio_nonzero_samples",nonzeroSamples,"audio_queue_age_budget_ms",MAX_AGE_MS);}
        synchronized void clear(){epoch++;items.clear();initialized=false;}
    }
}
