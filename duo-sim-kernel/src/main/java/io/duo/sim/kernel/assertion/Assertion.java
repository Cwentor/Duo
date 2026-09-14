package io.duo.sim.kernel.assertion;

import io.duo.sim.kernel.api.Event;

import java.util.List;

/**
 * 断言（M1 T20）：对事件流做判定，产出 {@link Outcome}。
 * YAML 内置评估（{@link AssertionEvaluator}）与 JUnit 编程式包装（duo-sim-junit）
 * 共用同一接口与同一事件事实源（§11 双轨分工）。
 */
public interface Assertion {

    /** 断言名（YAML assertions 节的键名，如 failoverWithin）。 */
    String name();

    /** 对事件流评估。 */
    Outcome evaluate(List<Event> events);

    /** 评估结果。 */
    record Outcome(String name, boolean passed, String detail) {
    }
}
