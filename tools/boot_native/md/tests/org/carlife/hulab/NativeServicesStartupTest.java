package org.carlife.hulab;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
public final class NativeServicesStartupTest {
    static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static final class Source implements NativeServices.Registration {
        int attempts,releases;boolean available;Exception error;Runnable duringAcquire;
        public boolean acquire()throws Exception{attempts++;if(duringAcquire!=null)duringAcquire.run();if(error!=null)throw error;return available;}
        public void release(){releases++;}
    }
    static final class ListenerRegistry {private final Map<Object,Object> mListeners=new HashMap<Object,Object>();}
    static byte[] subscribe(int id)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(MdMain.Proto.ints(1,1));NativeSessionProtocol.bytes(b,2,MdMain.Proto.ints(1,id));return b.toByteArray();}
    static int support(NativeServiceState.Output out)throws Exception {check(out!=null&&out.id==0x10044,"capability ACK");Map<Integer,Object> list=MdMain.Proto.parse(out.payload);return MdMain.Proto.number(MdMain.Proto.parse((byte[])list.get(2)),2,-1);}
    public static void main(String[] args)throws Exception{
        NativeServices.StartupRetry stage=new NativeServices.StartupRetry();Source source=new Source();
        check(stage.run(0,source)&&!stage.ready()&&source.releases==1,"partial/unavailable registration cleaned");check(stage.next()==1000,"first retry 1s");check(!stage.run(999,source)&&source.attempts==1,"backoff does not re-register");
        source.error=new IOException("binder not ready");check(stage.run(1000,source)&&stage.next()==3000&&!stage.blocked,"transient failure retries with backoff");check(source.releases==2,"failed attempt cleaned");
        source.error=null;source.available=true;check(stage.run(3000,source)&&stage.ready(),"failure to ready");check(stage.next()==Long.MAX_VALUE&&!stage.run(999999,source)&&source.attempts==3,"success never registers duplicate listener");
        NativeServices.StartupRetry navi=new NativeServices.StartupRetry();Source notifications=new Source();navi.run(0,notifications);check(stage.ready()&&source.attempts==3,"failed independent service leaves successful media intact");notifications.available=true;navi.run(1000,notifications);check(navi.ready(),"independent failed stage becomes ready");
        NativeServices.StartupRetry denied=new NativeServices.StartupRetry();Source permissions=new Source();permissions.error=new InvocationTargetException(new SecurityException("denied"));denied.run(0,permissions);check(denied.blocked&&denied.next()==Long.MAX_VALUE,"wrapped permission denial terminal");check(!denied.run(999999,permissions)&&permissions.attempts==1,"permission not retried or bypassed");
        NativeServices.StartupRetry stopped=new NativeServices.StartupRetry();Source pending=new Source();stopped.run(0,pending);stopped.close();pending.available=true;check(!stopped.run(1000,pending)&&pending.attempts==1&&!stopped.ready(),"close during backoff cannot open service");
        final NativeServices.StartupRetry inflight=new NativeServices.StartupRetry();Source racing=new Source();racing.available=true;racing.duringAcquire=new Runnable(){public void run(){inflight.close();}};inflight.run(0,racing);check(!inflight.ready()&&racing.releases==1&&inflight.next()==Long.MAX_VALUE,"close during acquire cleans successful late registration");
        NativeServiceState wire=new NativeServiceState();wire.command(0x18043,subscribe(2));check(wire.poll()==null,"request does not invent readiness");wire.capabilities(false,false);check(support(wire.poll())==0,"initial unavailable reports supported0");wire.command(0x18045,subscribe(2));wire.media("p","song","artist","album","id",10000,1,true,0,new byte[0]);check(wire.poll()==null,"START while unavailable cannot emit metadata");wire.capabilities(stage.ready(),false);check(support(wire.poll())==1,"actual successful retry replays supported1");check(wire.poll().id==0x10035&&wire.poll().id==0x10036,"already requested metadata starts on actual readiness");
        check(NativeServices.StartupRetry.backoff(1)==1000&&NativeServices.StartupRetry.backoff(2)==2000&&NativeServices.StartupRetry.backoff(3)==4000&&NativeServices.StartupRetry.backoff(100)==30000,"bounded backoff no tight perpetual poll");
        ListenerRegistry registry=new ListenerRegistry();Object listener=new Object();check(Boolean.FALSE.equals(NativeServices.listenerRegistered(registry,listener)),"available AOSP registry detects swallowed registration failure");registry.mListeners.put(listener,new Object());check(Boolean.TRUE.equals(NativeServices.listenerRegistered(registry,listener)),"actual registered listener confirmed");check(NativeServices.listenerRegistered(new Object(),listener)==null,"OEM missing private field uses snapshot polling instead of permanent failure");
        stage.close();navi.close();wire.close();check(!stage.ready()&&!navi.ready()&&wire.poll()==null,"all capabilities fenced after close");
        System.out.println("NativeServicesStartupTest PASS "+checks+" startup failure/ready/cleanup/subscription checks; Android boundaries injected");
    }
}
