package org.carlife.hulab;
import java.io.*;import java.util.*;
public final class ProjectionTouchTest {
 static int checks;static void check(boolean a,String s){checks++;if(!a)throw new AssertionError(s);}interface Try{void run()throws Exception;}static void rejects(Try t)throws Exception{try{t.run();throw new AssertionError("accepted invalid frame");}catch(IOException|IllegalArgumentException e){checks++;}}
 static TouchFrame f(int a,int...ids)throws Exception{float[]x=new float[ids.length],y=new float[ids.length];for(int i=0;i<ids.length;i++){x[i]=10+i*100;y[i]=20+i*100;}return new TouchFrame(a,ids,x,y,0,7,100,"fixture");}
 static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
 public static void main(String[]args)throws Exception{
  double max=0;
  for(int[]v:new int[][]{{800,480},{1024,600},{1280,720},{1280,480},{1920,720}})for(int rot:new int[]{0,90,180,270}){
   ProjectionGeometry g=new ProjectionGeometry(1920,1080,v[0],v[1],rot);
   for(int x:new int[]{0,17,959,1501,1919})for(int y:new int[]{0,19,539,991,1079}){float[]q=g.toVideo(x,y);float[]r=g.toSource(Math.round(q[0]),Math.round(q[1]));check(r!=null,"edge remains within viewport");double error=Math.max(Math.abs(r[0]-x),Math.abs(r[1]-y));max=Math.max(max,error);check(error<=2.001,"quantized source error <=2pixels "+g+" "+error);}
   if(g.left>0)check(g.toSource(0,v[1]/2)==null,"pillarbox is not clamped to target");if(g.top>0)check(g.toSource(v[0]/2,0)==null,"letterbox is not clamped to target");
   check(g.toSource(-1,0)==null&&g.toSource(v[0],0)==null,"outside bounds");
  }
  TouchFrame a=TouchFrame.action3(hex("000000000700000000"),0,7,100);check(a.action==0&&a.ids[0]==7&&a.xs[0]==0,"independent pinned DOWN golden");
  TouchFrame b=TouchFrame.action3(hex("00000105070000000009050002d0"),0,7,100);check(b.action==5&&b.actionIndex==1&&b.ids[1]==9&&b.xs[1]==1280&&b.ys[1]==720,"independent pinned POINTER_DOWN bigendian golden");
  TouchFrame.State s=new TouchFrame.State();s.accept(f(0,7));s.accept(f(0x105,7,9));s.accept(f(2,9,7));s.accept(f(6,9,7));check(s.ids.length==1&&s.ids[0]==7,"first lifted pointer survivor ID stable after compaction");s.accept(f(1,7));check(s.ids.length==0,"final UP clears state");
  s.accept(f(0,7));s.accept(f(0x105,7,9));s.accept(f(0x106,7,9));check(s.ids[0]==7,"second lifted pointer stable");s.accept(s.cancel(110));check(s.ids.length==0,"CANCEL clears all");
  rejects(()->f(0,1,1));rejects(()->f(0x205,1,2));rejects(()->TouchFrame.action3(new byte[10],0,1,0));rejects(()->TouchFrame.action3(new byte[59],0,1,0));rejects(()->new TouchFrame.State().accept(f(2,1)));
  TouchFrame.State t=new TouchFrame.State();t.accept(f(0,1));rejects(()->t.accept(f(0,1)));rejects(()->t.accept(f(0x105,1,2,3)));rejects(()->t.accept(f(1,2)));
  TouchFrame.State d=new TouchFrame.State();Map<Integer,Object>m=MdMain.Proto.parse(MdMain.Proto.ints(1,0,2,0,3,0,4,0,5,0));d.accept(TouchFrame.legacy(m,true,d,0,1,100));check(d.ids.length==1,"zero-valued second fields do not create a finger");
  m=MdMain.Proto.parse(MdMain.Proto.ints(1,0x105,2,0,3,0,4,0,5,0));TouchFrame two=TouchFrame.legacy(m,true,d,0,1,110);d.accept(two);check(two.ids.length==2&&two.xs[1]==0,"real second finger at origin retained");
  m=MdMain.Proto.parse(MdMain.Proto.ints(1,6,2,0,3,0,4,0,5,0));d.accept(TouchFrame.legacy(m,true,d,0,1,120));check(d.ids[0]==1,"legacy first lift preserves synthetic surviving ID1");
  m=MdMain.Proto.parse(MdMain.Proto.ints(1,1,2,0,3,0,4,0,5,0));d.accept(TouchFrame.legacy(m,true,d,0,1,130));check(d.ids.length==0,"legacy final UP clears");
  System.out.println("PASS "+checks+" geometry/touch checks; max quantized source error="+max+"px; lab formats SELF only");
 }
}
