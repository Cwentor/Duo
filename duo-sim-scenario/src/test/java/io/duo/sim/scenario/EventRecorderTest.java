package io.duo.sim.scenario;

import io.duo.sim.kernel.api.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T21 事件录制单测：落盘格式与回读等价。 */
class EventRecorderTest {

    @Test
    void writeAndReadBackRoundTrip(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("nested/events.jsonl");
        var recorder = EventRecorder.to(file);
        recorder.onEvent(Event.sim("sim.scenario-started", "sc", Map.of("k", "v")));
        recorder.onEvent(Event.sut("sut.task-retry", "sut",
                Map.of("taskId", "t1", "nextAttempt", 2)));
        recorder.flush();

        assertTrue(Files.exists(file), "recording file must be created (with parent dirs)");
        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"type\":\"sim.scenario-started\""));
        assertTrue(lines.get(1).contains("\"type\":\"sut.task-retry\""));

        var back = EventRecorder.readBack(file);
        assertEquals(2, back.size());
        assertEquals("sim.scenario-started", back.get(0).get("type"));
        assertEquals("sc", back.get(0).get("sourceId"));
        assertTrue(back.get(0).get("timestamp") instanceof String);
        assertEquals("t1", ((Map<?, ?>) back.get(1).get("payload")).get("taskId"));
    }

    @Test
    void closeFlushesIdempotently(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("events.jsonl");
        var recorder = EventRecorder.to(file);
        recorder.onEvent(Event.sim("sim.scenario-finished", "sc", Map.of()));
        recorder.close();
        recorder.close(); // 幂等：不重复写
        assertEquals(1, Files.readAllLines(file).size());
        // 关闭后的事件被忽略（不追加）
        recorder.onEvent(Event.sim("sim.late", "sc", Map.of()));
        recorder.flush();
        assertEquals(1, Files.readAllLines(file).size());
    }

    @Test
    void emptyRecordingProducesEmptyFile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("empty.jsonl");
        EventRecorder.to(file).flush();
        assertTrue(Files.exists(file));
        assertEquals(0, Files.readAllLines(file).size());
    }

    @Test
    void payloadPreservedForAssertionReview(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("audit.jsonl");
        var recorder = EventRecorder.to(file);
        recorder.onEvent(new Event("sim.fault-injected", "workers-3", Instant.parse(
                "2026-01-01T00:00:10Z"), Map.of("action", "crash")));
        recorder.flush();
        var back = EventRecorder.readBack(file);
        assertEquals("crash", ((Map<?, ?>) back.get(0).get("payload")).get("action"));
        assertEquals("workers-3", back.get(0).get("sourceId"));
        assertEquals("2026-01-01T00:00:10Z", back.get(0).get("timestamp"));
    }
}
