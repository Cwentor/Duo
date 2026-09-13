package io.duo.sim.components.provider;

import io.duo.sim.kernel.api.CapabilityMetadata;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * worker/engine 类组件的行为剧本解析（计划 §2/T11 的 M0 子集）：
 * default profile + 按任务名（精确匹配）覆盖的具名 profile。
 * 由组件管理器在 wiring 注入时以 config 片段传入（YAML 解析在 scenario 模块）。
 */
public final class BehaviorResolver {

    private final BehaviorEntry defaultProfile;
    private final Map<String, BehaviorEntry> byTaskName;

    public record BehaviorEntry(long durationMillis, double jitterRatio, double successRate,
                                String exceptionType, List<String> logLines) {
    }

    public BehaviorResolver(BehaviorEntry defaultProfile, Map<String, BehaviorEntry> byTaskName) {
        this.defaultProfile = defaultProfile;
        this.byTaskName = new ConcurrentHashMap<>(byTaskName);
    }

    /** 从 ComponentContext.config 解析（键约定：behaviors.default.* 与 behaviors.named.<task>.<field>）。 */
    public static BehaviorResolver fromConfig(Map<String, String> config) {
        BehaviorEntry def = new BehaviorEntry(
                parseLong(config.get("behaviors.default.duration"), 1000L),
                parseDouble(config.get("behaviors.default.jitter"), 0.0),
                parseDouble(config.get("behaviors.default.successRate"), 1.0),
                config.get("behaviors.default.exception"),
                List.of());
        Map<String, BehaviorEntry> named = new ConcurrentHashMap<>();
        config.keySet().stream()
                .filter(k -> k.startsWith("behaviors.named."))
                .map(k -> k.substring("behaviors.named.".length()))
                .map(k -> k.split("\\.")[0])
                .distinct()
                .forEach(task -> named.put(task, new BehaviorEntry(
                        parseLong(config.get("behaviors.named." + task + ".duration"), 1000L),
                        parseDouble(config.get("behaviors.named." + task + ".jitter"), 0.0),
                        parseDouble(config.get("behaviors.named." + task + ".successRate"), 1.0),
                        config.get("behaviors.named." + task + ".exception"),
                        List.of())));
        return new BehaviorResolver(def, named);
    }

    /** 匹配优先级（M0 两级）：精确任务名 > default。 */
    public BehaviorEntry resolve(String taskName) {
        BehaviorEntry named = byTaskName.get(taskName);
        return named != null ? named : defaultProfile;
    }

    private static long parseLong(String v, long dflt) {
        return v == null ? dflt : Long.parseLong(v.trim());
    }

    private static double parseDouble(String v, double dflt) {
        return v == null ? dflt : Double.parseDouble(v.trim());
    }
}
