package io.duo.sim.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 报文 ⇄ 帧编解码门面：DuoMessage ↔ JSON payload ↔ 带 header 的完整帧。
 * 线程安全（ObjectMapper 配置后不可变使用）。
 */
public final class DuoCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .build();

    private DuoCodec() {
    }

    /** 编码完整帧（含 header）。 */
    public static byte[] encode(DuoMessage message) {
        try {
            byte[] payload = MAPPER.writeValueAsBytes(message);
            return FrameCodec.encodeFrame(payload);
        } catch (IOException e) {
            throw new UncheckedIOException("encode failed: " + message, e);
        }
    }

    /** 解析完整帧（含 header）为报文。 */
    public static DuoMessage decode(byte[] frameBytes) {
        FrameCodec.Frame frame = FrameCodec.decodeFrame(frameBytes);
        try {
            return MAPPER.readValue(frame.payload(), DuoMessage.class);
        } catch (IOException e) {
            throw new ProtocolViolationException("payload is not a valid DuoMessage: "
                    + e.getMessage());
        }
    }
}
