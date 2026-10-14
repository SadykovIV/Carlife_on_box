package org.carlife.hulab;
import java.io.*;
import java.util.*;

/** Canonical internal touchscreen frame. No additional fields are added to CarLife wire messages. */
final class TouchFrame {
    static final int MAX_POINTERS=10;
    final int action,actionIndex,displayId; final long generation,timeMs;final String source;
    final int[] ids;final float[] xs,ys;
    TouchFrame(int fullAction,int[] pids,float[] x,float[] y,int display,long gen,long time,String origin)throws IOException{
        action=fullAction&255;actionIndex=(fullAction>>>8)&255;displayId=display;generation=gen;timeMs=time;source=origin;
        if((fullAction&0xffff0000)!=0||(action!=0&&action!=1&&action!=2&&action!=3&&action!=5&&action!=6))throw new IOException("invalid full touch action");
        if(pids.length<1||pids.length>MAX_POINTERS||x.length!=pids.length||y.length!=pids.length)throw new IOException("pointer count bound");
        if(actionIndex>=pids.length||((action!=5&&action!=6)&&actionIndex!=0))throw new IOException("invalid actionIndex");
        Set<Integer> used=new HashSet<Integer>();for(int i=0;i<pids.length;i++)if(pids[i]<0||pids[i]>31||!used.add(pids[i])||!Float.isFinite(x[i])||!Float.isFinite(y[i]))throw new IOException("invalid or duplicate pointer ID/coordinates");
        ids=pids.clone();xs=x.clone();ys=y.clone();
    }
    int fullAction(){return action|(actionIndex<<8);}
    /** Legacy fields 4/5 may appear only when the second contact is introduced. */
    static boolean sameWireFamily(String a,String b){return a.equals(b)||((a.equals("legacy-single")||a.equals("legacy-dual"))&&(b.equals("legacy-single")||b.equals("legacy-dual")));}
    boolean repeatsDown(TouchFrame previous){return previous!=null&&action==0&&previous.action==0&&ids.length==1&&previous.ids.length==1&&displayId==previous.displayId&&generation==previous.generation&&sameWireFamily(source,previous.source)&&Arrays.equals(ids,previous.ids)&&Arrays.equals(xs,previous.xs)&&Arrays.equals(ys,previous.ys);}
    TouchFrame mapped(ProjectionGeometry g)throws IOException{float[] x=new float[ids.length],y=new float[ids.length];for(int i=0;i<ids.length;i++){float[] xy=g.toSource(xs[i],ys[i]);if(xy==null)return null;x[i]=xy[0];y[i]=xy[1];}return new TouchFrame(fullAction(),ids,x,y,displayId,generation,timeMs,source);}
    static TouchFrame action3(byte[] bytes,int display,long gen,long time)throws IOException{
        if(bytes.length<9||(bytes.length-4)%5!=0||(bytes.length-4)/5>MAX_POINTERS)throw new IOException("ACTION3 invalid length/count");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));int a=in.readInt(),n=(bytes.length-4)/5;int[] ids=new int[n];float[]x=new float[n],y=new float[n];for(int i=0;i<n;i++){ids[i]=in.readUnsignedByte();x[i]=in.readUnsignedShort();y[i]=in.readUnsignedShort();}return new TouchFrame(a,ids,x,y,display,gen,time,"action3-multi");
    }
    static TouchFrame legacy(Map<Integer,Object> fields,boolean dual,State state,int display,long gen,long time)throws IOException{
        MdMain.Proto.requireInts(fields,1,2,3);int a=MdMain.Proto.number(fields,1,-1),masked=a&255,index=(a>>>8)&255;
        if(!dual&&(masked>3||a!=masked))throw new IOException("LEGACY_SINGLE only single actions");
        if(dual)MdMain.Proto.requireInts(fields,4,5);
        int[] ids;if(masked==0)ids=new int[]{0};else if(masked==5&&dual){if(state.ids.length!=1||index>1)throw new IOException("LEGACY_DUAL ambiguous pointer-down state");ids=new int[2];ids[index]=state.ids[0]==0?1:0;ids[1-index]=state.ids[0];}else ids=state.ids.clone();
        if(ids.length<1||ids.length>(dual?2:1))throw new IOException("legacy pointer lifecycle unavailable");
        float[]x=new float[ids.length],y=new float[ids.length];x[0]=MdMain.Proto.number(fields,2,-1);y[0]=MdMain.Proto.number(fields,3,-1);if(ids.length==2){x[1]=MdMain.Proto.number(fields,4,-1);y[1]=MdMain.Proto.number(fields,5,-1);}
        return new TouchFrame(a,ids,x,y,display,gen,time,dual?"legacy-dual":"legacy-single");
    }
    static final class State {
        int[] ids=new int[0];TouchFrame last;long downTime;
        void clear(){ids=new int[0];last=null;}
        void accept(TouchFrame f)throws IOException{
            Set<Integer> old=set(ids),now=set(f.ids);int n=f.ids.length;
            if(last!=null&&(f.displayId!=last.displayId||f.generation!=last.generation))throw new IOException("touch generation/display changed without CANCEL");
            if(last!=null&&f.timeMs<last.timeMs)throw new IOException("non-monotonic touch timestamp");
            if(f.action==0){if(ids.length!=0||n!=1)throw new IOException("DOWN must start one new contact");downTime=f.timeMs;}
            else if(f.action==5){Set<Integer> reduced=new HashSet<Integer>(now);reduced.remove(f.ids[f.actionIndex]);if(ids.length==0||n!=ids.length+1||!old.equals(reduced))throw new IOException("POINTER_DOWN lifecycle");}
            else if(f.action==6){if(n<2||n!=ids.length||!old.equals(now))throw new IOException("POINTER_UP lifecycle");}
            else if(f.action==1){if(n!=1||ids.length!=1||!old.equals(now))throw new IOException("UP lifecycle");}
            else if(ids.length==0||n!=ids.length||!old.equals(now))throw new IOException("MOVE/CANCEL lifecycle");
            if(f.action==1||f.action==3){ids=new int[0];last=null;}else if(f.action==6){ids=new int[n-1];for(int i=0,j=0;i<n;i++)if(i!=f.actionIndex)ids[j++]=f.ids[i];float[]x=new float[n-1],y=new float[n-1];for(int i=0,j=0;i<n;i++)if(i!=f.actionIndex){x[j]=f.xs[i];y[j++]=f.ys[i];}last=new TouchFrame(2,ids,x,y,f.displayId,f.generation,f.timeMs,f.source);}else{ids=f.ids.clone();last=f;}
        }
        TouchFrame cancel(long time)throws IOException{return last==null?null:new TouchFrame(3,last.ids,last.xs,last.ys,last.displayId,last.generation,time,last.source);}
        static Set<Integer> set(int[] a){Set<Integer>s=new HashSet<Integer>();for(int x:a)s.add(x);return s;}
    }
}
