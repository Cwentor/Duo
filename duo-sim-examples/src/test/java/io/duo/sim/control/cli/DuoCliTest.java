package io.duo.sim.control.cli;

import io.duo.sim.control.ScenarioHost;
import io.duo.sim.control.rest.RestControlServer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T34 CLI 单测：参数解析、同进程 inject 空态与 attached 注入、--url 跨进程模式。 */
class DuoCliTest {

    private static final String FAST = """
            name: cli-smoke
            topology:
              - id: zk
                contract: registry
                tier: virtual
              - id: master
                contract: scheduler
                tier: real
                sut: true
                launch: { mode: in-process, main: io.duo.sim.examples.scheduler.DemoScheduler }
                config: { dag.tasks: "a" }
                exposes: [{ contract: scheduler, port: 0 }]
                wiring:
                  registry: { node: zk, contract: registry }
              - id: workers
                contract: worker
                tier: virtual
                count: 1
                capacity: { slots: 1 }
                wiring:
                  registry: { node: zk, contract: registry }
            behaviors:
              profiles:
                default: { duration: 10s, jitter: 0.0, successRate: 1.0 }
              bindings:
                - node: workers
                  profile: default
            timeline: []
            assertions: []
            """;

    /**
     * 注入演练场景（T34 单命令形态）：4 任务 / 4 worker（slots=1），
     * 在途期崩溃一个 worker → 任务重派发到其余实例。timeline 为空——
     * 故障由 {@code --inject-after} 从外部热注入（M3 的核心差异）。
     */
    private static final String INJECT_AFTER = """
            name: cli-inject-after
            topology:
              - id: zk
                contract: registry
                tier: virtual
              - id: master
                contract: scheduler
                tier: real
                sut: true
                launch: { mode: in-process, main: io.duo.sim.examples.scheduler.DemoScheduler }
                config: { dag.tasks: "job-a,job-b,job-c,job-d" }
                exposes: [{ contract: scheduler, port: 0 }]
                wiring:
                  registry: { node: zk, contract: registry }
              - id: workers
                contract: worker
                tier: virtual
                count: 4
                capacity: { slots: 1 }
                wiring:
                  registry: { node: zk, contract: registry }
            behaviors:
              profiles:
                default: { duration: 5s, jitter: 0.1, successRate: 1.0 }
              bindings:
                - node: workers
                  profile: default
            timeline: []
            assertions:
              - noTaskLost: { requireAllSuccess: true }
            """;

    @Test
    void helpReturnsZero() {
        assertEquals(0, DuoCli.run("help"));
        assertEquals(0, DuoCli.run());
    }

    @Test
    void unknownCommandReturnsOne() {
        assertEquals(1, DuoCli.run("bogus"));
    }

    @Test
    void injectWithoutAttachedScenarioFailsNotSilent() {
        assertEquals(1, DuoCli.run("inject", "crash", "workers[1]"));
    }

    @Test
    void runKeepThenInjectThenDetach() throws Exception {
        Path f = Files.createTempFile("duo-cli", ".yaml");
        Files.writeString(f, FAST, StandardCharsets.UTF_8);

        // run --keep：注册到进程级注册表（不 --wait，场景保持在途）
        assertEquals(0, DuoCli.run("run", f.toString(), "--keep"));
        assertNotNull(ScenarioHost.attached("default"));

        // 同进程注入：crash workers[1]
        int rc = DuoCli.run("inject", "crash", "workers[1]");
        assertEquals(0, rc, "injection must succeed on attached scenario");

        // status 可读（RUNNING 或已推进）
        assertEquals(0, DuoCli.run("status"));

        // 清理：detach 并停止
        ScenarioHost attached = ScenarioHost.attached("default");
        attached.stop();
        attached.detach("default");
        assertEquals(1, DuoCli.run("inject", "crash", "workers[1]"),
                "after detach, inject must fail (no attached scenario)");
    }

    @Test
    void eventsOnIdleHostIsEmpty() {
        assertEquals(0, DuoCli.run("events"));
    }

    @Test
    void assertOnIdleHostFailsWithExitOne() {
        // 空态 host 的 passed=false → 退出码 1（断言库口径一致）
        assertEquals(1, DuoCli.run("assert"));
    }

    // ---- T34 单命令验收形态：run --inject-after "<action> <target>" --wait ----

    @Test
    void parseActionSpecHandlesTargetFormsAndDuration() {
        var group = DuoCli.parseActionSpec("crash workers", null);
        assertEquals("crash", group.type());
        assertEquals("workers", group.target().componentId().value());
        assertNull(group.target().instanceIndex(), "no bracket = whole group");
        assertNull(group.durationMillis());

        var inst = DuoCli.parseActionSpec("crash workers[2]", null);
        assertEquals(2, inst.target().instanceIndex());

        var flap = DuoCli.parseActionSpec("registry-flap zk 5s", null);
        assertEquals(5_000L, flap.durationMillis(), "third token is the duration");

        // 显式 override 优先于 spec 内联时长
        assertEquals(1_500L, DuoCli.parseActionSpec("crash workers[2] 5s", "1500ms").durationMillis());

        assertThrows(IllegalArgumentException.class, () -> DuoCli.parseActionSpec("crash", null));
        assertThrows(IllegalArgumentException.class, () -> DuoCli.parseActionSpec("crash w 5", null),
                "duration without unit must be rejected (§1 v4)");
    }

    @Test
    void runWithInjectAfterIsSingleCommandAcceptance() throws Exception {
        // 4 任务 / 4 worker（slots=1）：崩溃的 worker 上有在途任务 → 重派发到其他实例
        Path f = Files.createTempFile("duo-cli-inject", ".yaml");
        Files.writeString(f, INJECT_AFTER, StandardCharsets.UTF_8);

        int rc = DuoCli.run("run", f.toString(), "--inject-after", "1s", "crash workers[2]",
                "--wait");
        assertEquals(0, rc, "injection during flight must not lose tasks (assertions pass)");
    }

    @Test
    void injectAfterRejectsMalformedSpec() throws Exception {
        Path f = Files.createTempFile("duo-cli-badspec", ".yaml");
        Files.writeString(f, INJECT_AFTER, StandardCharsets.UTF_8);
        assertEquals(1, DuoCli.run("run", f.toString(), "--inject-after", "1s", "crash"),
                "spec without target must fail loudly");
    }

    // ---- T34 跨进程模式：CLI 作为 REST 客户端（--url） ----

    @Test
    void urlModeDrivesRunningScenarioOverRest() throws Exception {
        Path f = Files.createTempFile("duo-cli-url", ".yaml");
        Files.writeString(f, INJECT_AFTER, StandardCharsets.UTF_8);

        try (ScenarioHost host = new ScenarioHost();
             RestControlServer server = new RestControlServer(host,
                     RestControlServer.Auth.TOKEN, io.duo.sim.control.rest.TestTokens.TOKEN)) {
            int port = server.start(0);
            String url = "http://127.0.0.1:" + port;
            host.start(f);
            String token = io.duo.sim.control.rest.TestTokens.TOKEN;

            // 无令牌 → 401：客户端必须显式带凭据，控制面不会"因为你是本机进程就放行"
            assertEquals(1, DuoCli.run("status", "--url", url),
                    "unauthenticated REST call must fail loudly");

            assertEquals(0, DuoCli.run("status", "--url", url, "--token", token));
            assertEquals(0, DuoCli.run("events", "--since", "0", "--url", url, "--token", token));
            assertEquals(0, DuoCli.run("topology", "--url", url, "--token", token));
            assertEquals(0, DuoCli.run("inject", "crash", "workers[2]", "--url", url,
                            "--token", token),
                    "cross-process hot injection must succeed");

            host.awaitFinish(60_000);
            assertEquals(0, DuoCli.run("assert", "--url", url, "--token", token),
                    "assertions must pass");
            assertEquals(0, DuoCli.run("stop", "--url", url, "--token", token));

            // 错误路径：未知主机不得静默成功
            assertEquals(1, DuoCli.run("status", "--url", "http://127.0.0.1:1", "--token", token));
        }
    }

    /** serve 的认证材料解析：没有令牌就必须拒绝启动（C-1 的根因是"默认可用"）。 */
    @Test
    void serveRefusesToStartWithoutTokenWhenEnvAbsent() throws Exception {
        if (System.getenv("DUO_TOKEN") != null) {
            return; // 环境里已有令牌时不适用（避免测试依赖宿主机环境）
        }
        Path f = Files.createTempFile("duo-cli-serve", ".yaml");
        Files.writeString(f, INJECT_AFTER, StandardCharsets.UTF_8);
        assertEquals(1, DuoCli.run("serve", f.toString(), "--port", "0"),
                "serve without a token and without --insecure-no-auth must refuse to start");
    }

    // ---- 由 RestControlServerTest 迁入的两条「外部输入拒绝」用例（第 14 轮，2026-09-20）----
    // 原因：它们需要一条带 launch.main 与 config 的**真实 SUT 节点**才能构造场景，
    // 而 duo-sim-control 的测试类路径上没有档位实现（SUT 就是本模块的 DemoScheduler）。
    // 覆盖没有减少，只是搬到了能构造出该场景的模块——hoisting 见 docs/DEVELOPMENT.md §3.2。

    /** 任意类加载（`main: java.lang.ProcessBuilder`）必须在**校验期**被拒（400/异常）。 */
    @Test
    void arbitraryClassLaunchIsRejected() throws Exception {
        Path f = Files.createTempFile("duo-cli-arbitrary-main", ".yaml");
        Files.writeString(f, FAST.replace(
                "main: io.duo.sim.examples.scheduler.DemoScheduler",
                "main: java.lang.ProcessBuilder"), StandardCharsets.UTF_8);
        // 走 REST 形态（与安全审计报告里那条 PoC 同路径：控制面收的是不可信字节）
        try (ScenarioHost host = new ScenarioHost();
             RestControlServer server = new RestControlServer(host,
                     RestControlServer.Auth.TOKEN, io.duo.sim.control.rest.TestTokens.TOKEN)) {
            int port = server.start(0);
            String url = "http://127.0.0.1:" + port;
            String token = io.duo.sim.control.rest.TestTokens.TOKEN;
            var resp = postScenario(url, token, Files.readString(f));
            assertEquals(400, resp.statusCode(), () -> "body: " + resp.body());
            assertTrue(resp.body().contains("framework namespace"), resp.body());
        }
    }

    /**
     * 未知 config 键必须在**校验期**被拒（审计 M-1/M-2：认不出的键默认拒绝）。
     *
     * <p>判据走「非200 且理由是未知键」而不是硬编码 400，因为两条路径的错误形态不同：
     * {@code ScenarioValidator} 抛 {@link IllegalArgumentException} → 400，而
     * {@code ScenarioHost.start} 对校验失败抛 {@link IllegalStateException} → 409。
     * 口径不变——**拒绝且说清理由**（§12 不静默）。
     */
    @Test
    void unknownConfigKeyIsRejected() throws Exception {
        Path f = Files.createTempFile("duo-cli-unknown-key", ".yaml");
        Files.writeString(f, INJECT_AFTER.replace(
                "config: { dag.tasks: \"job-a,job-b,job-c,job-d\" }",
                "config: { totally.unknown.key: \"x\" }"), StandardCharsets.UTF_8);
        try (ScenarioHost host = new ScenarioHost();
             RestControlServer server = new RestControlServer(host,
                     RestControlServer.Auth.TOKEN, io.duo.sim.control.rest.TestTokens.TOKEN)) {
            int port = server.start(0);
            String token = io.duo.sim.control.rest.TestTokens.TOKEN;
            var resp = postScenario("http://127.0.0.1:" + port, token, Files.readString(f));
            assertTrue(resp.statusCode() >= 400 && resp.statusCode() < 500,
                    () -> "未知 config 键必须被拒（4xx），实际 " + resp.statusCode()
                            + " body: " + resp.body());
            assertTrue(resp.body().contains("not accepted for external input"),
                    () -> "拒绝理由必须指名未知键，body: " + resp.body());
        }
    }

    private static java.net.http.HttpResponse<String> postScenario(
            String url, String token, String yaml) throws Exception {
        var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url + "/scenario"))
                .header("Authorization", "Bearer " + token)
                .timeout(java.time.Duration.ofSeconds(10))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        yaml, StandardCharsets.UTF_8))
                .build();
        return java.net.http.HttpClient.newHttpClient()
                .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private static void assertNotNull(Object o) {
        if (o == null) {
            throw new AssertionError("expected non-null");
        }
    }
}
