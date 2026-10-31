package io.duo.sim.control;

import io.duo.sim.kernel.api.Event;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FaultDiagnostics 链重建的合成事件单测（评审 F1，2026-10-30）。
 *
 * <p>背景：dispatch 失败的注入会背靠背发布 {@code sim.fault-injected} +
 * {@code sim.fault-inject-failed}（ScenarioRuntime.inject 的因果顺序——"因"先落流）。
 * 此前分析器把它们拆成"已下达无反应"+"被拒绝"两条链、两个 gap（幽灵链）；
 * 修复后必须折成**单一 REJECTED 链 + 单一 gap**。容器实测复现见
 * docs/code-review-2026-10-30.md §1（freeze 实例级注入被拒的运行）。
 */
class FaultDiagnosticsTest {

    private static Event injected(String source, String action) {
        return Event.sim("sim.fault-injected", source, Map.of("action", action));
    }

    private static Event injectFailed(String source, String action, String reason) {
        return Event.sim("sim.fault-inject-failed", source,
                Map.of("action", action, "reason", reason));
    }

    @Test
    void pairedDispatchFailureFoldsIntoSingleRejectedChain() {
        // 容器实测形态：injected 带实例级 sourceId（workers-2），failed 带组件级 sourceId（workers）
        var report = FaultDiagnostics.analyzeEvents(List.of(
                injected("workers-2", "freeze"),
                injectFailed("workers", "freeze", "dispatch threw: unsupported fault on instance"),
                Event.sut("sut.heartbeat", "sut", Map.of())), null);
        assertEquals(1, report.chains().size(), () -> String.valueOf(report.chains()));
        var chain = report.chains().get(0);
        assertNotNull(chain.failedReason(), "paired failure must mark the chain REJECTED");
        assertTrue(chain.failedReason().contains("unsupported fault"));
        assertEquals(1, report.gaps().size());
        assertTrue(report.gaps().get(0).contains("被拒绝"), String.valueOf(report.gaps()));
        assertFalse(report.gaps().get(0).contains("无反应"),
                "the phantom 'no reaction' gap must be gone");
        assertFalse(report.complete(), "a rejected injection is an incomplete causal chain");
    }

    @Test
    void unpairedPrecheckFailureKeepsItsOwnChain() {
        // precheck 失败只发 failed（无前置 injected）——保持原有独立 REJECTED 链
        var report = FaultDiagnostics.analyzeEvents(List.of(
                injectFailed("workers", "freeze", "unsupported fault: freeze")), null);
        assertEquals(1, report.chains().size());
        assertNotNull(report.chains().get(0).failedReason());
        assertEquals(1, report.gaps().size());
        assertTrue(report.gaps().get(0).contains("被拒绝"));
    }

    @Test
    void adjacentInjectionsAreNotMispaired() {
        // crash A 成功（有 SUT 事实）→ freeze B precheck 失败：failed 不属于 A 的链
        var report = FaultDiagnostics.analyzeEvents(List.of(
                injected("workers-1", "crash"),
                Event.sut("sut.task-retry", "sut", Map.of("taskId", "a")),
                injectFailed("workers", "freeze", "unsupported fault: freeze")), null);
        assertEquals(2, report.chains().size());
        var first = report.chains().get(0);
        assertTrue(first.failedReason() == null && first.sutFactTotal() == 1,
                () -> String.valueOf(first));
        assertTrue(first.complete(), "successful injection with SUT facts must be complete");
        assertTrue(report.chains().get(1).failedReason() != null);
        assertEquals(1, report.gaps().size());
        assertTrue(report.gaps().get(0).contains("被拒绝"));
    }
}
