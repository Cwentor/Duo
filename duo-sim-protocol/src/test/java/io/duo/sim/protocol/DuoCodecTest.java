package io.duo.sim.protocol;

import io.duo.sim.protocol.message.HeartbeatReport;
import io.duo.sim.protocol.message.RegisterRequest;
import io.duo.sim.protocol.message.SlotReport;
import io.duo.sim.protocol.message.TaskCancel;
import io.duo.sim.protocol.message.TaskDispatch;
import io.duo.sim.protocol.message.TaskStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DuoCodecTest {

    @Test
    void roundTripAllMessageTypes() {
        assertRoundTrip(new RegisterRequest("workers-1", 4, 8));
        assertRoundTrip(new HeartbeatReport("workers-1", 42L));
        assertRoundTrip(new SlotReport("workers-1", 3, 4));
        assertRoundTrip(new TaskDispatch("t-100", "spark-etl", 2, 1, 2));
        assertRoundTrip(new TaskStatus("t-100", "workers-1", TaskStatus.FAILED, "OOM"));
        assertRoundTrip(new TaskCancel("t-100", "upstream failed"));
    }

    private void assertRoundTrip(DuoMessage original) {
        byte[] frame = DuoCodec.encode(original);
        assertEquals(original, DuoCodec.decode(frame));
    }

    @Test
    void frameHeaderLayout() {
        byte[] frame = DuoCodec.encode(new RegisterRequest("w", 1, 1));
        assertEquals(FrameCodec.MAGIC, ((frame[0] & 0xFF) << 24) | ((frame[1] & 0xFF) << 16)
                | ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF));
        assertEquals(FrameCodec.VERSION, frame[4]);
        assertEquals(frame.length - FrameCodec.HEADER_LENGTH,
                ((frame[5] & 0xFF) << 24) | ((frame[6] & 0xFF) << 16)
                        | ((frame[7] & 0xFF) << 8) | (frame[8] & 0xFF));
    }

    @Test
    void rejectBadMagic() {
        byte[] frame = DuoCodec.encode(new RegisterRequest("w", 1, 1));
        frame[0] = 'X';
        assertThrows(ProtocolViolationException.class, () -> FrameCodec.decodeFrame(frame));
    }

    @Test
    void rejectBadVersion() {
        byte[] frame = DuoCodec.encode(new RegisterRequest("w", 1, 1));
        frame[4] = 9;
        assertThrows(ProtocolViolationException.class, () -> FrameCodec.decodeFrame(frame));
    }

    @Test
    void rejectBadLength() {
        byte[] frame = DuoCodec.encode(new RegisterRequest("w", 1, 1));
        frame[5] = (byte) 0xFF; // 巨大长度
        assertThrows(ProtocolViolationException.class, () -> FrameCodec.decodeFrame(frame));
    }

    @Test
    void rejectTruncatedFrame() {
        byte[] frame = DuoCodec.encode(new RegisterRequest("w", 1, 1));
        byte[] truncated = new byte[frame.length - 3];
        System.arraycopy(frame, 0, truncated, 0, truncated.length);
        assertThrows(ProtocolViolationException.class, () -> FrameCodec.decodeFrame(truncated));
    }

    @Test
    void rejectUnknownPayloadType() {
        byte[] frame = DuoCodec.encode(new RegisterRequest("w", 1, 1));
        // 把报文 type 篡改为未注册类型
        String json = new String(frame, FrameCodec.HEADER_LENGTH,
                frame.length - FrameCodec.HEADER_LENGTH).replace("\"register\"", "\"bogus\"");
        byte[] payload = json.getBytes();
        byte[] bogus = new byte[FrameCodec.HEADER_LENGTH + payload.length];
        System.arraycopy(frame, 0, bogus, 0, FrameCodec.HEADER_LENGTH);
        System.arraycopy(payload, 0, bogus, FrameCodec.HEADER_LENGTH, payload.length);
        assertThrows(ProtocolViolationException.class, () -> DuoCodec.decode(bogus));
    }

    @Test
    void framesAreSelfContained() {
        // 相邻两帧互不影响（帧定界正确性）
        byte[] f1 = DuoCodec.encode(new RegisterRequest("w-1", 1, 2));
        byte[] f2 = DuoCodec.encode(new SlotReport("w-2", 0, 4));
        byte[] joined = new byte[f1.length + f2.length];
        System.arraycopy(f1, 0, joined, 0, f1.length);
        System.arraycopy(f2, 0, joined, f1.length, f2.length);
        assertEquals(new RegisterRequest("w-1", 1, 2),
                DuoCodec.decode(subarray(joined, 0, f1.length)));
        assertEquals(new SlotReport("w-2", 0, 4),
                DuoCodec.decode(subarray(joined, f1.length, f2.length)));
    }

    private static byte[] subarray(byte[] src, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }
}
