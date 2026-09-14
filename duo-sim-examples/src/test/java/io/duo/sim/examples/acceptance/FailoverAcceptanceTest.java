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
 * M1 验收（计划 §1/T23）：完整 §8 金标准 YAML（含 timeline）运行——
 * {@code crash workers[3]} @10s → 任务 30s 内转移成功，且为非空真通过。
 *
 * <p>断言双轨（§11）：YAML 内置评估（ScenarioResult）+ JUnit 编程式（DuoAssertions）
 * 共用同一事件事实源；两者结果必须一致。
 */
class FailoverAcceptanceTest {

    @Test
    void m1GoldenScenarioPassesWithTimelineDrivenFailover() throws Exception {
        Scenario scenario;
        try (InputStream in = getClass().getResourceAsStream("/scenarios/m1-failover-acceptance.yaml")) {
            assertNotNull(in, "golden scenario resource must exist");
            scenario = ScenarioLoader.load(in);
        }
        var registry = ContractRegistry.loadFromServiceLoader();
        try (ScenarioEngine engine = ScenarioEngine.validated(scenario, registry)) {
            // 顺序：SUT 先（注册端点）→ 组件（workers 发现拨号）；时间线在 startComponents 内启动
            engine.startSut();
            engine.startComponents();

            // 时间线：crash@10s → 重派发等首批终态（15s）→ 重派发跑 15s → DAG 终态 ~30s
            // restart@27s、断言窗口 30s（死线 T+40s）——给足余量
            assertTrue(engine.awaitSutExit(90_000), "SUT did not exit within 90s");
            engine.stop(); // 触发统一清理 + scenario-finished + 断言评估

            List<Event> events = engine.events();
            String types = events.stream().map(Event::type).distinct()
                    .reduce((a, b) -> a + "," + b).orElse("(none)");

            // ---- JUnit 编程式断言（双轨之一）----
            DuoAssertions.assertThat(events)
                    .affectedTasksAtLeast(1)
                    .failoverWithin(30)
                    .noTaskLostAllSuccess()
                    .eventSequence("sim.fault-injected", "sut.task-retry");

            // ---- YAML 内置评估（双轨之二）：结果必须与 JUnit 一致且全过 ----
            var result = engine.result();
            var snapshot = result.snapshot();
            assertTrue(snapshot.injectionFailures().isEmpty(),
                    "no injection failure expected: " + snapshot.injectionFailures());
            assertEquals(4, snapshot.assertions().size(),
                    "all four assertions must be evaluated: " + snapshot.assertions());
            snapshot.assertions().forEach(a -> assertTrue(a.passed(),
                    "assertion '" + a.name() + "' failed: " + a.detail()));
            assertTrue(result.passed(), "scenario result must pass");

            // ---- 事件流关键事实（诊断可读）----
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.fault-injected")
                            && "crash".equals(e.payload().get("action"))),
                    () -> "no crash injection: " + types);
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.failover")),
                    () -> "no failover fact: " + types);
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.registry-flap-started")),
                    () -> "no registry flap: " + types);
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.registry-flap-cleared")),
                    () -> "no flap clear: " + types);
            assertTrue(events.stream().anyMatch(e ->
                            e.type().equals("sim.worker-instance-restarted")
                                    && "workers-3".equals(e.sourceId())),
                    () -> "no workers-3 restart: " + types);
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.dag-terminal")),
                    () -> "no dag-terminal: " + types);

            // ---- 录制落盘（T21）----
            // 录制是审查材料（§11：真实时钟不承诺逐字节重放），故断言"关键事实可回读"
            // 而非与内存流严格等量（stop 后的收尾事件可能晚于 flush 边界）
            var recording = engine.recordingPath();
            assertNotNull(recording);
            assertTrue(java.nio.file.Files.exists(recording),
                    "event recording must be written: " + recording);
            var back = io.duo.sim.scenario.EventRecorder.readBack(recording);
            assertTrue(back.size() >= 15, "recording should have substantive content: "
                    + back.size());
            var recordedTypes = back.stream().map(m -> String.valueOf(m.get("type")))
                    .distinct().toList();
            assertTrue(recordedTypes.contains("sim.scenario-started"),
                    "recording must contain scenario start: " + recordedTypes);
            assertTrue(recordedTypes.contains("sim.fault-injected"),
                    "recording must contain the crash injection: " + recordedTypes);
            assertTrue(recordedTypes.contains("sut.failover"),
                    "recording must contain the failover fact: " + recordedTypes);
            assertTrue(recordedTypes.contains("sut.task-retry"),
                    "recording must contain the retry fact: " + recordedTypes);
            assertTrue(recordedTypes.contains("sim.scenario-finished"),
                    "recording must contain scenario finish: " + recordedTypes);
        }
    }
}
