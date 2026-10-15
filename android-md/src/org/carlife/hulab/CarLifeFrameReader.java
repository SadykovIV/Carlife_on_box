package org.carlife.hulab;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Native CarLife receive compatibility, independently implemented from the observed
 * outer-stream / per-channel-inner contract. No USB calls and no resynchronization.
 * CMD reserved is opaque. A complete outer with a nonempty inner body uses
 * min(declared, available); valid concatenated inners are separated first.
 * An inner header alone can be followed by its body in later same-channel outers.
 */
public final class CarLifeFrameReader {
    public static final int MAX_BYTES=1024*1024, CHANNELS=6;
    public static final long DEFAULT_PARTIAL_TIMEOUT_MS=3000;
    public interface Clock { long millis(); }
    public interface Diagnostics { void accept(Header h); }
    public static final class Frame {
        public final int channel,id,timestamp;
        public final byte[] payload;
        public final TouchWire touchWire;
        Frame(int channel,int id,byte[] payload,TouchWire touchWire){this(channel,id,payload,touchWire,0);}
        Frame(int channel,int id,byte[] payload,TouchWire touchWire,int timestamp){this.channel=channel;this.id=id;this.payload=payload;this.touchWire=touchWire;this.timestamp=timestamp;}
    }
    /** Native TOUCH evidence only; preserves the full opaque inner header and the
     * available body before reference min(declared, available) delivery semantics.
     * Does not reinterpret reserved bytes as a pointer ID or change parsing.
     */
    public static final class TouchWire {
        public static final int PREFIX_BYTES=2048;
        public final byte[] innerHeader,availableBodyPrefix;
        public final int declaredLength,availableLength,deliveredLength;
        public final String innerSha256;
        TouchWire(byte[] body,int offset,int size,int declaredLength,int deliveredLength){
            this.innerHeader=Arrays.copyOfRange(body,offset,offset+8);
            this.declaredLength=declaredLength;this.availableLength=size-8;this.deliveredLength=deliveredLength;
            this.availableBodyPrefix=Arrays.copyOfRange(body,offset+8,offset+8+Math.min(availableLength,PREFIX_BYTES));
            String hash=null;
            try{MessageDigest d=MessageDigest.getInstance("SHA-256");d.update(body,offset,size);byte[] bytes=d.digest();StringBuilder text=new StringBuilder();for(byte b:bytes){int v=b&255;if(v<16)text.append('0');text.append(Integer.toHexString(v));}hash=text.toString();}
            catch(NoSuchAlgorithmException unavailable){/* Evidence failure cannot change wire delivery. */}
            this.innerSha256=hash;
        }
    }
    /** Only framing bytes, never protobuf text, are included in headerHex. */
    public static final class Header {
        public final String reason,headerHex;
        public final int channel,outerLength,innerLength,headerLength,declaredLength,reserved,messageId,availableLength;
        Header(String reason,int channel,int outerLength,int innerLength,int headerLength,int declaredLength,int reserved,int messageId,int availableLength,byte[] bytes,int offset,int count){
            this.reason=reason;this.channel=channel;this.outerLength=outerLength;this.headerLength=headerLength;
            this.innerLength=innerLength;
            this.declaredLength=declaredLength;this.reserved=reserved;this.messageId=messageId;this.availableLength=availableLength;
            StringBuilder s=new StringBuilder();
            for(int i=offset;i<offset+Math.min(count,12);i++){int v=bytes[i]&255;if(v<16)s.append('0');s.append(Integer.toHexString(v));}
            headerHex=s.toString();
        }
    }
    private static final class Pending {
        final byte[] bytes;final long since;
        Pending(byte[] bytes,long since){this.bytes=bytes;this.since=since;}
    }
    private final DataInputStream in;
    private final Diagnostics diagnostics;
    private final Clock clock;
    private final long partialTimeoutMs;
    private final Pending[] pending=new Pending[CHANNELS+1];
    private final ArrayDeque<Frame> ready=new ArrayDeque<Frame>();
    private int pendingBytes,currentOuterLength;
    private boolean failed;

    public CarLifeFrameReader(InputStream input,Diagnostics diagnostics){
        this(input,diagnostics,new Clock(){public long millis(){return System.nanoTime()/1000000L;}},DEFAULT_PARTIAL_TIMEOUT_MS);
    }
    public CarLifeFrameReader(InputStream input,Diagnostics diagnostics,Clock clock,long partialTimeoutMs){
        if(input==null||clock==null||partialTimeoutMs<1)throw new IllegalArgumentException("input, clock and positive partial deadline required");
        this.in=new DataInputStream(input);this.diagnostics=diagnostics;this.clock=clock;this.partialTimeoutMs=partialTimeoutMs;
    }
    /** Boundary timeout returns null without discarding any per-channel pending bytes. */
    public Frame readNext()throws IOException {
        if(failed)throw new IOException("CarLife reader already failed; no resynchronization");
        try {
            for(;;){
                checkDeadlines();
                if(!ready.isEmpty())return ready.removeFirst();
                int first;
                try{first=in.read();}catch(SocketTimeoutException idle){checkDeadlines();return null;}
                if(first<0)throw new EOFException(pendingBytes==0?"CarLife EOF at outer boundary":"CarLife EOF with incomplete inner");
                byte[] outer=new byte[8];outer[0]=(byte)first;
                try{in.readFully(outer,1,7);}catch(SocketTimeoutException e){throw new IOException("CarLife timeout inside outer header",e);}
                catch(EOFException e){throw new EOFException("CarLife truncated outer header");}
                int channel=u32(outer,0),length=u32(outer,4);
                if(channel<1||channel>CHANNELS||length<0||length>MAX_BYTES){
                    diagnostic(new Header("INVALID_OUTER",channel,length,-1,8,-1,-1,-1,-1,outer,0,8));
                    throw new IOException("CarLife invalid outer channel/length");
                }
                currentOuterLength=length;
                diagnostic(new Header("OUTER_HEADER",channel,length,-1,8,-1,-1,-1,-1,outer,0,8));
                byte[] body=new byte[length];
                try{in.readFully(body);}catch(SocketTimeoutException e){throw new IOException("CarLife timeout inside outer body",e);}
                catch(EOFException e){throw new EOFException("CarLife truncated outer body");}
                checkDeadlines();
                acceptOuter(channel,body);
            }
        }catch(IOException e){failed=true;throw e;}
    }
    private void checkDeadlines()throws IOException {
        long now=clock.millis();
        for(int c=1;c<=CHANNELS;c++)if(pending[c]!=null&&now-pending[c].since>=partialTimeoutMs){
            describe("INCOMPLETE_INNER_TIMEOUT",c,pending[c].bytes,0,pending[c].bytes.length);
            throw new IOException("CarLife incomplete inner deadline channel="+c);
        }
    }
    private void acceptOuter(int c,byte[] body)throws IOException {
        Pending prior=pending[c];
        if(prior!=null){
            if(body.length>MAX_BYTES-pendingBytes)throw new IOException("CarLife aggregate incomplete inner exceeds 1MiB");
            pending[c]=null;pendingBytes-=prior.bytes.length;
            ByteArrayOutputStream joined=new ByteArrayOutputStream(prior.bytes.length+body.length);
            joined.write(prior.bytes,0,prior.bytes.length);joined.write(body,0,body.length);
            drain(c,joined.toByteArray(),prior.since);return;
        }
        int h=headerLength(c);
        if(body.length>=h){
            validateHeader(c,body,0);
            int declared=declared(c,body,0);
            if(body.length==h&&declared>0){describe("SPLIT_INNER_HEADER",c,body,0,body.length);hold(c,body,clock.millis());return;}
            if(body.length>h+declared&&isChain(c,body)){drain(c,body,clock.millis());return;}
        }
        emit(c,body,0,body.length);
    }
    /** Recognition only; a foreign tail is not guessed to be another message. */
    private boolean isChain(int c,byte[] body){
        int offset=0,h=headerLength(c);
        while(body.length-offset>=h){
            if(!headerValid(c,body,offset))return false;
            offset+=h+declared(c,body,offset);
        }
        return true;
    }
    private void drain(int c,byte[] body,long originalSince)throws IOException {
        int offset=0,h=headerLength(c);
        while(body.length-offset>=h){
            validateHeader(c,body,offset);
            int size=h+declared(c,body,offset);
            if(size>body.length-offset)break;
            emit(c,body,offset,size);offset+=size;
        }
        if(offset<body.length)hold(c,Arrays.copyOfRange(body,offset,body.length),offset==0?originalSince:clock.millis());
    }
    private void hold(int c,byte[] body,long since)throws IOException {
        if(body.length>MAX_BYTES-pendingBytes)throw new IOException("CarLife aggregate incomplete inner exceeds 1MiB");
        if(pending[c]!=null)throw new IOException("CarLife pending ownership conflict");
        pending[c]=new Pending(body,since);pendingBytes+=body.length;
    }
    private void emit(int c,byte[] body,int offset,int size)throws IOException {
        int h=headerLength(c);
        if(size<h){describe("SHORT_INNER_IGNORED",c,body,offset,size);return;}
        validateHeader(c,body,offset);
        int n=declared(c,body,offset),available=size-h;
        describe(n==available?"INNER_HEADER":"INNER_LENGTH_MISMATCH",c,body,offset,size);
        // Match reference parseCmd/parseData semantics, never invent payload bytes.
        int copied=Math.min(n,available),id=u32(body,offset+(h==8?4:8));
        if(ready.size()>=MAX_BYTES/8)throw new IOException("CarLife complete inner queue exceeds bounded outer capacity");
        ready.addLast(new Frame(c,id,Arrays.copyOfRange(body,offset+h,offset+h+copied),c==6?new TouchWire(body,offset,size,n,copied):null,h==12?u32(body,offset+4):0));
    }
    private void validateHeader(int c,byte[] b,int o)throws IOException {
        if(!headerValid(c,b,o)){
            describe("INVALID_INNER_HEADER",c,b,o,b.length-o);
            throw new IOException("CarLife invalid inner length or message ID/channel");
        }
    }
    private boolean headerValid(int c,byte[] b,int o){
        int h=headerLength(c),n=declared(c,b,o),id=u32(b,o+(h==8?4:8));
        return n>=0&&n<=MAX_BYTES-h&&(id>>>16)==c;
    }
    private void describe(String reason,int c,byte[] b,int o,int size){
        int h=headerLength(c);boolean full=size>=h;
        diagnostic(new Header(reason,c,currentOuterLength,size,h,full?declared(c,b,o):-1,full&&h==8?u16(b,o+2):-1,
            full?u32(b,o+(h==8?4:8)):-1,Math.max(0,size-h),b,o,Math.min(size,h)));
    }
    private void diagnostic(Header h){if(diagnostics!=null)try{diagnostics.accept(h);}catch(RuntimeException ignored){/* diagnostics must not alter framing */}}
    private static int headerLength(int c){return c==1||c==6?8:12;}
    private static int declared(int c,byte[] b,int o){return headerLength(c)==8?u16(b,o):u32(b,o);}
    private static int u16(byte[] b,int o){return((b[o]&255)<<8)|(b[o+1]&255);}
    private static int u32(byte[] b,int o){return((b[o]&255)<<24)|((b[o+1]&255)<<16)|((b[o+2]&255)<<8)|(b[o+3]&255);}
}
