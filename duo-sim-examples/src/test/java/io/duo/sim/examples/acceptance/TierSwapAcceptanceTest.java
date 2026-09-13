package io.duo.sim.examples.acceptance;

import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.scenario.ScenarioEngine;
import io.duo.sim.scenario.ScenarioLoader;
import io.duo.sim.scenario.model.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M0 档位切换验收（§13/计划 T15）：同一测试体对两份仅 workers.tier 不同的拓扑运行——
 * virtual（VirtualWorker）↔ real（demo real worker），测试代码零改动。
 *
 * <p>通过条件（计划 §1）：场景启动成功、DAG 全部任务终态、失败任务重试事实 ≥1、
 * 事件流含 sim.scenario-finished、无注入失败/停止失败事件。
 */
class TierSwapAcceptanceTest {

    @ParameterizedTest(name = "workers tier = {0}")
    @ValueSource(strings = {"virtual", "real"})
    void sameTopologySwappingWorkerTierRunsGreen(String tier) throws Exception {
        String resource = "/scenarios/m0-acceptance-" + tier + "-workers.yaml";
        Scenario scenario;
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            scenario = ScenarioLoader.load(in);
        }
        var registry = ContractRegistry.loadFromServiceLoader();
        try (ScenarioEngine engine = ScenarioEngine.validated(scenario, registry)) {
            // SUT 先启动（注册端点进 registry）→ 内核组件后启动（发现等待语义不依赖时序）
            engine.startSut();
            engine.startComponents();

            // SUT 跑完 DAG 即 run() 返回 → sut.exited → 场景结束信号
            assertTrue(engine.awaitSutExit(60_000), "SUT did not exit within 60s: " + tier);
            engine.stop(); // 触发统一清理 + sim.scenario-finished
            Thread.sleep(200); // 让终态事件落定

            List<Event> events = engine.events();
            String types = events.stream().map(Event::type).distinct()
                    .reduce((a, b) -> a + "," + b).orElse("(none)");

            // 场景生命周期
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.scenario-started")),
                    () -> "no scenario-started: " + types);
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.scenario-finished")),
                    () -> "no scenario-finished: " + types);

            // SUT 事实：派发、重试（unstable-task 三次尝试）、终态
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.task-dispatched")),
                    () -> "no dispatch: " + types);
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.task-retry")),
                    () -> "no retry fact: " + types);
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.task-terminal")),
                    () -> "no terminal facts: " + types);
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.dag-terminal")),
                    () -> "no dag-terminal: " + types);

            // 不允许注入失败/停止失败（§12 不静默）
            assertTrue(events.stream().noneMatch(e ->
                            e.type().equals("sim.fault-inject-failed")),
                    () -> "unexpected inject-failed: " + types);

            // 换档证据：worker 实例事件存在——virtual 档为 sim.worker-instance-*（sourceId
            // workers-N，§7.4），real 档（demo real worker 报文走 SUT 事件）为 sut.worker-registered
            boolean virtualEvidence = events.stream().anyMatch(e ->
                    e.type().equals("sim.worker-instance-crashed")
                            || (e.sourceId() != null && e.sourceId().startsWith("workers-")));
            boolean realEvidence = events.stream().anyMatch(e ->
                    e.type().equals("sut.worker-registered"));
            assertTrue(tier.equals("real") ? realEvidence : virtualEvidence,
                    () -> "no per-instance worker evidence for tier=" + tier
                            + ": types=" + types);
        }
    }

    @Test
    void validationRejectsBadScenario() throws Exception {
        // 复用加载器做负例： sut 缺失
        String yaml = """
                name: bad
                topology:
                  - id: zk
                    contract: registry
                    tier: virtual
                """;
        var scenario = ScenarioLoader.load(new java.io.ByteArrayInputStream(
                yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var report = new io.duo.sim.scenario.ScenarioValidator(
                ContractRegistry.loadFromServiceLoader()).validate(scenario);
        assertTrue(!report.ok());
        assertTrue(String.join(";", report.errors()).contains("exactly one node"));
    }
}
