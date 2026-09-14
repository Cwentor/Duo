package io.duo.sim.kernel.assertion;

import io.duo.sim.kernel.api.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T20 断言库单测：三断言正反例 + 守护断言 + 重启后实例作目标的反向用例。 */
class AssertionsTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** 事件构造：秒偏移。 */
    private static Event sim(String type, String source, long atSecond, Map<String, Object> p) {
        return new Event(type, source, T0.plusSeconds(atSecond), p);
    }

    private static Event sut(String type, String source, long atSecond, Map<String, Object> p) {
        return new Event(type, source, T0.plusSeconds(atSecond), p);
    }

    /** 标准失败转移剧本：crash workers-3 @10s → 重派发 workers-1 @15s → SUCCESS @25s。 */
    private static List<Event> failoverScript() {
        List<Event> e = new ArrayList<>();
        e.add(sut("sut.task-dispatched", "sut", 1,
                Map.of("taskId", "t1", "attempt", 1, "instance", "workers-3")));
        e.add(sut("sut.task-dispatched", "sut", 1,
                Map.of("taskId", "t2", "attempt", 1, "instance", "workers-1")));
        e.add(sim("sim.fault-injected", "workers-3", 10, Map.of("action", "crash")));
        e.add(sut("sut.instance-lost", "sut", 10, Map.of("instance", "workers-3")));
        e.add(sut("sut.task-retry", "sut", 10, Map.of("taskId", "t1", "nextAttempt", 2)));
        e.add(sut("sut.failover", "sut", 10, Map.of("taskId", "t1", "from", "workers-3")));
        e.add(sut("sut.task-dispatched", "sut", 15,
                Map.of("taskId", "t1", "attempt", 2, "instance", "workers-1")));
        e.add(sut("sut.task-terminal", "sut", 25,
                Map.of("taskId", "t1", "state", "SUCCESS", "instance", "workers-1")));
        e.add(sut("sut.task-terminal", "sut", 25,
                Map.of("taskId", "t2", "state", "SUCCESS", "instance", "workers-1")));
        return e;
    }

    @Test
    void failoverWithinPassesOnCleanFailover() {
        var o = Assertions.failoverWithin(30).evaluate(failoverScript());
        assertTrue(o.passed(), o.detail());
    }

    @Test
    void failoverWithinFailsWhenWindowExceeded() {
        var events = failoverScript();
        // 重派发推到 crash 后 35s（超 30s 窗口）
        List<Event> late = new ArrayList<>();
        for (Event e : events) {
            if (e.type().equals("sut.task-dispatched") && e.timestamp().equals(T0.plusSeconds(15))) {
                late.add(sut("sut.task-dispatched", "sut", 45,
                        Map.of("taskId", "t1", "attempt", 2, "instance", "workers-1")));
            } else {
                late.add(e);
            }
        }
        var o = Assertions.failoverWithin(30).evaluate(late);
        assertFalse(o.passed(), o.detail());
    }

    @Test
    void failoverWithinFailsWhenNoReDispatch() {
        // crash 后无重派发（任务卡死）
        List<Event> events = failoverScript().stream()
                .filter(e -> !(e.type().equals("sut.task-dispatched")
                        && "2".equals(String.valueOf(e.payload().get("attempt")))))
                .toList();
        var o = Assertions.failoverWithin(30).evaluate(events);
        assertFalse(o.passed(), o.detail());
        assertTrue(o.detail().contains("was not re-dispatched"), o.detail());
    }

    @Test
    void failoverWithinFailsWhenReDispatchedToCrashedInstanceWithoutRestart() {
        // 重派发回 crash 时刻的 workers-3（未重启）→ 不算转移
        List<Event> events = new ArrayList<>();
        events.add(sut("sut.task-dispatched", "sut", 1,
                Map.of("taskId", "t1", "attempt", 1, "instance", "workers-3")));
        events.add(sim("sim.fault-injected", "workers-3", 10, Map.of("action", "crash")));
        events.add(sut("sut.task-dispatched", "sut", 12,
                Map.of("taskId", "t1", "attempt", 2, "instance", "workers-3")));
        events.add(sut("sut.task-terminal", "sut", 20,
                Map.of("taskId", "t1", "state", "SUCCESS", "instance", "workers-3")));
        var o = Assertions.failoverWithin(30).evaluate(events);
        assertFalse(o.passed(), o.detail());
    }

    @Test
    void failoverWithinAcceptsRestartedInstanceAsNewInstance() {
        // 反向用例（T20 判据）：重启事件之后的 workers-3 视为新实例，其回报计入成功
        List<Event> events = new ArrayList<>();
        events.add(sut("sut.task-dispatched", "sut", 1,
                Map.of("taskId", "t1", "attempt", 1, "instance", "workers-3")));
        events.add(sim("sim.fault-injected", "workers-3", 10, Map.of("action", "crash")));
        events.add(sim("sim.worker-instance-restarted", "workers-3", 27, Map.of()));
        events.add(sut("sut.task-dispatched", "sut", 30,
                Map.of("taskId", "t1", "attempt", 2, "instance", "workers-3")));
        events.add(sut("sut.task-terminal", "sut", 35,
                Map.of("taskId", "t1", "state", "SUCCESS", "instance", "workers-3")));
        var o = Assertions.failoverWithin(30).evaluate(events);
        assertTrue(o.passed(), o.detail());
    }

    @Test
    void failoverWithinIgnoresRestartAndFlapFaultEvents() {
        // 锚定 action=crash：restart/flap 的 fault-injected 不得被当作起点
        List<Event> events = new ArrayList<>();
        events.add(sim("sim.fault-injected", "zk", 5, Map.of("action", "registry-flap")));
        events.add(sim("sim.fault-injected", "workers-3", 10, Map.of("action", "crash")));
        events.add(sut("sut.task-dispatched", "sut", 1,
                Map.of("taskId", "t1", "attempt", 1, "instance", "workers-3")));
        events.add(sut("sut.task-dispatched", "sut", 15,
                Map.of("taskId", "t1", "attempt", 2, "instance", "workers-1")));
        events.add(sut("sut.task-terminal", "sut", 25,
                Map.of("taskId", "t1", "state", "SUCCESS", "instance", "workers-1")));
        var o = Assertions.failoverWithin(30).evaluate(events);
        assertTrue(o.passed(), o.detail());
    }

    @Test
    void noTaskLostRequireAllSuccessRejectsFailed() {
        List<Event> events = new ArrayList<>(failoverScript());
        events.add(sut("sut.task-terminal", "sut", 30,
                Map.of("taskId", "t3", "state", "FAILED")));
        events.add(sut("sut.task-dispatched", "sut", 1,
                Map.of("taskId", "t3", "attempt", 1, "instance", "workers-2")));
        var strict = Assertions.noTaskLost(true).evaluate(events);
        assertFalse(strict.passed(), strict.detail());
        // 宽松口径：到达终态即可
        var lenient = Assertions.noTaskLost(false).evaluate(events);
        assertTrue(lenient.passed(), lenient.detail());
    }

    @Test
    void noTaskLostFailsOnMissingTerminal() {
        List<Event> events = failoverScript().stream()
                .filter(e -> !(e.type().equals("sut.task-terminal")
                        && "t2".equals(String.valueOf(e.payload().get("taskId")))))
                .toList();
        var o = Assertions.noTaskLost(true).evaluate(events);
        assertFalse(o.passed(), o.detail());
        assertTrue(o.detail().contains("no terminal state"), o.detail());
    }

    @Test
    void eventSequenceMatchesSubsequence() {
        var o = Assertions.eventSequence(List.of("sim.fault-injected", "sut.task-retry"))
                .evaluate(failoverScript());
        assertTrue(o.passed(), o.detail());
    }

    @Test
    void eventSequenceFailsOnWrongOrder() {
        var o = Assertions.eventSequence(List.of("sut.task-retry", "sim.fault-injected"))
                .evaluate(failoverScript());
        assertFalse(o.passed(), o.detail());
    }

    @Test
    void affectedTasksAtLeastGuardsAgainstEmptyTruth() {
        // 守护：crash 时 workers-3 无在途任务 → 失败（防 failoverWithin 空真）
        List<Event> events = new ArrayList<>();
        events.add(sim("sim.fault-injected", "workers-3", 10, Map.of("action", "crash")));
        events.add(sut("sut.task-dispatched", "sut", 1,
                Map.of("taskId", "t1", "attempt", 1, "instance", "workers-1")));
        var o = Assertions.affectedTasksAtLeast(1).evaluate(events);
        assertFalse(o.passed(), o.detail());

        // 正例：workers-3 持有 1 条
        var ok = Assertions.affectedTasksAtLeast(1).evaluate(failoverScript());
        assertTrue(ok.passed(), ok.detail());
    }

    @Test
    void parserAcceptsYamlShapes() {
        var parsed = AssertionParser.parse(List.of(
                Map.of("failoverWithin", Map.of("seconds", 30)),
                Map.of("noTaskLost", Map.of("requireAllSuccess", true)),
                Map.of("eventSequence", List.of("sim.fault-injected", "sut.task-retry")),
                Map.of("affectedTasksAtLeast", Map.of("min", 1))));
        assertTrue(parsed.size() == 4);
        assertTrue(parsed.get(0).evaluate(failoverScript()).passed());
        assertTrue(parsed.get(3).evaluate(failoverScript()).passed());
    }

    @Test
    void parserRejectsUnknownAssertion() {
        try {
            AssertionParser.parse(List.of(Map.of("bogusAssertion", Map.of())));
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("unknown assertion"));
        }
    }

    @Test
    void parserDefaultsWhenNoParams() {
        var parsed = AssertionParser.parse(List.of(Map.of("noTaskLost", Map.of())));
        // 默认 requireAllSuccess=true
        List<Event> events = new ArrayList<>(failoverScript());
        events.add(sut("sut.task-dispatched", "sut", 1,
                Map.of("taskId", "t9", "attempt", 1, "instance", "workers-1")));
        events.add(sut("sut.task-terminal", "sut", 5,
                Map.of("taskId", "t9", "state", "FAILED")));
        assertFalse(parsed.get(0).evaluate(events).passed());
    }
}
