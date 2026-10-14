package android.net;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Offline transport fixture: enforces Android create-before-setOption behavior. */
public final class LocalSocket implements Closeable {
    public static String replyCase="ok";
    public static int peerUid=0, closed=0, writes=0;
    private boolean connected=false;
    private final ByteArrayOutputStream request=new ByteArrayOutputStream();
    private InputStream input;
    public void connect(LocalSocketAddress a) throws IOException {
        if(!"carlife_boot_selector_v1".equals(a.name))throw new IOException("wrong address");
        connected=true;
    }
    public void setSoTimeout(int millis) throws IOException {
        if(!connected)throw new IOException("socket not created (Android LocalSocketImpl.setOption)");
        if(millis!=12000)throw new IOException("wrong read budget");
    }
    public Credentials getPeerCredentials() { return new Credentials(peerUid); }
    public OutputStream getOutputStream() { writes++;return request; }
    public InputStream getInputStream() throws IOException {
        if(input!=null)return input;
        byte[] bytes=request.toByteArray();
        if(bytes.length==0 || bytes[bytes.length-1]!=10)throw new IOException("actual LF required");
        for(byte b:bytes)if(b=='\\')throw new IOException("literal slash rejected");
        String wire=new String(bytes,StandardCharsets.US_ASCII);
        String[] fields=wire.substring(0,wire.length()-1).split(String.valueOf((char)9),-1);
        if(fields.length!=4 || !fields[0].equals("V1") || !fields[1].matches("[a-f0-9]{32}"))throw new IOException("bad actual wire");
        String mode;
        if(fields[2].equals("GET") && fields[3].equals("-"))mode="android_auto";
        else if(fields[2].equals("SELECT") && (fields[3].equals("android_auto") || fields[3].equals("carplay") || fields[3].equals("carlife")))mode=fields[3];
        else throw new IOException("bad verb");
        // Independently constructed bytes, not Protocol.reply: prevents a test
        // from duplicating the same incorrect escaping on both endpoints.
        String nonce=replyCase.equals("nonce")?"ffffffffffffffffffffffffffffffff":fields[1];
        String status=replyCase.equals("refused")?"ERROR":"OK";
        if(replyCase.equals("refused"))mode="REFUSED";
        if(replyCase.equals("mode"))mode="other";
        String reply="V1"+(char)9+nonce+(char)9+status+(char)9+mode+(replyCase.equals("eof")?"":String.valueOf((char)10));
        input=new ByteArrayInputStream(reply.getBytes(StandardCharsets.US_ASCII));return input;
    }
    public void close() { closed++; }
}
