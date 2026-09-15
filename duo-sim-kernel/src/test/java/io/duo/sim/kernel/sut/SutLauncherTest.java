package io.duo.sim.kernel.sut;

import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.SutMain;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SutLauncherTest {

    private final List<Event> events = new ArrayList<>();

    @Test
    void normalExitEmitsSutExited() throws Exception {
        SutMain main = ctx -> {
            ctx.ready();
            ctx.events().publish("sut.task-terminal", Map.of("taskId", "t-1"));
            // run() 正常返回
        };
        var launcher = new SutLauncher("m", main, Map.of(), Map.of(),
                Map.of("scheduler", "127.0.0.1:9"), events::add, null);
        launcher.start();
        assertTrue(launcher.stop());
        // 轮询退出态
        for (int i = 0; i < 50; i++) {
            if (launcher.exitState() != null) {
                break;
            }
            Thread.sleep(20);
        }
        assertEquals(true, launcher.exitState().normal());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.exited")));
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.task-terminal")));
    }

    @Test
    void crashEmitsSutCrashed() {
        SutMain main = ctx -> {
            ctx.ready();
            throw new IllegalStateException("boom");
        };
        var launcher = new SutLauncher("m", main, Map.of(), Map.of(),
                Map.of(), events::add, null);
        launcher.start();
        // run() 抛异常 → sut.crashed；退出态 normal=false
        for (int i = 0; i < 50 && launcher.exitState() == null; i++) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertFalse(launcher.exitState().normal());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.crashed")));
    }

    @Test
    void readyTimeoutThrowsStartFailure() {
        SutMain main = ctx -> {
            // 永不 ready，阻塞
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var launcher = new SutLauncher("m", main, Map.of(), Map.of(), Map.of(),
                events::add, null, 200); // ready 超时 200ms
        var e = assertThrows(ComponentException.class, launcher::start);
        assertTrue(e.getMessage().contains("ready timeout"));
    }

    /**
     * run() 在 ready 前**正常返回**：须快速失败并报真实根因，而非等满 ready 超时。
     * 回归防护：修复曾删掉 finally 的无条件 countDown，使该路径退化为 60s 超时。
     */
    @Test
    void runReturningBeforeReadyFailsFastWithRealCause() {
        SutMain main = ctx -> {
            // 不调 ctx.ready()，直接返回
        };
        var launcher = new SutLauncher("m", main, Map.of(), Map.of(), Map.of(),
                events::add, null, 5_000);
        long t0 = System.nanoTime();
        var e = assertThrows(ComponentException.class, launcher::start);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(e.getMessage().contains("exited before ready"),
                "真实根因应为 exited before ready，实际：" + e.getMessage());
        assertTrue(elapsedMs < 2_000, "须快速失败（实测 " + elapsedMs + "ms），不得等满 ready 超时");
    }

    /** run() 在 ready 前**抛异常**：同样快速失败，且根因含崩溃信息。 */
    @Test
    void crashBeforeReadyFailsFastWithCrashReason() {
        SutMain main = ctx -> {
            throw new IllegalStateException("early-boom");
        };
        var launcher = new SutLauncher("m", main, Map.of(), Map.of(), Map.of(),
                events::add, null, 5_000);
        long t0 = System.nanoTime();
        var e = assertThrows(ComponentException.class, launcher::start);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(e.getMessage().contains("exited before ready"), e.getMessage());
        assertTrue(e.getMessage().contains("early-boom"), "根因须含崩溃信息：" + e.getMessage());
        assertTrue(elapsedMs < 2_000, "须快速失败（实测 " + elapsedMs + "ms）");
    }

    /**
     * ready 已确认后立即崩溃**不是**启动失败（§12：SUT 崩溃属测试结果）。
     * 循环多轮以暴露 JIT 预热后才显现的竞态——修复前该场景误判率约 71%（长生命周期 JVM）。
     */
    @Test
    void readyThenImmediateCrashIsNotStartupFailure() throws Exception {
        int iterations = 3_000;
        for (int i = 0; i < iterations; i++) {
            SutMain main = ctx -> {
                ctx.ready();
                throw new IllegalStateException("boom-after-ready");
            };
            var launcher = new SutLauncher("m", main, Map.of(), Map.of(), Map.of(),
                    events::add, null, 5_000);
            try {
                launcher.start(); // ready 已确认 → 不得抛启动失败
            } catch (ComponentException ex) {
                throw new AssertionError("第 " + i + " 轮误判为启动失败：" + ex.getMessage(), ex);
            }
            // 崩溃事实仍须经 exitState 传达（run() 线程与 start() 并发，故限时轮询——
            // 全仓回归的高负载 JVM 下自旋预算会早于线程调度耗尽，须按时间而非迭代数等待）
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (launcher.exitState() == null && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertNotNull(launcher.exitState(), "第 " + i + " 轮：崩溃未反映到 exitState");
            assertFalse(launcher.exitState().normal());
        }
    }

    @Test
    void cooperativeStopRunsHandler() throws Exception {
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        SutMain main = ctx -> {
            ctx.onStop(() -> stopped.set(true)); // 协作式停止
            ctx.ready();
            while (!stopped.get()) {
                Thread.sleep(10);
            }
        };
        var launcher = new SutLauncher("m", main, Map.of(), Map.of(), Map.of(),
                events::add, null);
        launcher.start();
        assertTrue(launcher.stop());
        assertTrue(stopped.get());
        assertTrue(launcher.exitState().normal());
    }

    @Test
    void missingStopHandlerFallsBackToInterrupt() {
        SutMain main = ctx -> {
            ctx.ready();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 正常响应中断退出 → exited
            }
        };
        var launcher = new SutLauncher("m", main, Map.of(), Map.of(), Map.of(),
                events::add, null);
        launcher.start();
        assertTrue(launcher.stop()); // 未注册 handler → interrupt 兜底
        assertTrue(launcher.exitState().normal()); // run() 响应中断，返回＝exited
    }

    @Test
    void sutPublisherEnforcesPrefix() {
        SutMain main = ctx -> {
            ctx.ready();
            assertThrows(IllegalArgumentException.class,
                    () -> ctx.events().publish("bogus-event", Map.of()));
        };
        var launcher = new SutLauncher("m", main, Map.of(), Map.of(), Map.of(),
                events::add, null);
        launcher.start();
        assertTrue(launcher.stop());
    }
}
