package android.net;
public final class LocalSocketAddress {
    public enum Namespace { ABSTRACT }
    public final String name;
    public LocalSocketAddress(String name, Namespace ns) { this.name=name; }
}
