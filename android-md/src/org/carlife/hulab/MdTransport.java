package org.carlife.hulab;

import java.io.*;
import java.net.Socket;

/** A byte stream carrying the unchanged CarLife channel/length and message headers. */
interface MdTransport extends Closeable {
    InputStream input();
    OutputStream output();
    /** One logical packet; native USB may preserve outer-header transfer boundaries. */
    default void writePacket(byte[] packet)throws IOException{output().write(packet);output().flush();}
    String name();
    boolean isNativeUsb();
    /** False forbids reopening another accessory fd while an old syscall might own it. */
    boolean awaitStopped(long timeoutMs);
}

final class SocketMdTransport implements MdTransport {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    SocketMdTransport(Socket socket)throws IOException {
        this.socket=socket;socket.setTcpNoDelay(true);socket.setSoTimeout(1000);
        socket.setSendBufferSize(131072);in=socket.getInputStream();out=socket.getOutputStream();
    }
    public InputStream input(){return in;}
    public OutputStream output(){return out;}
    public String name(){return "DEV_STREAM_OVER_ADB";}
    public boolean isNativeUsb(){return false;}
    public void close()throws IOException{socket.close();}
    public boolean awaitStopped(long timeoutMs){return true;}
}
