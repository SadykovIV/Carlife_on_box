package org.carlife.hulab;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Honda ordinary app/foreground commands, independent of Android or an attached HU. */
public final class NativeHuControlsTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static final class Memory implements MdTransport {
        public InputStream input(){return new ByteArrayInputStream(new byte[0]);}
        public OutputStream output(){return new ByteArrayOutputStream();}
        public String name(){return "offline-hu-controls";}
        public boolean isNativeUsb(){return true;}
        public void close(){}public boolean awaitStopped(long timeout){return true;}
    }
    static final class Keys extends MdMain.InputSink {
        final List<Integer> androidKeys=new ArrayList<Integer>();
        int cancels;boolean accepted=true;
        @Override boolean pressAndroidKey(int code){androidKeys.add(code);return accepted;}
        @Override synchronized void cancel(){cancels++;}
    }
    static MdMain.Frame take(MdMain.Session session,int id)throws Exception{
        MdMain.Frame frame=MdMain.readFrame(new DataInputStream(new ByteArrayInputStream(session.controls.remove().data)));
        check(frame.channel==MdMain.CMD&&frame.id==id&&frame.payload.length==0,"empty control response "+Integer.toHexString(id));return frame;
    }
    public static void main(String[] args)throws Exception{
        boolean oldBoot=MdMain.bootNative,oldServices=MdMain.nativeServicesEnabled;
        MdMain.bootNative=true;MdMain.nativeServicesEnabled=false;
        Keys keys=new Keys();MdMain.Session session=new MdMain.Session(new Memory(),keys);
        try{
            try{session.dispatch(1,0x1801d,new byte[0]);throw new AssertionError("HOME accepted before negotiation");}
            catch(IOException expected){check(expected.getMessage().contains("version required"),"negotiation guard retained");}
            check(keys.androidKeys.isEmpty(),"no input before VERSION");
            session.version=true;session.huMajor=2;session.initialized=true;session.videoGeneration=9;
            session.pendingInit=new PendingVideoInit(1280,720,30,9);
            MdMain.Packet video=new MdMain.Packet(new byte[]{1,2,3},9);session.videos.add(video);
            session.dispatch(1,0x1801d,new byte[0]);
            check(keys.androidKeys.equals(Arrays.asList(3)),"LAUNCH_MODE_NORMAL opens actual Android HOME");
            check(keys.cancels==1,"HOME cancels active touch before navigation");
            check(session.controls.isEmpty(),"HOME does not invent a protocol acknowledgement");
            session.dispatch(6,0x68008,MdMain.Proto.ints(1,0x13));
            check(keys.androidKeys.equals(Arrays.asList(3,164)),"HU MUTE affects playback volume, never microphone");
            check(keys.cancels==2,"hardkey keeps existing touch cancellation behavior");
            session.dispatch(1,0x18025,new byte[0]);
            check(keys.androidKeys.equals(Arrays.asList(3,164,224)),"foreground wakes with WAKEUP, never POWER or HOME");
            take(session,0x10018);take(session,0x1001b);take(session,0x1004c);
            check(keys.cancels==2,"foreground does not reset active application or touch");
            session.dispatch(1,0x18025,new byte[0]);
            take(session,0x10018);take(session,0x1001b);take(session,0x1004c);
            check(keys.androidKeys.get(3)==224,"repeated foreground cannot toggle display off");
            keys.accepted=false;session.dispatch(1,0x18025,new byte[0]);
            check(session.controls.isEmpty(),"rejected Android wake does not report successful foreground");
            check(session.initialized&&session.open&&session.videoGeneration==9&&session.pendingInit!=null&&session.videos.peek()==video,
                    "all controls preserve video queue, pending source and generation");
            check(MdMain.androidKeyCodeForCarLife(0x13)==164&&MdMain.androidKeyCodeForCarLife(3)<0,
                    "MUTE supported without introducing a call hardkey");
            System.out.println("PASS "+checks+" Honda Home/Foreground/Mute controls; offline wire and input-boundary checks");
        }finally{session.close();MdMain.bootNative=oldBoot;MdMain.nativeServicesEnabled=oldServices;}
    }
}
