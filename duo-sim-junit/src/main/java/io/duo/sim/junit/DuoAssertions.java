package io.duo.sim.junit;

import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.assertion.Assertion;

import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * JUnit 编程式断言包装（M1 T20）：把 kernel 的 {@link Assertion} 结果转成 JUnit 断言，
 * 与 YAML 内置评估共用同一事件事实源（§11 双轨分工）。
 *
 * <p>用法：
 * <pre>{@code
 * DuoAssertions.assertThat(engine.events())
 *         .failoverWithin(30)
 *         .noTaskLostAllSuccess();
 * }</pre>
 */
public final class DuoAssertions {

    private final List<Event> events;

    private DuoAssertions(List<Event> events) {
        this.events = List.copyOf(events);
    }

    public static DuoAssertions assertThat(List<Event> events) {
        return new DuoAssertions(events);
    }

    /** failoverWithin（秒）：crash 后窗口内转移成功。 */
    public DuoAssertions failoverWithin(long seconds) {
        return check(io.duo.sim.kernel.assertion.Assertions.failoverWithin(seconds));
    }

    /** noTaskLost：M1 口径＝全部 SUCCESS。 */
    public DuoAssertions noTaskLostAllSuccess() {
        return check(io.duo.sim.kernel.assertion.Assertions.noTaskLost(true));
    }

    /** noTaskLost：宽松口径＝全部到达终态。 */
    public DuoAssertions noTaskLostTerminalOnly() {
        return check(io.duo.sim.kernel.assertion.Assertions.noTaskLost(false));
    }

    /** eventSequence：类型子序列按序出现。 */
    public DuoAssertions eventSequence(String... types) {
        return check(io.duo.sim.kernel.assertion.Assertions.eventSequence(List.of(types)));
    }

    /** affectedTasksAtLeast：crash 时受影响任务数下限（防空真）。 */
    public DuoAssertions affectedTasksAtLeast(int min) {
        return check(io.duo.sim.kernel.assertion.Assertions.affectedTasksAtLeast(min));
    }

    /** 自定义断言（扩展点）。 */
    public DuoAssertions check(Assertion assertion) {
        var outcome = assertion.evaluate(events);
        if (!outcome.passed()) {
            fail("assertion '" + outcome.name() + "' failed: " + outcome.detail());
        }
        return this;
    }
}
