package org.carlife.hulab;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/** Independent wire fixtures for optional legacy positions and both pointer-release orders. */
public final class LegacyMultitouchTest {
    static int checks;
    interface Attempt { void run() throws Exception; }
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    static void rejects(Attempt action)throws Exception{
        try{action.run();throw new AssertionError("invalid/ambiguous touch accepted");}
        catch(IOException expected){checks++;}
    }
    static byte[] hex(String text){
        byte[] data=new byte[text.length()/2];
        for(int i=0;i<data.length;i++)data[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);
        return data;
    }
    static Map<Integer,Object> fields(String hex)throws Exception{return MdMain.Proto.parse(hex(hex));}
    static TouchFrame decode(TouchFrame.State state,int id,String hex,long time)throws Exception{
        return TouchFrame.legacyCompatible(id,fields(hex),state,0,9,time);
    }
    static TouchFrame step(TouchFrame.State state,int id,String hex,long time)throws Exception{
        TouchFrame frame=decode(state,id,hex,time);state.accept(frame);return frame;
    }
    public static void main(String[] ignored)throws Exception{
        // Transparent: DOWN(10,20), pointer index1 DOWN at(100,200), then MOVE without optional fields.
        TouchFrame.State raw=new TouchFrame.State();
        step(raw,0x68001,"0800100a1814",100);
        TouchFrame second=step(raw,0x68001,"088502100a1814206428c801",110);
        check(second.ids[1]==1&&second.xs[1]==100&&second.ys[1]==200,"literal second pointer position");
        TouchFrame move=step(raw,0x68001,"0802100b1815",120);
        check(move.ids.length==2&&move.xs[1]==100&&move.ys[1]==200,"absent pair keeps measured position");
        ProjectionGeometry geometry=new ProjectionGeometry(1920,1080,1280,720,0);
        TouchFrame mapped=move.mapped(geometry);
        check(mapped.xs[1]==150.25f&&mapped.ys[1]==300.25f,"raw fallback mapped exactly once with pixel-centre convention");
        check(raw.last.xs[1]==100&&raw.last.ys[1]==200,"raw state never stores mapped coordinates");
        // First pointer lifts; index0 in the following packet is still Android pointer ID1.
        step(raw,0x68001,"0806100b1815",130);
        check(Arrays.equals(raw.ids,new int[]{1}),"first lift retains survivor ID1");
        TouchFrame survivor=step(raw,0x68001,"0802106518c901",140);
        check(survivor.ids[0]==1&&survivor.xs[0]==101,"survivor compacts without ID change");
        step(raw,0x68001,"0801106518c901",150);
        check(raw.ids.length==0,"final UP releases survivor");

        // Gesture single-point protobuf uses x/y=1/2, pointerx/y=3/4 (not transparent 2/3,4/5).
        step(raw,0x6800a,"080a1014",200);
        TouchFrame gesture=step(raw,0x6800b,"080a1014186420c801",210);
        check(gesture.fullAction()==0x105&&gesture.xs[1]==100&&gesture.ys[1]==200,"gesture second DOWN fields");
        step(raw,0x68004,"080c1016186520c901",220);
        TouchFrame lifted=step(raw,0x6800d,"080d1017",230);
        check(lifted.fullAction()==0x106&&lifted.xs[1]==101&&lifted.ys[1]==201,"gesture second UP with last measured pair");
        check(Arrays.equals(raw.ids,new int[]{0}),"second lift retains ID0");
        step(raw,0x68003,"080d1017",240);
        check(raw.ids.length==0,"gesture final UP");
        step(raw,0x68002,"080a1014",250);
        step(raw,0x6800b,"080a1014186420c801",260);
        step(raw,0x6800c,"080a1014",270);
        check(Arrays.equals(raw.ids,new int[]{1}),"gesture first-lift opcode maps index0");
        step(raw,0x68003,"086410c801",280);

        // The documented HU sender writes zero-valued optional fields even for real coordinate(0,0).
        step(raw,0x68001,"0800100a1814",300);
        second=step(raw,0x68001,"088502100a181420002800",310);
        check(second.xs[1]==0&&second.ys[1]==0,"explicit origin is not an absent-position sentinel");
        move=step(raw,0x68001,"0802100b1815",320);
        check(move.ids.length==2&&move.xs[1]==0&&move.ys[1]==0,"real origin survives omitted pair");
        raw.accept(raw.cancel(330));

        // A packet proving a second contact exists cannot reveal an absent new position.
        step(raw,0x68001,"0800100a1814",400);
        rejects(()->decode(raw,0x68001,"088502100a1814",410));
        rejects(()->decode(raw,0x6800b,"080a1014",410));
        check(raw.ids.length==1&&raw.last.timeMs==400,"rejected decode leaves raw state unchanged");
        rejects(()->decode(raw,0x68001,"088502100a18142064",410));
        rejects(()->decode(raw,0x6800b,"080a10142064",410));
        rejects(()->decode(raw,0x68001,"088504100a1814206428c801",410));
        rejects(()->decode(raw,0x68001,"080010ffffffff0f1814",410));
        rejects(()->decode(raw,0x68001,"08001080808080101814",410));
        rejects(()->decode(raw,0x68005,"080a1014",410));
        rejects(()->decode(raw,0x68006,"080a1014",410));
        rejects(()->decode(raw,0x68007,"080a1014",410));
        check(!TouchFrame.isLegacyStream(0x68005)&&!TouchFrame.isLegacyStream(0x68008),"discrete gesture and key remain distinct");

        // New index0 has its position in x/y; the old pointer at index1 retains its measured position.
        TouchFrame indexZero=step(raw,0x68001,"0805106418c801",420);
        check(Arrays.equals(indexZero.ids,new int[]{1,0})&&indexZero.xs[1]==10&&indexZero.ys[1]==20,"pointer-down index0 preserves old measured contact");
        raw.clear();
        rejects(()->step(raw,0x68004,"080a1014",430));
        check(raw.ids.length==0,"no fabricated DOWN when gesture history is missing");

        // ACTION3 remains independent and supports 3 contacts, real IDs and arbitrary release order.
        TouchFrame.State multi=new TouchFrame.State();
        multi.accept(TouchFrame.action3(hex("0000000007000a0014"),0,9,500));
        multi.accept(TouchFrame.action3(hex("0000010507000a001409006400c8"),0,9,510));
        multi.accept(TouchFrame.action3(hex("0000020507000a001409006400c80c012c0190"),0,9,520));
        multi.accept(TouchFrame.action3(hex("0000010607000a001409006400c80c012c0190"),0,9,530));
        check(Arrays.equals(multi.ids,new int[]{7,12}),"ACTION3 removes middle index without renumbering survivors");
        multi.accept(multi.cancel(540));
        check(multi.ids.length==0,"ACTION3 CANCEL clears all contacts");
        System.out.println("PASS "+checks+" legacy/gesture multitouch compatibility checks; synthetic fixtures, no real-HU claim");
    }
}
