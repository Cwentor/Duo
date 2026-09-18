package io.duo.sim.examples.acceptance;

import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.scenario.ScenarioEngine;
import io.duo.sim.scenario.ScenarioLoader;
import io.duo.sim.scenario.model.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 验收（ROADMAP §4 M6 验收标准）：一个**不可改码**的 external 进程作为 SUT 接入，
 * 跑通「启动 → 端点告知 → ready 探针 → 故障注入 → 旁路断言通过 → 场景结束不杀进程」，
 * 且「中途崩溃/退出」分别产生 {@code sut.crashed} / {@code sut.exited} 并终止场景。
 *
 * <p>被测 SUT ＝ {@code src/test/resources/external/FakeThirdPartySut.java}：**零 Duo 依赖**的
 * 独立 JVM（JDK 单文件源码模式启动，无 classpath、无编译产物），即设计 §7.3「不可改码第三方
 * SUT 只能走 external + 旁路观测」的最小化身（决策 D1：暂不绑定真实产品）。
 *
 * <p>旁路断言取自**替身侧**事实（embedded registry 的 {@code sim.registry-flap-*}），
 * 不依赖 external SUT 内部可观测性（设计 §7.3 三途径之①）。
 */
class ExternalSutAcceptanceTest {

    @TempDir
    Path tmp;

    // ---- ① 完整验收链路 ----

    @Test
    void externalThirdPartySutEndToEndWithoutKillingProcess() throws Exception {
        int port = freePort();
        Path configOut = tmp.resolve("sut.properties");
        Path report = tmp.resolve("learned.txt");
        Path yaml = writeFullScenario("m6-external-sut-acceptance", "hold", port, configOut, report);

        Scenario scenario = ScenarioLoader.load(yaml);
        var registry = ContractRegistry.loadFromServiceLoader();
        ScenarioEngine engine = ScenarioEngine.validated(scenario, registry);
        try {
            engine.startSut();
            engine.startComponents();
            assertNotNull(engine.externalSut(), "external 形态必须暴露启动器句柄（D7）");
            assertTrue(engine.externalSut().readyConfirmed(), "ready 探针必须确认");

            // ① 端点告知（主途径）：内核写出的端点配置文件确实被 SUT 读到
            String notified = Files.readString(configOut);
            assertTrue(notified.contains("duo.endpoint.registry=127.0.0.1:"),
                    () -> "端点配置文件必须含真实 ZK 端点:\n" + notified);
            String registryEndpoint = notified.lines()
                    .filter(l -> l.startsWith("duo.endpoint.registry="))
                    .map(l -> l.substring("duo.endpoint.registry=".length()).trim())
                    .findFirst().orElseThrow();
            awaitUntil("SUT 读到端点配置文件", () -> {
                try {
                    return Files.exists(report) && Files.readString(report)
                            .contains("learned endpoint: registry -> " + registryEndpoint);
                } catch (IOException e) {
                    return false;
                }
            }, 30_000);

            // ② 端点发现（兜底途径）：SUT stdout 宣告自身端点
            awaitUntil("stdout 端点宣告", () -> engine.externalSut()
                    .discoveredEndpoints().containsKey("scheduler"), 15_000);
            assertTrue(engine.events().stream().anyMatch(e -> e.type().equals("sim.external-endpoint")
                            && "stdout".equals(e.payload().get("source"))),
                    () -> "缺 sim.external-endpoint 事件: " + types(engine));

            // ③ 故障注入 + 旁路观测：时间线 registry-flap（替身侧事实，不依赖 SUT 内部）
            awaitUntil("替身侧 registry-flap 旁路事件", () -> engine.events().stream()
                    .anyMatch(e -> e.type().equals("sim.registry-flap-cleared")), 40_000);

            // ④ 场景结束：只拆接线、不杀 external 进程（§7.3）
            engine.stop();
            assertTrue(engine.events().stream().anyMatch(
                            e -> e.type().equals("sim.scenario-finished")),
                    () -> "缺 sim.scenario-finished: " + types(engine));
            assertTrue(engine.externalSut().process().isAlive(),
                    "§7.3：场景结束不得杀 external 进程");
            assertTrue(engine.events().stream().anyMatch(
                            e -> e.type().equals("sim.external-process-left-running")),
                    () -> "缺「进程留活」终态事件: " + types(engine));
            assertTrue(engine.warnings().stream().anyMatch(w -> w.contains("lifecycle")),
                    () -> "必须显式提示用户自行终止: " + engine.warnings());

            // ⑤ YAML 旁路断言通过（场景结果判 pass）
            var snapshot = engine.result().snapshot();
            assertTrue(snapshot.injectionFailures().isEmpty(),
                    () -> "无注入失败: " + snapshot.injectionFailures());
            assertFalse(snapshot.assertions().isEmpty(), "YAML 断言必须被评估");
            snapshot.assertions().forEach(a -> assertTrue(a.passed(),
                    () -> "断言 '" + a.name() + "' 失败: " + a.detail()));
            assertTrue(engine.result().passed());
        } finally {
            killProcess(engine);
        }
    }

    // ---- ② ready 后正常退出 → sut.exited 并终止场景 ----

    @Test
    void externalSutNormalExitPublishesExitedAndEndsScenario() throws Exception {
        int port = freePort();
        Path yaml = writeMinimalScenario("m6-external-sut-exit", "exit0", port,
                tmp.resolve("exit.properties"), tmp.resolve("exit-report.txt"));
        Scenario scenario = ScenarioLoader.load(yaml);
        var registry = ContractRegistry.loadFromServiceLoader();
        try (ScenarioEngine engine = ScenarioEngine.validated(scenario, registry)) {
            engine.startSut();
            engine.startComponents();

            assertTrue(engine.awaitSutExit(40_000), "external SUT 退出必须终止场景（§7.3）");
            engine.stop();

            assertTrue(engine.events().stream().anyMatch(e -> e.type().equals("sut.exited")),
                    () -> "正常退出须发 sut.exited: " + types(engine));
            assertFalse(engine.events().stream().anyMatch(e -> e.type().equals("sut.crashed")),
                    () -> "正常退出不得报崩溃: " + types(engine));
            assertFalse(engine.externalSut().leftRunning());
        }
    }

    // ---- ③ ready 后崩溃 → sut.crashed 并终止场景 ----

    @Test
    void externalSutCrashPublishesCrashedAndEndsScenario() throws Exception {
        int port = freePort();
        Path yaml = writeMinimalScenario("m6-external-sut-crash", "crash", port,
                tmp.resolve("crash.properties"), tmp.resolve("crash-report.txt"));
        Scenario scenario = ScenarioLoader.load(yaml);
        var registry = ContractRegistry.loadFromServiceLoader();
        try (ScenarioEngine engine = ScenarioEngine.validated(scenario, registry)) {
            engine.startSut();
            engine.startComponents();

            assertTrue(engine.awaitSutExit(40_000), "external SUT 崩溃必须终止场景（§7.3）");
            engine.stop();

            Event crashed = engine.events().stream()
                    .filter(e -> e.type().equals("sut.crashed")).findFirst().orElse(null);
            assertNotNull(crashed, () -> "崩溃须发 sut.crashed: " + types(engine));
            assertTrue(((Number) crashed.payload().get("exitCode")).intValue() != 0,
                    "崩溃退出码须非 0: " + crashed.payload());
        }
    }

    // ---- 场景文件生成 ----

    /** 完整拓扑：embedded registry（真实 ZK 端口，wire 面）＋ external SUT ＋ 时间线注入 ＋ 旁路断言。 */
    private Path writeFullScenario(String name, String mode, int port, Path configOut, Path report)
            throws Exception {
        String yaml = """
                name: %s
                topology:
                  - id: zk
                    contract: registry
                    tier: embedded
                  - id: master
                    contract: scheduler
                    tier: real
                    sut: true
                    launch:
                      mode: external
                      command: '%s'
                      configOut: %s
                      ready: { type: tcp, port: %d, timeout: 30s }
                    exposes: [{ contract: scheduler, port: %d, addr: 127.0.0.1 }]
                    wiring:
                      registry: { node: zk, contract: registry }
                timeline:
                  - at: 2s
                    action: registry-flap
                    target: zk
                assertions:
                  - eventSequence: [sim.fault-injected, sim.registry-flap-cleared]
                """.formatted(name, command(mode, port, report), posix(configOut), port, port);
        return write(name, yaml);
    }

    /** 最小拓扑：只有 external SUT 节点（无替身），用于退出/崩溃生命周期用例。 */
    private Path writeMinimalScenario(String name, String mode, int port, Path configOut, Path report)
            throws Exception {
        String yaml = """
                name: %s
                topology:
                  - id: master
                    contract: scheduler
                    tier: real
                    sut: true
                    launch:
                      mode: external
                      command: '%s'
                      configOut: %s
                      ready: { type: tcp, port: %d, timeout: 30s }
                    exposes: [{ contract: scheduler, port: %d, addr: 127.0.0.1 }]
                """.formatted(name, command(mode, port, report), posix(configOut), port, port);
        return write(name, yaml);
    }

    /** {@code ${java}} 占位符由引擎展开（D7）——场景文件不写死本机 JDK 路径。 */
    private String command(String mode, int port, Path report) throws Exception {
        var url = getClass().getResource("/external/FakeThirdPartySut.java");
        assertNotNull(url, "假第三方 SUT 源码必须在测试 classpath 上");
        return "${java} " + posix(Path.of(url.toURI())) + " " + port + " " + mode + " "
                + posix(report);
    }

    private Path write(String name, String yaml) throws IOException {
        Path file = tmp.resolve(name + ".yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return file;
    }

    /** YAML 单引号标量里反斜杠是普通字符，但统一转正斜杠更稳（Windows/Linux 皆可）。 */
    private static String posix(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static String types(ScenarioEngine engine) {
        return engine.events().stream().map(Event::type).distinct().toList().toString();
    }

    private static void awaitUntil(String what, BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timeout waiting for: " + what);
    }

    /** 测试自己拥有 external 进程（内核不杀），收尾由测试负责（§7.3 生命周期归用户）。 */
    private static void killProcess(ScenarioEngine engine) {
        var external = engine.externalSut();
        if (external == null) {
            return;
        }
        Process p = external.process();
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
    }
}
