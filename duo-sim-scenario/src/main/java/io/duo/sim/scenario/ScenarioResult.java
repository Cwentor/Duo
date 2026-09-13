package io.duo.sim.scenario;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 最小场景结果模型（M1 计划 T16/T20 共用，不各写一份）：
 * 注入失败（§12 一级事件同步记入）+ 断言评估结果（T20 写入）+ 警告。
 * 引擎 {@code result()} 暴露收集器本体，场景结束经 {@link #snapshot()} 固化只读视图。
 */
public final class ScenarioResult {

    /** 注入失败记录。 */
    public record InjectionFailure(String action, String target, String reason, Instant at) {
    }

    /** 断言评估记录（T20 断言库写入）。 */
    public record AssertionOutcome(String name, boolean passed, String detail) {
    }

    private final CopyOnWriteArrayList<InjectionFailure> injectionFailures =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AssertionOutcome> assertions =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> warnings = new CopyOnWriteArrayList<>();

    public void recordInjectionFailure(String action, String target, String reason) {
        injectionFailures.add(new InjectionFailure(action, target, reason, Instant.now()));
    }

    public void recordAssertion(String name, boolean passed, String detail) {
        assertions.add(new AssertionOutcome(name, passed, detail));
    }

    public void recordWarning(String warning) {
        warnings.add(warning);
    }

    /** 快照（场景结束固化）。 */
    public Snapshot snapshot() {
        return new Snapshot(List.copyOf(injectionFailures), List.copyOf(assertions),
                List.copyOf(warnings));
    }

    /** 场景是否通过：无注入失败、无断言失败（§12：注入失败计入场景结果）。 */
    public boolean passed() {
        return injectionFailures.isEmpty() && assertions.stream().allMatch(AssertionOutcome::passed);
    }

    public record Snapshot(List<InjectionFailure> injectionFailures,
                           List<AssertionOutcome> assertions,
                           List<String> warnings) {

        public boolean passed() {
            return injectionFailures.isEmpty() && assertions.stream().allMatch(AssertionOutcome::passed);
        }
    }

    /** 新建结果收集器（引擎内部用）。 */
    public static ScenarioResult create() {
        return new ScenarioResult();
    }

    // 占位避免误用：快照之外不可变视图
    public List<InjectionFailure> injectionFailures() {
        return List.copyOf(injectionFailures);
    }
}
