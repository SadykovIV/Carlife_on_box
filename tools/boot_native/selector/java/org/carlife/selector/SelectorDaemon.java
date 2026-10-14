package org.carlife.selector;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Credentials;
import android.os.Process;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/** Root-owned fixed API. No su grants, USB, property writes, reboot or arbitrary argv. */
public final class SelectorDaemon {
    private static final String HELPER = "/data/adb/carlife-boot/selector-control.sh";
    private static String expected;
    private static String sha(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) d.update(b, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte v : d.digest()) s.append(String.format("%02x", v & 255));
        return s.toString();
    }
    private static void pinnedHelper() throws Exception {
        File f = new File(HELPER);
        if (!f.getCanonicalPath().equals(HELPER) || !sha(f).equals(expected))
            throw new IOException("helper_changed");
        android.system.StructStat st = android.system.Os.stat(HELPER);
        if (st.st_uid != 0 || st.st_gid != 0 || (st.st_mode & 07777) != 0500)
            throw new IOException("helper_metadata");
    }
    private static String readLine(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i <= limit; i++) {
            int c = in.read();
            if (c == 10) return b.toString("US-ASCII");
            if (c < 0 || c > 127 || c == 0) throw new IOException("invalid_line");
            b.write(c);
        }
        throw new IOException("line_too_long");
    }
    private static String execute(String[] p) throws Exception {
        pinnedHelper();
        String[] argv = "GET".equals(p[2]) ? new String[]{"/system/bin/sh", HELPER, "get"} :
            new String[]{"/system/bin/sh", HELPER, "select-next-boot", p[3]};
        java.lang.Process child = new ProcessBuilder(argv).redirectErrorStream(true).start();
        if (!child.waitFor(10, TimeUnit.SECONDS)) {
            child.destroy(); if (!child.waitFor(1, TimeUnit.SECONDS)) child.destroyForcibly();
            throw new IOException("helper_timeout_selection_must_be_reread");
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = child.getInputStream()) {
            for (int v; (v = in.read()) != -1;) { if (buf.size() >= 2048) throw new IOException("helper_output"); buf.write(v); }
        }
        String out = buf.toString("US-ASCII");
        if (child.exitValue() != 0) throw new IOException("helper_refused");
        return Protocol.helperReply(p[2], p[3], out);
    }
    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 0 || args.length != 1 || !args[0].matches("[0-9a-f]{64}"))
            throw new SecurityException("root_and_helper_sha_required");
        expected = args[0]; pinnedHelper();
        try (LocalServerSocket server = new LocalServerSocket(Protocol.SOCKET)) {
            System.out.println("SELECTOR_READY_V1 uid=0 trust_domain=android.uid.system");
            while (true) {
                try (LocalSocket s = server.accept()) {
                    s.setSoTimeout(3000);
                    Credentials peer = s.getPeerCredentials();
                    if (peer.getUid() != 1000 && peer.getUid() != 0) continue;
                    String[] p = Protocol.parse(readLine(s.getInputStream(), 100));
                    String response;
                    try { response = Protocol.reply(p[1], execute(p)); }
                    catch (Exception e) {
                        System.err.println("SELECTOR_REQUEST_REFUSED " + e.getClass().getSimpleName() + ":" + e.getMessage());
                        response = Protocol.reply(p[1], null);
                    }
                    s.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    s.getOutputStream().flush();
                } catch (IOException | IllegalArgumentException e) {
                    // Invalid peers have no persistent effect. No retry of a selection.
                }
            }
        }
    }
}
