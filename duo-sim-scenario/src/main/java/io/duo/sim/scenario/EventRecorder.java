package io.duo.sim.scenario;

import io.duo.sim.kernel.api.Event;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    private final Path outputFile;
    private final List<Event> buffered = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong dropped =
            new java.util.concurrent.atomic.AtomicLong();
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            com.fasterxml.jackson.databind.json.JsonMapper.builder().build();
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

    private String toJsonLine(Event e) throws IOException {
        return mapper.writeValueAsString(Map.of(
                "type", e.type(),
                "sourceId", e.sourceId() == null ? "" : e.sourceId(),
                "timestamp", e.timestamp().toString(),
                "payload", e.payload() == null ? Map.of() : e.payload()));
    }

    /** 回读（审查/比对用）。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> readBack(Path file) {
        try {
            var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder().build();
            List<Map<String, Object>> out = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    out.add(mapper.readValue(line, Map.class));
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
