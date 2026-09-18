package io.duo.sim.components.provider;

import io.duo.sim.components.provider.BehaviorResolver.BehaviorEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T17 四级匹配单测：精确任务名 > 标签 > 通配 > default。 */
class BehaviorResolverM1Test {

    private static BehaviorEntry entry(long duration) {
        return new BehaviorEntry(duration, 0.0, 1.0, null, List.of());
    }

    private static BehaviorResolver resolver() {
        Map<String, String> cfg = Map.of(
                "behaviors.default.duration", "1000",
                "behaviors.named.spark-etl.duration", "200",
                "behaviors.named.spark-*.duration", "300",
                "behaviors.by-label.etl.duration", "400");
        return BehaviorResolver.fromConfig(cfg);
    }

    @Test
    void exactNameWinsOverEverything() {
        var r = resolver();
        assertEquals(200, r.resolve("spark-etl", Set.of("etl")).durationMillis());
        assertEquals(200, r.resolve("spark-etl", Set.of()).durationMillis());
    }

    @Test
    void labelWinsOverPatternAndDefault() {
        var r = resolver();
        // 非精确名、但带 etl 标签 → 标签级（400），不走通配
        assertEquals(400, r.resolve("spark-load", Set.of("etl")).durationMillis());
    }

    @Test
    void patternWinsOverDefault() {
        var r = resolver();
        // 命中 spark-* 通配（300），无标签
        assertEquals(300, r.resolve("spark-load", Set.of()).durationMillis());
        assertEquals(300, r.resolve("spark-write", Set.of("other")).durationMillis());
    }

    @Test
    void defaultUsedWhenNothingMatches() {
        var r = resolver();
        assertEquals(1000, r.resolve("plain-task", Set.of()).durationMillis());
        assertEquals(1000, r.resolve("plain-task", Set.of("stray-label")).durationMillis());
    }

    @Test
    void wildcardMatchesPrefixSuffixAndInfix() {
        assertTrue(BehaviorResolver.matches("spark-*", "spark-etl"));
        assertTrue(BehaviorResolver.matches("*-etl", "spark-etl"));
        assertTrue(BehaviorResolver.matches("spark*etl", "spark-xyz-etl"));
        assertTrue(BehaviorResolver.matches("exact", "exact"));
        assertFalse(BehaviorResolver.matches("spark-*", "flink-etl"));
        assertFalse(BehaviorResolver.matches("exact", "exact-2"));
        // 长度边界：pattern 比 taskName 长时不匹配
        assertFalse(BehaviorResolver.matches("abcd*", "abc"));
    }

    @Test
    void m0TwoLevelStillWorks() {
        // 仅 default + 精确名（M0 YAML 形态），四级解析退化为既有语义
        var r = BehaviorResolver.fromConfig(Map.of(
                "behaviors.default.duration", "500",
                "behaviors.named.unstable-task.duration", "60"));
        assertEquals(60, r.resolve("unstable-task").durationMillis());
        assertEquals(500, r.resolve("load-orders").durationMillis());
    }

    @Test
    void m1FieldsParsedFromConfig() {
        var r = BehaviorResolver.fromConfig(Map.of(
                "behaviors.default.duration", "100",
                "behaviors.named.boom.duration", "50",
                "behaviors.named.boom.failAt", "60",
                "behaviors.named.boom.neverReport", "true",
                "behaviors.named.boom.progress", "periodic"));
        var e = r.resolve("boom");
        assertEquals(50, e.durationMillis());
        assertEquals(60, e.failAtPercent());
        assertTrue(e.neverReport());
        assertEquals("periodic", e.progressMode());
        // 未声明的任务字段取默认
        var d = r.resolve("other");
        assertEquals(null, d.failAtPercent());
        assertFalse(d.neverReport());
    }

    // ---- G7：设计 §8 示例的百分号形态 ----

    @Test
    void jitterAcceptsPercentForm() {
        var r = BehaviorResolver.fromConfig(Map.of(
                "behaviors.default.jitter", "20%",
                "behaviors.named.half.jitter", "0.5",
                "behaviors.named.full.jitter", "100%"));
        assertEquals(0.2, r.resolve("plain").jitterRatio(), 1e-9);
        assertEquals(0.5, r.resolve("half").jitterRatio(), 1e-9);
        assertEquals(1.0, r.resolve("full").jitterRatio(), 1e-9);
    }

    @Test
    void failAtAcceptsPercentForm() {
        var r = BehaviorResolver.fromConfig(Map.of(
                "behaviors.default.failAt", "60%",
                "behaviors.named.zero.failAt", "0",
                "behaviors.named.all.failAt", "100%"));
        assertEquals(60, r.resolve("plain").failAtPercent());
        assertEquals(0, r.resolve("zero").failAtPercent());
        assertEquals(100, r.resolve("all").failAtPercent());
    }

    @Test
    void outOfRangeJitterAndFailAtAreRejectedWithKeyName() {
        // 不静默取默认值：越界必须报错，且错误信息点出配置键
        var jitter = assertThrows(IllegalArgumentException.class,
                () -> BehaviorResolver.fromConfig(Map.of("behaviors.default.jitter", "1.5")));
        assertTrue(jitter.getMessage().contains("behaviors.default.jitter"), jitter.getMessage());

        var pct = assertThrows(IllegalArgumentException.class,
                () -> BehaviorResolver.fromConfig(Map.of("behaviors.default.failAt", "120%")));
        assertTrue(pct.getMessage().contains("behaviors.default.failAt"), pct.getMessage());

        var nan = assertThrows(IllegalArgumentException.class,
                () -> BehaviorResolver.fromConfig(Map.of("behaviors.default.jitter", "abc")));
        assertTrue(nan.getMessage().contains("not a number"), nan.getMessage());
    }

    @Test
    void logLinesParsedFromCommaSeparatedConfig() {
        var r = BehaviorResolver.fromConfig(Map.of(
                "behaviors.default.logLines", "load start, load done ,",
                "behaviors.named.quiet.logLines", ""));
        assertEquals(List.of("load start", "load done"), r.resolve("plain").logLines());
        // 显式置空 = 该任务不打假日志
        assertTrue(r.resolve("quiet").logLines().isEmpty());
        // 未声明 logLines 的任务继承 default（不是空）
        assertEquals(List.of("load start", "load done"), r.resolve("absent").logLines());
    }
}
