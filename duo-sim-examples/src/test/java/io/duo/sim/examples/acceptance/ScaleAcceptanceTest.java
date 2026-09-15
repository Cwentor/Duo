package io.duo.sim.examples.acceptance;

import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.scenario.ScenarioEngine;
import io.duo.sim.scenario.ScenarioLoader;
import io.duo.sim.scenario.model.Scenario;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 验收（计划 T37）：千/万 Worker 心跳压测（§14 验收口径「千~万 Worker 心跳压测报告」）。
 *
 * <p>门控：{@code -Dduo.scale=true} 显式触发（计划 D2——压测分钟级耗时 + 大内存，
 * 不进常规回归；报告由压测命令的实测输出落盘）。口径：
 * <ol>
 *   <li>注册爬坡：全部 worker 实例注册完成（sut.worker-registered 数＝count）；</li>
 *   <li>稳态吞吐：sut.heartbeat-meter 事件给出 master 侧真实心跳速率（不随采样率失真）；</li>
 *   <li>规模正确性：无 sut.instance-lost（无实例掉线）、DAG 全终态、场景结果 pass；</li>
 *   <li>指标落盘 build/scale/&lt;name&gt;.json 供压测报告引用。</li>
 * </ol>
 */
@EnabledIfSystemProperty(named = "duo.scale", matches = "true")
class ScaleAcceptanceTest {

    @ParameterizedTest
    @ValueSource(strings = {"/scenarios/scale-1k.yaml", "/scenarios/scale-10k.yaml"})
    void heartbeatScaleScenarioPasses(String scenarioResource) throws Exception {
        Scenario scenario;
        try (InputStream in = getClass().getResourceAsStream(scenarioResource)) {
            scenario = ScenarioLoader.load(in);
        }
        int expected = scenario.nodes().stream()
                .filter(n -> "workers".equals(n.id()))
                .findFirst().orElseThrow()
                .count();
        String name = scenario.name();

        long usedHeap0 = usedHeapBytes();
        long gcTime0 = totalGcTimeMs();
        Instant t0 = Instant.now();

        var registry = ContractRegistry.loadFromServiceLoader();
        try (ScenarioEngine engine = ScenarioEngine.validated(scenario, registry)) {
            engine.startSut();
            engine.startComponents();

            // ---- 注册爬坡：轮询事件流直至全部实例注册（超时按规模分档）----
            long registerTimeoutMs = expected >= 10_000 ? 300_000 : 120_000;
            Instant registeredAt = awaitRegistrations(engine, expected, registerTimeoutMs);
            Duration ramp = Duration.between(t0, registeredAt);

            // ---- SUT 存活至 DAG 全终态（30s 长任务 + 注册期）----
            long exitTimeoutMs = expected >= 10_000 ? 300_000 : 180_000;
            assertTrue(engine.awaitSutExit(exitTimeoutMs), "SUT did not exit in time");
            engine.stop();
            Duration wall = Duration.between(t0, Instant.now());

            // ---- 断言（规模正确性）----
            List<Event> events = engine.events();
            long registered = events.stream()
                    .filter(e -> e.type().equals("sut.worker-registered")).count();
            assertEquals(expected, registered,
                    "all worker instances must register: " + registered + "/" + expected);

            // 无实例掉线（仅运行窗口）：SUT 的 DAG 终态确认后即进入收尾拆除——
            // closeAll 关闭全部连接会让 master 侧批量感知失联（sut.instance-lost），
            // 属拆除噪声；只有早于首个 sut.dag-terminal 的丢失才算规模故障
            Instant dagTerminalAt = events.stream()
                    .filter(e -> e.type().equals("sut.dag-terminal"))
                    .map(Event::timestamp).findFirst().orElse(Instant.MAX);
            List<Event> lost = events.stream()
                    .filter(e -> e.type().equals("sut.instance-lost"))
                    .filter(e -> e.timestamp().isBefore(dagTerminalAt))
                    .toList();
            assertTrue(lost.isEmpty(),
                    () -> "no instance may be lost during the run: "
                            + lost.stream().limit(5).toList());

            // ---- 稳态吞吐：meter 事件（真实计数，不随采样率失真）----
            List<Event> meters = events.stream()
                    .filter(e -> e.type().equals("sut.heartbeat-meter")).toList();
            assertTrue(!meters.isEmpty(), "heartbeat meter must report at least one window");
            double maxRate = meters.stream()
                    .mapToDouble(e -> ((Number) e.payload().get("ratePerSec")).doubleValue())
                    .max().orElse(0);
            long totalHeartbeats = meters.stream()
                    .mapToLong(e -> ((Number) e.payload().get("total")).longValue())
                    .max().orElse(0);
            // 吞吐合理性：稳态窗口速率应达到 count × (1/interval) 的显著比例（≥50%）
            // （1k/10k 均为 1s 心跳 → 理论速率≈count/s；50% 下限容忍爬坡期与统计窗口边界）
            assertTrue(maxRate >= expected * 0.5,
                    () -> "steady-state heartbeat rate too low: " + maxRate
                            + "/s (expected >= " + (expected * 0.5) + "/s)");
            assertTrue(totalHeartbeats > 0, "heartbeats must have been counted");

            // ---- 场景结果 ----
            var result = engine.result();
            assertTrue(result.snapshot().injectionFailures().isEmpty());
            assertTrue(result.passed(), () -> "scenario result must pass: "
                    + result.snapshot().assertions());

            // ---- 指标落盘（压测报告素材）----
            long usedHeap1 = usedHeapBytes();
            long gcTime1 = totalGcTimeMs();
            String json = metricsJson(name, expected, ramp, wall, maxRate, totalHeartbeats,
                    meters.size(), events.size(), usedHeap0, usedHeap1, gcTime1 - gcTime0);
            Path out = Path.of("build", "scale", name + ".json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, json);

            System.out.printf("[scale] %s: workers=%d ramp=%ds wall=%ds maxHbRate=%.0f/s "
                            + "totalHb=%d events=%d heapDelta=%.0fMB gcTimeDelta=%dms%n",
                    name, expected, ramp.getSeconds(), wall.getSeconds(), maxRate,
                    totalHeartbeats, events.size(), (usedHeap1 - usedHeap0) / 1e6, gcTime1 - gcTime0);
        }
    }

    /** 轮询事件流直至 sut.worker-registered 达到 expected，返回达到时刻。 */
    private static Instant awaitRegistrations(ScenarioEngine engine, int expected,
                                              long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<Event> evs = engine.events().stream()
                    .filter(e -> e.type().equals("sut.worker-registered")).toList();
            if (evs.size() >= expected) {
                return evs.stream().map(Event::timestamp).max(Comparator.naturalOrder())
                        .orElse(Instant.now());
            }
            Thread.sleep(200);
        }
        long got = engine.events().stream()
                .filter(e -> e.type().equals("sut.worker-registered")).count();
        throw new AssertionError("registration ramp incomplete: " + got + "/" + expected);
    }

    private static long usedHeapBytes() {
        var mb = ManagementFactory.getMemoryMXBean();
        return mb.getHeapMemoryUsage().getUsed();
    }

    private static long totalGcTimeMs() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
    }

    private static String metricsJson(String name, int workers, Duration ramp, Duration wall,
                                      double maxRate, long totalHb, int meterEvents,
                                      int totalEvents, long heap0, long heap1, long gcDeltaMs) {
        return "{\n"
                + "  \"scenario\": \"" + name + "\",\n"
                + "  \"workers\": " + workers + ",\n"
                + "  \"registrationRampSeconds\": " + ramp.getSeconds() + ",\n"
                + "  \"wallSeconds\": " + wall.getSeconds() + ",\n"
                + "  \"maxHeartbeatRatePerSec\": " + maxRate + ",\n"
                + "  \"totalHeartbeats\": " + totalHb + ",\n"
                + "  \"meterEvents\": " + meterEvents + ",\n"
                + "  \"totalEvents\": " + totalEvents + ",\n"
                + "  \"heapUsedStartBytes\": " + heap0 + ",\n"
                + "  \"heapUsedEndBytes\": " + heap1 + ",\n"
                + "  \"gcTimeDeltaMs\": " + gcDeltaMs + "\n"
                + "}\n";
    }
}
