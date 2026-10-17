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
        NativeMicrophone.Recorder periodic=new NativeMicrophone.Recorder(10060,1,593,false,15,"","ru.yandex.yandexnavi");
        NativeMicrophone.Recorder periodicRouted=new NativeMicrophone.Recorder(10060,1,593,false,25,"next-route","ru.yandex.yandexnavi");
        for(int attempt=0;attempt<4;attempt++)check(NativeMicrophone.select(java.util.Arrays.asList(attempt%2==0?periodic:periodicRouted),periodic,0)==null,"Honda STOP keeps actual session593 suppressed across hardware/remote-submix retry callbacks");
        NativeMicrophone.Recorder screenRestart=new NativeMicrophone.Recorder(10060,1,601,false,15,"","ru.yandex.yandexnavi");
        check(NativeMicrophone.select(java.util.Arrays.asList(screenRestart),periodic,0)==screenRestart,"new screen MIC session is admitted without requiring a HU key");
        check(NativeMicrophone.select(java.util.Arrays.asList(periodic),null,0)==periodic,"explicit HU start may rearm the still-existing application recorder");
        check(NativeMicrophone.retryDelay(periodic,periodic,6000,1000)==5000,"failed route keeps bounded retry for the same capture identity");
        check(NativeMicrophone.retryDelay(periodicRouted,periodic,6000,1100)==4900,"device callback does not bypass failed recorder cooldown");
        check(NativeMicrophone.retryDelay(screenRestart,periodic,6000,1100)==0,"new screen recording session does not inherit old recorder's five-second delay");
        check(NativeMicrophone.retryDelay(recorder(10061,1,593,false,15,""),periodic,6000,1100)==0,"another UID is not delayed even if session number matches");
        check(NativeMicrophone.retryDelay(recorder(10060,6,593,false,15,""),periodic,6000,1100)==0,"another source is a distinct capture identity");
        check(NativeMicrophone.retryDelay(periodic,periodic,6000,6000)==0,"failed recorder may retry exactly at deadline");
        check(NativeMicrophone.retryDelay(periodic,null,6000,1100)==0,"explicit HU start can clear retry owner");
        check(NativeMicrophone.retryDelay(null,periodic,6000,1100)==0,"no selected recorder does not schedule a retry");
        NativeMicrophone.Preparation preparation=new NativeMicrophone.Preparation();NativeVoiceFocus.State focus=new NativeVoiceFocus.State();
        check(preparation.select(Arrays.asList(periodic),null,focus,false)==periodic,"new idle persistent Navigator MIC can be prepared without a voice grant");
        check(!focus.permits(periodic.uid,periodic.source,periodic.pkg),"preparation selection does not grant capture or HU microphone admission");
        preparation.consume(periodic);
        check(preparation.select(Arrays.asList(periodic),null,focus,false)==null,"used identity is not prewarmed again during ambiguous follow-up MEDIA turn");
        check(preparation.select(Collections.<NativeMicrophone.Recorder>emptyList(),null,focus,false)==null,"queued empty callback cannot create a preparation target");
        check(preparation.select(Arrays.asList(periodic),null,focus,false)==null,"same identity restored after queued absence cannot be prewarmed again, even after cleanup ended");
        focus.grant(periodic.uid,periodic.pkg,"voice",4,1);
        check(NativeMicrophone.select(Arrays.asList(periodic),null,0,focus)==periodic,"already used identity still starts normally through the cold path");
        check(preparation.select(Arrays.asList(screenRestart),null,focus,false)==null,"active focus cannot be mislabeled as idle preparation");
        focus.loss(periodic.uid,"voice");
        check(preparation.select(Arrays.asList(screenRestart),null,focus,false)==screenRestart,"new session gets preparation independently of previous consumed identity");
        check(preparation.select(Arrays.asList(screenRestart),screenRestart,focus,false)==null,"HU suppression prevents preparation of its exact recorder");
        check(preparation.select(Arrays.asList(screenRestart),null,focus,true)==null,"reply phase never prewarms a fresh input behind playback");
        check(preparation.select(Arrays.asList(periodicRouted),null,focus,false)==null,"another live submix is not silently prepared or taken over");
        check(preparation.select(Arrays.asList(newer,owned),null,focus,false)==null,"unmeasured applications and source6 retain their original path");
        preparation.select(Collections.<NativeMicrophone.Recorder>emptyList(),null,focus,false);
        check(preparation.select(Arrays.asList(periodic),null,focus,false)==null,"repeated empty snapshots never erase the one consumed identity");
        preparation.consume(screenRestart);
        check(preparation.select(Arrays.asList(screenRestart),null,focus,false)==null,"different actual identity replaces consumed history when prepared or activated");
        check(preparation.select(Arrays.asList(periodic),null,focus,false)==periodic,"history remains one bounded identity rather than an ever-growing deny list");
        focus.explicitStart();check(preparation.select(Arrays.asList(periodic),null,focus,false)==null,"explicit HU START uses admitted path rather than idle preparation");
        NativeMicrophone.Activation activation=new NativeMicrophone.Activation();
        check(!activation.started()&&!activation.inGrace(600000,2500),"prepared idle time never consumes routing grace");
        activation.start(600000);
        check(activation.started()&&activation.inGrace(600001,2500)&&activation.inGrace(602499,2500),"routing grace begins at actual activation even after a long preparation");
        check(!activation.inGrace(602500,2500)&&!activation.inGrace(599999,2500),"grace expires at its boundary and rejects backwards time");
        boolean duplicate=false;try{activation.start(600100);}catch(IllegalStateException expected){duplicate=true;}
        check(duplicate,"duplicate callbacks cannot play the same prepared AudioTrack twice");
        System.out.println("NativeMicrophoneTest PASS "+checks+" checks; Android reroute and HU PCM require device acceptance");
    }
}
