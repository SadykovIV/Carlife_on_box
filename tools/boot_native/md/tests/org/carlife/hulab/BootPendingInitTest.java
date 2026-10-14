package org.carlife.hulab;
import java.io.*;

/** First command before media readiness; pending initialization cancellation is identity/generation bound. */
public final class BootPendingInitTest {
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static final class Transport implements MdTransport{
        final ByteArrayOutputStream out=new ByteArrayOutputStream();
        public InputStream input(){return new ByteArrayInputStream(new byte[0]);}
        public OutputStream output(){return out;}
        public boolean isNativeUsb(){return true;}
        public String name(){return "TEST_NATIVE";}
        public void close(){}public boolean awaitStopped(long ms){return true;}
    }
    static MdMain.Session session()throws Exception{return new MdMain.Session(new Transport());}
    static void version(MdMain.Session s)throws Exception{s.dispatch(1,0x18001,MdMain.Proto.ints(1,1,2,0));}
    static void init(MdMain.Session s)throws Exception{s.dispatch(1,0x18007,MdMain.Proto.ints(1,1280,2,720,3,30));}
    public static void main(String[]args)throws Exception{
        MdMain.bootNative=true;MdMain.videoSource="mediacodec";MdMain.running=true;
        MdMain.displayWidth=0;MdMain.displayHeight=0;
        MdMain.Session s=session();
        // Android stub services throw if accessed: version/info must still succeed.
        version(s);check(s.version,"version accepted without display/input services");
        check(s.controls.size()==1,"real VERSION_MATCH queued before media");
        s.dispatch(1,0x18003,new byte[0]);check(s.controls.size()==2,"real MD_INFO queued");
        init(s);check(!s.initialized&&s.pendingInit!=null,"INIT held pending");
        check(s.controls.size()==2,"no fabricated INIT_DONE");
        PendingVideoInit first=s.pendingInit;
        try{init(s);throw new AssertionError("duplicate pending INIT accepted");}catch(IOException expected){}
        s.dispatch(1,0x1800b,new byte[0]);check(s.pendingInit==null,"RESET cancels pending");
        check(!first.current(s.pendingInit,s.videoGeneration,s.open),"old generation discarded");
        s.completeBootInit(first,null);check(s.controls.size()==2&&!s.initialized,"stale callback emitted no ACK");
        init(s);PendingVideoInit second=s.pendingInit;
        version(s);check(s.pendingInit==null&&!s.initialized,"fresh VERSION before media replaces pending session");
        check(!second.current(s.pendingInit,s.videoGeneration,s.open),"previous logical session canceled");
        init(s);PendingVideoInit third=s.pendingInit;
        s.close();check(!third.current(s.pendingInit,s.videoGeneration,s.open),"EOF cancels media wait");
        // This mode never turns STOP/PAUSE/RESET into a selected protocol change.
        MdMain.Session ready=session();version(ready);init(ready);
        MdMain.displayWidth=1920;MdMain.displayHeight=1080;
        PendingVideoInit accepted=ready.pendingInit;
        ready.completeBootInit(accepted,new MediaCodecDisplaySource.Selection("TEST_CAPABILITY","repeat-previous-frame-after","max-fps-to-encoder",1280,720,30)); // No MediaCodec resource is created by callback.
        check(ready.initialized&&ready.geometry!=null&&ready.pendingInit==null,"admitted geometry committed");
        check(ready.controls.size()==2,"one and only one INIT_DONE after capability admission");
        ready.completeBootInit(accepted,null);check(ready.controls.size()==2,"same ticket cannot acknowledge twice");
        ready.close();MdMain.bootNative=false;
        System.out.println("PASS command-before-framework / deferred INIT / RESET / EOF / fresh VERSION / single ACK");
    }
}
