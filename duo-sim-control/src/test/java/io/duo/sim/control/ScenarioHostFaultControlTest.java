package io.duo.sim.control;

import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.FaultAction;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M10 计划一 Task 1：手动清除注入与事件丢弃计数的控制面通路（薄委托，零内核改动）。 */
class ScenarioHostFaultControlTest {

    private static FaultAction freezeWorkers1() {
        // freeze/slow/resource-exhaust 是**组件级**动作（内核拒绝实例寻址），用 ComponentAddress.of
        return new FaultAction("freeze",
                FaultAction.ComponentAddress.of(new ComponentId("workers")),
                Map.of(), null);
    }

    @Test
    void clearFailsLoudlyWhenNoScenarioRunning() {
        try (ScenarioHost host = new ScenarioHost()) {
            var r = host.clear(freezeWorkers1());
            assertFalse(r.success());
            assertEquals("scenario not running", r.reason());
        }
    }

    @Test
    void injectedFreezeCanBeClearedManually() throws Exception {
        String yaml = """
                name: host-clear-smoke
                topology:
                  - id: zk
                    contract: registry
                    tier: virtual
                  - id: master
                    contract: scheduler
                    tier: virtual
                    sut: true
                    launch: { mode: in-process, main: io.duo.sim.control.testfixture.ControlFixtureSut }
                    config: { dag.tasks: "a,b" }
                    exposes: [{ contract: scheduler, port: 0 }]
                    wiring:
                      registry: { node: zk, contract: registry }
                  - id: workers
                    contract: worker
                    tier: virtual
                    count: 2
                    capacity: { slots: 1 }
                    wiring:
                      registry: { node: zk, contract: registry }
                behaviors:
                  profiles:
                    default: { duration: 3s, jitter: 0.0, successRate: 1.0 }
                  bindings:
                    - node: workers
                      profile: default
                timeline: []
                assertions:
                  - noTaskLost: { requireAllSuccess: true }
                """;
        Path yamlFile = Files.createTempFile("host-clear-smoke-", ".yaml");
        Files.writeString(yamlFile, yaml, StandardCharsets.UTF_8);
        try (ScenarioHost host = new ScenarioHost()) {
            host.start(yamlFile);
            long deadline = System.currentTimeMillis() + 10_000;
            while (!host.isRunning() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(host.isRunning(), "scenario should be running within 10s");

            // 组件注册可能晚于 start 返回：对 "unknown target" 类失败做有界重试
            io.duo.sim.kernel.core.ScenarioRuntime.InjectionResult injected =
                    new io.duo.sim.kernel.core.ScenarioRuntime.InjectionResult(false, "not attempted");
            for (int i = 0; i < 20 && !injected.success(); i++) {
                injected = host.inject(freezeWorkers1());
                if (!injected.success()) {
                    Thread.sleep(100);
                }
            }
            assertTrue(injected.success(), "inject freeze should succeed: " + injected.reason());

            var cleared = host.clear(freezeWorkers1());
            assertTrue(cleared.success(), "clear should succeed: " + cleared.reason());
            assertEquals(0L, host.droppedEvents(), "no drops under normal operation");
        } finally {
            Files.deleteIfExists(yamlFile);
        }
    }
}
