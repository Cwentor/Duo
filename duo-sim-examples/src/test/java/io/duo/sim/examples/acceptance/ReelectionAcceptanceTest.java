package io.duo.sim.examples.acceptance;

import io.duo.sim.junit.DuoAssertions;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.scenario.ScenarioEngine;
import io.duo.sim.scenario.ScenarioLoader;
import io.duo.sim.scenario.model.Scenario;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 验收（设计文档 §14 M2 行 / 计划 T31）：**注册中心闪断 5s → 重新选主且无任务丢失**。
 *
 * <p>拓扑：embedded registry（Curator TestingServer，真实 ZK）+ master（real SUT，
 * wire 路径真实 Curator 客户端）+ workers（virtual，direct 门面）——双连接路径并存（D1/D1b）。
 * 剧本：DAG 运行中 registry-flap → 整服闪断 → SUT 客户端重连并重新注册 →
 * 发布 sut.leader-elected（epoch 递增）→ 任务不受影响继续到 SUCCESS。
 */
class ReelectionAcceptanceTest {

    @Test
    void registryFlapTriggersReelectionWithoutTaskLoss() throws Exception {
        Scenario scenario;
        try (InputStream in = getClass().getResourceAsStream(
                "/scenarios/m2-reelection-acceptance.yaml")) {
            assertNotNull(in, "M2 golden scenario must exist");
            scenario = ScenarioLoader.load(in);
        }
        var registry = ContractRegistry.loadFromServiceLoader();
        try (ScenarioEngine engine = ScenarioEngine.validated(scenario, registry)) {
            engine.startSut();
            engine.startComponents();

            assertTrue(engine.awaitSutExit(120_000), "SUT did not exit within 120s");
            engine.stop(); // 清理 + scenario-finished + 断言评估

            List<Event> events = engine.events();
            String types = events.stream().map(Event::type).distinct()
                    .reduce((a, b) -> a + "," + b).orElse("(none)");

            // ---- JUnit 编程式断言（双轨之一）----
            DuoAssertions.assertThat(events)
                    .check(io.duo.sim.kernel.assertion.Assertions.masterReelectedWithin(30))
                    .noTaskLostAllSuccess()
                    .eventSequence("sim.fault-injected", "sut.leader-elected");

            // ---- YAML 内置评估（双轨之二）----
            var snapshot = engine.result().snapshot();
            assertTrue(snapshot.injectionFailures().isEmpty(),
                    "no injection failure expected: " + snapshot.injectionFailures());
            assertEquals(3, snapshot.assertions().size(),
                    "three assertions must be evaluated: " + snapshot.assertions());
            snapshot.assertions().forEach(a -> assertTrue(a.passed(),
                    "assertion '" + a.name() + "' failed: " + a.detail()));

            // ---- 关键事实：真实 ZK 闪断与 SUT 自愈 ----
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.fault-injected")
                            && "registry-flap".equals(e.payload().get("action"))),
                    () -> "no registry-flap injection: " + types);
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.registry-flap-started")),
                    () -> "no flap-started (embedded restart): " + types);
            // SUT 在闪断后重新注册并发布 leader-elected（epoch 递增证明是"重新"选主）
            var elections = events.stream()
                    .filter(e -> e.type().equals("sut.leader-elected"))
                    .toList();
            assertTrue(elections.size() >= 2,
                    () -> "expected initial + post-flap leader-elected, got " + elections.size()
                            + ": " + types);
            assertTrue(elections.stream().anyMatch(e -> "zk".equals(e.payload().get("mode"))),
                    () -> "SUT must use the wire (real ZK client) path: " + elections);
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.dag-terminal")),
                    () -> "DAG must complete despite flap: " + types);

            // ---- 录制落盘（§11 审查材料）----
            assertTrue(java.nio.file.Files.exists(engine.recordingPath()),
                    "recording must be written");
        }
    }
}
