package io.duo.sim.protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * 双向长连接上的帧读写（计划 §2 连接模型：worker 拨号、单条双向长连接）。
 * 单线程读、单线程写互不共享状态；{@link #readFrame()} 阻塞直至一帧完整到达。
 */
public final class FrameConnection implements Closeable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public FrameConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    /** 阻塞读取一个完整帧（含 header）；流结束抛 {@link EOFException}。 */
    public byte[] readFrame() throws IOException {
        byte[] header = readFully(FrameCodec.HEADER_LENGTH);
        int magic = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (magic != FrameCodec.MAGIC) {
            throw new ProtocolViolationException("bad magic: 0x" + Integer.toHexString(magic));
        }
        if (header[4] != FrameCodec.VERSION) {
            throw new ProtocolViolationException("unsupported version: " + header[4]);
        }
        int len = ((header[5] & 0xFF) << 24) | ((header[6] & 0xFF) << 16)
                | ((header[7] & 0xFF) << 8) | (header[8] & 0xFF);
        if (len <= 0 || len > FrameCodec.MAX_PAYLOAD_LENGTH) {
            throw new ProtocolViolationException("bad payload length: " + len);
        }
        byte[] headerAndPayload = new byte[FrameCodec.HEADER_LENGTH + len];
        System.arraycopy(header, 0, headerAndPayload, 0, FrameCodec.HEADER_LENGTH);
        byte[] payload = readFully(len);
        System.arraycopy(payload, 0, headerAndPayload, FrameCodec.HEADER_LENGTH, len);
        return headerAndPayload;
    }

    /** 读取一个完整报文。 */
    public DuoMessage read() throws IOException {
        return DuoCodec.decode(readFrame());
    }

    /** 发送一个报文（写完整帧并 flush）。 */
    public void write(DuoMessage message) throws IOException {
        out.write(DuoCodec.encode(message));
        out.flush();
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int read = in.read(buf, off, n - off);
            if (read < 0) {
                throw new EOFException("stream ended after " + off + "/" + n + " bytes");
            }
            off += read;
        }
        return buf;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
