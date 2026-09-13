package io.duo.sim.scenario;

import java.util.concurrent.TimeUnit;

/**
 * 带单位的时长解析（M1 计划 T16/T23）：YAML 中 duration/at 统一要求带单位
 * {@code ms | s | m}，**无单位值拒绝解析**（§1 v4：算术建立在单位显式的前提上）。
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
        String num = v.substring(0, v.length() - Math.max(1, unitLength(v))).trim();
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
        return switch (unitOf(v)) {
            case "ms" -> value;
            case "s" -> TimeUnit.SECONDS.toMillis(value);
            case "m" -> TimeUnit.MINUTES.toMillis(value);
            default -> throw new IllegalArgumentException("duration '" + text
                    + "' lacks a unit (expected ms|s|m)");
        };
    }

    private static int unitLength(String v) {
        if (v.endsWith("ms")) {
            return 2;
        }
        if (v.endsWith("s") || v.endsWith("m")) {
            return 1;
        }
        return 1; // 无单位：让 unitOf 抛出明确错误
    }

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
