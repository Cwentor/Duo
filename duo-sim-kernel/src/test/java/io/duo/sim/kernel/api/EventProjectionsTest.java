package io.duo.sim.kernel.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link EventProjections#toMap} 直接单测（评审 P3，2026-10-30）：公共工具类此前零直接
 * 单测——null payload 落空 Map 的口径只写在 javadoc，REST 侧仅有 {@code contains("events:[")}
 * 浅断言。键序（type/sourceId/timestamp/payload）是 METRICS.md 记录的 events.jsonl 书写契约，
 * 一并钉死。
 */
class EventProjectionsTest {

    @Test
    void toMapCarriesTypeSourceTimestampAndPayloadInContractOrder() {
        var e = Event.sim("sim.worker-started", "workers", Map.of("count", 2));
        var m = EventProjections.toMap(e);
        assertEquals("sim.worker-started", m.get("type"));
        assertEquals("workers", m.get("sourceId"));
        assertNotNull(m.get("timestamp"));
        assertEquals(Map.of("count", 2), m.get("payload"));
        assertEquals(List.of("type", "sourceId", "timestamp", "payload"),
                new ArrayList<>(m.keySet()), "键序＝events.jsonl 书写契约（METRICS.md）");
    }

    @Test
    void nullPayloadFallsBackToEmptyMap() {
        var e = Event.sut("sut.dag-terminal", "sut", null);
        var m = EventProjections.toMap(e);
        assertEquals(Map.of(), m.get("payload"));
    }

    @Test
    void sutNamespaceKeepsItsSourceId() {
        var e = Event.sut("sut.task-dispatched", "sut", Map.of("taskId", "t-1"));
        var m = EventProjections.toMap(e);
        assertEquals("sut.task-dispatched", m.get("type"));
        assertEquals("sut", m.get("sourceId"));
    }
}
