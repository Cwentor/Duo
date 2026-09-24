package io.duo.sim.control;

// 由 duo-sim-examples 迁回本模块（第 14 轮口径修正，2026-09-20）：
// ScenarioHost 是控制面类，它的契约测试**不需要任何档位实现**——本文件的场景不声明
// SUT/external 节点，也就不用 DemoScheduler（本模块测试类路径上没有 examples）。
// 依赖 DemoScheduler 的编排层用例（DuoCliTest）留在 examples 步内按模块归属执行，
// 见 docs/DEVELOPMENT.md §3.2。

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

    /**
     * 快速收敛的场景（短时长，便于测试）。
     *
     * <p>SUT 用本模块测试夹具 {@code ControlFixtureSut}（scheduler 端点应答 + 注册静默期后
     * 自行退出）：控制面契约测试要的是「启动/状态/事件/断言/停止全链路真的走通」，而不是
     * 调度算法本身（那在 examples 的 {@code DemoScheduler}）。依赖方向因此保持单向
     * （examples → control）。{@code fixture.holdMs} 是夹具的私有键——本测试走本机配置档
     * 可用；REST 层的外部输入档不收它，靠夹具缺省窗口。
     */
    private static final String FAST_SCENARIO = """
            name: control-host-smoke
            topology:
              - id: zk
                contract: registry
                tier: virtual
              - id: master
                contract: scheduler
                tier: virtual
                sut: true
                launch: { mode: in-process, main: io.duo.sim.control.testfixture.ControlFixtureSut }
                config: { dag.tasks: "a,b", fixture.holdMs: "300" }
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

    /**
     * 先自证「virtual 档实现确实在本模块测试类路径上」。
     *
     * <p>理由：契约测试最容易的坏死法是「因为找不到实现而变成一条不测任何东西的用例」
     * ——这里用一条显式断言把前置条件钉住，失败信息直接指向依赖声明（§12 不静默）。
     */
    @Test
    void virtualProvidersVisibleOnThisModuleTestClasspath() {
        var registry = io.duo.sim.kernel.core.ContractRegistry.loadFromServiceLoader();
        assertTrue(registry.hasImplementation(
                        io.duo.sim.kernel.api.Contract.SCHEDULER, io.duo.sim.kernel.api.Tier.VIRTUAL),
                "duo-sim-control 的测试需要 virtual 档实现（test 作用域依赖 duo-sim-components）");
        assertTrue(registry.hasImplementation(
                        io.duo.sim.kernel.api.Contract.WORKER, io.duo.sim.kernel.api.Tier.VIRTUAL),
                "同上：worker 契约需要 virtual 档实现");
    }

    private static final long _60S = 60_000;

    /** 夹具 SUT 自行退出的观察上限：静默期 600ms + hold 300ms + 启动余量，15s 绰绰有余。 */
    private static final long _15S = 15_000;

    @Test
    void startRunsScenarioAndReportsStatus(@TempDir Path tmp) throws Exception {
        Path f = writeScenario(tmp, FAST_SCENARIO);
        try (var host = new ScenarioHost()) {
            var st = host.start(f);
            assertEquals("RUNNING", st.get("state"));
            assertEquals("control-host-smoke", st.get("scenario"));
            assertTrue(host.isRunning());

            // 夹具 SUT 在注册静默期后 run() 返回 → sut.exited → 场景终态。
            // 这也是 §7.3 结束条件为「SUT 退出」的可执行证据：不是时间到，是 SUT 说了算。
            var fin = host.awaitFinish(_15S);
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
            host.awaitFinish(_60S);
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
    void registeredHookIsInvokedByYamlTimeline(@TempDir Path tmp) throws Exception {
        // M5/G5：宿主暴露 HookRegistry，YAML 时间线的 custom-hook 才能按名调到用户代码
        String withHook = FAST_SCENARIO
                .replace("duration: 200ms", "duration: 3s")
                .replace("timeline: []", """
                        timeline:
                          - at: 200ms
                            action: custom-hook
                            target: master
                            params: { hook: quiesce, mode: drain }""");
        Path f = writeScenario(tmp, withHook);
        try (var host = new ScenarioHost()) {
            var called = new java.util.concurrent.atomic.AtomicReference<String>();
            host.hooks().register("quiesce", ctx -> {
                called.set(ctx.target() + ":" + ctx.params().get("mode"));
                ctx.emit("sut.hook-quiesced", java.util.Map.of("target", ctx.target()));
            });
            host.start(f);
            host.awaitFinish(30_000);
            assertEquals("master:drain", called.get(),
                    "hook must be invoked with target + params");
            assertTrue(host.eventsSince(0).stream()
                            .anyMatch(e -> e.get("type").equals("sim.hook-executed")),
                    "sim.hook-executed must be recorded");
            assertTrue(host.eventsSince(0).stream()
                            .anyMatch(e -> e.get("type").equals("sut.hook-quiesced")),
                    "hook-emitted fact must be recorded");
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
            // 先等终态：跑到停机后再读断言，语义与「postmortem」一致——不等就可能读到空集，
            // 让「断言为空」这条真实缺陷掩盖在竞态里（本轮就是这么被发现的）
            host.awaitFinish(_60S);
            var a = host.assertions();
            assertEquals(Boolean.TRUE, a.get("passed"));
            assertFalse(((List<?>) a.get("assertions")).isEmpty());
        }
    }

    @Test
    void validationFailureIsReported(@TempDir Path tmp) throws Exception {
        // 认不出的契约 → 校验失败（§8 快速失败）
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
