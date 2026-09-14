package io.duo.sim.control;

import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.FaultAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T32 单测：控制面适配层（零内核改动前提下驱动既有引擎 API）。 */
class ScenarioHostTest {

    /** 快速收敛的场景（短时长，便于测试）。 */
    private static final String FAST_SCENARIO = """
            name: control-host-smoke
            topology:
              - id: zk
                contract: registry
                tier: virtual
              - id: master
                contract: scheduler
                tier: real
                sut: true
                launch: { mode: in-process, main: io.duo.sim.examples.scheduler.DemoScheduler }
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
                default: { duration: 200ms, jitter: 0.0, successRate: 1.0 }
              bindings:
                - node: workers
                  profile: default
            timeline: []
            assertions:
              - noTaskLost: { requireAllSuccess: true }
            """;

    private Path writeScenario(@TempDir Path tmp, String yaml) throws Exception {
        Path f = tmp.resolve("scenario.yaml");
        Files.writeString(f, yaml, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void startRunsScenarioAndReportsStatus(@TempDir Path tmp) throws Exception {
        Path f = writeScenario(tmp, FAST_SCENARIO);
        try (var host = new ScenarioHost()) {
            var st = host.start(f);
            assertEquals("RUNNING", st.get("state"));
            assertEquals("control-host-smoke", st.get("scenario"));
            assertTrue(host.isRunning());

            var fin = host.awaitFinish(60_000);
            assertEquals("FINISHED", fin.get("state"),
                    () -> "assertion failures: " + fin.get("assertions"));
            assertEquals(Boolean.TRUE, fin.get("passed"));
            assertNotNull(fin.get("recording"));
        }
    }

    @Test
    void eventsIncrementalCursorDoesNotRepeatOrSkip(@TempDir Path tmp) throws Exception {
        Path f = writeScenario(tmp, FAST_SCENARIO);
        try (var host = new ScenarioHost()) {
            host.start(f);
            // 第一轮：拿到 seq 1..N
            List<Map<String, Object>> first = host.eventsSince(0);
            assertFalse(first.isEmpty(), "should have startup events");
            int lastSeq = (int) first.get(first.size() - 1).get("seq");
            assertEquals(first.size(), lastSeq, "sequences must be 1..N contiguous");

            // since=lastSeq → 只有新增（可能为空，但不得重复历史）
            host.awaitFinish(60_000);
            List<Map<String, Object>> delta = host.eventsSince(lastSeq);
            for (var e : delta) {
                assertTrue((int) e.get("seq") > lastSeq, "delta must only contain new events");
            }
            // 全量 = 第一轮 + 增量（不重不漏）
            List<Map<String, Object>> all = host.eventsSince(0);
            assertEquals(first.size() + delta.size(), all.size(),
                    "full history must equal first + delta");
        }
    }

    @Test
    void injectForwardsToEngineWhileRunning(@TempDir Path tmp) throws Exception {
        // 用长任务让场景保持在途，便于注入
        String longScenario = FAST_SCENARIO.replace("duration: 200ms", "duration: 3s");
        Path f = writeScenario(tmp, longScenario);
        try (var host = new ScenarioHost()) {
            host.start(f);
            Thread.sleep(500); // 让派发发生
            var action = new FaultAction(FaultAction.CRASH,
                    FaultAction.ComponentAddress.ofInstance(new ComponentId("workers"), 1),
                    Map.of(), null);
            var r = host.inject(action);
            assertTrue(r.success(), () -> "inject should succeed: " + r.reason());
            // 注入事实进事件流
            host.awaitFinish(30_000);
            assertTrue(host.eventsSince(0).stream()
                            .anyMatch(e -> e.get("type").equals("sim.fault-injected")),
                    "injection must appear in the event stream");
        }
    }

    @Test
    void injectWhenNotRunningFailsNotSilent() {
        try (var host = new ScenarioHost()) {
            var r = host.inject(new FaultAction(FaultAction.CRASH,
                    FaultAction.ComponentAddress.of(new ComponentId("workers")), Map.of(), null));
            assertFalse(r.success());
            assertTrue(r.reason().contains("not running"));
        }
    }

    @Test
    void topologyListsNodesWithTierAndHealth(@TempDir Path tmp) throws Exception {
        Path f = writeScenario(tmp, FAST_SCENARIO);
        try (var host = new ScenarioHost()) {
            host.start(f);
            var topo = host.topology();
            assertEquals(3, topo.size(), () -> "topology: " + topo);
            var zk = topo.stream().filter(n -> n.get("id").equals("zk")).findFirst().orElseThrow();
            assertEquals("registry", zk.get("contract"));
            assertEquals("virtual", zk.get("tier"));
            assertEquals(Boolean.TRUE, zk.get("hosted"));
            var master = topo.stream().filter(n -> n.get("id").equals("master"))
                    .findFirst().orElseThrow();
            assertEquals(Boolean.TRUE, master.get("sut"));
            var workers = topo.stream().filter(n -> n.get("id").equals("workers"))
                    .findFirst().orElseThrow();
            assertEquals(2, workers.get("count"));
        }
    }

    @Test
    void assertionsReadableAfterFinish(@TempDir Path tmp) throws Exception {
        Path f = writeScenario(tmp, FAST_SCENARIO);
        try (var host = new ScenarioHost()) {
            host.start(f);
            host.awaitFinish(60_000);
            var a = host.assertions();
            assertEquals(Boolean.TRUE, a.get("passed"));
            assertFalse(((List<?>) a.get("assertions")).isEmpty());
        }
    }

    @Test
    void validationFailureIsReported(@TempDir Path tmp) throws Exception {
        // 无 SUT 节点 → 校验失败（§8 快速失败）
        String bad = """
                name: bad
                topology:
                  - id: zk
                    contract: registry
                    tier: virtual
                """;
        Path f = writeScenario(tmp, bad);
        try (var host = new ScenarioHost()) {
            assertThrows(IllegalArgumentException.class, () -> host.start(f));
            assertEquals("FAILED", host.status().get("state"));
            assertNotNull(host.status().get("error"));
        }
    }

    @Test
    void doubleStartRejected(@TempDir Path tmp) throws Exception {
        Path f = writeScenario(tmp, FAST_SCENARIO.replace("duration: 200ms", "duration: 3s"));
        try (var host = new ScenarioHost()) {
            host.start(f);
            assertThrows(IllegalStateException.class, () -> host.start(f));
        }
    }
}
