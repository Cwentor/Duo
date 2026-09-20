package io.duo.sim.control.metrics;

import io.duo.sim.kernel.api.Event;
import io.duo.sim.control.ScenarioHost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus 文本格式指标导出（M8 交付物 1，决策 D4＝零依赖手写，不引 Prometheus 客户端）。
 *
 * <p><b>为什么这样取数（而不是在组件里埋点）</b>：内核已有一条**只追加**的事件流
 * （{@code ScenarioEngine.events()}），里面含心跳、注入、断言等全部一等事实。指标层
 * 因此只是「**从事件流和组件状态里读**」的薄层——遵循 §10「控制面零内核改动」，
 * 指标口径变更不需要改任何内核/组件代码。代价是计数在**首次抓取时**才开始累积，
 * 这一点必须写在文档里而不是假装是累计量（§12）。
 *
 * <p><b>单调性设计</b>：所有 `_total` 计数器按「已消费事件下标游标」单向推进，
 * 只在 {@link #collect()} 时累加。同一份事件快照被重复抓取不会重复计数；
 * 但**若中途无人抓取，期间的事件会在下次抓取时一次性计入**——因此计数对
 * 「采集间隔之外的瞬时速率」不敏感，只保证不丢、不重、单调（这是 counter 的正确语义）。
 */
public final class MetricsCollector {

    /**
     * 按类型计数器的**键基数上限**（安全审计 2026-09-20 H-4 / §5 caveat 5）。
     *
     * <p>为什么要有界：{@code type} 是事件流里的原始字符串，{@code sim.*} 受
     * {@code Event.SIM_EVENT_TYPES} 约束，但 {@code sut.*} 由**被测对象**自由产生
     * （hook、SUT 进程、自定义组件都能 emit 任意类型）。一个不断产生新类型的 SUT
     * 就能让 {@code countersByType} 无限增长——控制面被观测对象拖垮。
     * 超过上限后新类型统一并入 {@value #OVERFLOW_KEY}（总量仍准确，只是不再按名区分）。
     */
    public static final int MAX_EVENT_TYPES = 256;

    /** 溢出桶（不再逐个区分的新类型）。 */
    public static final String OVERFLOW_KEY = "__other__";

    /** 单键最大长度（同样是为了不让一个 SUT 用超长类型名撑爆输出）。 */
    private static final int MAX_TYPE_LENGTH = 200;

    private final ScenarioHost host;
    private final AtomicLong scrapes = new AtomicLong();

    /** 已消费到的事件下标（0-based 游标，单调不减）。 */
    private int cursor;
    /** 会话内按类型累计（跨 start/stop 保留，对齐 Prometheus counter 的进程生命周期语义）。 */
    private final Map<String, Long> countersByType = new LinkedHashMap<>();
    /** 因超出 {@link #MAX_EVENT_TYPES} 而被并入 {@link #OVERFLOW_KEY} 的事件条数。 */
    private long overflowedByType;
    private long injectionTotal;
    private long injectionFailedTotal;
    private long heartbeatTotal;
    private long sutFactTotal;
    private long simFactTotal;

    public MetricsCollector(ScenarioHost host) {
        this.host = host;
    }

    /** 渲染 Prometheus 文本格式（`text/plain; version=0.0.4`）。 */
    public synchronized String scrape() {
        consumeNewEvents();
        Map<String, Object> status = host.status();
        Map<String, Object> assertions = host.assertions();
        List<Map<String, Object>> topology = host.topology();

        StringBuilder sb = new StringBuilder(4096);
        metric(sb, "duo_up", "gauge",
                "控制面可达性（进程存活＝1）", 1, List.of());
        metric(sb, "duo_scenario_running", "gauge",
                "场景是否处于 RUNNING", host.isRunning() ? 1 : 0, List.of());
        metric(sb, "duo_events_total", "counter",
                "内核事件流中的事件总数（快照长度，非单调增量）",
                num(status.get("events")), List.of());
        metric(sb, "duo_scrapes_total", "counter",
                "本控制面被 /metrics 抓取的次数", scrapes.incrementAndGet(), List.of());

        // ---- 注入 ----
        metric(sb, "duo_injections_total", "counter",
                "成功下达的故障注入次数", injectionTotal, List.of());
        metric(sb, "duo_injections_failed_total", "counter",
                "下达失败的故障注入次数（含目标不存在/动作未声明）", injectionFailedTotal, List.of());
        metric(sb, "duo_injection_failures_reported", "gauge",
                "结果快照中记录的注入失败条数（去重后）",
                num(status.get("injectionFailures")), List.of());

        // ---- 事件分类 ----
        metric(sb, "duo_heartbeat_events_total", "counter",
                "心跳类事件累计（duo.heartbeat / sut.heartbeat*）", heartbeatTotal, List.of());
        metric(sb, "duo_sim_events_total", "counter",
                "`sim.` 一级事实事件累计", simFactTotal, List.of());
        metric(sb, "duo_sut_events_total", "counter",
                "`sut.` 被测对象事实事件累计", sutFactTotal, List.of());
        if (!countersByType.isEmpty()) {
            sb.append("# HELP duo_events_by_type_total 按事件类型累计的条数（仅列出已出现的类型；")
                    .append("类型基数上限 ").append(MAX_EVENT_TYPES)
                    .append("，超出后并入 ").append(OVERFLOW_KEY).append("）\n");
            sb.append("# TYPE duo_events_by_type_total counter\n");
            List<String> types = new ArrayList<>(countersByType.keySet());
            types.sort(String::compareTo);
            for (String t : types) {
                sb.append("duo_events_by_type_total{type=\"").append(escape(t)).append("\"} ")
                        .append(countersByType.get(t)).append('\n');
            }
        }

        // ---- 断言 ----
        boolean passed = Boolean.TRUE.equals(assertions.get("passed"));
        metric(sb, "duo_assertions_passed", "gauge",
                "断言整体是否通过（未评估或未通过＝0）", passed ? 1 : 0, List.of());
        List<Map<String, Object>> assertionList = asList(assertions.get("assertions"));
        metric(sb, "duo_assertions_total", "gauge",
                "已评估的断言条数", assertionList.size(), List.of());
        long failedAssertions = assertionList.stream()
                .filter(a -> !Boolean.TRUE.equals(a.get("passed"))).count();
        metric(sb, "duo_assertions_failed", "gauge",
                "未通过的断言条数", failedAssertions, List.of());

        // ---- 组件健康 / 连接数 ----
        long hosted = 0;
        long unhealthy = 0;
        long endpoints = 0;
        long instances = 0;
        for (Map<String, Object> n : topology) {
            if (Boolean.TRUE.equals(n.get("hosted"))) {
                hosted++;
                if (Boolean.FALSE.equals(n.get("healthy"))) {
                    unhealthy++;
                }
                endpoints += asList(n.get("endpoints")).size();
            }
            instances += num(n.get("count"));
        }
        metric(sb, "duo_components_hosted", "gauge",
                "内核托管的组件节点数（不含 SUT）", hosted, List.of());
        metric(sb, "duo_components_unhealthy", "gauge",
                "健康检查未通过的组件数", unhealthy, List.of());
        metric(sb, "duo_component_instances", "gauge",
                "拓扑声明的逻辑实例总数（含 count>1 展开）", instances, List.of());
        metric(sb, "duo_endpoints_online", "gauge",
                "在线端点条数（Connection 类契约的对外地址）", endpoints, List.of());
        return sb.toString();
    }

    /** 消费事件流的新增部分（下标游标单调推进，不重复计数）。 */
    private void consumeNewEvents() {
        List<Event> events = host.eventsSnapshot();
        if (events.size() < cursor) {
            // 事件流被替换（新一轮 start）：重置游标，旧计数保留（counter 语义）
            cursor = 0;
        }
        for (int i = cursor; i < events.size(); i++) {
            Event e = events.get(i);
            String type = e.type() == null ? "" : e.type();
            countByType(type);
            if (type.equals("duo.heartbeat") || type.startsWith("sut.heartbeat")) {
                heartbeatTotal++;
            }
            if (type.startsWith(Event.SUT_PREFIX)) {
                sutFactTotal++;
            } else if (type.startsWith(Event.SIM_PREFIX) || type.startsWith("duo.")) {
                simFactTotal++;
            }
            if (type.equals("sim.fault-injected")) {
                injectionTotal++;
            } else if (type.equals("sim.fault-inject-failed")) {
                injectionFailedTotal++;
            }
        }
        cursor = events.size();
    }

    /**
     * 按类型累计，**键基数有界**（审计 H-4）：已知类型继续按名计数；新类型在未达上限前
     * 各自计数，达上限后并入 {@link #OVERFLOW_KEY}——总量恒等，只是不再逐名展开，
     * 这样既保住了 {@code duo_events_by_type_total} 的可用性，又堵住了 SUT 拖垮控制面的路。
     */
    private void countByType(String type) {
        String key = type.length() > MAX_TYPE_LENGTH ? type.substring(0, MAX_TYPE_LENGTH) : type;
        if (countersByType.containsKey(key)) {
            countersByType.merge(key, 1L, Long::sum);
            return;
        }
        if (countersByType.size() < MAX_EVENT_TYPES) {
            countersByType.put(key, 1L);
            return;
        }
        overflowedByType++;
        countersByType.merge(OVERFLOW_KEY, 1L, Long::sum);
    }

    /** 因类型基数上限而未逐名区分的累计条数（供摘要行与自检使用）。 */
    public synchronized long overflowedEventTypes() {
        return overflowedByType;
    }

    // ---- Prometheus 文本格式辅助 ----

    private static void metric(StringBuilder sb, String name, String type, String help,
                               long value, List<String> labels) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        sb.append(name);
        if (!labels.isEmpty()) {
            sb.append('{').append(String.join(",", labels)).append('}');
        }
        sb.append(' ').append(value).append('\n');
    }

    private static String escape(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    /** 供测试/CLI 复用的可读摘要（非 Prometheus 格式）。 */
    public synchronized Map<String, Object> summary() {
        consumeNewEvents();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventsTotal", countersByType.values().stream().mapToLong(Long::longValue).sum());
        m.put("injectionsTotal", injectionTotal);
        m.put("injectionsFailedTotal", injectionFailedTotal);
        m.put("heartbeatEventsTotal", heartbeatTotal);
        m.put("simEventsTotal", simFactTotal);
        m.put("sutEventsTotal", sutFactTotal);
        m.put("byType", new LinkedHashMap<>(countersByType));
        m.put("scrapes", scrapes.get());
        m.put("locale", Locale.ROOT.getLanguage());
        return m;
    }

    /** 便于 CLI/REST 打日志用的单行摘要。 */
    public synchronized String summaryLine() {
        Map<String, Object> s = summary();
        return "events=" + s.get("eventsTotal")
                + " injections=" + s.get("injectionsTotal")
                + "/failed=" + s.get("injectionsFailedTotal")
                + " heartbeat=" + s.get("heartbeatEventsTotal")
                + " sim=" + s.get("simEventsTotal")
                + " sut=" + s.get("sutEventsTotal");
    }

    /** 组件维度的健康明细（供诊断用；不参与 Prometheus 文本）。 */
    public List<Map<String, Object>> unhealthyComponents() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> n : host.topology()) {
            if (Boolean.FALSE.equals(n.get("healthy"))) {
                out.add(Map.of("id", String.valueOf(n.get("id")),
                        "contract", String.valueOf(n.get("contract"))));
            }
        }
        return out;
    }

    /** 暴露给测试：当前游标（证明单调推进）。 */
    public synchronized int cursor() {
        return cursor;
    }
}
