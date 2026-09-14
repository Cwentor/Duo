package io.duo.sim.components.taskstub;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T17 行为字段全集单测：failAt / neverReport / progress。 */
class BehaviorProfileM1FieldsTest {

    @Test
    void failAtTruncatesAtGivenPercentDeterministically() throws Exception {
        // duration 100ms、failAt 40% → 在 ~40ms 处确定性失败（不跑满 100ms）
        var p = new BehaviorProfile(100, 0.0, 1.0, null, List.of(), 40, false, null);
        for (int i = 0; i < 3; i++) {
            var out = p.execute("t", new Random(i));
            assertFalse(out.success(), "failAt must be deterministic failure");
            assertTrue(out.errorMessage().contains("failAt 40%"));
            assertTrue(out.duration() <= 60, "should truncate near 40ms, got " + out.duration());
        }
    }

    @Test
    void failAtOverridesSuccessRateAndException() throws Exception {
        // successRate=1.0 且无 exception，仍因 failAt 必败
        var p = new BehaviorProfile(50, 0.0, 1.0, null, List.of(), 100, false, null);
        assertFalse(p.execute("t", new Random(1)).success());
    }

    @Test
    void failAtZeroFailsImmediately() throws Exception {
        var p = new BehaviorProfile(200, 0.0, 1.0, null, List.of(), 0, false, null);
        long t0 = System.currentTimeMillis();
        var out = p.execute("t", new Random(1));
        assertFalse(out.success());
        assertTrue(System.currentTimeMillis() - t0 < 150, "failAt 0 should not sleep full duration");
    }

    @Test
    void periodicProgressReportsAtQuarterSteps() throws Exception {
        var p = new BehaviorProfile(200, 0.0, 1.0, null, List.of(), null, false,
                BehaviorProfile.PROGRESS_PERIODIC);
        List<Integer> pcts = new ArrayList<>();
        p.execute("t", new Random(1), pcts::add);
        assertFalse(pcts.isEmpty(), "periodic mode must report progress");
        assertEquals(100, pcts.get(pcts.size() - 1), "final report should reach 100");
        // 严格递增
        for (int i = 1; i < pcts.size(); i++) {
            assertTrue(pcts.get(i) > pcts.get(i - 1), "progress must be increasing: " + pcts);
        }
    }

    @Test
    void progressOffProducesNoCallbacks() throws Exception {
        var p = new BehaviorProfile(30, 0.0, 1.0, null, List.of());
        List<Integer> pcts = new ArrayList<>();
        p.execute("t", new Random(1), pcts::add);
        assertTrue(pcts.isEmpty(), "off mode must not report: " + pcts);
    }

    @Test
    void failAtWithProgressDoesNotOverReportPastFailurePoint() throws Exception {
        // failAt 50% + periodic：进度不应虚报超过 50%
        var p = new BehaviorProfile(200, 0.0, 1.0, null, List.of(), 50, false,
                BehaviorProfile.PROGRESS_PERIODIC);
        List<Integer> pcts = new ArrayList<>();
        var out = p.execute("t", new Random(1), pcts::add);
        assertFalse(out.success());
        assertTrue(pcts.stream().allMatch(x -> x <= 50),
                "progress must not exceed failure point: " + pcts);
    }

    @Test
    void neverReportFlagIsCarriedOnProfile() {
        var p = new BehaviorProfile(10, 0.0, 1.0, null, List.of(), null, true, null);
        assertTrue(p.neverReport());
        // 执行本身照常产出结果（丢弃回报是 worker 侧职责）
        assertTrue(assertDoesNotThrow(() -> p.execute("t", new Random(1))).success());
    }

    @Test
    void validationRejectsBadM1Fields() {
        assertThrows(IllegalArgumentException.class,
                () -> new BehaviorProfile(10, 0.0, 1.0, null, List.of(), 101, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> new BehaviorProfile(10, 0.0, 1.0, null, List.of(), -1, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> new BehaviorProfile(10, 0.0, 1.0, null, List.of(), null, false, "bogus"));
    }

    @Test
    void m0CompatibilityConstructorStillWorks() throws Exception {
        var p = BehaviorProfile.alwaysFail(5, "java.lang.RuntimeException");
        assertFalse(p.execute("t", new Random(1)).success());
        var n = BehaviorProfile.normal(5, 0.0, 1.0);
        assertTrue(n.execute("t", new Random(1)).success());
    }

    private static <T> T assertDoesNotThrow(ThrowingSupplier<T> s) {
        try {
            return s.get();
        } catch (Exception e) {
            throw new AssertionError("unexpected: " + e, e);
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static void assertThrows(Class<? extends Throwable> type, Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected " + type.getSimpleName());
        } catch (RuntimeException e) {
            assertTrue(type.isInstance(e), "unexpected: " + e);
        }
    }
}
