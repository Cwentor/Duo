package io.duo.sim.protocol;

/**
 * Duo 线协议帧结构（设计文档 §3「Duo 线协议」、T2）。
 *
 * <p>帧布局：{@code [magic: 4B][version: 1B][payloadLength: 4B][payload: NB]}，
 * 大端序。payload 为 UTF-8 编码的 JSON 报文。
 */
public final class FrameCodec {

    /** 帧头魔数，固定 "DUO1"（0x44554F31），用于快速识别协议错连。 */
    public static final int MAGIC = 0x44554F31;

    /** 当前协议版本。 */
    public static final byte VERSION = 1;

    /** 帧头长度：magic(4) + version(1) + payloadLength(4)。 */
    public static final int HEADER_LENGTH = 9;

    /** 单帧 payload 上限（1 MiB），防止畸形帧耗尽内存。 */
    public static final int MAX_PAYLOAD_LENGTH = 1 << 20;

    private FrameCodec() {
    }

    /**
     * 将报文 payload 编码为一帧。返回的数组长度恒为 {@code HEADER_LENGTH + payload.length}。
     *
     * @throws IllegalArgumentException payload 为空或超长
     */
    public static byte[] encodeFrame(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        if (payload.length > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("payload too large: " + payload.length);
        }
        byte[] frame = new byte[HEADER_LENGTH + payload.length];
        frame[0] = (byte) (MAGIC >>> 24);
        frame[1] = (byte) (MAGIC >>> 16);
        frame[2] = (byte) (MAGIC >>> 8);
        frame[3] = (byte) MAGIC;
        frame[4] = VERSION;
        int len = payload.length;
        frame[5] = (byte) (len >>> 24);
        frame[6] = (byte) (len >>> 16);
        frame[7] = (byte) (len >>> 8);
        frame[8] = (byte) len;
        System.arraycopy(payload, 0, frame, HEADER_LENGTH, payload.length);
        return frame;
    }

    /** 已解析的一帧：版本与 payload。 */
    public record Frame(byte version, byte[] payload) {
    }

    /**
     * 解析一帧（调用方保证 {@code bytes} 恰好是一个完整帧）。
     *
     * @throws ProtocolViolationException 魔数/版本/长度非法
     */
    public static Frame decodeFrame(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_LENGTH) {
            throw new ProtocolViolationException("frame shorter than header: "
                    + (bytes == null ? 0 : bytes.length));
        }
        int magic = ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
        if (magic != MAGIC) {
            throw new ProtocolViolationException("bad magic: 0x" + Integer.toHexString(magic));
        }
        byte version = bytes[4];
        if (version != VERSION) {
            throw new ProtocolViolationException("unsupported version: " + version);
        }
        int len = ((bytes[5] & 0xFF) << 24) | ((bytes[6] & 0xFF) << 16)
                | ((bytes[7] & 0xFF) << 8) | (bytes[8] & 0xFF);
        if (len <= 0 || len > MAX_PAYLOAD_LENGTH) {
            throw new ProtocolViolationException("bad payload length: " + len);
        }
        if (bytes.length != HEADER_LENGTH + len) {
            throw new ProtocolViolationException("frame length mismatch: expected "
                    + (HEADER_LENGTH + len) + ", got " + bytes.length);
        }
        byte[] payload = new byte[len];
        System.arraycopy(bytes, HEADER_LENGTH, payload, 0, len);
        return new Frame(version, payload);
    }
}
