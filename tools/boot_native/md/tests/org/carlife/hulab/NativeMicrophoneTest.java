package org.carlife.hulab;

import java.util.Arrays;
import java.util.Collections;

public final class NativeMicrophoneTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static NativeMicrophone.Recorder recorder(int uid,int source,int session,boolean silenced,int device,String address){return new NativeMicrophone.Recorder(uid,source,session,silenced,device,address,"app");}
    public static void main(String[] args){
        NativeMicrophone.Recorder yandex=recorder(10060,1,113,false,15,"");
        NativeMicrophone.Recorder routed=recorder(10060,1,113,false,25,"route-1");
        check(yandex.eligible()&&yandex.same(routed),"route-only callback retains exact capture identity");
        check(routed.onRoute("route-1")&&!routed.onRoute("route-2"),"must verify own mix address, not merely remote-submix device type");
        check(!recorder(0,1,1,false,15,"").eligible(),"native daemon/probe recording is not an application voice request");
        check(recorder(1000,6,1,false,15,"").eligible(),"privileged system-UID assistant is not rejected solely for its UID");
        check(!recorder(10060,1,113,true,15,"").eligible(),"silenced recorder does not demand HU audio");
        check(!recorder(10060,8,113,false,25,"").eligible(),"output REMOTE_SUBMIX capture cannot create a feedback input route");
        check(!recorder(10060,1999,113,false,15,"").eligible(),"hotword/internal source excluded");
        for(int source:new int[]{1,6,7,9})check(recorder(10234,source,5,false,15,"").eligible(),"supported actual application recording source");
        NativeMicrophone.Recorder newer=recorder(10061,6,200,false,15,"");
        check(NativeMicrophone.select(Arrays.asList(routed,newer))==newer,"new keyboard/assistant recorder preempts continuously recording navigation app");
        check(NativeMicrophone.select(Arrays.asList(routed))==routed,"device-only callback retains capture identity");
        check(NativeMicrophone.select(Arrays.asList(yandex,newer))==newer,"choose current newest session initially");
        check(NativeMicrophone.select(Collections.<NativeMicrophone.Recorder>emptyList())==null,"no recorder releases demand");
        NativeMicrophone.PcmQueue queue=new NativeMicrophone.PcmQueue();queue.reset(7);
        byte[] packet=new byte[640];packet[0]=19;queue.offer(7,packet,0);packet[0]=99;
        check(queue.poll(7,0).data[0]==19,"USB caller buffer is copied");
        queue.offer(6,new byte[640],0);check(queue.bytes()==0,"old generation cannot enqueue");
        queue.offer(7,new byte[3],0);queue.offer(7,new byte[32002],0);check(queue.invalidPackets()==2,"malformed PCM is counted, not fatal to session");
        for(int i=0;i<20;i++)queue.offer(7,new byte[640],i);
        check(queue.bytes()==6400&&queue.droppedBytes()==6400,"oldest packets shed under 200ms queue byte budget");
        check(queue.poll(7,171)==null&&queue.bytes()==0,"packets older than150ms do not reach track");
        queue.offer(7,new byte[10000],200);check(queue.bytes()==6400,"large valid packet retains bounded recent tail");
        queue.reset(8);check(queue.bytes()==0&&queue.poll(8,200)==null,"new route cannot replay old PCM");
        queue.offer(7,new byte[640],201);check(queue.bytes()==0,"racing old callback remains fenced after reset");
        queue.offer(8,new byte[640],201);check(queue.poll(8,201).epoch==8,"current generation can resume");
        NativeMicrophone.Recorder owned=new NativeMicrophone.Recorder(10060,6,50,false,25,"owned","voice.app"),other=new NativeMicrophone.Recorder(10070,1,40,false,1,"","other.app"),replacement=new NativeMicrophone.Recorder(10060,6,51,false,1,"","voice.app");
        check(NativeMicrophone.select(java.util.Arrays.asList(owned,other),owned,0)==other,"HU STOP suppresses only its exact recorder and preserves unrelated app");
        check(NativeMicrophone.select(java.util.Arrays.asList(owned),owned,0)==null,"same stopped recorder cannot reopen injection on refresh");
        check(NativeMicrophone.select(java.util.Arrays.asList(owned,replacement),owned,0)==replacement,"new app recording session restores normal microphone routing");
        check(NativeMicrophone.select(java.util.Arrays.asList(owned),null,0)==owned,"explicit new HU start releases prior suppression");
        System.out.println("NativeMicrophoneTest PASS "+checks+" checks; Android reroute and HU PCM require device acceptance");
    }
}
