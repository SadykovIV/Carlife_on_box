package org.carlife.hulab;

/** Phone/HFP ownership regression. Does not emulate a successful real call. */
public final class NativeMicrophoneCallGuardTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static NativeMicrophone.Recorder recorder(int source,int device){return new NativeMicrophone.Recorder(10060,source,93,false,device,device==25?"our-submix":"","voice.app");}
    public static void main(String[] args){
        NativeMicrophone.Recorder app=recorder(1,15),injected=recorder(1,25),voip=recorder(7,15),sco=recorder(7,7);
        for(int mode:new int[]{2,3,4,5,6}){
            check(NativeMicrophone.shouldYieldToCall(mode,null),"call mode globally suspends our injector without recorder snapshot: "+mode);
            check(NativeMicrophone.shouldYieldToCall(mode,injected),"call mode retires already redirected remote submix: "+mode);
        }
        check(!NativeMicrophone.shouldYieldToCall(0,app),"normal assistant keeps USB microphone");
        check(!NativeMicrophone.shouldYieldToCall(1,app),"ringtone alone is not an active call route");
        check(!NativeMicrophone.shouldYieldToCall(0,voip),"source7 alone may be an assistant using AEC, not proof of a call");
        check(!NativeMicrophone.shouldYieldToCall(0,injected),"normal existing remote submix remains owned");
        check(NativeMicrophone.shouldYieldToCall(0,sco),"actual SCO route preserved even before mode callback");
        check(NativeMicrophone.shouldYieldToCall(1,sco),"actual SCO route takes precedence during ringing");
        check(NativeMicrophone.shouldYieldToCall(0,recorder(6,7)),"SCO preservation independent of source6/7 spelling");
        check(!NativeMicrophone.shouldYieldToCall(0,null),"idle no-recorder snapshot does not invent call");
        check(!NativeMicrophone.shouldYieldToCall(0,app),"return to normal permits a fresh source lease");
        NativeMicrophone.Recorder scoDefault=recorder(0,7);
        check(NativeMicrophone.select(java.util.Arrays.asList(app,scoDefault),null,0)==null,"SCO route has priority even when its source is outside our injection allowlist");
        NativeMicrophone.Recorder silencedSco=new NativeMicrophone.Recorder(10060,0,94,true,7,"","voice.app");
        check(NativeMicrophone.select(java.util.Arrays.asList(app,silencedSco),null,0)==app,"silenced inactive SCO does not invent a live call when audio mode is normal");
        System.out.println("NativeMicrophoneCallGuardTest PASS "+checks+" checks; app microphone guard for exclusive call owner handover; USB call acceptance is separate");
    }
}
