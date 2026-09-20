package io.duo.sim.scenario;

import io.duo.sim.kernel.api.Event;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件流录制（M1 T21）：订阅事件总线 → JSON Lines 落盘
 * （{@code build/scenarios/<name>/events.jsonl}）→ 场景结束 flush → 回读 API。
 *
 * <p>用途（§11）：事后回放审查与回归比对。**真实时钟下不承诺确定性逐字节重放**——
 * 录制是审查材料，不是可复现重放脚本。
 *
 * <p><b>缓冲有界</b>（安全审计 2026-09-20 H-4）：{@code flush()} 在**场景结束时**才调用，
 * 期间缓冲只增不减；一个刷日志的 SUT 或自定义 hook 就能把控制面堆到 OOM。故设上限
 * {@link #MAX_BUFFERED_EVENTS}，超出后停止累积并计入 {@link #droppedEvents()}——
 * 用"显式丢了多少"替代"静默吃掉内存"（§12：跳过必须可见）。
 */
public final class EventRecorder implements AutoCloseable {

    /**
     * 内存缓冲上限。取值依据：既有最大验收场景（万级任务）事件量在 10^4 量级，
     * 5×10^5 留足一个数量级余量，同时把单场景缓冲内存封在 ~10^8 字节以内。
     */
    public static final int MAX_BUFFERED_EVENTS = 500_000;

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            com.fasterxml.jackson.databind.json.JsonMapper.builder()
                    .enable(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                    .build();

    private final Path outputFile;
    private final List<Event> buffered = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong dropped =
            new java.util.concurrent.atomic.AtomicLong();
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = MAPPER;
    private volatile boolean closed;

    private EventRecorder(Path outputFile) {
        this.outputFile = outputFile;
    }

    /** 创建录制器（目录自动创建）。 */
    public static EventRecorder to(Path outputFile) {
        return new EventRecorder(outputFile);
    }

    /** 是否因达到缓冲上限而丢弃过事件（诊断/告警用）。 */
    public long droppedEvents() {
        return dropped.get();
    }

    /** 事件总线订阅入口。 */
    public void onEvent(Event event) {
        if (closed) {
            return;
        }
        synchronized (buffered) {
            if (buffered.size() >= MAX_BUFFERED_EVENTS) {
                dropped.incrementAndGet();
                return;
            }
            buffered.add(event);
        }
    }

    /** flush 到磁盘（场景结束时调用；可多次）。 */
    public void flush() {
        List<Event> snapshot;
        synchronized (buffered) {
            snapshot = List.copyOf(buffered);
        }
        writeSnapshot(snapshot);
    }

    /**
     * 带世代的 flush（安全审计 2026-09-20 复核 H-4）。
     *
     * <p>顶替场景时控制面在后台收摊旧引擎，而旧引擎与**新**场景写同一个
     * {@code build/scenarios/<name>/events.jsonl}（同名场景重启）。若旧引擎的 flush 来得比
     * 新的晚，就会把新场景的录制**整份覆盖**成旧事件——静默的数据损坏，比丢事件更糟。
     * 故交付方必须携带启动时的世代号：世代不再是最新就放弃写盘，并把丢弃显式说出来。
     *
     * @param epoch 启动本引擎时的世代号（见 {@code ScenarioHost} 的活动世代）
     * @return true ＝ 已落盘；false ＝ 已过期，写盘被放弃（调用方须告警）
     */
    public boolean flushIfCurrent(java.util.function.LongSupplier currentEpoch, long epoch) {
        List<Event> snapshot;
        synchronized (buffered) {
            snapshot = List.copyOf(buffered);
        }
        if (currentEpoch.getAsLong() != epoch) {
            return false; // 已被更新的场景顶替：这份录制是历史，不能覆盖现任
        }
        writeSnapshot(snapshot);
        return true;
    }

    private void writeSnapshot(List<Event> snapshot) {
        try {
            if (outputFile.getParent() != null) {
                Files.createDirectories(outputFile.getParent());
            }
            try (BufferedWriter w = Files.newBufferedWriter(outputFile,
                    StandardCharsets.UTF_8)) {
                for (Event e : snapshot) {
                    w.write(toJsonLine(e));
                    w.newLine();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write event recording: " + outputFile, e);
        }
    }

    /**
     * 一行 JSON。字段固定为 {@code {type, sourceId, timestamp, payload}}——这是
     * {@code events.jsonl} 的**对外契约**（{@code docs/METRICS.md} §4、审计 INFO-1/DSL §5）。
     *
     * <p><b>键序固定</b>（审计 2026-09-20 复核）：写侧若用 {@code Map.of(...)} 直接序列化，
     * 键序随 JVM 每次启动的 SALT 变化，逐字节 diff 两个场景/两次运行会整片假变更；
     * 而契约本身是稳定字段集。故用 {@link LinkedHashMap} 按
     * {@code type → sourceId → timestamp → payload} 固定插入序，使"同事件流 ⇒ 同字节"成立。
     * （注意 Jackson 的 {@code SORT_PROPERTIES_ALPHABETICALLY} 只管 POJO 属性、不排 Map 键，
     * 确定性来自这里的写入顺序，而非 {@link #MAPPER} 的开关。）
     *
     * <p><b>归并而非嵌套</b>（同上复核）：{@code RecordedEvent} 这类只保留
     * {@code type/sourceId/timestamp/payload} 的接收端，对多字段信封会**静默丢弃整条事件**
     * （Jackson 默认 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}）；把四个字段平铺在一层，
     * 两种接收端都能读，且与 {@code ScenarioHost.eventsSince} 的 REST 形态同构。
     */
    private static String toJsonLine(Event e) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", e.type());
        row.put("sourceId", e.sourceId() == null ? "" : e.sourceId());
        row.put("timestamp", e.timestamp() == null ? "" : e.timestamp().toString());
        row.put("payload", payloadOrEmpty(e));
        return MAPPER.writeValueAsString(row);
    }

    /** payload 中如出现 {@code null} 值，替换为空串——书写端契约是"值恒非 null"。 */
    private static Map<String, Object> payloadOrEmpty(Event e) {
        Map<String, Object> p = e.payload();
        if (p == null || p.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>(p.size());
        p.forEach((k, v) -> out.put(k, v == null ? "" : v));
        return out;
    }

    /** 回读（审查/比对用）。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> readBack(Path file) {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    out.add(MAPPER.readValue(line, Map.class));
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read event recording: " + file, e);
        }
    }

    public Path outputFile() {
        return outputFile;
    }

    @Override
    public void close() {
        if (!closed) {
            flush();
            closed = true;
        }
    }
}
