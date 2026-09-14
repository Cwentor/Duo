package io.duo.sim.examples.acceptance;

import io.duo.sim.junit.VirtualCluster;
import io.duo.sim.junit.VirtualClusterExtension;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.scenario.ScenarioEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T30 单测：@VirtualCluster 扩展——加载/启动/注入/断言评估/清理全链路。
 *
 * <p>放在 examples 模块（而非 duo-sim-junit 自身）：扩展测试需要 DemoScheduler 与
 * examples 的场景资源，而 examples 依赖 junit 模块——把测试放这里避免模块循环依赖。
 */
@ExtendWith(VirtualClusterExtension.class)
@VirtualCluster(value = "/scenarios/junit-extension-smoke.yaml", sutExitTimeoutMs = 60_000)
class VirtualClusterExtensionTest {

    @Test
    void engineIsInjectedAndScenarioRanToCompletion(ScenarioEngine engine) {
        assertNotNull(engine, "engine must be injected by the extension");
        List<Event> events = engine.events();
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.scenario-started")),
                "scenario must have started");
        // 场景已在 beforeEach 里 awaitSutExit → DAG 已终态
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.dag-terminal")),
                "DAG must have reached terminal states before the test body");
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.task-terminal")),
                "tasks must report terminal states");
    }

    @Test
    void assertionEvaluationRunsOnCleanup(ScenarioEngine engine) {
        // afterEach 会评估 YAML assertions（noTaskLost）并在失败时让测试失败；
        // 这里验证录制路径可用（afterEach 的审查材料输出）
        assertNotNull(engine.recordingPath());
    }
}
