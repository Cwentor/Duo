package io.duo.sim.control;

import io.duo.sim.kernel.api.Event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一次故障注入的**完整因果链**重建（M8 交付物 3：诊断能力）。
 *
 * <p>ROADMAP 的验收原文是「单命令导出一次注入的完整因果链（注入 → 组件反应 → SUT 事实 → 断言）」。
 * 这里把它落成四段**可判定的**结构，而不是"打印一大堆事件让人自己看"：
 *
 * <ol>
 *   <li><b>注入</b>：{@code sim.fault-injected} / {@code sim.fault-inject-failed}；</li>
 *   <li><b>组件反应</b>：本次注入窗口内、目标组件的 {@code sim.*} 事实
 *       （如 {@code sim.worker-instance-crashed}、{@code sim.registry-flap-cleared}）；</li>
 *   <li><b>SUT 事实</b>：窗口内 {@code sut.*} 事件，按类型归并计数
 *       （心跳一次几十条，逐条铺开等于没输出——见下方"为什么归并"）；</li>
 *   <li><b>断言</b>：场景结果快照里的断言明细。</li>
 * </ol>
 *
 * <p><b>为什么 SUT 事实要按类型归并</b>：一个 60s 场景能产出上千条 {@code sut.heartbeat}，
 * 逐条打印的"完整因果链"实际上不可读（也无法被测试断言）。归并后仍然是**完整**的：
 * 每个类型都给出条数与首末时间，信息量不减，可读性大增。
 *
 * <p><b>为什么窗口在"下一次注入 / 场景结束"处封闭</b>：注入的因果链只在场景结束（或下一次
 * 注入开始）时才是**完整**的——早一秒读都会漏掉后续反应。因此窗口终点取
 * {@code sim.scenario-finished} / {@code sim.sut-exited} / 下一次注入，三者先到者为准。
 *
 * <p><b>断链必须显式报出</b>（§12 不静默）：注入之后没有任何 SUT 事实 ⇒
 * 「已下达但对被测对象无反应」，这是最需要人看见的情形，报告以 {@code MISSING}/
 * {@code gaps} 标出并让 CLI 退出码非 0。
 */
public final class FaultDiagnostics {

    private FaultDiagnostics() {
    }

    /** SUT 事实的类型化归并（类型 → 条数/首末时间）。 */
    public record FactKind(String type, int count, String firstAt, String lastAt) {
    }

    /** 一次注入的因果链。 */
    public record Chain(int injectedIndex, String target, String action, String failedReason,
                        boolean cleared, List<Event> componentReactions, List<FactKind> sutFacts,
                        int sutFactTotal, int windowEnd) {

        /** 链是否完整：注入成功 + 至少一条 SUT 事实。 */
        public boolean complete() {
            return injectedIndex >= 0 && failedReason == null && sutFactTotal > 0;
        }
    }

    /** 诊断报告（注入链 + 断言尾）。 */
    public record Report(List<Chain> chains, List<Map<String, Object>> assertions, int eventsTotal,
                         List<String> gaps) {

        public boolean complete() {
            return !chains.isEmpty() && gaps.isEmpty();
        }

        /** 渲染成人类可读文本（CLI 直接打印；分段对应 §11 观测通道）。 */
        public String render(String source) {
            StringBuilder sb = new StringBuilder();
            sb.append("来源: ").append(source).append("  事件总数: ").append(eventsTotal)
                    .append("  注入次数: ").append(chains.size()).append('\n');
            for (int i = 0; i < chains.size(); i++) {
                Chain c = chains.get(i);
                sb.append("\n[").append(i + 1).append("] 注入 ").append(c.action())
                        .append(" → ").append(c.target())
                        .append("  (事件 #").append(c.injectedIndex()).append(")\n");
                if (c.failedReason() != null) {
                    sb.append("    结果: REJECTED  reason=").append(c.failedReason()).append('\n');
                } else {
                    sb.append("    结果: 已下达").append(c.cleared() ? "（duration 到期已清除）" : "")
                            .append("  窗口至事件 #").append(c.windowEnd()).append('\n');
                }
                sb.append("    组件反应 (").append(c.componentReactions().size()).append("): ")
                        .append(c.componentReactions().isEmpty()
                                ? "MISSING——注入后目标组件无任何 sim.* 事实\n"
                                : renderFacts(c.componentReactions())).append('\n');
                sb.append("    SUT 事实 (").append(c.sutFactTotal()).append(" 条，")
                        .append(c.sutFacts().size()).append(" 类): ")
                        .append(c.sutFacts().isEmpty()
                                ? "MISSING——被测对象对该注入无任何反应\n"
                                : renderKinds(c.sutFacts())).append('\n');
            }
            if (assertions.isEmpty()) {
                sb.append("\n断言: 未评估（场景未结束或未停止）\n");
            } else {
                long failed = assertions.stream()
                        .filter(a -> !Boolean.TRUE.equals(a.get("passed"))).count();
                sb.append("\n断言: ").append(assertions.size()).append(" 条，失败 ")
                        .append(failed).append(" 条\n");
                for (Map<String, Object> a : assertions) {
                    sb.append("    ").append(Boolean.TRUE.equals(a.get("passed")) ? "PASS" : "FAIL")
                            .append(' ').append(a.get("name")).append(": ")
                            .append(a.get("detail")).append('\n');
                }
            }
            if (!gaps.isEmpty()) {
                sb.append("\n断链（需处理）:\n");
                for (String g : gaps) {
                    sb.append("    - ").append(g).append('\n');
                }
            }
            return sb.toString();
        }

        private static String renderFacts(List<Event> events) {
            StringBuilder sb = new StringBuilder();
            for (Event e : events) {
                sb.append(e.type()).append('@').append(e.sourceId()).append("  ");
            }
            return sb.toString().stripTrailing();
        }

        private static String renderKinds(List<FactKind> kinds) {
            StringBuilder sb = new StringBuilder();
            for (FactKind k : kinds) {
                sb.append(k.type()).append('×').append(k.count())
                        .append(" [").append(k.firstAt()).append(" → ").append(k.lastAt())
                        .append("]  ");
            }
            return sb.toString().stripTrailing();
        }
    }

    /**
     * 从事件流重建因果链。事件可来自内核直读（同进程，{@link Event}）或 REST
     * {@code /events}（已转 Map），两种形态统一在 {@link Event} 上分析。
     *
     * <p>REST 路径拿不到内核结果快照，因此断言段由调用方另行传入（{@code null} = 未评估）。
     */
    public static Report analyze(List<Map<String, Object>> rawEvents,
                                 List<Map<String, Object>> assertionDetails) {
        List<Event> events = new ArrayList<>(rawEvents.size());
        for (Map<String, Object> m : rawEvents) {
            events.add(toEvent(m));
        }
        return analyzeChains(events, assertionDetails);
    }

    /** REST 事件 + 无断言明细（断言段显示"未评估"）。 */
    public static Report analyze(List<Map<String, Object>> rawEvents) {
        return analyze(rawEvents, null);
    }

    /** 内核 {@link Event} 直读版本（同进程：事件与断言明细都来自宿主）。 */
    public static Report analyzeEvents(List<Event> events,
                                       List<Map<String, Object>> assertionDetails) {
        return analyzeChains(events, assertionDetails);
    }

    private static Report analyzeChains(List<Event> events,
                                        List<Map<String, Object>> assertionDetails) {
        List<Chain> chains = new ArrayList<>();
        List<String> gaps = new ArrayList<>();

        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            String type = e.type() == null ? "" : e.type();
            if (type.equals("sim.fault-injected")) {
                String target = e.sourceId();
                String action = String.valueOf(payload(e).getOrDefault("action", "?"));
                List<Event> reactions = new ArrayList<>();
                Map<String, int[]> counts = new LinkedHashMap<>();
                Map<String, String[]> times = new LinkedHashMap<>();
                boolean cleared = false;
                boolean finished = false;
                int end = events.size();
                for (int j = i + 1; j < events.size(); j++) {
                    Event f = events.get(j);
                    String ft = f.type() == null ? "" : f.type();
                    if (ft.equals("sim.fault-injected") || ft.equals("sim.fault-inject-failed")) {
                        end = j; // 下一次注入：本次窗口结束
                        break;
                    }
                    if (ft.equals("sim.scenario-finished") || ft.equals("sim.sut-exited")
                            || ft.equals("sim.sut-crashed")) {
                        finished = true;
                    }
                    if (ft.startsWith(Event.SUT_PREFIX)) {
                        counts.computeIfAbsent(ft, k -> new int[1])[0]++;
                        String at = String.valueOf(f.timestamp());
                        times.computeIfAbsent(ft, k -> new String[]{at, at})[1] = at;
                        if (finished) {
                            end = j;
                            break;
                        }
                    } else if (ft.startsWith(Event.SIM_PREFIX)) {
                        if (ft.endsWith("-cleared")) {
                            cleared = true;
                        }
                        if (ft.equals("sim.scenario-finished") || ft.equals("sim.sut-exited")
                                || ft.equals("sim.sut-crashed")) {
                            end = j;
                            break;
                        }
                        reactions.add(f);
                    }
                }
                List<FactKind> kinds = new ArrayList<>();
                int total = 0;
                for (Map.Entry<String, int[]> en : counts.entrySet()) {
                    String[] t = times.get(en.getKey());
                    kinds.add(new FactKind(en.getKey(), en.getValue()[0], t[0], t[1]));
                    total += en.getValue()[0];
                }
                kinds.sort((a, b) -> Integer.compare(b.count(), a.count()));
                Chain c = new Chain(i, target, action, null, cleared, reactions, kinds, total, end);
                chains.add(c);
                if (total == 0) {
                    gaps.add("注入 #" + i + "(" + action + " → " + target
                            + ") 之后没有任何 sut.* 事实：注入已下达但被测对象无反应");
                }
            } else if (type.equals("sim.fault-inject-failed")) {
                String target = e.sourceId();
                String action = String.valueOf(payload(e).getOrDefault("action", "?"));
                String reason = String.valueOf(payload(e).getOrDefault("reason", "<none>"));
                chains.add(new Chain(i, target, action, reason, false, List.of(), List.of(), 0, i));
                gaps.add("注入 #" + i + "(" + action + " → " + target + ") 被拒绝：" + reason);
            }
        }
        return new Report(chains, assertionDetails == null ? List.of() : assertionDetails,
                events.size(), gaps);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Event e) {
        return e.payload() == null ? new TreeMap<>() : e.payload();
    }

    /** REST 形态（{type, sourceId, timestamp, payload}） → 内核 {@link Event}。 */
    @SuppressWarnings("unchecked")
    private static Event toEvent(Map<String, Object> m) {
        Map<String, Object> payload = m.get("payload") instanceof Map<?, ?> p
                ? (Map<String, Object>) p : Map.of();
        String type = String.valueOf(m.getOrDefault("type", ""));
        String source = String.valueOf(m.getOrDefault("sourceId", ""));
        if (type.startsWith(Event.SUT_PREFIX)) {
            return Event.sut(type, source, payload);
        }
        return Event.sim(type, source, payload);
    }
}
