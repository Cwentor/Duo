package io.duo.sim.kernel.assertion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * YAML {@code assertions} 节解析（M1 T20）：把场景 DSL 的断言条目转成 {@link Assertion}。
 *
 * <p>支持形态（§8 示例）：
 * <ul>
 *   <li>{@code - failoverWithin: { seconds: 30 }}</li>
 *   <li>{@code - noTaskLost} 或 {@code - noTaskLost: { requireAllSuccess: true }}</li>
 *   <li>{@code - eventSequence: [sim.fault-injected, sut.task-retry]}</li>
 *   <li>{@code - affectedTasksAtLeast: { min: 1 }}</li>
 * </ul>
 * 未知断言名 → 抛 IllegalArgumentException（§8 快速失败：不静默忽略）。
 */
public final class AssertionParser {

    private AssertionParser() {
    }

    /** 解析 assertions 节（条目列表）。 */
    public static List<Assertion> parse(List<Map<String, Object>> entries) {
        List<Assertion> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        for (Map<String, Object> entry : entries) {
            if (entry == null || entry.isEmpty()) {
                throw new IllegalArgumentException("empty assertion entry");
            }
            var it = entry.entrySet().iterator();
            var e = it.next();
            String name = e.getKey();
            Object value = e.getValue();
            out.add(parseOne(name, value));
        }
        return out;
    }

    private static Assertion parseOne(String name, Object value) {
        return switch (name) {
            case "failoverWithin" -> Assertions.failoverWithin(
                    longParam(value, "seconds", 30L));
            case "noTaskLost" -> Assertions.noTaskLost(
                    boolParam(value, "requireAllSuccess", true));
            case "eventSequence" -> Assertions.eventSequence(
                    listParam(value, name));
            case "affectedTasksAtLeast" -> Assertions.affectedTasksAtLeast(
                    (int) longParam(value, "min", 1L));
            case "masterReelectedWithin" -> Assertions.masterReelectedWithin(
                    longParam(value, "seconds", 30L));
            default -> throw new IllegalArgumentException(
                    "unknown assertion: " + name);
        };
    }

    private static long longParam(Object value, String key, long dflt) {
        if (value instanceof Map<?, ?> m) {
            Object v = m.get(key);
            if (v instanceof Number n) {
                return n.longValue();
            }
        }
        return dflt;
    }

    private static boolean boolParam(Object value, String key, boolean dflt) {
        if (value instanceof Map<?, ?> m) {
            Object v = m.get(key);
            if (v instanceof Boolean b) {
                return b;
            }
            if (v != null) {
                return Boolean.parseBoolean(String.valueOf(v));
            }
        }
        return dflt;
    }

    private static List<String> listParam(Object value, String name) {
        if (value instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        throw new IllegalArgumentException("assertion '" + name
                + "' requires a list value");
    }
}
