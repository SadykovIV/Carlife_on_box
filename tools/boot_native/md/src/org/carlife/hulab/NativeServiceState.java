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
    private int microphoneConsumers,voiceConsumers;
    private long lastWrittenVrRunning=-1,lastWrittenVrIdle=-1;
    static final long VOICE_CONTROL_ECHO_MS=1500;
    private YandexNotificationRoute.Turn route;
    private int maneuverDistance;private long lastRouteSent=-1;private boolean urgentRoute;
    private byte[] lastRouteContent;
    synchronized void capabilities(boolean music,boolean navigation){if(closed)return;musicAvailable=music;naviAvailable=navigation;capabilitiesReady=true;try{if(capabilityRequest!=null)ack();replayMedia();replayRoute();}catch(IOException impossible){throw new IllegalStateException(impossible);}}
    private void ack()throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(MdMain.Proto.ints(1,capabilityRequest.size()));for(int v:capabilityRequest)NativeSessionProtocol.bytes(b,2,MdMain.Proto.ints(1,v,2,(v==MUSIC&&musicAvailable||v==NAVI&&naviAvailable)?1:0));put(0x10044,b.toByteArray());}
    synchronized boolean command(int id,byte[] payload)throws IOException {
        if(closed)return false;
        if(id==0x18027){Map<Integer,Object> f=MdMain.Proto.parse(payload);if("20622116".equals(string(f,4)))desay=true;return false;}
        if(id!=0x18043&&id!=0x18045&&id!=0x18046)return false;
        List<Integer> ids=subscriptionIds(payload);
        if(id==0x18043){capabilityRequest=ids;if(capabilitiesReady)ack();return true;}
        for(int v:ids){boolean on=id==0x18045;if(v==MUSIC){musicSubscribed=on;if(on)replayMedia();else{pending.remove(INFO);pending.remove(PROGRESS);}}if(v==NAVI){naviSubscribed=on;if(on){lastRouteContent=null;urgentRoute=true;replayRoute();}else{pending.remove(TURN);routeShown=false;lastRouteContent=null;}}}
        return true;
    }
    synchronized void huInfo(byte[] p)throws IOException {Map<Integer,Object> f=MdMain.Proto.parse(p);if("Desay SV Automotive".equals(string(f,13))&&"G6S".equals(string(f,14)))desay=true;}
    synchronized void media(String source,String song,String artist,String album,String songId,long durationMs,int count,boolean isPlaying,int positionMs,byte[] cover)throws IOException {
        if(closed)return;
        byte[] next=NativeSessionProtocol.join(MdMain.Proto.strings(1,limit(source),2,utf8Limit(song,255),3,utf8Limit(artist,255),4,limit(album),8,limit(songId)),bytes(5,cover==null||cover.length>28000?new byte[0]:cover),MdMain.Proto.ints(6,saturate(durationMs/1000),7,Math.max(0,count),9,0));
        boolean changed=!Arrays.equals(media,next)||playing!=isPlaying;media=next;playing=isPlaying;position=Math.max(0,positionMs);
        if(!playing){pending.remove(INFO);pending.remove(PROGRESS);lastProgress=-1;return;}
        if(changed){lastProgress=-1;replayMedia();}
    }
    synchronized void progress(int positionMs,long now)throws IOException {if(closed)return;int previous=position;position=Math.max(0,positionMs);if(musicSubscribed&&playing&&(lastProgress<0||now-lastProgress>=1000||position<previous)){put(PROGRESS,MdMain.Proto.ints(1,position));lastProgress=now;}}
    synchronized void route(YandexNotificationRoute.Turn turn)throws IOException {
        if(closed)return;
        boolean changed=turn==null||route==null||!turn.maneuverKey().equals(route.maneuverKey());
        if(changed){maneuverDistance=turn==null?0:turn.meters;urgentRoute=true;}
        else if(turn.meters>maneuverDistance)maneuverDistance=turn.meters;
        route=turn;replayRoute();
    }
    // Honda starts TBT subscription AFTER NAVI_RUNNING. Activity must not wait for it.
    // Desay G6S/20622116 exception matches HeadSwap's exact HU quirk, not all Desay HUs.
    synchronized boolean routeNaviWanted(){return !closed&&route!=null&&!desay;}
    synchronized void control(int id,byte[] data){if(id==0x10023){pending.remove(0x10024);pending.remove(0x10022);}if(id==0x10024)pending.remove(0x10023);put(id,data);}
    /** One HU capture stream shared by explicit recognition and Android recorders. */
    synchronized void microphone(boolean voiceConsumer,boolean wanted){
        microphoneConsumer(voiceConsumer?1:2,wanted);
    }
    private void microphoneConsumer(int bit,boolean wanted){
        if(closed)return;int previous=microphoneConsumers;
        microphoneConsumers=wanted?previous|bit:previous&~bit;
        if(previous==0&&microphoneConsumers!=0)control(0x10024,new byte[0]);
        else if(previous!=0&&microphoneConsumers==0)control(0x10023,new byte[0]);
    }
    /** Honda requires VR_RUNNING to open its recorder, even for an app screen button.
     * This is recording status only: no VR playback stream is created here. */
    synchronized void applicationMicrophone(boolean wanted){
        if(closed)return;
        try{
            if(wanted){voiceConsumer(4,true,false);microphone(false,true);}
            else{microphone(false,false);voiceConsumer(4,false,false);}
        }catch(IOException impossible){throw new IllegalStateException(impossible);}
    }
    /** Separate owners: explicit voice, assistant playback and app recorder. */
    synchronized void voiceModule(boolean voiceOwner,boolean wanted)throws IOException{
        voiceConsumer(voiceOwner?1:2,wanted,voiceOwner);
    }
    private void voiceConsumer(int bit,boolean wanted,boolean acknowledgeIdle)throws IOException{
        if(closed)return;int previous=voiceConsumers;
        voiceConsumers=wanted?previous|bit:previous&~bit;
        boolean active=voiceConsumers!=0;
        // A failed/pending voice launch still acknowledges IDLE, but never
        // cancels another owner's assistant response. Repeated STOP is fenced
        // by the NativeVoice generation before reaching this method.
        if((previous!=0)!=active||acknowledgeIdle&&!wanted&&!active){
            ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(MdMain.Proto.ints(1,1));
            NativeSessionProtocol.bytes(b,2,MdMain.Proto.ints(1,4,2,active?1:0));
            // Replacing a LinkedHashMap value retains its old position. Move this
            // coalesced state after the preceding MIC_END on rapid start/stop.
            pending.remove(0x10026);control(0x10026,b.toByteArray());
        }
    }
    synchronized void callVoice(boolean wanted){try{voiceConsumer(8,wanted,false);}catch(IOException impossible){throw new IllegalStateException(impossible);}}
    synchronized void callMicrophone(boolean wanted){microphoneConsumer(4,wanted);}
    synchronized boolean nonCallMicrophoneIdle(){return (microphoneConsumers&3)==0;}
    synchronized boolean voiceModuleActive(){return !closed&&voiceConsumers!=0;}
    /** HeadSwap documents HUs reflecting our VR_RUNNING as MODULE_CONTROL VR=1.
     * Start the short echo window only after a real successful USB write, not
     * while a coalesced state is still pending. Explicit hardkeys bypass it. */
    synchronized void outputWritten(int id,byte[] payload,long now){
        if(closed||now<0)return;
        if(id==0x10024||id==0x10023){
            MdMain.logFields("native_mic_command_written","active",id==0x10024,"message_id",id,"written_uptime_ms",now);
            return;
        }
        if(id!=0x10026)return;
        try{
            Map<Integer,Object> list=MdMain.Proto.parse(payload);
            Object nested=list.get(2);
            if(!(nested instanceof byte[]))return;
            Map<Integer,Object> module=MdMain.Proto.parse((byte[])nested);
            int state=MdMain.Proto.number(module,2,-1);
            if(MdMain.Proto.number(module,1,-1)==4&&(state==0||state==1))voiceStatusWritten(state==1,now);
        }catch(IOException ignored){/* A malformed or unrelated status cannot arm the guard. */}
    }
    synchronized void voiceStatusWritten(boolean active,long now){if(!closed&&now>=0){if(active)lastWrittenVrRunning=now;else lastWrittenVrIdle=now;MdMain.logFields("native_voice_status_written","active",active,"written_uptime_ms",now);}}
    synchronized boolean voiceStartEcho(long now){return voiceControlEcho(1,now);}
    synchronized boolean voiceControlEcho(int state,long now){
        if(closed||(state!=0&&state!=1))return false;
        long sent=state==1?lastWrittenVrRunning:lastWrittenVrIdle;
        return sent>=0&&now>=sent&&now-sent<VOICE_CONTROL_ECHO_MS;
    }
    synchronized Output poll(){return poll(System.nanoTime()/1000000L);}
    synchronized Output poll(long now){
        if(closed)return null;
        for(Iterator<Map.Entry<Integer,byte[]>> i=pending.entrySet().iterator();i.hasNext();){
            Map.Entry<Integer,byte[]> e=i.next();
            if(e.getKey()==TURN&&routeShown&&!urgentRoute&&lastRouteSent>=0&&now-lastRouteSent<1000)continue;
            i.remove();
            if(e.getKey()==TURN){
                try{routeShown=MdMain.Proto.number(MdMain.Proto.parse(e.getValue()),1,3)!=3;
                    lastRouteContent=routeShown&&route!=null?navi(2,route.nextTurn,maneuverDistance,route.meters,route.icon):null;
                }catch(IOException impossible){routeShown=false;lastRouteContent=null;}
                lastRouteSent=now;urgentRoute=false;
            }
            return new Output(e.getKey(),e.getValue());
        }
        return null;
    }
    synchronized void close(){closed=true;pending.clear();route=null;media=null;microphoneConsumers=0;voiceConsumers=0;lastWrittenVrRunning=-1;lastWrittenVrIdle=-1;}
    private void replayMedia()throws IOException {if(musicSubscribed&&musicAvailable&&playing&&media!=null){put(INFO,media);put(PROGRESS,MdMain.Proto.ints(1,position));}}
    private void replayRoute()throws IOException {
        if(!naviSubscribed||!naviAvailable)return;
        if(route==null){if(routeShown)put(TURN,navi(3,24,0,0,new byte[0]));else pending.remove(TURN);return;}
        // Field 4 is the distance at the current maneuver's first observation, not
        // total route length. Never invent street names or remaining travel time.
        byte[] content=navi(2,route.nextTurn,maneuverDistance,route.meters,route.icon);
        if(Arrays.equals(content,lastRouteContent)){pending.remove(TURN);return;}
        put(TURN,routeShown?content:navi(1,route.nextTurn,maneuverDistance,route.meters,route.icon));
    }
    private static byte[] bytes(int field,byte[] value)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();NativeSessionProtocol.bytes(out,field,value);return out.toByteArray();}
    // Two PRIMARY wire schemas reuse field 6: CarLife-MD-Lib requires varint time;
    // Baidu MD 8.9.5 requires bytes turnIconData. Generated protobuf decoders ignore
    // the other wire type. Both forms preserve required-field validity (see dual-schema test).
    private static byte[] navi(int action,int turn,int total,int meters,byte[] icon)throws IOException {return NativeSessionProtocol.join(MdMain.Proto.ints(1,action,2,turn,4,total,5,meters,6,0),MdMain.Proto.strings(3,""),bytes(6,icon));}
    private void put(int id,byte[] p){if(closed)return;switch(id){case INFO:case PROGRESS:case TURN:case 0x10044:case 0x10026:case 0x10022:case 0x10023:case 0x10024:break;default:throw new IllegalArgumentException("unknown native services output slot");}pending.put(id,p);}
    static String string(Map<Integer,Object> f,int k){Object o=f.get(k);return o instanceof byte[]?new String((byte[])o,StandardCharsets.UTF_8):"";}
    static int saturate(long n){return (int)Math.max(0,Math.min(Integer.MAX_VALUE,n));}
    private static String limit(String s){return s==null?"":s.length()>1024?s.substring(0,1024):s;}
    // Honda RH00468A copies song/artist into 256-byte buffers and uses uint8 lengths.
    // Leave room for NUL and never cut a UTF-8 character in half.
    static String utf8Limit(String value,int maxBytes){
        if(value==null)return "";int offset=0,bytes=0;
        while(offset<value.length()){
            int point=value.codePointAt(offset),size=point<=0x7f?1:point<=0x7ff?2:point<=0xffff?3:4;
            if(bytes+size>maxBytes)break;bytes+=size;offset+=Character.charCount(point);
        }
        return value.substring(0,offset);
    }
    static List<Integer> subscriptionIds(byte[] p)throws IOException {
        ByteArrayInputStream in=new ByteArrayInputStream(p);List<Integer> ids=new ArrayList<Integer>();int fields=0;
        while(in.available()>0){if(++fields>128)throw new IOException("subscription field limit");long key=MdMain.Proto.varint(in);int f=(int)(key>>>3),w=(int)(key&7);if(f==0)throw new IOException("subscription field zero");if(w==0){MdMain.Proto.varint(in);continue;}if(w==1||w==5){int n=w==1?8:4;if(in.skip(n)!=n)throw new EOFException();continue;}if(w!=2)throw new IOException("subscription wire");long n=MdMain.Proto.varint(in);if(n<0||n>in.available())throw new EOFException();byte[] b=new byte[(int)n];in.read(b);if(f==2){Map<Integer,Object> item=MdMain.Proto.parse(b);MdMain.Proto.requireInts(item,1);ids.add(MdMain.Proto.number(item,1,-1));}}
        return ids;
    }
}
