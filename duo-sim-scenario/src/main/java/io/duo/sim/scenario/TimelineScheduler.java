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
    private final HookRegistry hooks;
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
        this(entries, runtime, result, new HookRegistry());
    }

    public TimelineScheduler(List<Scenario.TimelineEntry> entries,
                             ScenarioRuntime runtime, ScenarioResult result,
                             HookRegistry hooks) {
        this.entries = List.copyOf(entries);
        this.runtime = runtime;
        this.result = result;
        this.hooks = hooks;
    }

    /** 以当前时刻为 t0 调度全部时间线动作。 */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        for (Scenario.TimelineEntry e : entries) {
            long delayMs;
            try {
                delayMs = io.duo.sim.kernel.util.Durations.parseMillis(e.at());
            } catch (RuntimeException ex) {
                result.recordWarning("timeline entry '" + e.action() + "': " + ex.getMessage());
                continue;
            }
            pending.add(ticker.schedule(() -> fire(e), delayMs, TimeUnit.MILLISECONDS));
        }
    }

    /** 触发单个动作（包内可见供测试）。 */
    void fire(Scenario.TimelineEntry e) {
        // custom-hook（T22）：target 可为 SUT（§7.2 豁免），不经 ScenarioRuntime 寻址
        if ("custom-hook".equals(e.action())) {
            var r = hooks.execute(e.target(), e.params(), ev -> {
                // hook 事件经 ScenarioRuntime 的 recorder 汇入统一事件流
                runtime.emitExternal(ev);
            });
            if (!r.success()) {
                result.recordInjectionFailure("custom-hook", e.target(), r.reason());
            }
            return;
        }
        String target = e.target();
        io.duo.sim.kernel.api.FaultAction.ComponentAddress addr;
        try {
            addr = io.duo.sim.kernel.api.FaultAction.ComponentAddress.parse(target);
        } catch (IllegalArgumentException ex) {
            result.recordInjectionFailure(e.action(), target,
                    "unparseable target: " + ex.getMessage());
            return;
        }
        Long durationMs = null;
        if (e.duration() != null && !e.duration().isBlank()) {
            try {
                durationMs = io.duo.sim.kernel.util.Durations.parseMillis(e.duration());
            } catch (RuntimeException ex) {
                result.recordWarning("timeline entry '" + e.action() + "': " + ex.getMessage());
            }
        }
        boolean lifecycle = FaultAction.isLifecycle(e.action());
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
            try {
                pending.add(ticker.schedule(() -> {
                    var c = runtime.clear(action);
                    if (!c.success()) {
                        result.recordInjectionFailure(e.action(), target,
                                "auto-clear failed: " + c.reason());
                    }
                }, durationMs, TimeUnit.MILLISECONDS));
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                // 防御性收敛（评审 P3，2026-10-30）：close() 之后 ticker 已 shutdown，
                // 此刻到达的 fire 若带 duration 会让 auto-clear 排程失败——显式记失败
                // 而不是被 executor 静默吞掉（当前编排下 fire 由 ticker 线程自调，
                // 与 close 竞态仅在场景终止窗口内可达，但"不静默"不区分可达性）。
                result.recordInjectionFailure(e.action(), target,
                        "timeline closed before auto-clear could be scheduled");
            }
        }
    }

    /**
     * 停止调度（场景结束/关闭时）；已排定未触发的动作被取消——场景终止后不再注入。
     * 不复位 {@code started}（评审 P3，2026-10-30）：复位会让已关闭的调度器可被
     * {@code start()} 重新 CAS 拉起并**重放全部时间线**（防御性收敛，当前编排下
     * TimelineScheduler 随引擎一次性使用，不构成可达路径）。
     */
    @Override
    public void close() {
        pending.forEach(f -> f.cancel(false));
        pending.clear();
        ticker.shutdownNow();
    }

    /** 已排定未触发动作计数（测试用）。 */
    public int pendingCount() {
        return pending.size();
    }
}
