package io.duo.sim.scenario;

import io.duo.sim.scenario.model.Scenario;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 场景 YAML 加载器（§8）。只做结构解析，语义校验归 {@link ScenarioValidator}。 */
public final class ScenarioLoader {

    private ScenarioLoader() {
    }

    public static Scenario load(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return load(in);
        }
    }

    @SuppressWarnings("unchecked")
    public static Scenario load(InputStream in) {
        Map<String, Object> doc = new Yaml().load(in);
        if (doc == null) {
            throw new IllegalArgumentException("empty scenario document");
        }
        String name = (String) doc.get("name");
        List<Map<String, Object>> rawNodes = (List<Map<String, Object>>) doc.get("topology");
        if (rawNodes == null || rawNodes.isEmpty()) {
            throw new IllegalArgumentException("scenario requires non-empty topology");
        }
        List<Scenario.NodeSpec> nodes = new ArrayList<>();
        for (Map<String, Object> n : rawNodes) {
            nodes.add(parseNode(n));
        }
        Scenario.Behaviors behaviors = parseBehaviors(
                (Map<String, Object>) doc.get("behaviors"));
        List<Scenario.TimelineEntry> timeline = new ArrayList<>();
        for (Map<String, Object> t :
                (List<Map<String, Object>>) doc.getOrDefault("timeline", List.of())) {
            timeline.add(new Scenario.TimelineEntry(str(t.get("at")), str(t.get("action")),
                    str(t.get("target")), str(t.get("duration")),
                    mapStrObj(t.get("params"))));
        }
        List<Map<String, Object>> assertions =
                (List<Map<String, Object>>) doc.get("assertions");
        return new Scenario(name, nodes, behaviors, timeline,
                assertions == null ? List.of() : assertions);
    }

    @SuppressWarnings("unchecked")
    private static Scenario.NodeSpec parseNode(Map<String, Object> n) {
        String id = str(n.get("id"));
        Scenario.Launch launch = null;
        Map<String, String> config = new LinkedHashMap<>();
        Object launchRaw = n.get("launch");
        if (launchRaw instanceof Map<?, ?> lm) {
            Object allowExternal = null;
            for (var entry : lm.entrySet()) {
                if ("allowExternalProcess".equals(String.valueOf(entry.getKey()))) {
                    allowExternal = entry.getValue();
                }
            }
            launch = new Scenario.Launch(str(lm.get("mode")), str(lm.get("main")),
                    str(lm.get("configOut")), str(lm.get("command")),
                    allowExternal != null && Boolean.parseBoolean(String.valueOf(allowExternal)));
            // launch.ready.{type,port,timeout} → config.ready.*（DSL ready 声明统一落 config）
            Object readyRaw = lm.get("ready");
            if (readyRaw instanceof Map<?, ?> rm) {
                rm.forEach((k, v) -> config.put("ready." + k, String.valueOf(v)));
            }
        }
        Object configRaw = n.get("config");
        if (configRaw instanceof Map<?, ?> cm) {
            cm.forEach((k, v) -> config.put(String.valueOf(k), String.valueOf(v)));
        }
        // 节点级 ready（M5/G5 兼容别名，DSL §1.4）：与 launch.ready 同义；两者冲突时**显式报错**，
        // 不静默择一（否则用户以为生效的是自己写的那份）。
        Object nodeReadyRaw = n.get("ready");
        if (nodeReadyRaw instanceof Map<?, ?> rm) {
            rm.forEach((k, v) -> {
                String key = "ready." + k;
                String val = String.valueOf(v);
                String prev = config.putIfAbsent(key, val);
                if (prev != null && !prev.equals(val)) {
                    throw new IllegalArgumentException("node '" + id + "': ready." + k
                            + " declared twice with conflicting values ('" + prev
                            + "' under launch.ready vs '" + val + "' at node level)");
                }
            });
        }
        List<Scenario.ExposeSpec> exposes = new ArrayList<>();
        for (Map<String, Object> e :
                (List<Map<String, Object>>) n.getOrDefault("exposes", List.of())) {
            Integer port = e.get("port") == null ? null : ((Number) e.get("port")).intValue();
            exposes.add(new Scenario.ExposeSpec(str(e.get("contract")), port,
                    str(e.get("addr"))));
        }
        Map<String, Scenario.WiringSpec> wiring = new LinkedHashMap<>();
        Object wiringRaw = n.get("wiring");
        if (wiringRaw instanceof Map<?, ?> wm) {
            wm.forEach((k, v) -> wiring.put(String.valueOf(k),
                    parseSlot(String.valueOf(k), v)));
        }
        validateNodeKeys(id, n);
        return new Scenario.NodeSpec(id, str(n.get("contract")), str(n.get("tier")),
                Boolean.TRUE.equals(n.get("sut")), launch, config, exposes, wiring,
                n.get("count") == null ? null : ((Number) n.get("count")).intValue(),
                capacity(n.get("capacity")), str(n.get("impl")),
                !Boolean.FALSE.equals(n.get("autoStart")));
    }

    /** 节点允许出现的键——其余键**显式报错**（G11：未知键静默忽略与 §12「不静默」冲突）。 */
    private static final Set<String> NODE_KEYS = Set.of(
            "id", "contract", "tier", "sut", "launch", "config", "exposes", "wiring",
            "count", "capacity", "impl", "ready", "autoStart");

    private static void validateNodeKeys(String id, Map<String, Object> n) {
        for (String key : n.keySet()) {
            if (!NODE_KEYS.contains(key)) {
                throw new IllegalArgumentException("node '" + id + "': unknown key '" + key
                        + "' (supported: " + new TreeSet<>(NODE_KEYS) + ")");
            }
        }
    }

    /** 简写 {@code 槽名: 节点id}（规则 2：契约名＝槽名）与显式 {@code {node, contract, path}} 两种形态。 */
    private static Scenario.WiringSpec parseSlot(String slotName, Object v) {
        if (v instanceof Map<?, ?> m) {
            return new Scenario.WiringSpec(str(m.get("node")), str(m.get("contract")),
                    str(m.get("path")));
        }
        return new Scenario.WiringSpec(str(v), slotName, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> capacity(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> cm) {
            cm.forEach((k, v) -> out.put("capacity." + k, String.valueOf(v)));
        }
        return out;
    }

    private static Scenario.Behaviors parseBehaviors(Map<String, Object> raw) {
        if (raw == null) {
            return new Scenario.Behaviors(Map.of(), List.of());
        }
        Map<String, Map<String, String>> profiles = new LinkedHashMap<>();
        Object p = raw.get("profiles");
        if (p instanceof Map<?, ?> pm) {
            pm.forEach((k, v) -> {
                Map<String, String> fields = new LinkedHashMap<>();
                if (v instanceof Map<?, ?> fm) {
                    fm.forEach((kk, vv) -> fields.put(String.valueOf(kk), flatten(vv)));
                }
                profiles.put(String.valueOf(k), fields);
            });
        }
        List<Scenario.Binding> bindings = new ArrayList<>();
        Object b = raw.get("bindings");
        if (b instanceof List<?> bl) {
            for (Object bo : bl) {
                if (bo instanceof Map<?, ?> bm) {
                    Map<String, Object> match = asMap(bm.get("match"));
                    bindings.add(new Scenario.Binding(str(bm.get("node")),
                            str(bm.get("profile")),
                            match.get("taskName") == null ? null
                                    : String.valueOf(match.get("taskName")),
                            match.get("label") == null ? null
                                    : String.valueOf(match.get("label"))));
                }
            }
        }
        return new Scenario.Behaviors(profiles, bindings);
    }

    /**
     * profile 字段值归一为字符串（G7）：YAML 列表（如 {@code logLines: [a, b]}）→ 逗号串
     * （{@code "a,b"}），避免 {@code List.toString()} 带方括号污染取值；其余按 {@code String.valueOf}。
     */
    private static String flatten(Object vv) {
        if (vv instanceof List<?> l) {
            return l.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
        }
        return String.valueOf(vv);
    }

    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapStrObj(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
