package io.duo.sim.scenario;

import io.duo.sim.kernel.api.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
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

    /** 安全审计 2026-09-20 H-4：缓冲有界，超出部分显式计数（不静默 OOM）。 */
    @Test
    void bufferIsBoundedAndOverflowIsCounted(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("bounded.jsonl");
        var recorder = EventRecorder.to(file);
        for (int i = 0; i < EventRecorder.MAX_BUFFERED_EVENTS + 5; i++) {
            recorder.onEvent(Event.sim("sim.flood", "sc", Map.of("i", i)));
        }
        assertEquals(5, recorder.droppedEvents(), "溢出条数必须精确可读");
        recorder.flush();
        // 落盘内容恰好是上限条数（不是 0、不是全部）：丢的是尾部，前面的审查材料保住
        assertEquals(EventRecorder.MAX_BUFFERED_EVENTS, Files.readAllLines(file).size());
    }

    /**
     * 安全审计 2026-09-20 **复核**：事件行的键序必须稳定。
     *
     * <p>写侧原先直接序列化 {@code Map.of(...)}，键序随 JVM 每次启动的 SALT 变化——
     * "同一事件流 ⇒ 同一字节"因此不成立，逐字节 diff 两份录制会整片假变更。
     * 修法是**固定插入序**（{@code LinkedHashMap}，type→sourceId→timestamp→payload）：
     * Jackson 的 {@code SORT_PROPERTIES_ALPHABETICALLY} 只管 POJO 属性、**不排 Map 键**，
     * 故确定性只能来自写入侧的顺序本身（本用例第二段就是这条的回归）。
     */
    @Test
    void jsonLineKeyOrderIsStableAcrossRuns(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a.jsonl");
        Path b = tmp.resolve("b.jsonl");
        // 同一个 Event（同一时间戳）写两次：本用例只验"序列化"这一环的确定性，
        // 故时间戳必须固定——真实场景里时间戳本就不同，那不是格式问题。
        Event e = Event.sim("sim.scenario-started", "sc", Map.of("k", "v"));
        for (Path p : List.of(a, b)) {
            var recorder = EventRecorder.to(p);
            recorder.onEvent(e);
            recorder.flush();
        }
        assertEquals(Files.readAllLines(a), Files.readAllLines(b),
                "同一条事件两次落盘必须逐字节相同（键序不得随 SALT 抖动）");
        assertEquals("{\"type\":\"sim.scenario-started\",\"sourceId\":\"sc\","
                        + "\"timestamp\":\"" + e.timestamp() + "\",\"payload\":{\"k\":\"v\"}}",
                Files.readAllLines(a).get(0),
                "键序为契约固定序：type/sourceId/timestamp/payload");
    }

    /**
     * 安全审计 2026-09-20 **复核**：行信封必须与「只保留 type/sourceId/timestamp/payload 的
     * 接收端」兼容——这是录制回读与 {@code ScenarioHost.eventsSince} 共用的契约。
     *
     * <p>若行变成 {@code {"event":{...}}} 这类嵌套信封，Jackson 默认
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} 会让接收端**静默丢掉整条事件**
     * （读出空对象、丢掉 payload），比"读不出来"更糟。
     */
    @Test
    void jsonLineIsReadableByTheFixedFieldContract(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("contract.jsonl");
        var recorder = EventRecorder.to(file);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("password", "p");
        payload.put("nullable", null);
        recorder.onEvent(Event.sim("sim.store-started", "store", payload));
        recorder.flush();

        record Row(String type, String sourceId, String timestamp,
                   Map<String, Object> payload) {
        }
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Row row = mapper.readValue(Files.readAllLines(file).get(0), Row.class);
        assertEquals("sim.store-started", row.type());
        assertEquals("store", row.sourceId());
        assertEquals("p", row.payload().get("password"),
                "payload 内容必须原样可读（不得被信封吞掉）");
        assertEquals("", row.payload().get("nullable"),
                "payload 值恒非 null：null 以空串落盘（读取端不必再判空）");
    }
}
