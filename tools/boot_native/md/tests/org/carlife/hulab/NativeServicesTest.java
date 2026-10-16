package org.carlife.hulab;
import java.io.*;
import java.util.*;
/** Pure producer/wire regressions. Android service availability is a device acceptance item. */
public final class NativeServicesTest {
    static int checks;
    static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    static byte[] subscription(int... ids)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(MdMain.Proto.ints(1,ids.length));for(int v:ids)NativeSessionProtocol.bytes(b,2,MdMain.Proto.ints(1,v));return b.toByteArray();}
    static Map<Integer,Object> fields(NativeServiceState.Output out,int id)throws Exception{check(out!=null&&out.id==id,"output "+id);return MdMain.Proto.parse(out.payload);}
    static int n(Map<Integer,Object> f,int k){return MdMain.Proto.number(f,k,-1);}
    public static void main(String[] args)throws Exception {
        YandexNotificationRoute.Turn t=YandexNotificationRoute.parse("ru.yandex.yandexnavi","Через 300 м поверните направо",null,null);
        check(t!=null&&t.nextTurn==3&&t.meters==300,"explicit RU right");
        t=YandexNotificationRoute.parse("ru.yandex.yandexmaps",null,"← 1,2 км",null);check(t!=null&&t.nextTurn==7&&t.meters==1200,"glyph km");
        check(YandexNotificationRoute.parse("ru.yandex.yandexmaps",null,"Turn left in 80 meters",null).nextTurn==7,"EN left");
        check(YandexNotificationRoute.parse("other.app",null,"← 80 м",null)==null,"package allowlist");
        for(String bad:new String[]{"Через 300 м","← домой","300 м налево, затем направо","Маршрут построен","1,5 часа до прибытия"})check(YandexNotificationRoute.parse("ru.yandex.yandexnavi",bad,null,null)==null,"do not invent "+bad);
        check(YandexNotificationRoute.parse("ru.yandex.yandexnavi","300 м","налево",null)==null,"no mixing disconnected fields");
        NativeServiceState s=new NativeServiceState();check(s.command(0x18043,subscription(0,2,9)),"subscribe consumed");check(s.poll()==null,"ACK waits for real API capability result");s.capabilities(true,true);
        NativeServiceState.Output ack=s.poll();check(NativeServiceState.subscriptionIds(ack.payload).equals(Arrays.asList(0,2,9)),"ACK echoes requested IDs");
        check(!s.command(0x18099,new byte[0]),"unrelated command remains session-owned");
        s.media("player","Title","Artist","Album","track",65432,5,true,1234,new byte[]{1,2,3});check(s.poll()==null,"no unsolicited metadata");s.command(0x18045,subscription(2));Map<Integer,Object> f=fields(s.poll(),0x10035);check(n(f,6)==65,"duration seconds");check(Arrays.equals((byte[])f.get(5),new byte[]{1,2,3}),"real bounded cover bytes");check("Title".equals(NativeServiceState.string(f,2)),"metadata text");check(n(fields(s.poll(),0x10036),1)==1234,"progress milliseconds");
        s.progress(2000,1000);s.progress(3000,1500);check(n(fields(s.poll(),0x10036),1)==2000,"progress <=1Hz");check(s.poll()==null,"no periodic backlog");s.progress(4000,2000);check(n(fields(s.poll(),0x10036),1)==4000,"progress after interval");
        s.media("player","Title","Artist","Album","track",65432,5,false,4000,new byte[]{1});s.command(0x18045,subscription(2));check(s.poll()==null,"paused metadata does not take radio");
        s.route(t);check(s.routeNaviWanted(),"route activity precedes Honda subscription");s.command(0x18045,subscription(0));s.route(t);check(s.routeNaviWanted(),"route NAVI wanted");NativeServiceState.Output naviWire=s.poll(0);StringBuilder wireHex=new StringBuilder();for(byte value:naviWire.payload)wireHex.append(String.format("%02x",value&255));System.out.println("NAVI_DUAL_SCHEMA_HEX="+wireHex);f=fields(naviWire,0x10030);check(n(f,1)==1,"coalesced first route remains SHOW");check(n(f,2)==7&&n(f,5)==1200,"actual turn/distance");check("".equals(NativeServiceState.string(f,3))&&n(f,4)==1200,"unknown road empty; first maneuver distance retained");check(f.get(6) instanceof byte[],"new Baidu icon wire6 present with legacy time");
        s.route(t);check(s.poll()==null,"duplicate route not repeated");
        s.route(new YandexNotificationRoute.Turn(t.nextTurn,1100,"← 1,1 км"));check(n(fields(s.poll(2000),0x10030),1)==2,"subsequent distance UPDATE");
        s.huInfo(NativeSessionProtocol.join(MdMain.Proto.strings(13,"Desay SV Automotive",14,"other")));check(s.routeNaviWanted(),"no whole-vendor quirk");s.huInfo(MdMain.Proto.strings(13,"Desay SV Automotive",14,"G6S"));check(!s.routeNaviWanted(),"exact G6S route hold suppressed");s.route(t);check(s.poll(3000)!=null,"G6S still receives route payload");
        s.route(null);f=fields(s.poll(),0x10030);check(n(f,1)==3&&n(f,2)==24&&f.containsKey(3),"route ends with complete required fields");
        NativeServiceState fresh=new NativeServiceState();fresh.capabilities(true,true);fresh.command(0x18045,subscription(0));fresh.route(t);fresh.route(null);check(fresh.poll()==null,"unsent route does not emit orphan END");fresh.route(t);fresh.command(0x18027,MdMain.Proto.strings(4,"20622116"));check(!fresh.routeNaviWanted(),"exact firmware channel quirk");
        NativeServiceState newer=new NativeServiceState();newer.capabilities(true,true);newer.command(0x18045,subscription(0));newer.route(t);check(newer.routeNaviWanted(),"new logical session resets HU quirk");
        newer.control(0x10024,new byte[0]);newer.control(0x10023,new byte[0]);while((ack=newer.poll())!=null)check(ack.id!=0x10024,"cancel removes unsent mic-start");
        for(int i=0;i<1000;i++){newer.control(0x10023,new byte[0]);newer.media("p","s"+i,"a","b","id",10000,1,true,i,new byte[0]);newer.command(0x18043,subscription(0,2));}
        boolean stop=false,done=false;int count=0;while((ack=newer.poll())!=null){count++;if(ack.id==0x10023)stop=true;if(ack.id==0x10044)done=true;}check(stop&&done&&count<=8,"metadata storm cannot evict lifecycle/ACK slots");
        newer.close();newer.route(t);newer.control(0x10024,new byte[0]);check(newer.poll()==null&&!newer.routeNaviWanted(),"closed session drops delayed callbacks");
        NativeServiceState mic=new NativeServiceState();
        mic.microphone(true,false);check(mic.poll()==null,"idle recognizer does not stop app microphone");
        mic.microphone(false,true);fields(mic.poll(),0x10024);check(mic.poll()==null,"raw MIC arbiter alone does not own VR; production app uses applicationMicrophone");
        mic.microphone(false,true);mic.microphone(true,true);check(mic.poll()==null,"shared capture starts only once");
        mic.microphone(true,false);check(mic.poll()==null,"recognizer completion preserves app recording");
        mic.microphone(false,false);fields(mic.poll(),0x10023);check(mic.poll()==null,"last consumer stops capture");
        mic.microphone(true,true);mic.microphone(false,true);mic.microphone(false,false);fields(mic.poll(),0x10024);check(mic.poll()==null,"app completion preserves recognizer");
        mic.microphone(true,false);mic.microphone(true,true);fields(mic.poll(),0x10024);check(mic.poll()==null,"rapid restart removes pending stop");
        mic.microphone(true,false);fields(mic.poll(),0x10023);mic.microphone(true,false);check(mic.poll()==null,"duplicate stop is idempotent");
        mic.close();mic.microphone(false,true);check(mic.poll()==null,"late recorder callback cannot restart closed session");
        try{NativeServiceState.subscriptionIds(new byte[]{18,10,1});throw new AssertionError("truncation accepted");}catch(IOException expected){checks++;}
        System.out.println("NativeServicesTest PASS "+checks+" checks; synthetic Yandex text fixtures, no hardware claim");
    }
}
