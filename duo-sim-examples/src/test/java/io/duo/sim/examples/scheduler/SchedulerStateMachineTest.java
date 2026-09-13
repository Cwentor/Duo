package io.duo.sim.examples.scheduler;

import io.duo.sim.kernel.api.Event;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerStateMachineTest {

    /** 录制型 listener。 */
    static final class Recorder implements SchedulerStateMachine.Listener {
        final List<String> log = new ArrayList<>();
        final AtomicInteger retries = new AtomicInteger();

        @Override
        public void onDispatch(String taskId, String taskName, int attempt, String inst) {
            log.add("dispatch:" + taskId + ":" + attempt);
        }

        @Override
        public void onStatus(String taskId, String state, String detail, int attempt) {
            log.add("status:" + taskId + ":" + state);
        }

        @Override
        public void onRetry(String taskId, int nextAttempt) {
            retries.incrementAndGet();
            log.add("retry:" + taskId + ":" + nextAttempt);
        }

        @Override
        public void onAllTerminal() {
            log.add("all-terminal");
        }
    }

    private static Map<String, List<String>> dag() {
        return Map.of(
                "load", List.of(),
                "clean", List.of("load"),
                "unstable", List.of("clean"),
                "report", List.of("unstable"));
    }

    @Test
    void dependencyGatingDispatchesInOrder() {
        var rec = new Recorder();
        var sm = new SchedulerStateMachine(dag(), rec);
        assertEquals(List.of("load"), sm.dispatchable());
        sm.dispatch("load", "w-1");
        assertEquals(1, sm.attemptOf("load"));
        // load RUNNING 时 clean 不可派发
        assertTrue(sm.dispatchable().isEmpty());
        sm.onStatus("load", "SUCCESS", null);
        assertEquals(List.of("clean"), sm.dispatchable());
    }

    @Test
    void boundedRetryThenFailedThenSkippedDownstream() {
        var rec = new Recorder();
        var sm = new SchedulerStateMachine(dag(), rec);
        sm.dispatch("load", "w-1");
        sm.onStatus("load", "SUCCESS", null);
        sm.dispatch("clean", "w-1");
        sm.onStatus("clean", "SUCCESS", null);

        // unstable 三次尝试全失败（exception 型任务）
        sm.dispatch("unstable", "w-1");
        assertTrue(sm.onStatus("unstable", "FAILED", "RuntimeException"));
        assertEquals(1, rec.retries.get());
        sm.dispatch("unstable", "w-1"); // attempt 2
        assertTrue(sm.onStatus("unstable", "FAILED", "RuntimeException"));
        sm.dispatch("unstable", "w-1"); // attempt 3 = MAX_ATTEMPTS
        assertFalse(sm.onStatus("unstable", "FAILED", "RuntimeException")); // 不再重试
        assertEquals("FAILED", sm.phaseOf("unstable"));
        assertEquals(3, sm.attemptsOf("unstable"));
        // 下游 SKIPPED
        assertEquals("SKIPPED", sm.phaseOf("report"));
        assertTrue(sm.allTerminal());
        assertTrue(rec.log.contains("all-terminal"));
    }

    @Test
    void retryResetsToPendingAndRedispatches() {
        var rec = new Recorder();
        var sm = new SchedulerStateMachine(dag(), rec);
        sm.dispatch("load", "w-1");
        sm.onStatus("load", "SUCCESS", null);
        sm.dispatch("clean", "w-1");
        sm.onStatus("clean", "FAILED", "x"); // attempt 1 失败 → 重试
        sm.dispatch("clean", "w-2");
        sm.onStatus("clean", "SUCCESS", null);
        assertEquals(2, sm.attemptsOf("clean"));
        assertEquals("SUCCESS", sm.phaseOf("clean"));
    }

    @Test
    void dagCycleRejected() {
        var e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SchedulerStateMachine(Map.of(
                        "a", List.of("b"), "b", List.of("a")), new Recorder()));
        assertTrue(e.getMessage().contains("cycle"));
    }
}
