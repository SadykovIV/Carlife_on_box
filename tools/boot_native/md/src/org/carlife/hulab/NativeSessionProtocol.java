package org.carlife.hulab;

import java.io.*;
import java.util.*;

/** Native CarLife compatibility, informed by HeadSwap 6835e50 / Wizcar Honda logs.
 * No HU/model whitelist. This is independent of the Android display/video backend.
 */
final class NativeSessionProtocol {
    static final byte[] EMPTY=new byte[0];
    // Native v2 selected output: HeadSwap R720 default, not the HU SurfaceView size.
    static final int VIDEO_WIDTH=1280,VIDEO_HEIGHT=720;
    static int versionStatus(Map<Integer,Object> f)throws IOException {
        MdMain.Proto.requireInts(f,1,2);
        long major=(Long)f.get(1),minor=(Long)f.get(2);
        // Versions select the common wire protocol, not a hardcoded HU allowlist.
        return major>0&&major<=Integer.MAX_VALUE&&minor>=0&&minor<=Integer.MAX_VALUE?1:2;
    }
    static int fps(int requested){return requested>=1&&requested<=30?requested:30;}
    /** Baidu CarLife hard-key IDs -> public Android KeyEvent values.
     * Navigation/voice/rotary need app-specific handling; no AA-private keycodes.
     */
    static int androidKeyCode(int key){
        switch(key){
            case 0x01:case 0x1e:return 3; // HOME / MAIN
            case 0x0e:return 4; // BACK
            case 0x0f:return 88; // SEEK_SUB -> MEDIA_PREVIOUS
            case 0x10:return 87; // SEEK_ADD -> MEDIA_NEXT
            case 0x14:return 23; // OK -> DPAD_CENTER
            case 0x15:return 21; // MOVE_LEFT
            case 0x16:return 22; // MOVE_RIGHT
            case 0x17:return 19; // MOVE_UP
            case 0x18:return 20; // MOVE_DOWN
            case 0x1f:return 126; // MEDIA_START -> MEDIA_PLAY
            case 0x20:return 127; // MEDIA_STOP -> MEDIA_PAUSE
            default:return -1;
        }
    }
    static byte[] join(byte[]...parts)throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[]p:parts)b.write(p);return b.toByteArray();
    }
    static void bytes(ByteArrayOutputStream b,int field,byte[] data)throws IOException {
        MdMain.Proto.varint(b,(field<<3)|2);MdMain.Proto.varint(b,data.length);b.write(data);
    }
    static byte[] idleModules()throws IOException {
        int[] ids={1,2,3,4,6,8,9};ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(MdMain.Proto.ints(1,ids.length));
        for(int id:ids)bytes(b,2,MdMain.Proto.ints(1,id,2,0));return b.toByteArray();
    }
    static byte[] multitouchFeatureRequest()throws IOException {
        // Apollo HU SDK fddb0be: CarlifeFeatureConfigList(cnt=1, featureConfig=[
        // {key="MULTI_TOUCH", value=1}]). Its feature listener selects ACTION_3.
        // This is an MD capability request; actual HU support is not assumed.
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        b.write(MdMain.Proto.ints(1,1));
        bytes(b,2,join(MdMain.Proto.strings(1,"MULTI_TOUCH"),MdMain.Proto.ints(2,1)));
        return b.toByteArray();
    }
    static byte[] subscribeDone(byte[] request)throws IOException {
        // These are navigation/media metadata subscriptions, not video or touch.
        // Report unsupported until a corresponding metadata producer exists.
        ByteArrayInputStream in=new ByteArrayInputStream(request);List<Integer> ids=new ArrayList<Integer>();int count=0;
        while(in.available()>0){
            if(++count>128)throw new IOException("subscription field count");long key=MdMain.Proto.varint(in);int field=(int)(key>>>3),wire=(int)(key&7);
            if(field==0)throw new IOException("subscription field zero");
            if(wire==0){MdMain.Proto.varint(in);continue;}
            if(wire==1||wire==5){int n=wire==1?8:4;if(in.skip(n)!=n)throw new EOFException("subscription fixed field");continue;}
            if(wire!=2)throw new IOException("subscription wire type");long length=MdMain.Proto.varint(in);
            if(length<0||length>in.available())throw new EOFException("subscription message");byte[] body=new byte[(int)length];in.read(body);
            if(field==2){Map<Integer,Object> f=MdMain.Proto.parse(body);MdMain.Proto.requireInts(f,1);ids.add(MdMain.Proto.number(f,1,-1));}
        }
        ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(MdMain.Proto.ints(1,ids.size()));
        for(int id:ids)bytes(b,2,MdMain.Proto.ints(1,id,2,0));return b.toByteArray();
    }
    static byte[] deviceInfo(String marker)throws IOException {
        // Actual platform identity; no impersonation of Baidu/Wizcar or SDK downgrade.
        return join(MdMain.Proto.strings(1,"Android",2,value(android.os.Build.BOARD),4,value(android.os.Build.BRAND),
            7,value(android.os.Build.DEVICE),8,value(android.os.Build.DISPLAY),10,value(android.os.Build.HARDWARE),
            13,value(android.os.Build.MANUFACTURER),14,value(android.os.Build.MODEL),15,marker,
            19,value(android.os.Build.VERSION.RELEASE),20,Integer.toString(android.os.Build.VERSION.SDK_INT),24,"native-interop-1"),
            MdMain.Proto.ints(21,android.os.Build.VERSION.SDK_INT,25,1));
    }
    static String value(String s){return s==null?"":s;}
}
