package io.duo.sim.components.provider;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * worker/engine 类组件的行为剧本解析（M1 计划 T17：四级优先级）。
 *
 * <p>匹配顺序：**精确任务名 &gt; 标签 &gt; 通配 pattern &gt; default**。
 * config 键约定：
 * <ul>
 *   <li>{@code behaviors.default.<field>}——缺省；</li>
 *   <li>{@code behaviors.named.<X>.<field>}——X 含 {@code *} 时归通配表（如
 *       {@code spark-*}），否则归精确表（M0 YAML 向后兼容）；</li>
 *   <li>{@code behaviors.by-label.<label>.<field>}——标签表。</li>
 * </ul>
 * 字段集（M1 全集）：duration / jitter / successRate / exception /
 * failAt / neverReport / progress。
 *
 * <p>注意：标签匹配仅在 resolver 单测覆盖——线协议 {@code TaskDispatch} 报文
 * 无 label 字段，端到端覆盖留后续（M1 计划已注明）。
 */
public final class BehaviorResolver {

    private final BehaviorEntry defaultProfile;
    private final Map<String, BehaviorEntry> byTaskName;
    private final Map<String, BehaviorEntry> byLabel;
    private final Map<String, BehaviorEntry> byPattern;

    /** 行为条目（字段全集；与 BehaviorProfile 字段一一对应）。 */
    public record BehaviorEntry(long durationMillis, double jitterRatio, double successRate,
                                String exceptionType, List<String> logLines,
                                Integer failAtPercent, boolean neverReport,
                                String progressMode) {

        /** M0 兼容构造器（无 M1 字段）。 */
        public BehaviorEntry(long durationMillis, double jitterRatio, double successRate,
                             String exceptionType, List<String> logLines) {
            this(durationMillis, jitterRatio, successRate, exceptionType, logLines,
                    null, false, null);
        }
    }

    public BehaviorResolver(BehaviorEntry defaultProfile, Map<String, BehaviorEntry> byTaskName) {
        this(defaultProfile, byTaskName, Map.of(), Map.of());
    }

    public BehaviorResolver(BehaviorEntry defaultProfile, Map<String, BehaviorEntry> byTaskName,
                            Map<String, BehaviorEntry> byLabel,
                            Map<String, BehaviorEntry> byPattern) {
        this.defaultProfile = defaultProfile;
        this.byTaskName = new ConcurrentHashMap<>(byTaskName);
        this.byLabel = new ConcurrentHashMap<>(byLabel);
        this.byPattern = new ConcurrentHashMap<>(byPattern);
    }

    /** 从 ComponentContext.config 解析（键约定见类注释）。 */
    public static BehaviorResolver fromConfig(Map<String, String> config) {
        BehaviorEntry def = parseEntry("behaviors.default.", config);
        Map<String, BehaviorEntry> exact = new ConcurrentHashMap<>();
        Map<String, BehaviorEntry> pattern = new ConcurrentHashMap<>();
        Map<String, BehaviorEntry> label = new ConcurrentHashMap<>();
        config.keySet().stream()
                .filter(k -> k.startsWith("behaviors.named."))
                .map(k -> k.substring("behaviors.named.".length()))
                .map(k -> k.split("\\.")[0])
                .distinct()
                .forEach(x -> {
                    var e = parseEntry("behaviors.named." + x + ".", config);
                    if (x.contains("*")) {
                        pattern.put(x, e);
                    } else {
                        exact.put(x, e);
                    }
                });
        config.keySet().stream()
                .filter(k -> k.startsWith("behaviors.by-label."))
                .map(k -> k.substring("behaviors.by-label.".length()))
                .map(k -> k.split("\\.")[0])
                .distinct()
                .forEach(l -> label.put(l, parseEntry("behaviors.by-label." + l + ".", config)));
        return new BehaviorResolver(def, exact, label, pattern);
    }

    private static BehaviorEntry parseEntry(String prefix, Map<String, String> config) {
        return new BehaviorEntry(
                parseLong(config.get(prefix + "duration"), 1000L),
                parseDouble(config.get(prefix + "jitter"), 0.0),
                parseDouble(config.get(prefix + "successRate"), 1.0),
                config.get(prefix + "exception"),
                List.of(),
                config.get(prefix + "failAt") == null ? null
                        : Integer.parseInt(config.get(prefix + "failAt").trim()),
                Boolean.parseBoolean(config.get(prefix + "neverReport")),
                config.get(prefix + "progress"));
    }

    /** M0 兼容：等价于 resolve(taskName, Set.of())。 */
    public BehaviorEntry resolve(String taskName) {
        return resolve(taskName, Set.of());
    }

    /** 匹配优先级（M1 四级）：精确任务名 > 标签 > 通配 pattern > default。 */
    public BehaviorEntry resolve(String taskName, Set<String> labels) {
        BehaviorEntry exact = byTaskName.get(taskName);
        if (exact != null) {
            return exact;
        }
        for (String l : labels) {
            BehaviorEntry byL = byLabel.get(l);
            if (byL != null) {
                return byL;
            }
        }
        for (var p : byPattern.entrySet()) {
            if (matches(p.getKey(), taskName)) {
                return p.getValue();
            }
        }
        return defaultProfile;
    }

    /** 简单通配：单个 {@code *} 支持前缀/后缀/中缀；无 {@code *} 等价精确。 */
    static boolean matches(String pattern, String taskName) {
        int star = pattern.indexOf('*');
        if (star < 0) {
            return pattern.equals(taskName);
        }
        String head = pattern.substring(0, star);
        String tail = pattern.substring(star + 1);
        return taskName.startsWith(head) && taskName.endsWith(tail)
                && taskName.length() >= head.length() + tail.length();
    }

    /** 时长解析：带单位（ms/s/m）优先（T23 单位校验）；无单位按毫秒向后兼容 M0 YAML。 */
    private static long parseLong(String v, long dflt) {
        if (v == null) {
            return dflt;
        }
        String t = v.trim();
        if (t.endsWith("ms") || t.endsWith("s") || t.endsWith("m")) {
            return io.duo.sim.kernel.util.Durations.parseMillis(t);
        }
        return Long.parseLong(t);
    }

    private static double parseDouble(String v, double dflt) {
        return v == null ? dflt : Double.parseDouble(v.trim());
    }
}
