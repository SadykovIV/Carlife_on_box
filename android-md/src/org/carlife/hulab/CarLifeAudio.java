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
    enum Lane {
        MEDIA(3,0x30001,0x30002,0x30006,48000,2),
        TTS(4,0x40001,0x40002,0x40003,16000,1);
        final int channel,initId,stopId,dataId,rate,channels,bits=16,frameBytes,chunkFrames,chunkBytes;
        Lane(int ch,int init,int stop,int data,int hz,int count){channel=ch;initId=init;stopId=stop;dataId=data;rate=hz;channels=count;frameBytes=count*2;chunkFrames=hz/50;chunkBytes=chunkFrames*frameBytes;}
        byte[] init(){return MdMain.Proto.ints(1,rate,2,channels,3,bits);}
    }
    static byte[] init(){return Lane.MEDIA.init();}
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
        final int channel,id,timestamp;final byte[] payload;final long queued,epoch;
        Item(int channel,int id,byte[] p,int time,long now,long token){this.channel=channel;this.id=id;payload=p;timestamp=time;queued=now;epoch=token;}
    }
    /** Same FIFO for INIT/DATA/STOP; only complete independent PCM chunks may be shed. */
    static final class Queue {
        final Lane lane;
        Queue(){this(Lane.MEDIA);}
        Queue(Lane lane){if(lane==null)throw new NullPointerException("audio lane");this.lane=lane;}
        final ArrayDeque<Item> items=new ArrayDeque<Item>();long dropped,offered,written,nonzeroSamples,epoch;boolean initialized;
        synchronized long epoch(){return epoch;}
        synchronized boolean current(Item item){return item.channel==lane.channel&&item.epoch==epoch;}
        synchronized boolean start(long token,long now){if(token!=epoch)return false;Iterator<Item> old=items.iterator();while(old.hasNext())if(old.next().id!=lane.stopId)old.remove();initialized=true;items.add(new Item(lane.channel,lane.initId,lane.init(),0,now,token));return true;}
        synchronized void data(long token,byte[] p,int timestamp,long now)throws IOException{
            if(token!=epoch||!initialized)return;
            if(p.length==0||p.length>lane.chunkBytes||p.length%lane.frameBytes!=0)throw new IOException("PCM chunk alignment/size");
            int data=0;for(Item i:items)if(i.id==lane.dataId)data++;
            if(data>=MAX_QUEUED_DATA){Iterator<Item> it=items.iterator();while(it.hasNext())if(it.next().id==lane.dataId){it.remove();dropped++;break;}}
            items.add(new Item(lane.channel,lane.dataId,p.clone(),timestamp,now,token));offered++;
            for(int i=0;i<p.length;i+=2)if(p[i]!=0||p[i+1]!=0)nonzeroSamples++;
        }
        synchronized void stop(long token,long now){
            if(token!=epoch||!initialized)return;
            // An INIT already in flight remains before STOP in the single transport writer.
            for(Item item:items)if(item.id==lane.dataId)dropped++;items.clear();items.add(new Item(lane.channel,lane.stopId,new byte[0],0,now,token));initialized=false;
        }
        synchronized Item poll(long now){
            while(!items.isEmpty()){Item i=items.remove();if(i.id==lane.dataId&&now-i.queued>MAX_AGE_MS){dropped++;continue;}return i;}return null;
        }
        synchronized int size(){return items.size();}
        synchronized void wrote(){written++;}
        synchronized void appendProgress(List<Object> fields){appendProgress(fields,"audio");}
        synchronized void appendProgress(List<Object> fields,String prefix){Collections.addAll(fields,prefix+"_queue_items",items.size(),prefix+"_pcm_chunks_enqueued",offered,prefix+"_packets_written",written,prefix+"_pcm_chunks_dropped",dropped,prefix+"_nonzero_samples",nonzeroSamples,prefix+"_queue_age_budget_ms",MAX_AGE_MS);}
        synchronized void clear(){epoch++;items.clear();initialized=false;}
    }
}
