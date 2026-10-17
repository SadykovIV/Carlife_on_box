package org.carlife.hulab;

import java.io.IOException;
import java.util.Arrays;

/** Ownership tests only: no fake audio mode is applied to Android or to a real call. */
public final class NativeCallAudioTest {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    interface Action {void run()throws Exception;}
    static void fails(Action action,String label)throws Exception {try{action.run();throw new AssertionError(label);}catch(IOException expected){checks++;}}
    static void startFails(Action action,String label)throws Exception {try{action.run();throw new AssertionError(label);}catch(IllegalStateException expected){checks++;}}
    static NativeCallAudio.PstnStartCheck gate(int mode,int state,int play,int usage,int flags,int delay,
            boolean present,boolean waiting,boolean allowed,String phone,String owner,String caller){
        return new NativeCallAudio.PstnStartCheck(mode,state,play,usage,flags,delay,present,waiting,allowed,phone,owner,caller);
    }
    static NativeCallAudio.PstnStartCheck blocked(){return gate(2,1,1,17,0x10800,0,true,false,false,"1","com.android.phone","app_process");}
    static final class StartFake implements NativeCallAudio.UplinkStarter {
        int playState=1,mode=2,plays,inspects,starts,reports;
        boolean normalWorks,compatWorks=true,modeChangesAtStart,gateChanges;
        NativeCallAudio.PstnStartCheck check=blocked();
        public void play(){plays++;if(normalWorks)playState=3;}
        public int playState(){return playState;}
        public int mode(){return mode;}
        public NativeCallAudio.PstnStartCheck inspect(){inspects++;return gateChanges&&inspects>1?null:check;}
        public void startAuthorizedCallImpl(){starts++;if(compatWorks)playState=3;if(modeChangesAtStart)mode=0;}
        public void report(String state,NativeCallAudio.PstnStartCheck check){reports++;}
    }
    static void pstnStartCompatibility()throws Exception {
        check(blocked().eligible(false),"known OEM package exclusion permits only owned PSTN call track compatibility");
        for(int mode:new int[]{0,1,3,6})check(!gate(mode,1,1,17,0x10800,0,true,false,false,"1","phone","md").eligible(false),"not PSTN "+mode);
        check(!gate(2,0,1,17,0x10800,0,true,false,false,"1","phone","md").eligible(false),"uninitialized rejected");
        check(!gate(2,1,3,17,0x10800,0,true,false,false,"1","phone","md").eligible(false),"already playing not compatibility target");
        check(!gate(2,1,2,17,0x10800,0,true,false,false,"1","phone","md").eligible(false),"initial start does not accept paused state");
        check(gate(2,1,2,17,0x10800,0,true,false,false,"1","phone","md").eligible(true),"owned flush can resume paused track");
        for(int usage:new int[]{1,2,16})check(!gate(2,1,1,usage,0x10800,0,true,false,false,"1","phone","md").eligible(false),"ordinary media/voice/assistant not bypassed "+usage);
        check(!gate(2,1,1,17,0x800,0,true,false,false,"1","phone","md").eligible(false),"call redirection flag required");
        check(!gate(2,1,1,17,0x10800,300,true,false,false,"1","phone","md").eligible(false),"legitimate delayed start not bypassed");
        check(!gate(2,1,1,17,0x10800,0,false,false,false,"1","phone","md").eligible(false),"unknown OEM implementation rejected");
        check(!gate(2,1,1,17,0x10800,0,true,true,false,"1","phone","md").eligible(false),"pending OEM start not duplicated");
        check(!gate(2,1,1,17,0x10800,0,true,false,true,"1","phone","md").eligible(false),"gate must actually refuse");
        check(!gate(2,1,1,17,0x10800,0,true,false,false,"0","phone","md").eligible(false),"inactive property rejected");
        check(!gate(2,1,1,17,0x10800,0,true,false,false,"1","","md").eligible(false),"missing phone owner rejected");
        check(!gate(2,1,1,17,0x10800,0,true,false,false,"1","phone",null).eligible(false),"unknown caller rejected");
        check(!gate(2,1,1,17,0x10800,0,true,false,false,"1","phone","phone").eligible(false),"same owner not OEM exclusion");
        StartFake normal=new StartFake();normal.normalWorks=true;NativeCallAudio.startOwnedUplink(normal,false);
        check(normal.plays==1&&normal.inspects==0&&normal.starts==0,"normal platform start remains first and sole path when it works");
        StartFake compatible=new StartFake();NativeCallAudio.startOwnedUplink(compatible,false);
        check(compatible.plays==1&&compatible.inspects==2&&compatible.starts==1&&compatible.playState==3,"compatibility starts same track once after fresh gate recheck");
        StartFake unsupported=new StartFake();unsupported.check=null;startFails(()->NativeCallAudio.startOwnedUplink(unsupported,false),"missing OEM support remains failure");check(unsupported.starts==0,"unknown wrapper not invoked");
        StartFake changed=new StartFake();changed.gateChanges=true;startFails(()->NativeCallAudio.startOwnedUplink(changed,false),"gate changed before start");check(changed.starts==0,"changed gate not bypassed");
        StartFake idle=new StartFake();idle.mode=0;startFails(()->NativeCallAudio.startOwnedUplink(idle,false),"actual call mode rechecked");check(idle.starts==0,"inactive mode cannot start");
        StartFake fail=new StartFake();fail.compatWorks=false;startFails(()->NativeCallAudio.startOwnedUplink(fail,false),"compatibility does not mask stopped track");
        StartFake ended=new StartFake();ended.modeChangesAtStart=true;startFails(()->NativeCallAudio.startOwnedUplink(ended,false),"call ending during start remains failure");
        StartFake flush=new StartFake();flush.playState=2;flush.check=gate(2,1,2,17,0x10800,0,true,false,false,"1","phone","md");
        NativeCallAudio.startOwnedUplink(flush,true);check(flush.starts==1&&flush.playState==3,"mute flush resumes through same verified path");
    }
    static final class Fake implements NativeCallAudio.Platform {
        int mode,opens,reads,writes,downClosed,upClosed,flushes,downStarts,upStarts;
        boolean muted,failUp,failDownStart,failUpStart,failDownClose,failUpClose,failWrite,failRead,failMode,failMute,failFlush,uncertainOpen;
        boolean linkUpStart,linkUpClose,linkRead,linkWrite,linkMode,linkMute,linkUpOpen,linkFlush;
        int changeAfterDown=-1,changeAfterUpStart=-1;byte[] written;
        public int mode()throws Exception{if(linkMode)throw new NoSuchMethodError("mode");if(failMode)throw new IOException("mode unavailable");return mode;}
        public boolean microphoneMuted()throws Exception{if(linkMute)throw new NoSuchMethodError("mute");if(failMute)throw new IOException("mute unavailable");return muted;}
        public NativeCallAudio.Downlink openDownlink(){opens++;if(changeAfterDown>=0)mode=changeAfterDown;return new NativeCallAudio.Downlink(){
            public void start()throws Exception{downStarts++;if(failDownStart)throw new IOException("down start");}
            public int read(byte[] b,int o,int n)throws Exception{reads++;if(linkRead)throw new UnsatisfiedLinkError("read");if(failRead)throw new IOException("read");Arrays.fill(b,o,o+n,(byte)3);return n;}
            public void close()throws Exception{downClosed++;if(failDownClose)throw new IOException("down close");}
        };}
        public NativeCallAudio.Uplink openUplink()throws Exception{opens++;if(linkUpOpen)throw new NoClassDefFoundError("uplink");if(uncertainOpen)throw new NativeCallAudio.CleanupUnconfirmedException(new IOException("release during open"));if(failUp)throw new IOException("up open");return new NativeCallAudio.Uplink(){
            public void start()throws Exception{upStarts++;if(linkUpStart)throw new NoSuchMethodError("start");if(changeAfterUpStart>=0)mode=changeAfterUpStart;if(failUpStart)throw new IOException("up start");}
            public int write(byte[] b,int o,int n)throws Exception{writes++;if(linkWrite)throw new UnsatisfiedLinkError("write");if(failWrite)throw new IOException("write");written=Arrays.copyOfRange(b,o,o+n);return n;}
            public void flush()throws Exception{flushes++;if(linkFlush)throw new NoSuchMethodError("flush");if(failFlush)throw new IOException("flush");}
            public void close()throws Exception{upClosed++;if(linkUpClose)throw new UnsatisfiedLinkError("release");if(failUpClose)throw new IOException("up close");}
        };}
    }
    public static void main(String[] args)throws Exception {
        pstnStartCompatibility();
        Fake idle=new Fake();NativeCallAudio off=new NativeCallAudio(idle,new SourceLease());
        check(off.openForCurrentMode()==0&&idle.opens==0,"NORMAL creates no endpoint");
        idle.mode=1;check(off.openForCurrentMode()==0&&idle.opens==0,"RINGTONE is not a connected call");off.close();
        for(int mode:new int[]{2,3,4,5,6}){
            Fake p=new Fake();p.mode=mode;SourceLease l=new SourceLease();NativeCallAudio a=new NativeCallAudio(p,l);
            long token=a.openForCurrentMode();check(token!=0&&a.active()&&l.isHeld(),"active mode "+mode);
            check(p.downStarts==1&&p.upStarts==1,"both directions started "+mode);
            check(a.openForCurrentMode()==token&&p.opens==2,"idempotent same call "+mode);
            a.close();check(p.downClosed==1&&p.upClosed==1&&!l.isHeld(),"both directions retired "+mode);
            a.close();check(p.downClosed==1&&p.upClosed==1,"close idempotent "+mode);
        }
        Fake partial=new Fake();partial.mode=2;partial.failUp=true;SourceLease partialLease=new SourceLease();
        NativeCallAudio pair=new NativeCallAudio(partial,partialLease);
        fails(()->pair.openForCurrentMode(),"partial open fails");
        check(partial.downClosed==1&&partial.upClosed==0&&!partialLease.isHeld()&&!pair.active(),"partial transaction closes first endpoint");
        for(int failure=0;failure<4;failure++){
            Fake p=new Fake();p.mode=3;
            if(failure==0)p.changeAfterDown=0;if(failure==1)p.changeAfterUpStart=2;if(failure==2)p.failDownStart=true;if(failure==3)p.failUpStart=true;
            NativeCallAudio a=new NativeCallAudio(p,new SourceLease());fails(()->a.openForCurrentMode(),"mode/start failure");
            check(!a.active()&&p.downClosed==1,"failed transaction releases downlink");
            check(p.upClosed==(failure==0?0:1),"failed transaction releases any uplink");
        }
        Fake p=new Fake();p.mode=3;NativeCallAudio a=new NativeCallAudio(p,new SourceLease());long first=a.openForCurrentMode();
        byte[] mic=new byte[640];Arrays.fill(mic,(byte)9);byte[] dl=new byte[3840];
        check(a.writeUplink(first,mic,0,640)==640&&p.written[0]==9,"unmuted input forwarded");
        check(a.readDownlink(first,dl,0,3840)==3840&&dl[0]==3,"downlink forwarded");
        p.muted=true;check(a.writeUplink(first,mic,0,640)==640&&p.written[0]==0&&mic[0]==9,"platform mute sends silence, preserves input");
        check(p.flushes==1,"mute drops queued pre-mute audio");
        a.writeUplink(first,mic,0,640);check(p.flushes==1,"stable mute does not repeatedly flush");
        p.muted=false;a.setMuted(true);check(p.flushes==1,"local mute holds platform unmute");
        a.setMuted(false);check(p.flushes==2,"unmute drops stale silence");
        a.writeUplink(first,mic,0,640);check(p.written[0]==9,"unmute resumes current input");
        p.mode=0;check(!a.refresh()&&!a.active()&&p.downClosed==1&&p.upClosed==1,"actual mode exit closes pair");
        p.mode=2;long second=a.openForCurrentMode();check(second!=first,"fresh call has new epoch");
        int writes=p.writes,reads=p.reads;
        check(a.writeUplink(first,mic,0,640)==0&&a.readDownlink(first,dl,0,3840)==0&&p.writes==writes&&p.reads==reads,"late callbacks cannot reach next call");
        p.mode=3;check(a.writeUplink(second,mic,0,640)==0&&!a.active(),"PSTN to VoIP transition closes old endpoints");
        p.mode=3;a.openForCurrentMode();a.stop();check(!a.active(),"USB loss retires endpoint pair");a.close();
        for(int failure=0;failure<4;failure++){
            Fake f=new Fake();f.mode=3;SourceLease l=new SourceLease();NativeCallAudio n=new NativeCallAudio(f,l);long t=n.openForCurrentMode();
            if(failure==0)f.failRead=true;if(failure==1)f.failWrite=true;if(failure==2)f.failMode=true;if(failure==3)f.failMute=true;
            final boolean read=failure==0;fails(()->{if(read)n.readDownlink(t,dl,0,3840);else n.writeUplink(t,mic,0,640);},"runtime failure");
            check(!n.active()&&!l.isHeld()&&f.downClosed==1&&f.upClosed==1,"runtime failure retires both endpoints");
        }
        Fake poison=new Fake();poison.mode=2;poison.failUpClose=true;SourceLease fence=new SourceLease();NativeCallAudio broken=new NativeCallAudio(poison,fence);broken.openForCurrentMode();broken.stop();
        check(broken.poisoned()&&fence.isHeld()&&poison.downClosed==1,"uncertain cleanup fences new ownership but attempts both closes");
        fails(()->broken.openForCurrentMode(),"poisoned owner cannot restart");
        broken.close();check(fence.isHeld(),"close cannot accidentally release a poisoned lease");
        Fake rival=new Fake();rival.mode=3;NativeCallAudio waiting=new NativeCallAudio(rival,fence);fails(()->waiting.openForCurrentMode(),"other owner cannot cross poisoned lease");check(rival.opens==0,"lease checked before platform open");
        Fake one=new Fake();one.mode=2;SourceLease shared=new SourceLease();NativeCallAudio owner=new NativeCallAudio(one,shared);owner.openForCurrentMode();Fake two=new Fake();two.mode=3;NativeCallAudio other=new NativeCallAudio(two,shared);
        fails(()->other.openForCurrentMode(),"one active endpoint owner");owner.close();check(other.openForCurrentMode()!=0,"confirmed cleanup permits next owner");other.close();
        Fake failedFlush=new Fake();failedFlush.mode=2;NativeCallAudio muteFailure=new NativeCallAudio(failedFlush,new SourceLease());muteFailure.openForCurrentMode();failedFlush.failFlush=true;
        fails(()->muteFailure.setMuted(true),"mute flush failure is surfaced");check(!muteFailure.active()&&failedFlush.upClosed==1&&failedFlush.downClosed==1,"failed mute never continues with buffered speech");
        Fake leaked=new Fake();leaked.mode=2;leaked.uncertainOpen=true;SourceLease openFence=new SourceLease();NativeCallAudio incomplete=new NativeCallAudio(leaked,openFence);
        fails(()->incomplete.openForCurrentMode(),"platform reports uncertain cleanup during open");incomplete.close();check(incomplete.poisoned()&&openFence.isHeld()&&leaked.downClosed==1,"partial-open cleanup uncertainty fences all further owners");
        Fake startLink=new Fake();startLink.mode=2;startLink.linkUpStart=true;SourceLease startLease=new SourceLease();NativeCallAudio failedStart=new NativeCallAudio(startLink,startLease);
        fails(()->failedStart.openForCurrentMode(),"LinkageError during uplink start becomes owned failure");check(startLink.downClosed==1&&startLink.upClosed==1&&!startLease.isHeld()&&!failedStart.active(),"start linkage failure releases both directions");
        Fake openLink=new Fake();openLink.mode=2;openLink.linkUpOpen=true;SourceLease linkLease=new SourceLease();NativeCallAudio failedOpen=new NativeCallAudio(openLink,linkLease);
        fails(()->failedOpen.openForCurrentMode(),"LinkageError during second endpoint open is caught");check(openLink.downClosed==1&&!linkLease.isHeld(),"open linkage failure releases first endpoint");
        Fake closeLink=new Fake();closeLink.mode=2;closeLink.linkUpClose=true;SourceLease closeLease=new SourceLease();NativeCallAudio failedClose=new NativeCallAudio(closeLink,closeLease);failedClose.openForCurrentMode();failedClose.stop();
        check(closeLink.upClosed==1&&closeLink.downClosed==1&&failedClose.poisoned()&&closeLease.isHeld(),"uplink release linkage failure still attempts downlink and poisons lease");failedClose.close();check(closeLease.isHeld(),"terminal close cannot release uncertain native resource");
        for(int failure=0;failure<5;failure++){
            Fake f=new Fake();f.mode=3;SourceLease l=new SourceLease();NativeCallAudio n=new NativeCallAudio(f,l);long t=n.openForCurrentMode();
            if(failure==0)f.linkRead=true;if(failure==1)f.linkWrite=true;if(failure==2)f.linkMode=true;if(failure==3)f.linkMute=true;if(failure==4){f.linkFlush=true;f.muted=true;}
            final boolean read=failure==0;fails(()->{if(read)n.readDownlink(t,dl,0,3840);else n.writeUplink(t,mic,0,640);},"runtime LinkageError");
            check(!n.active()&&!l.isHeld()&&f.downClosed==1&&f.upClosed==1,"runtime linkage failure retires both endpoints");
        }
        System.out.println("NativeCallAudioTest PASS "+checks+" checks; Android call/HU full-duplex acceptance still pending");
    }
}
