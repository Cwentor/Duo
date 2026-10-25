package io.duo.sim.kernel.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件投影（卡 7）：{@code {type, sourceId, timestamp, payload}} 的 REST 形态**唯一实现**
 * ——此前 ScenarioHost.eventsSince 与 RestControlServer.reportToJson 各手抄一份
 * （体检实证 4 处投影平行），新消费方（控制台/诊断/指标）一律经此，不再复制字段拼装。
 *
 * <p>纯 Map 构造，无 Jackson（kernel 零第三方依赖不破）。{@code timestamp} 以
 * {@code toString()} 落地（Instant ISO-8601，与既有 REST 契约一致）；{@code payload}
 * 为 null 时投 {@code Map.of()}。返回**新建可变 LinkedHashMap**，字段序
 * type→sourceId→timestamp→payload——调用方可先并序自有字段（如 /events 的 1-based
 * {@code seq}）再 putAll，键序与既有 REST 响应逐键一致。
 *
 * <p>边界（如实声明）：{@code events.jsonl} 书写端（EventRecorder.toJsonLine）的
 * 契约**不同**（sourceId/timestamp 判空为空串、payload 值级 null 替换——METRICS.md §4
 * 钉住的录制文件格式），不并入本投影；MetricsCollector 是按下标的计数器、非 Map 投影。
 */
public final class EventProjections {

    private EventProjections() {
    }

    /** 事件的 REST 形态投影（新建 Map；字段序 type→sourceId→timestamp→payload）。 */
    public static Map<String, Object> toMap(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", e.type());
        m.put("sourceId", e.sourceId());
        m.put("timestamp", e.timestamp().toString());
        m.put("payload", e.payload() == null ? Map.of() : e.payload());
        return m;
    }
}
