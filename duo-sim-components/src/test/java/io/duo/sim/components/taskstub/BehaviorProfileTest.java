package io.duo.sim.components.taskstub;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorProfileTest {

    private static final Random SEEDED = new Random(42L);

    @Test
    void exceptionProfileAlwaysFailsDeterministically() throws Exception {
        var p = BehaviorProfile.alwaysFail(5, "java.lang.RuntimeException");
        for (int attempt = 1; attempt <= 5; attempt++) {
            var out = p.execute("unstable-task", new Random(attempt));
            assertFalse(out.success());
            assertTrue(out.errorMessage().startsWith("java.lang.RuntimeException"));
        }
    }

    @Test
    void successRateZeroAlwaysFails() throws Exception {
        var p = new BehaviorProfile(1, 0.0, 0.0, null, List.of());
        assertFalse(p.execute("t", SEEDED).success());
    }

    @Test
    void successRateOneAlwaysSucceeds() throws Exception {
        var p = BehaviorProfile.normal(1, 0.0, 1.0);
        var out = p.execute("t", SEEDED);
        assertTrue(out.success());
        assertEquals(1, out.duration());
    }

    @Test
    void jitterStaysWithinBoundsAndDeterministic() throws Exception {
        var p = new BehaviorProfile(100, 0.2, 1.0, null, List.of("line-a", "line-b"));
        long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
        for (int i = 0; i < 50; i++) {
            var out = p.execute("t", new Random(i));
            lo = Math.min(lo, out.duration());
            hi = Math.max(hi, out.duration());
            assertEquals(List.of("line-a", "line-b"), out.logs());
        }
        assertTrue(lo >= 80 && hi <= 120, "jitter out of bounds: " + lo + ".." + hi);
        // 同种子 → 同时长（确定性可复现）
        assertEquals(p.execute("t", new Random(7)).duration(),
                p.execute("t", new Random(7)).duration());
    }

    @Test
    void validationRejectsBadInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new BehaviorProfile(-1, 0.0, 1.0, null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new BehaviorProfile(1, 1.5, 1.0, null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new BehaviorProfile(1, 0.0, -0.1, null, List.of()));
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
