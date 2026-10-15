package org.carlife.hulab;

import java.io.*;
import java.util.*;
import android.os.SystemClock;

/** Actual native dispatch, raw/mapped separation and local recovery; platform injection is mocked. */
public final class NativeLegacyMultitouchTest {
    static int checks;
    static void check(boolean pass,String description){checks++;if(!pass)throw new AssertionError(description);}
    static void send(MdMain.Session session,int id,String body,long time)throws Exception{
        SystemClock.fixedTime=time;session.dispatch(6,id,NativeTouchRecoveryTest.hex(body));
    }
    static void gestureAndLastPosition()throws Exception{
        NativeTouchRecoveryTest.Sink sink=new NativeTouchRecoveryTest.Sink();
        MdMain.Session session=NativeTouchRecoveryTest.session(true,sink);
        send(session,0x6800a,"080a1014",100);
        send(session,0x68002,"080a1014",101);
        check(sink.injected.size()==1&&sink.rawState.downTime==100,"BEGIN followed by duplicate DOWN retains one Android DOWN");
        send(session,0x6800b,"080a1014186420c801",110);
        send(session,0x68004,"080b1015",120);
        check(sink.rawState.last.xs[1]==100&&sink.rawState.last.ys[1]==200,"gesture missing optional pair keeps measured raw position");
        check(sink.state.last.xs[1]==150.25f&&sink.state.last.ys[1]==300.25f,"gesture missing optional pair scales exactly once");
        check(session.touchRecoveries==1&&sink.state.ids.length==2,"only duplicate DOWN recovery, real gesture remains active");
        send(session,0x6800c,"080b1015",130);
        send(session,0x68004,"086510c901",140);
        check(sink.rawState.ids[0]==1&&sink.state.ids[0]==1,"both raw and mapped states retain survivor ID1");
        send(session,0x68003,"086510c901",150);
        check(sink.rawState.last==null&&sink.state.last==null,"gesture UP releases both states");
        NativeTouchRecoveryTest.preserved(session);
    }
    static void cancellationAndRecovery()throws Exception{
        NativeTouchRecoveryTest.Sink sink=new NativeTouchRecoveryTest.Sink();
        MdMain.Session session=NativeTouchRecoveryTest.session(true,sink);
        send(session,0x68001,"0800100a1814",200);
        send(session,0x68001,"088502100a181420002800",210);
        send(session,0x68001,"0802100b1815",220);
        check(sink.state.last.ids.length==2&&sink.rawState.last.xs[1]==0&&sink.state.last.xs[1]==0.25f,"real origin remains a measured second contact");
        sink.cancel();
        check(sink.state.last==null&&sink.rawState.last==null,"control-path cancel clears raw history too");
        send(session,0x68001,"0800100a1814",230);
        send(session,0x6800b,"080a1014",240);
        check(sink.ignoreUntilDown&&sink.state.last==null&&sink.rawState.last==null,"missing new pointer position causes input-local recovery");
        int injected=sink.injected.size();
        send(session,0x68004,"080b1015",250);
        check(sink.injected.size()==injected,"gesture MOVE after failure waits for fresh DOWN");
        send(session,0x6800a,"080c1016",260);
        sink.rejectAction=2;send(session,0x68004,"080d1017",270);
        check(sink.state.last==null&&sink.rawState.last==null&&sink.lastAttempt==null,"Android refusal clears raw and attempted contact");
        sink.rejectAction=-1;send(session,0x6800a,"080a1014",280);
        send(session,0x68004,"08800a1014",290); // x=1280 is outside the 1280-wide accepted video.
        check(sink.ignoreUntilDown&&sink.state.last==null&&sink.rawState.last==null,"outside geometry cancels both coordinate domains");
        NativeTouchRecoveryTest.preserved(session);
    }
    static void restartAndProfiles()throws Exception{
        NativeTouchRecoveryTest.Sink sink=new NativeTouchRecoveryTest.Sink();
        MdMain.Session session=NativeTouchRecoveryTest.session(true,sink);
        send(session,0x6800a,"080a1014",300);
        send(session,0x6800b,"080a1014186420c801",310);
        send(session,0x6800a,"081e1028",320);
        check(sink.rawState.ids.length==1&&sink.rawState.last.xs[0]==30&&sink.rawState.downTime==320,"fresh DOWN cancels old multi gesture then seeds new raw history");
        check(sink.injected.get(2).action==3&&sink.injected.get(3).action==0,"restart preserves CANCEL-before-DOWN order");
        // ACTION3 cannot silently consume raw legacy IDs: changing family cancels locally.
        send(session,0x6800e,"0000000007000a0014",330);
        check(sink.ignoreUntilDown&&sink.rawState.last==null&&session.open,"wire-family switch retains original cancellation rule");
        send(session,0x6800e,"0000000007000a0014",340);
        check(sink.state.ids[0]==7&&sink.rawState.last==null,"fresh ACTION3 ID is independent of legacy state");
        sink.cancel();
        send(session,0x68005,"080a1014",350);
        check(sink.state.last==null&&session.unknownTouchDiagnosticSamples==1,"discrete click is not fabricated Android CANCEL or DOWN");

        NativeTouchRecoveryTest.Sink devSink=new NativeTouchRecoveryTest.Sink();
        MdMain.Session dev=NativeTouchRecoveryTest.session(false,devSink);
        send(dev,0x6800a,"080a1014",400);
        check(devSink.injected.isEmpty()&&devSink.rawState.last==null,"DEV gesture behavior unchanged");
        MdMain.touchProfile="legacy-single";
        send(session,0x6800a,"080a1014",410);
        check(sink.state.last==null&&sink.rawState.last==null,"explicit legacy-single profile does not silently enable gesture compatibility");
        MdMain.touchProfile="recognized";
    }
    public static void main(String[] args)throws Exception{
        MdMain.bootNative=true;MdMain.touchProfile="recognized";MdMain.running=true;
        PrintStream previous=System.out;ByteArrayOutputStream logs=new ByteArrayOutputStream();
        System.setOut(new PrintStream(logs,true,"UTF-8"));
        try{gestureAndLastPosition();cancellationAndRecovery();restartAndProfiles();}
        finally{System.setOut(previous);SystemClock.fixedTime=-1;MdMain.bootNative=false;MdMain.touchProfile="recognized";}
        String text=logs.toString("UTF-8");
        check(text.contains("\"message_id\":425995")&&text.contains("\"full_action\":261"),"gesture opcode logs actual full action rather than field1 coordinate");
        check(text.contains("\"raw_xs\":\"[11.0, 100.0]\"")&&text.contains("\"mapped_xs\":\"[16.75, 150.25]\""),"diagnostics distinguish raw from mapped and retained second position");
        check(text.contains("missing second pointer position/history")&&text.contains("cancel_wait_down"),"ambiguous new contact recovery reason retained");
        System.out.println("PASS "+checks+" native legacy/gesture raw-state/geometry/CANCEL/recovery/profile checks; synthetic HU, no device I/O");
    }
}
