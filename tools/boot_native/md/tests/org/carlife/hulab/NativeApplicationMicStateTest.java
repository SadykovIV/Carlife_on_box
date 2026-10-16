package org.carlife.hulab;

import java.util.Map;

/** Honda recorder admission and shared VR/MIC ownership, without hardware claims. */
public final class NativeApplicationMicStateTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static NativeServiceState.Output output(NativeServiceState s,int id){
        NativeServiceState.Output p=s.poll();check(p!=null&&p.id==id,"expected output "+Integer.toHexString(id));return p;
    }
    static void vr(NativeServiceState s,int value)throws Exception{
        Map<Integer,Object> f=MdMain.Proto.parse(output(s,0x10026).payload);
        Map<Integer,Object> m=MdMain.Proto.parse((byte[])f.get(2));
        check(MdMain.Proto.number(m,1,-1)==4&&MdMain.Proto.number(m,2,-1)==value,"VR state "+value);
    }
    static void empty(NativeServiceState s){check(s.poll()==null,"no extra lifecycle commands/VR PCM");}
    public static void main(String[] args)throws Exception{
        NativeServiceState s=new NativeServiceState();
        s.applicationMicrophone(true);vr(s,1);output(s,0x10024);empty(s);
        s.applicationMicrophone(true);empty(s);
        s.applicationMicrophone(false);output(s,0x10023);vr(s,0);empty(s);
        s.applicationMicrophone(false);empty(s);
        // Undrained START followed by STOP must not put VR0 before MIC_END.
        s.applicationMicrophone(true);s.applicationMicrophone(false);output(s,0x10023);vr(s,0);empty(s);
        // Latest desired state wins without orphan STOP or duplicate START.
        s.applicationMicrophone(true);s.applicationMicrophone(false);s.applicationMicrophone(true);
        vr(s,1);output(s,0x10024);empty(s);
        // A pending explicit recognizer STOP cannot cancel a real app recorder.
        s.voiceModule(true,true);s.microphone(true,true);empty(s);
        s.voiceModule(true,false);s.microphone(true,false);empty(s);check(s.voiceModuleActive(),"app retains VR state");
        // App completion preserves assistant playback ownership, including unsent VR1.
        s.voiceModule(false,true);s.applicationMicrophone(false);output(s,0x10023);empty(s);
        check(s.voiceModuleActive(),"playback retains VR until its actual stop");
        s.voiceModule(false,false);vr(s,0);empty(s);
        NativeServiceState mixed=new NativeServiceState();mixed.voiceModule(false,true);mixed.applicationMicrophone(true);
        vr(mixed,1);output(mixed,0x10024);empty(mixed);
        mixed.voiceModule(false,false);empty(mixed);check(mixed.voiceModuleActive(),"playback end preserves recorder");
        mixed.applicationMicrophone(false);output(mixed,0x10023);vr(mixed,0);empty(mixed);
        // An undrained VR state can coexist with MIC_END without stale ordering.
        NativeServiceState coalesced=new NativeServiceState();coalesced.voiceModule(false,true);coalesced.applicationMicrophone(true);
        coalesced.voiceModule(false,false);coalesced.applicationMicrophone(false);
        output(coalesced,0x10023);vr(coalesced,0);empty(coalesced);
        NativeServiceState explicit=new NativeServiceState();explicit.voiceModule(true,true);explicit.microphone(true,true);
        vr(explicit,1);output(explicit,0x10024);explicit.applicationMicrophone(true);explicit.applicationMicrophone(false);empty(explicit);
        check(explicit.voiceModuleActive(),"explicit recognizer not stopped by app");
        explicit.microphone(true,false);explicit.voiceModule(true,false);output(explicit,0x10023);vr(explicit,0);empty(explicit);
        // poll can hand a packet to the single USB writer between transitions.
        NativeServiceState partial=new NativeServiceState();partial.applicationMicrophone(true);vr(partial,1);
        partial.applicationMicrophone(false);output(partial,0x10023);vr(partial,0);empty(partial);
        partial.applicationMicrophone(true);vr(partial,1);output(partial,0x10024);
        partial.applicationMicrophone(false);output(partial,0x10023);
        partial.applicationMicrophone(true);vr(partial,1);output(partial,0x10024);empty(partial);
        partial.applicationMicrophone(false);output(partial,0x10023);vr(partial,0);
        partial.applicationMicrophone(true);vr(partial,1);output(partial,0x10024);empty(partial);
        s.close();s.applicationMicrophone(true);s.applicationMicrophone(false);empty(s);check(!s.voiceModuleActive(),"closed state cannot restart");
        System.out.println("NativeApplicationMicStateTest PASS "+checks+" checks; no Honda hardware acceptance");
    }
}
