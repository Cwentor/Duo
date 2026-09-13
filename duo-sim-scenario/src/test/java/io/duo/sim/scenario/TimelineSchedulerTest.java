package io.duo.sim.scenario;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.FaultInjectable;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.core.ScenarioRuntime;
import io.duo.sim.scenario.model.Scenario;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TimelineScheduler} 单测（M1 T16 完成判据）：
 * at 顺序触发、duration 到期自动 clear、注入失败计入 {@link ScenarioResult}（§12 不静默）。
 */
class TimelineSchedulerTest {

    /** 记录型替身：inject/clear/stop 全部落 {@link #calls}，供顺序与自动 clear 观察。 */
    static final class RecordingComponent implements VirtualComponent, FaultInjectable {
        final List<String> calls = new CopyOnWriteArrayList<>();
        private final ComponentId id = new ComponentId("w");

        @Override public ComponentId id() {
            return id;
        }
        @Override public void init(ComponentContext ctx) { }
        @Override public void start() { }
        @Override public void stop(StopMode mode) {
            calls.add("stop:" + mode);
        }
        @Override public void restart() {
            calls.add("restart");
        }
        @Override public HealthReport health() {
            return HealthReport.ok();
        }
        @Override public List<ExposedEndpoint> endpoints() {
            return List.of();
        }
        @Override public void inject(FaultAction action) {
            calls.add("inject:" + action.type());
        }
        @Override public void clear(FaultAction action) {
            calls.add("clear:" + action.type());
        }
    }

    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final ScenarioRuntime runtime = new ScenarioRuntime(events::add);
    private final ScenarioResult result = ScenarioResult.create();

    private RecordingComponent registerStub() {
        RecordingComponent stub = new RecordingComponent();
        runtime.registerTarget("w", new ScenarioRuntime.Target(stub, "w", 1));
        return stub;
    }

    private static Scenario.TimelineEntry entry(String at, String action, String target,
                                                String duration) {
        return new Scenario.TimelineEntry(at, action, target, duration, Map.of());
    }

    /** 轮询等待条件成立（上限 timeoutMs），避免依赖固定 sleep 的抖动。 */
    private static void awaitUntil(long timeoutMs, AtomicBooleanSupplier cond)
            throws InterruptedException {
        awaitUntil(timeoutMs, "", cond);
    }

    /** 轮询等待条件成立（上限 timeoutMs），失败时附带诊断信息。 */
    private static void awaitUntil(long timeoutMs, String description, AtomicBooleanSupplier cond)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.get()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("condition not met within " + timeoutMs + "ms: " + description);
    }

    private interface AtomicBooleanSupplier {
        boolean get();
    }

    // ---------- 判据 1：at 顺序 ----------

    @Test
    void firesInAtOrderNotListOrder() throws Exception {
        RecordingComponent stub = registerStub();
        // 列表序 f1→f2→f3，但 at 升序为 f3→f2→f1：触发顺序必须按 at
        var scheduler = new TimelineScheduler(List.of(
                entry("120ms", "f1", "w", null),
                entry("80ms", "f2", "w", null),
                entry("40ms", "f3", "w", null)), runtime, result);
        scheduler.start();
        awaitUntil(5_000, () -> stub.calls.size() == 3);
        scheduler.close();
        assertEquals(List.of("inject:f3", "inject:f2", "inject:f1"), stub.calls);
    }

    @Test
    void nothingFiresBeforeStart() {
        RecordingComponent stub = registerStub();
        var scheduler = new TimelineScheduler(
                List.of(entry("10ms", "freeze", "w", null)), runtime, result);
        assertTrue(stub.calls.isEmpty(), "仅构造不得触发");
        scheduler.close();
    }

    @Test
    void closeCancelsPendingEntries() throws Exception {
        RecordingComponent stub = registerStub();
        var scheduler = new TimelineScheduler(
                List.of(entry("30ms", "freeze", "w", null)), runtime, result);
        scheduler.start();
        scheduler.close();
        Thread.sleep(150);
        assertTrue(stub.calls.isEmpty(), "场景终止（close）后不得再注入");
    }

    // ---------- 判据 2：duration 到期自动 clear ----------

    @Test
    void durationAutoClearsAndPublishesFaultCleared() throws Exception {
        RecordingComponent stub = registerStub();
        var scheduler = new TimelineScheduler(List.of(
                entry("10ms", "freeze", "w", "40ms")), runtime, result);
        scheduler.start();
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && !stub.calls.contains("clear:freeze")) {
            Thread.sleep(5);
        }
        scheduler.close();
        assertEquals(List.of("inject:freeze", "clear:freeze"), stub.calls);
        // §12 一级事件：注入成功与自动清除都必须可见
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.fault-injected")),
                "missing sim.fault-injected");
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.fault-cleared")),
                () -> "missing sim.fault-cleared, events=" + events);
    }

    @Test
    void lifecycleActionIgnoresDuration() throws Exception {
        RecordingComponent stub = registerStub();
        var scheduler = new TimelineScheduler(List.of(
                entry("10ms", FaultAction.CRASH, "w", "40ms")), runtime, result);
        scheduler.start();
        awaitUntil(5_000, () -> !stub.calls.isEmpty());
        Thread.sleep(150); // 越过 duration 到期点：不得出现 clear
        scheduler.close();
        assertEquals(List.of("stop:CRASH"), stub.calls,
                "crash 为生命周期动作：无 clear 可安排");
        assertTrue(result.snapshot().warnings().stream()
                        .anyMatch(w -> w.contains("lifecycle")),
                "被忽略的 duration 必须记警告，不得静默");
    }

    // ---------- 判据 3：失败计入 ScenarioResult ----------

    @Test
    void injectFailureRecordedInResult() {
        registerStub();
        var scheduler = new TimelineScheduler(List.of(), runtime, result);
        // 目标未注册 → runtime 校验失败（§12 一级事件）+ 计入 result
        scheduler.fire(entry("0ms", "freeze", "ghost", null));
        var failures = result.injectionFailures();
        assertEquals(1, failures.size());
        assertEquals("freeze", failures.get(0).action());
        assertEquals("ghost", failures.get(0).target());
        assertTrue(failures.get(0).reason().contains("unknown target component"));
        assertTrue(events.stream()
                .anyMatch(e -> e.type().equals("sim.fault-inject-failed")));
        assertTrue(!result.passed(), "存在注入失败时场景结果不得判通过");
        scheduler.close();
    }

    @Test
    void badTargetRecordedAsFailureWithoutTouchingRuntime() {
        RecordingComponent stub = registerStub();
        var scheduler = new TimelineScheduler(List.of(), runtime, result);
        scheduler.fire(entry("0ms", "freeze", "w[0]", null)); // 实例下标 1 起（§7.2）
        assertEquals(1, result.injectionFailures().size());
        assertTrue(stub.calls.isEmpty());
        assertTrue(!result.passed());
        scheduler.close();
    }

    @Test
    void malformedAtWarnedAndSkipped() throws Exception {
        RecordingComponent stub = registerStub();
        var scheduler = new TimelineScheduler(List.of(
                entry("15", "freeze", "w", null),   // 无单位：拒绝
                entry("10ms", "freeze", "w", null)), runtime, result);
        scheduler.start();
        awaitUntil(5_000, () -> !stub.calls.isEmpty());
        scheduler.close();
        assertEquals(List.of("inject:freeze"), stub.calls, "坏条目跳过、好条目照常");
        assertTrue(result.snapshot().warnings().stream()
                .anyMatch(w -> w.contains("lacks a unit")));
        assertTrue(result.injectionFailures().isEmpty(), "调度期解析问题记警告而非注入失败");
    }
}
