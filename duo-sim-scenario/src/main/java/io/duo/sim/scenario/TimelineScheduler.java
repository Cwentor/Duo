package io.duo.sim.scenario;

import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.core.ScenarioRuntime;
import io.duo.sim.scenario.model.Scenario;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 时间线执行器（M1 计划 T16）：场景 start 时刻为 t0，按 {@code at} 相对延时
 * 经既有 {@link ScenarioRuntime} 通路触发动作；带 {@code duration} 的动作到期自动 clear
 * （§7.2）。串行单线程节拍器模型（M0 单 JVM 足够）；注入失败记入 {@link ScenarioResult}
 * 并同步产生 {@code sim.fault-inject-failed} 一级事件（§12 不静默）。
 *
 * <p>约束：crash/restart 生命周期动作带 duration 无意义（不可"清除"），触发时记警告
 * 不安排 clear。
 */
public final class TimelineScheduler implements AutoCloseable {

    private final ScenarioRuntime runtime;
    private final ScenarioResult result;
    private final List<Scenario.TimelineEntry> entries;
    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "duo-timeline");
                t.setDaemon(true);
                return t;
            });
    private final List<ScheduledFuture<?>> pending = new CopyOnWriteArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();

    public TimelineScheduler(List<Scenario.TimelineEntry> entries,
                             ScenarioRuntime runtime, ScenarioResult result) {
        this.entries = List.copyOf(entries);
        this.runtime = runtime;
        this.result = result;
    }

    /** 以当前时刻为 t0 调度全部时间线动作。 */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        for (Scenario.TimelineEntry e : entries) {
            long delayMs;
            try {
                delayMs = Durations.parseMillis(e.at());
            } catch (RuntimeException ex) {
                result.recordWarning("timeline entry '" + e.action() + "': " + ex.getMessage());
                continue;
            }
            pending.add(ticker.schedule(() -> fire(e), delayMs, TimeUnit.MILLISECONDS));
        }
    }

    /** 触发单个动作（包内可见供测试）。 */
    void fire(Scenario.TimelineEntry e) {
        String target = e.target();
        var addr = parseTarget(target);
        if (addr == null) {
            result.recordInjectionFailure(e.action(), target,
                    "unparseable target: " + target);
            return;
        }
        Long durationMs = null;
        if (e.duration() != null && !e.duration().isBlank()) {
            try {
                durationMs = Durations.parseMillis(e.duration());
            } catch (RuntimeException ex) {
                result.recordWarning("timeline entry '" + e.action() + "': " + ex.getMessage());
            }
        }
        boolean lifecycle = FaultAction.CRASH.equals(e.action())
                || FaultAction.RESTART.equals(e.action());
        if (durationMs != null && lifecycle) {
            result.recordWarning("action '" + e.action()
                    + "' is lifecycle — duration ignored (nothing to clear)");
            durationMs = null;
        }
        var action = new FaultAction(e.action(), addr, e.params() == null ? Map.of()
                : e.params(), durationMs);
        var r = runtime.inject(action);
        if (!r.success()) {
            result.recordInjectionFailure(e.action(), target, r.reason());
            return;
        }
        if (durationMs != null) {
            pending.add(ticker.schedule(() -> {
                var c = runtime.clear(action);
                if (!c.success()) {
                    result.recordInjectionFailure(e.action(), target,
                            "auto-clear failed: " + c.reason());
                }
            }, durationMs, TimeUnit.MILLISECONDS));
        }
    }

    /** 解析 {@code componentId} 或 {@code componentId[N]}（索引 1 起，§7.2）。 */
    static FaultAction.ComponentAddress parseTarget(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        int bracket = target.indexOf('[');
        if (bracket < 0) {
            return FaultAction.ComponentAddress.of(
                    new io.duo.sim.kernel.api.ComponentId(target.trim()));
        }
        if (!target.endsWith("]")) {
            return null;
        }
        try {
            int idx = Integer.parseInt(target.substring(bracket + 1, target.length() - 1));
            if (idx < 1) {
                return null;
            }
            return FaultAction.ComponentAddress.ofInstance(
                    new io.duo.sim.kernel.api.ComponentId(target.substring(0, bracket)), idx);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 停止调度（场景结束/关闭时）；已排定未触发的动作被取消——场景终止后不再注入。 */
    @Override
    public void close() {
        pending.forEach(f -> f.cancel(false));
        pending.clear();
        ticker.shutdownNow();
        started.set(false);
    }

    /** 已排定未触发动作计数（测试用）。 */
    public int pendingCount() {
        return pending.size();
    }
}
