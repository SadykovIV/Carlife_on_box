package org.carlife.selector;

/** Fixed selector protocol; nonce correlates replies, SO_PEERCRED authenticates the peer. */
public final class Protocol {
    public static final String SOCKET = "carlife_boot_selector_v1";
    public static boolean mode(String s) {
        return "android_auto".equals(s) || "carplay".equals(s) || "carlife".equals(s);
    }
    public static String reply(String nonce, String selected) {
        if (!nonce.matches("[0-9a-f]{32}") || (selected != null && !mode(selected)))
            throw new IllegalArgumentException("reply");
        return "V1\t" + nonce + (selected == null ? "\tERROR\tREFUSED\n" : "\tOK\t" + selected + "\n");
    }
    public static String helperReply(String verb, String requested, String out) {
        String selected = "GET".equals(verb) ? out.trim() : requested;
        if (!mode(selected) || ("SELECT".equals(verb) && !out.startsWith("selected=" + selected + "\n")))
            throw new IllegalArgumentException("helper_reply");
        return selected;
    }
    public static String[] parse(String line) {
        if (line == null || line.length() > 100) throw new IllegalArgumentException("length");
        String[] p = line.split("\t", -1);
        if (p.length != 4 || !"V1".equals(p[0]) || !p[1].matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("envelope");
        if (!("GET".equals(p[2]) && "-".equals(p[3])) && !("SELECT".equals(p[2]) && mode(p[3])))
            throw new IllegalArgumentException("verb_or_mode");
        return p;
    }
    private Protocol() {}
}
