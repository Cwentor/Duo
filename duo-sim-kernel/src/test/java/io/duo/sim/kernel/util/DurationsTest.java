package io.duo.sim.kernel.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link Durations} 单位化时长解析单测（M1 T16；T23 loader 校验与行为剧本解析共用同一实现）：
 * {@code ms|s|m} 三单位；无单位/空白/非数字/负值一律拒绝。
 */
class DurationsTest {

    @Test
    void parsesAllSupportedUnits() {
        assertEquals(100L, Durations.parseMillis("100ms"));
        assertEquals(15_000L, Durations.parseMillis("15s"));
        assertEquals(120_000L, Durations.parseMillis("2m"));
        assertEquals(0L, Durations.parseMillis("0s"));
        assertEquals(1_500L, Durations.parseMillis(" 1500ms "));
        assertEquals(15_000L, Durations.parseMillis("15S")); // 大小写不敏感
    }

    @Test
    void rejectsValueWithoutUnit() {
        // §1 v4：算术建立在单位显式的前提上——裸数字必须拒绝
        assertThrows(IllegalArgumentException.class, () -> Durations.parseMillis("15"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parseMillis("0"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parseMillis("15x"));
    }

    @Test
    void rejectsBlankAndMalformed() {
        assertThrows(IllegalArgumentException.class, () -> Durations.parseMillis(null));
        assertThrows(IllegalArgumentException.class, () -> Durations.parseMillis("  "));
        assertThrows(IllegalArgumentException.class, () -> Durations.parseMillis("ms"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parseMillis("1.5s"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parseMillis("-5s"));
    }
}
