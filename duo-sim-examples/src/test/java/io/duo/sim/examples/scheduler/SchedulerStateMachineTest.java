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
        final List<String> instances = new ArrayList<>();
        final AtomicInteger retries = new AtomicInteger();

        @Override
        public void onDispatch(String taskId, String taskName, int attempt, String inst) {
            log.add("dispatch:" + taskId + ":" + attempt);
        }

        @Override
        public void onStatus(String taskId, String state, String detail, int attempt,
                             String instance) {
            log.add("status:" + taskId + ":" + state);
            if (instance != null) {
                instances.add(instance);
            }
        }

        @Override
        public void onFailover(String taskId, String fromInstance) {
            log.add("failover:" + taskId + ":" + fromInstance);
        }

        @Override
        public void onRejected(String taskId, String instanceName, String reason, int rejections) {
            log.add("rejected:" + taskId + ":" + instanceName + ":" + rejections);
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

    // ---- T19(b) 崩溃转移 ----

    @Test
    void instanceLostRequeuesInFlightTaskAndEmitsFailover() {
        var rec = new Recorder();
        var sm = new SchedulerStateMachine(dag(), rec);
        sm.dispatch("load", "workers-3");
        assertEquals(List.of("load"), sm.inFlightOn("workers-3"));
        assertEquals("workers-3", sm.ownerOf("load"));

        var requeued = sm.onInstanceLost("workers-3", "connection lost");
        assertEquals(List.of("load"), requeued);
        assertTrue(sm.dispatchable().contains("load"), "task must return to dispatchable");
        assertTrue(rec.log.contains("failover:load:workers-3"), "failover fact: " + rec.log);
        assertTrue(rec.retries.get() >= 1, "retry counted");
    }

    @Test
    void instanceLostAtMaxAttemptsFailsTaskAndSkipsDownstream() {
        var rec = new Recorder();
        var sm = new SchedulerStateMachine(dag(), rec);
        // 把 load 推到 MAX_ATTEMPTS，且处于 RUNNING
        for (int i = 0; i < SchedulerStateMachine.MAX_ATTEMPTS; i++) {
            sm.dispatch("load", "workers-3");
            if (i < SchedulerStateMachine.MAX_ATTEMPTS - 1) {
                sm.onStatus("load", "FAILED", "x"); // 触发重试回到 PENDING
            }
        }
        // 此刻 load RUNNING 且 attempts==MAX
        assertEquals(SchedulerStateMachine.MAX_ATTEMPTS, sm.attemptsOf("load"));
        var requeued = sm.onInstanceLost("workers-3", "connection lost");
        assertTrue(requeued.isEmpty(), "no requeue at max attempts (no degradation)");
        assertEquals("FAILED", sm.phaseOf("load"));
        assertEquals("SKIPPED", sm.phaseOf("clean"));
    }

    @Test
    void instanceLostWithNoInFlightTasksIsNoop() {
        var rec = new Recorder();
        var sm = new SchedulerStateMachine(dag(), rec);
        assertTrue(sm.onInstanceLost("workers-9", "connection lost").isEmpty());
        assertTrue(sm.inFlightOn("workers-9").isEmpty());
    }

    @Test
    void dispatchEmitsInstanceInStatusEvents() {
        var rec = new Recorder();
        var sm = new SchedulerStateMachine(dag(), rec);
        sm.dispatch("load", "workers-2");
        sm.onStatus("load", "SUCCESS", null, "workers-2");
        assertTrue(rec.instances.contains("workers-2"),
                "status event must carry instance: " + rec.instances);
    }

    // ---- G9：派发被 worker 显式拒绝（准入失败）----

    @Test
    void rejectedDispatchRollsBackAttemptAndRequeues() {
        var rec = new Recorder();
        var sm = new SchedulerStateMachine(dag(), rec);
        sm.dispatch("load", "workers-3");
        assertEquals("RUNNING", sm.phaseOf("load"));
        assertEquals(1, sm.attemptsOf("load"));

        assertTrue(sm.onRejected("load", "workers-3", "no free slot"));
        assertEquals("PENDING", sm.phaseOf("load"), "拒绝后必须回到待派发（不得停在 RUNNING）");
        assertEquals(0, sm.attemptsOf("load"), "准入失败不占 MAX_ATTEMPTS 额度");
        assertTrue(sm.dispatchable().contains("load"), "必须可被重派");
        assertTrue(rec.log.contains("rejected:load:workers-3:1"), rec.log.toString());
        assertEquals(0, rec.retries.get(), "拒绝不是执行失败，不得发重试事实");
    }

    @Test
    void rejectionOnNonRunningTaskIsIgnored() {
        var rec = new Recorder();
        var sm = new SchedulerStateMachine(dag(), rec);
        sm.dispatch("load", "workers-3");
        sm.onStatus("load", "SUCCESS", null, "workers-3");
        assertFalse(sm.onRejected("load", "workers-3", "no free slot"), "迟到拒绝须被忽略");
        assertEquals("SUCCESS", sm.phaseOf("load"));
    }

    @Test
    void repeatedRejectionsFailTaskLoudlyInsteadOfHanging() {
        var rec = new Recorder();
        var sm = new SchedulerStateMachine(dag(), rec);
        for (int i = 0; i < SchedulerStateMachine.MAX_REJECTIONS; i++) {
            sm.dispatch("load", "workers-3");
            sm.onRejected("load", "workers-3", "no free slot");
            assertEquals("PENDING", sm.phaseOf("load"), "第 " + (i + 1) + " 次拒绝仍应可重派");
        }
        // 超过上限 → 显式失败 + 下游跳过（DAG 必然终态：任何情况下都不允许永久挂起）
        sm.dispatch("load", "workers-3");
        sm.onRejected("load", "workers-3", "no free slot");
        assertEquals("FAILED", sm.phaseOf("load"));
        assertEquals("SKIPPED", sm.phaseOf("clean"));
        assertTrue(rec.log.stream().anyMatch(l -> l.startsWith("status:load:FAILED")), rec.log.toString());
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
