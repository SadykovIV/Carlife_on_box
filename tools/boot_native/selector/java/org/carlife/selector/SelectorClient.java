package org.carlife.selector;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
public final class SelectorClient {
    /** Read-only diagnostic entry; writes remain an explicit UI selection. */
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("GET-only diagnostic");
        System.out.println("selected=" + request(null));
    }
    public static String request(String mode) throws Exception {
        if (mode != null && !Protocol.mode(mode)) throw new IllegalArgumentException("mode");
        String n = UUID.randomUUID().toString().replace("-", "");
        try (LocalSocket s = new LocalSocket()) {
            s.connect(new LocalSocketAddress(Protocol.SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(12000);
            if (s.getPeerCredentials().getUid() != 0) throw new SecurityException("not_root_server");
            String q = "V1\t" + n + "\t" + (mode == null ? "GET\t-" : "SELECT\t" + mode) + "\n";
            s.getOutputStream().write(q.getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            boolean newline = false;
            for (int i = 0; i < 100; i++) {
                int c = s.getInputStream().read();
                if (c == 10) { newline = true; break; }
                if (c < 0 || c > 127) throw new IOException("reply");
                b.write(c);
            }
            if (!newline) throw new IOException("unterminated_reply");
            String[] p = b.toString("US-ASCII").split("\t", -1);
            if (p.length != 4 || !"V1".equals(p[0]) || !n.equals(p[1]) || !"OK".equals(p[2]) || !Protocol.mode(p[3]))
                throw new IOException("selector_refused_or_unavailable");
            if (mode != null && !mode.equals(p[3])) throw new IOException("different_selection");
            return p[3];
        }
    }
    private SelectorClient() {}
}
