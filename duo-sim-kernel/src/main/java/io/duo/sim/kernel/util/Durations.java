package io.duo.sim.kernel.util;

import java.util.concurrent.TimeUnit;

/**
 * 带单位的时长解析（M1 T16/T23）：YAML 中 duration/at 统一要求带单位
 * {@code ms | s | m}，**无单位值拒绝解析**（§1 v4：算术建立在单位显式的前提上）。
 *
 * <p>位于 kernel：components（行为剧本）与 scenario（时间线/断言）共用同一解析。
 */
public final class Durations {

    private Durations() {
    }

    /** 解析如 {@code 15s / 100ms / 2m}；空白或无单位抛 IllegalArgumentException。 */
    public static long parseMillis(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("duration is blank");
        }
        String v = text.trim().toLowerCase();
        String unit = unitOf(v);
        String num = v.substring(0, v.length() - unit.length()).trim();
        if (num.isEmpty()) {
            throw new IllegalArgumentException("duration '" + text + "' lacks a numeric value");
        }
        long value;
        try {
            value = Long.parseLong(num);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("duration '" + text + "' has a non-numeric value");
        }
        if (value < 0) {
            throw new IllegalArgumentException("duration '" + text + "' must not be negative");
        }
        return switch (unit) {
            case "ms" -> value;
            case "s" -> TimeUnit.SECONDS.toMillis(value);
            case "m" -> TimeUnit.MINUTES.toMillis(value);
            default -> throw new IllegalArgumentException("duration '" + text
                    + "' lacks a unit (expected ms|s|m)");
        };
    }

    /** 提取单位；无单位时抛出明确错误。 */
    private static String unitOf(String v) {
        if (v.endsWith("ms")) {
            return "ms";
        }
        if (v.endsWith("s")) {
            return "s";
        }
        if (v.endsWith("m")) {
            return "m";
        }
        throw new IllegalArgumentException("duration '" + v + "' lacks a unit (expected ms|s|m)");
    }
}
