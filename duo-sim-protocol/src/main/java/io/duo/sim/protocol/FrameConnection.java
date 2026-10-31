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
 * 读侧单线程、写侧**多线程安全**：{@link #readFrame()} 阻塞直至一帧完整到达；
 * {@link #write} 把「编码 + 写缓冲 + flush」作为临界区串行化——**帧边界即协议边界**，
 * 两个线程同时写同一连接时不得交错或截断（读侧单线程是硬约束：并发读会互相偷帧）。
 */
public final class FrameConnection implements Closeable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    /** 写侧串行化锁（与读侧分离：持锁读会阻塞写，且读本就要求单线程）。 */
    private final Object writeLock = new Object();

    /** 在已连接 socket 上装配缓冲读写流（读单线程/写串行化的约束见类注释）。 */
    public FrameConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    /** 阻塞读取一个完整帧（含 header）；流结束抛 {@link EOFException}。 */
    public byte[] readFrame() throws IOException {
        // 帧头校验单点在 FrameCodec.parseHeader（评审 R9：此前两处各抄一份）
        byte[] header = readFully(FrameCodec.HEADER_LENGTH);
        int len = FrameCodec.parseHeader(header).payloadLength();
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

    /**
     * 发送一个报文（写完整帧并 flush）。
     *
     * <p>**多线程安全**：生产侧同一条连接上有多个写者（{@code VirtualWorker} 的心跳主循环线程、
     * 下行读线程的拒绝回报、任务线程的终态回报/槽位上报），故「编码 + 写入 + flush」整体串行化。
     * 编码是纯函数，放在锁外以免拉长临界区。
     */
    public void write(DuoMessage message) throws IOException {
        byte[] frame = DuoCodec.encode(message);
        synchronized (writeLock) {
            out.write(frame);
            out.flush();
        }
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
