package org.carlife.hulab;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small session-owned wire state. Android callbacks never write to USB. */
final class NativeServiceState {
    static final int NAVI=0, MUSIC=2, INFO=0x10035, PROGRESS=0x10036, TURN=0x10030;
    static final class Output {final int id;final byte[] payload;Output(int id,byte[] p){this.id=id;payload=p;}}
    private final LinkedHashMap<Integer,byte[]> pending=new LinkedHashMap<Integer,byte[]>();
    private boolean musicAvailable,naviAvailable,musicSubscribed,naviSubscribed,playing,desay,closed,routeShown,capabilitiesReady;
    private List<Integer> capabilityRequest;
    private byte[] media;private int position;private long lastProgress=-1;
    private YandexNotificationRoute.Turn route;
    synchronized void capabilities(boolean music,boolean navigation){if(closed)return;musicAvailable=music;naviAvailable=navigation;capabilitiesReady=true;try{if(capabilityRequest!=null)ack();replayMedia();replayRoute();}catch(IOException impossible){throw new IllegalStateException(impossible);}}
    private void ack()throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(MdMain.Proto.ints(1,capabilityRequest.size()));for(int v:capabilityRequest)NativeSessionProtocol.bytes(b,2,MdMain.Proto.ints(1,v,2,(v==MUSIC&&musicAvailable||v==NAVI&&naviAvailable)?1:0));put(0x10044,b.toByteArray());}
    synchronized boolean command(int id,byte[] payload)throws IOException {
        if(closed)return false;
        if(id==0x18027){Map<Integer,Object> f=MdMain.Proto.parse(payload);if("20622116".equals(string(f,4)))desay=true;return false;}
        if(id!=0x18043&&id!=0x18045&&id!=0x18046)return false;
        List<Integer> ids=subscriptionIds(payload);
        if(id==0x18043){capabilityRequest=ids;if(capabilitiesReady)ack();return true;}
        for(int v:ids){boolean on=id==0x18045;if(v==MUSIC){musicSubscribed=on;if(on)replayMedia();else{pending.remove(INFO);pending.remove(PROGRESS);}}if(v==NAVI){naviSubscribed=on;if(on)replayRoute();else{pending.remove(TURN);routeShown=false;}}}
        return true;
    }
    synchronized void huInfo(byte[] p)throws IOException {Map<Integer,Object> f=MdMain.Proto.parse(p);if("Desay SV Automotive".equals(string(f,13))&&"G6S".equals(string(f,14)))desay=true;}
    synchronized void media(String source,String song,String artist,String album,String songId,long durationMs,int count,boolean isPlaying,int positionMs,byte[] cover)throws IOException {
        if(closed)return;
        byte[] next=NativeSessionProtocol.join(MdMain.Proto.strings(1,limit(source),2,limit(song),3,limit(artist),4,limit(album),8,limit(songId)),bytes(5,cover==null||cover.length>28000?new byte[0]:cover),MdMain.Proto.ints(6,saturate(durationMs/1000),7,Math.max(0,count),9,0));
        boolean changed=!Arrays.equals(media,next)||playing!=isPlaying;media=next;playing=isPlaying;position=Math.max(0,positionMs);
        if(!playing){pending.remove(INFO);pending.remove(PROGRESS);lastProgress=-1;return;}
        if(changed){lastProgress=-1;replayMedia();}
    }
    synchronized void progress(int positionMs,long now)throws IOException {if(closed)return;int previous=position;position=Math.max(0,positionMs);if(musicSubscribed&&playing&&(lastProgress<0||now-lastProgress>=1000||position<previous)){put(PROGRESS,MdMain.Proto.ints(1,position));lastProgress=now;}}
    synchronized void route(YandexNotificationRoute.Turn turn)throws IOException {if(closed)return;route=turn;replayRoute();}
    synchronized boolean routeNaviWanted(){return !closed&&naviSubscribed&&route!=null&&!desay;}
    synchronized void control(int id,byte[] data){if(id==0x10023){pending.remove(0x10024);pending.remove(0x10022);}if(id==0x10024)pending.remove(0x10023);put(id,data);}
    synchronized Output poll(){if(pending.isEmpty()||closed)return null;Map.Entry<Integer,byte[]> e=pending.entrySet().iterator().next();pending.remove(e.getKey());if(e.getKey()==TURN){try{routeShown=MdMain.Proto.number(MdMain.Proto.parse(e.getValue()),1,3)!=3;}catch(IOException impossible){routeShown=false;}}return new Output(e.getKey(),e.getValue());}
    synchronized void close(){closed=true;pending.clear();route=null;media=null;}
    private void replayMedia()throws IOException {if(musicSubscribed&&musicAvailable&&playing&&media!=null){put(INFO,media);put(PROGRESS,MdMain.Proto.ints(1,position));}}
    private void replayRoute()throws IOException {
        if(!naviSubscribed||!naviAvailable)return;
        if(route==null){if(routeShown)put(TURN,navi(3,24,0));else pending.remove(TURN);return;}
        // No invented road, total route distance or ETA. Only the app's explicit next-turn distance.
        put(TURN,navi(routeShown?2:1,route.nextTurn,route.meters));
    }
    private static byte[] bytes(int field,byte[] value)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();NativeSessionProtocol.bytes(out,field,value);return out.toByteArray();}
    // Two PRIMARY wire schemas reuse field 6: CarLife-MD-Lib requires varint time;
    // Baidu MD 8.9.5 requires bytes turnIconData. Generated protobuf decoders ignore
    // the other wire type. Both forms preserve required-field validity (see dual-schema test).
    private static byte[] navi(int action,int turn,int meters)throws IOException {return NativeSessionProtocol.join(MdMain.Proto.ints(1,action,2,turn,4,0,5,meters,6,0),MdMain.Proto.strings(3,""),bytes(6,new byte[0]));}
    private void put(int id,byte[] p){if(closed)return;switch(id){case INFO:case PROGRESS:case TURN:case 0x10044:case 0x10026:case 0x10022:case 0x10023:case 0x10024:break;default:throw new IllegalArgumentException("unknown native services output slot");}pending.put(id,p);}
    static String string(Map<Integer,Object> f,int k){Object o=f.get(k);return o instanceof byte[]?new String((byte[])o,StandardCharsets.UTF_8):"";}
    static int saturate(long n){return (int)Math.max(0,Math.min(Integer.MAX_VALUE,n));}
    private static String limit(String s){return s==null?"":s.length()>1024?s.substring(0,1024):s;}
    static List<Integer> subscriptionIds(byte[] p)throws IOException {
        ByteArrayInputStream in=new ByteArrayInputStream(p);List<Integer> ids=new ArrayList<Integer>();int fields=0;
        while(in.available()>0){if(++fields>128)throw new IOException("subscription field limit");long key=MdMain.Proto.varint(in);int f=(int)(key>>>3),w=(int)(key&7);if(f==0)throw new IOException("subscription field zero");if(w==0){MdMain.Proto.varint(in);continue;}if(w==1||w==5){int n=w==1?8:4;if(in.skip(n)!=n)throw new EOFException();continue;}if(w!=2)throw new IOException("subscription wire");long n=MdMain.Proto.varint(in);if(n<0||n>in.available())throw new EOFException();byte[] b=new byte[(int)n];in.read(b);if(f==2){Map<Integer,Object> item=MdMain.Proto.parse(b);MdMain.Proto.requireInts(item,1);ids.add(MdMain.Proto.number(item,1,-1));}}
        return ids;
    }
}
