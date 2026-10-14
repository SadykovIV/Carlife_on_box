package org.carlife.hulab;
/** One aspect-fit transform, shared by EGL viewport and video-space input. Pixel units, not dp. */
final class ProjectionGeometry {
    final int sourceWidth,sourceHeight,videoWidth,videoHeight,rotation,left,top,contentWidth,contentHeight;
    ProjectionGeometry(int sw,int sh,int vw,int vh,int rot){
        if(sw<1||sh<1||vw<1||vh<1||(rot!=0&&rot!=90&&rot!=180&&rot!=270))throw new IllegalArgumentException("geometry bounds");
        sourceWidth=sw;sourceHeight=sh;videoWidth=vw;videoHeight=vh;rotation=rot;
        int rw=rot%180==0?sw:sh,rh=rot%180==0?sh:sw;double s=Math.min(vw/(double)rw,vh/(double)rh);
        contentWidth=Math.min(vw,(int)Math.round(rw*s));contentHeight=Math.min(vh,(int)Math.round(rh*s));left=(vw-contentWidth)/2;top=(vh-contentHeight)/2;
    }
    float[] toSource(float x,float y){
        if(!Float.isFinite(x)||!Float.isFinite(y)||x<left||y<top||x>=left+contentWidth||y>=top+contentHeight)return null;
        double u=(x-left+.5)/contentWidth,v=(y-top+.5)/contentHeight,su=u,sv=v;
        if(rotation==90){su=v;sv=1-u;}else if(rotation==180){su=1-u;sv=1-v;}else if(rotation==270){su=1-v;sv=u;}
        return new float[]{(float)Math.max(0,Math.min(sourceWidth-1,su*sourceWidth-.5)),(float)Math.max(0,Math.min(sourceHeight-1,sv*sourceHeight-.5))};
    }
    float[] toVideo(float x,float y){double u=(x+.5)/sourceWidth,v=(y+.5)/sourceHeight,a=u,b=v;if(rotation==90){a=1-v;b=u;}else if(rotation==180){a=1-u;b=1-v;}else if(rotation==270){a=v;b=1-u;}return new float[]{(float)(left+a*contentWidth-.5),(float)(top+b*contentHeight-.5)};}
    public String toString(){return "source="+sourceWidth+"x"+sourceHeight+" video="+videoWidth+"x"+videoHeight+" rotation="+rotation+" fit=["+left+","+top+","+contentWidth+","+contentHeight+"]";}
}
