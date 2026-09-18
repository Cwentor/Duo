package io.duo.sim.kernel.sut;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6：external 就绪探针声明解析与单次探测语义（tcp/http）。
 * 声明位置＝节点 config 的 {@code ready.*}（DSL {@code launch.ready.*} 展平）。
 */
class ReadyProbeTest {

    @Test
    void specRequiresType() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ReadyProbe.spec(Map.of(), 8123));
        assertTrue(ex.getMessage().contains("ready probe"), ex.getMessage());
    }

    @Test
    void specRejectsUnknownType() {
        var ex = assertThrows(IllegalArgumentException.class, () -> ReadyProbe.spec(
                Map.of("ready.type", "udp", "ready.port", "8123"), 0));
        assertTrue(ex.getMessage().contains("unsupported ready.type"), ex.getMessage());
    }

    @Test
    void specFallsBackToExposePortAndDefaultTimeout() {
        var spec = ReadyProbe.spec(Map.of("ready.type", "TCP"), 8123);
        assertEquals("tcp", spec.type(), "type 大小写不敏感");
        assertEquals(8123, spec.port());
        assertEquals(ReadyProbe.DEFAULT_HOST, spec.host());
        assertEquals("/", spec.path());
        assertEquals(ReadyProbe.DEFAULT_TIMEOUT_MS, spec.timeoutMs(), "缺省与 in-process 同口径 60s");
        assertEquals("127.0.0.1:8123", spec.describe());
    }

    @Test
    void specParsesHostPathAndTimeout() {
        var spec = ReadyProbe.spec(Map.of("ready.type", "http", "ready.host", "10.0.0.7",
                "ready.port", "9090", "ready.path", "/health", "ready.timeout", "90s"), 0);
        assertEquals("http", spec.type());
        assertEquals("10.0.0.7", spec.host());
        assertEquals(9090, spec.port());
        assertEquals("/health", spec.path());
        assertEquals(90_000, spec.timeoutMs());
        assertEquals("10.0.0.7:9090/health", spec.describe());
    }

    @Test
    void specRejectsMissingPort() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ReadyProbe.spec(Map.of("ready.type", "tcp"), 0));
        assertTrue(ex.getMessage().contains("needs a port"), ex.getMessage());
    }

    @Test
    void specRejectsBadTimeoutUnit() {
        assertThrows(IllegalArgumentException.class, () -> ReadyProbe.spec(
                Map.of("ready.type", "tcp", "ready.port", "8123", "ready.timeout", "30"), 0));
    }

    @Test
    void tcpProbeDetectsListeningAndClosedPorts() throws Exception {
        try (ServerSocket listening = new ServerSocket(0)) {
            int port = listening.getLocalPort();
            var open = new ReadyProbe.Spec("tcp", "127.0.0.1", port, "/", 1000);
            assertTrue(ReadyProbe.once(open), "监听中的端口＝就绪");
        }
        int closed = freePort();
        var shut = new ReadyProbe.Spec("tcp", "127.0.0.1", closed, "/", 1000);
        assertFalse(ReadyProbe.once(shut), "未监听端口＝未就绪（不抛异常，交由超时裁决）");
    }

    @Test
    void httpProbeIsFalseWhenNothingListens() throws Exception {
        var spec = new ReadyProbe.Spec("http", "127.0.0.1", freePort(), "/health", 1000);
        assertFalse(ReadyProbe.once(spec));
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
