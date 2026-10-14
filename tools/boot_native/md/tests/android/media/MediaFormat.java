package android.media;
import java.nio.ByteBuffer;
import java.util.*;
/** Host-only recording double; actual encoder configuration remains a device check. */
public final class MediaFormat {
    public static final String KEY_COLOR_FORMAT="color-format", KEY_BIT_RATE="bitrate",
        KEY_FRAME_RATE="frame-rate", KEY_I_FRAME_INTERVAL="i-frame-interval", KEY_PRIORITY="priority",
        KEY_WIDTH="width", KEY_HEIGHT="height";
    private final Map<String,Object> values=new TreeMap<String,Object>();
    public static MediaFormat createVideoFormat(String mime,int width,int height){MediaFormat f=new MediaFormat();f.values.put("mime",mime);f.setInteger(KEY_WIDTH,width);f.setInteger(KEY_HEIGHT,height);return f;}
    public void setInteger(String key,int v){values.put(key,v);}
    public void setFloat(String key,float v){values.put(key,v);}
    public void setLong(String key,long v){values.put(key,v);}
    public int getInteger(String key){return ((Number)values.get(key)).intValue();}
    public boolean containsKey(String key){return values.containsKey(key);}
    public ByteBuffer getByteBuffer(String key){return (ByteBuffer)values.get(key);}
    public Map<String,Object> snapshot(){return new TreeMap<String,Object>(values);}
    public String toString(){return values.toString();}
}
