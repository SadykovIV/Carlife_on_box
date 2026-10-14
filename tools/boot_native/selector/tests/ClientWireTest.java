import android.net.LocalSocket;
import org.carlife.selector.SelectorClient;
public final class ClientWireTest {
    public static void main(String[] ignored) throws Exception {
        if(!"android_auto".equals(SelectorClient.request(null)))throw new AssertionError("GET");
        for(String mode:new String[]{"android_auto","carplay","carlife"})
            if(!mode.equals(SelectorClient.request(mode)))throw new AssertionError("SELECT");
        for(String bad:new String[]{"nonce","refused","mode","eof"}) {
            LocalSocket.replyCase=bad;
            try { SelectorClient.request(null);throw new AssertionError("Accepted "+bad); }
            catch(java.io.IOException expected) {}
        }
        LocalSocket.replyCase="ok";LocalSocket.peerUid=1000;
        int writes=LocalSocket.writes;
        try {SelectorClient.request(null);throw new AssertionError("Non-root peer accepted");}
        catch(SecurityException expected) {}
        if(LocalSocket.writes!=writes)throw new AssertionError("request to foreign peer");
        if(LocalSocket.closed!=9)throw new AssertionError("not all sockets closed");
        System.out.println("PASS real client: connect-before-timeout, actual TAB/LF request bytes, 4 successes/5 failures, root-peer check, 9 closes; Android transport is a stub");
    }
}
