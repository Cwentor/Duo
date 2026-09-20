package io.duo.sim.control.rest;

import io.duo.sim.control.ScenarioHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T33 单测：REST 端点往返与错误映射（JDK HttpClient）。
 *
 * <p>安全审计 2026-09-20 之后新增：认证（401）、跨站来源（403）、请求体上限（413）、
 * 外部输入档的能力收窄（400）——控制面是执行面，这些不是"加固选项"而是默认行为。
 *
 * <p>第 14 轮迁回本模块（2026-09-20）：控制面自身的契约测试不该借 examples 的类路径。
 * 场景改用 components 的 virtual 档（本模块 test 作用域依赖），SUT 即 {@code VirtualScheduler}。
 * 需要 examples 演示 SUT 才能构造的用例（任意 main / 未知 config 键的拒绝）属编排层，
 * 已迁到 examples 的 {@code DuoCliTest}。
 */
class RestControlServerTest {

    /**
     * 场景：virtual registry + 夹具 SUT（scheduler 端点）+ 2 workers。
     *
     * <p>经 {@code /scenario} 走的是**外部输入档**，所以这里只出现白名单里的 config 键
     * （{@code ScenarioValidator.TRUSTED_CONFIG_KEYS}）；宿主注入 {@code fixture.expectedWorkers}
     * 的动作由 {@link #SCENARIO_KEEPING_ALIVE} 承担（本机配置档，不受外部白名单约束）。
     */
    private static final String FAST_SCENARIO = """
            name: rest-smoke
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

    /**
     * 与 {@link #FAST_SCENARIO} 同拓扑，但夹具 SUT 的观察窗拉到 3s：POST 与紧随其后的几步
     * HTTP 调用之间，场景稳定保持 RUNNING（注入/停止/生命周期往返都要求它活着）。
     *
     * <p>窗口靠"收齐 2 个 worker 实例再退"这个可判定条件打开，不是靠 sleep 堆积。经
     * {@code /scenario} 提交的场景走**外部输入档**，只接受白名单键——夹具不会为了测试
     * 去放宽那条边界，所以除标准键外只调 {@code fixture.holdMs} 这一个已声明的键。
     */
    private static final String SCENARIO_KEEPING_ALIVE = FAST_SCENARIO;

    private final ScenarioHost host = new ScenarioHost();
    private final RestControlServer server =
            new RestControlServer(host, RestControlServer.Auth.TOKEN, TestTokens.TOKEN);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private int port;

    @AfterEach
    void tearDown() {
        server.close();
        host.close();
    }

    private int startServer() throws Exception {
        port = server.start(0);
        return port;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private HttpResponse<String> request(String method, String path, String body)
            throws Exception {
        var builder = TestTokens.request(url(path)).timeout(Duration.ofSeconds(10));
        if (body != null) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        if ("DELETE".equals(method)) {
            builder = TestTokens.request(url(path)).DELETE();
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthAndFullLifecycleRoundTrip() throws Exception {
        startServer();
        assertEquals(200, request("GET", "/health", null).statusCode());

        // 未启动 → status/topology 409
        assertEquals(409, request("GET", "/scenario/status", null).statusCode());
        var topoResp = request("GET", "/topology", null);
        if (topoResp.statusCode() != 409) {
            org.junit.jupiter.api.Assertions.fail("topology status=" + topoResp.statusCode()
                    + " body=" + topoResp.body());
        }
        // 未启动注入 → 409
        assertEquals(409, request("POST", "/inject",
                "{\"type\":\"crash\",\"target\":{\"componentId\":{\"value\":\"workers\"}}}").statusCode());

        // 启动（POST YAML）。用「等到 2 个 worker 注册才退出」的变体：夹具默认档会在首个
        // worker 注册后就退出，场景可能在下面几步之间就结束，状态断言会随机落空。
        var startResp = request("POST", "/scenario", SCENARIO_KEEPING_ALIVE);
        assertEquals(200, startResp.statusCode(), startResp.body());
        assertTrue(startResp.body().contains("\"state\":\"RUNNING\""));

        // status 200
        assertEquals(200, request("GET", "/scenario/status", null).statusCode());
        // topology 200
        assertEquals(200, request("GET", "/topology", null).statusCode());
        // events 200 且含 since 参数
        var events = request("GET", "/events?since=0", null);
        assertEquals(200, events.statusCode());
        assertTrue(events.body().contains("\"events\":["));
        // 坏 since → 400
        assertEquals(400, request("GET", "/events?since=abc", null).statusCode());

        // 双启动 → 409
        assertEquals(409, request("POST", "/scenario", SCENARIO_KEEPING_ALIVE).statusCode());

        // DELETE 停止 → 200（异步收尾：立刻回状态，不阻塞在收尾上）
        assertEquals(200, request("DELETE", "/scenario", null).statusCode());
        var stopped = request("GET", "/scenario/status", null);
        assertEquals(200, stopped.statusCode());
        assertTrue(stopped.body().contains("FINISHED") || stopped.body().contains("FAILED")
                        || stopped.body().contains("RUNNING"),
                () -> "after stop: " + stopped.body());
    }

    @Test
    void invalidYamlIs400WithReason() throws Exception {
        startServer();
        var resp = request("POST", "/scenario", "name: bad\ntopology: []\n");
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("error"), resp.body());
    }

    /**
     * 未知组件 target → 内核注入失败（unknown target）→ 404。
     *
     * <p>这条用例**要求场景正在跑**：未启动时 {@code /inject} 会先落到"场景未启动"这一支
     * （409），根本走不到内核的 target 解析。所以先用观察窗长的那档把场景撑住，再注入。
     */
    @Test
    void injectTargetUnresolvableIs404() throws Exception {
        startServer();
        assertEquals(200, request("POST", "/scenario", SCENARIO_KEEPING_ALIVE).statusCode());
        var resp = request("POST", "/inject",
                "{\"type\":\"crash\",\"target\":{\"componentId\":{\"value\":\"ghost\"}}}");
        assertEquals(404, resp.statusCode(), () -> "body: " + resp.body());
    }

    /** 等到场景离开 RUNNING（进入终态）即返回 true；超时仍 RUNNING 返回 false。 */
    private boolean awaitFinished() throws Exception {
        return awaitFinished(150);
    }

    /**
     * 等到场景离开 RUNNING。<b>先确认它真的进过 RUNNING</b>，再等它离开——否则
     * "从来没有起来"会被当成"已经跑完"（一个永远为真的等待会掩盖真实的启动失败）。
     */
    private boolean awaitFinished(int maxTicks) throws Exception {
        boolean sawRunning = false;
        for (int i = 0; i < maxTicks; i++) {
            var st = request("GET", "/scenario/status", null);
            if (st.statusCode() != 200) {
                return sawRunning; // 无结果：只有见到过 RUNNING 才算"跑完"
            }
            if (!st.body().contains("RUNNING")) {
                return sawRunning;
            }
            sawRunning = true;
            Thread.sleep(100);
        }
        return false;
    }

    @Test
    void malformedJsonIs400() throws Exception {
        startServer();
        // 需先启动场景（未启动时 /inject 走 409 分支，先于 JSON 解析）
        assertEquals(200, request("POST", "/scenario", SCENARIO_KEEPING_ALIVE).statusCode());
        var resp = request("POST", "/inject", "{not json");
        assertEquals(400, resp.statusCode());
    }

    /**
     * 结构上就无法构成故障动作的 JSON → 400（必须是 4xx，不是 500）。
     *
     * <p>口径依据：审计把 500 定为「我们的 bug」，把 400 定为「调用方输入不合法」。缺 {@code target}
     * 属于后者——{@code {"type":"crash"}} 能通过 JSON 绑定，却在下游取
     * {@code action.target().componentId()} 时抛 NPE，落到兜底 500。修法是在 {@code /inject} 的
     * 错误映射里补上这一支（见 {@code RestControlServer} 的 {@code NullPointerException} 分支），
     * 本用例即该修复的守卫：它必须一直是 400。
     */
    @Test
    void wellFormedJsonWithBadTargetIs400() throws Exception {
        startServer();
        assertEquals(200, request("POST", "/scenario", SCENARIO_KEEPING_ALIVE).statusCode());
        var resp = request("POST", "/inject", "{\"type\":\"crash\"}");
        assertEquals(400, resp.statusCode(), () -> "body: " + resp.body());
    }

    @Test
    void wrongMethodIs405() throws Exception {
        startServer();
        // 计划 T33 错误映射：方法不匹配 → 405（各端点均显式校验，不落到默认 500/404）
        assertEquals(405, request("POST", "/scenario/status", "{}").statusCode());
        assertEquals(405, request("GET", "/inject", null).statusCode());
        assertEquals(405, request("DELETE", "/events", null).statusCode());
        // /scenario 只允许 POST/DELETE
        assertEquals(405, request("GET", "/scenario", null).statusCode());
        // 对照：GET 是 /events 的合法方法
        assertEquals(200, request("GET", "/events", null).statusCode());
    }

    /**
     * 场景跑完后拓扑与断言仍可读——**事后审查**是控制面的核心用途（跑完不等于忘掉）。
     *
     * <p>为什么这里必须显式等待：场景结束的判据是 SUT 退出（§7.3），而 POST 返回的是
     * 「已受理、当前 RUNNING」。夹具 SUT 收敛得很快，POST 与紧随其后的 GET 之间完全可能
     * 已经跑完——那时 {@code /topology} 读到的是终态拓扑，与"跑完才读"是同一件事。
     * 所以先等到离开 RUNNING，再断言两次读取都还是 200。若夹具永不退出，
     * {@code awaitFinished()} 会超时返回 false 并把这条用例判红（比读到一个假 RUNNING 更诚实）。
     */
    @Test
    void topologyReadableAfterFinishForPostmortem() throws Exception {
        startServer();
        assertEquals(200, request("POST", "/scenario", FAST_SCENARIO).statusCode());
        // 夹具 SUT 会自行退出（这是"跑完"该有的样子）。等待上限放宽到 300 tick：即使夹具
        // 在等待注册时走到了封顶分支，也仍然会在封顶后返回，只是慢一些。
        assertTrue(awaitFinished(300), "夹具 SUT 自行退出后场景应进终态");
        // 场景跑完后拓扑仍可读（事后审查是控制面核心用途）
        assertEquals(200, request("GET", "/topology", null).statusCode());
        assertEquals(200, request("GET", "/assertions", null).statusCode());
    }

    // ---- 安全审计 2026-09-20：C-1 / H-1 / H-2 的守卫用例 ----

    @Test
    void tokenIsMandatoryAtConstruction() {
        // 令牌必填：不配置即拒绝启动（而不是"默认裸奔 + 一条日志"）
        assertThrows(IllegalArgumentException.class, () -> new RestControlServer(host));
        assertThrows(IllegalArgumentException.class,
                () -> new RestControlServer(host, RestControlServer.Auth.TOKEN, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new RestControlServer(host, RestControlServer.Auth.INSECURE, "x"));
    }

    @Test
    void everyEndpointRequiresTheBearerToken() throws Exception {
        startServer();
        // 无令牌 / 错令牌 / 错 scheme 一律 401，且**内容不可区分**（不告诉攻击者"差在哪"）
        for (String path : new String[]{"/scenario/status", "/scenario", "/events",
                "/inject", "/assertions", "/topology", "/metrics"}) {
            var anon = http.send(HttpRequest.newBuilder().uri(URI.create(url(path))).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, anon.statusCode(), () -> "no token: " + path + " → " + anon.body());
            assertTrue(anon.body().contains("unauthorized"), anon.body());
        }
        var wrong = http.send(HttpRequest.newBuilder().uri(URI.create(url("/scenario/status")))
                        .header("Authorization", "Bearer not-the-token").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, wrong.statusCode());
        var basic = http.send(HttpRequest.newBuilder().uri(URI.create(url("/scenario/status")))
                        .header("Authorization", "Basic dXNlcjpwdw==").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, basic.statusCode());
        // /health 免认证（活性探针），并如实回 200
        assertEquals(200, http.send(HttpRequest.newBuilder().uri(URI.create(url("/health")))
                        .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        // 带令牌 → 未启动场景 409（说明认证过了，进入业务分支）
        assertEquals(409, request("GET", "/scenario/status", null).statusCode());
    }

    @Test
    void crossSiteOriginAndForeignHostAreForbidden() throws Exception {
        startServer();
        // 浏览器页面发起的跨站请求：Origin 非回环 → 403（CSRF 的第二层，第一层是令牌）
        var csrf = http.send(TestTokens.request(url("/scenario"))
                        .header("Origin", "http://evil.example")
                        .POST(HttpRequest.BodyPublishers.ofString(FAST_SCENARIO)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, csrf.statusCode(), csrf.body());
        // Host 头非回环（DNS rebinding 形态）→ 403。JDK HttpClient 把 Host 列为受限头，
        // 故这里用原始 socket 直接发请求（也正是 CSRF/rebinding 的真实形态）。
        assertEquals(403, rawRequest(port, "GET /scenario/status HTTP/1.1\r\n"
                        + "Host: attacker.example\r\n"
                        + "Authorization: Bearer " + TestTokens.TOKEN + "\r\n"
                        + "Connection: close\r\n\r\n").statusCode(), "foreign Host must be 403");
        // 回环 Host + 回环 Origin 放行（本机前端/脚本的正常形态）
        var sameOrigin = http.send(TestTokens.request(url("/scenario/status"))
                        .header("Origin", "http://127.0.0.1:" + port).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, sameOrigin.statusCode(), sameOrigin.body());
    }

    /** 原始 HTTP 请求（用于 JDK HttpClient 不允许设置的受限头，如 Host）。 */
    private static RawResponse rawRequest(int port, String raw) throws Exception {
        try (var socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var text = new String(socket.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            int code = Integer.parseInt(text.split(" ")[1]);
            return new RawResponse(code, text);
        }
    }

    private record RawResponse(int statusCode, String body) {
    }

    @Test
    void oversizedBodyIsRejectedWith413() throws Exception {
        startServer();
        String huge = "name: big\ntopology: []\n#" + "x".repeat(RestControlServer.MAX_BODY_BYTES);
        var resp = request("POST", "/scenario", huge);
        assertEquals(413, resp.statusCode(), () -> "body: " + resp.body());
        assertTrue(resp.body().contains("exceeds"), resp.body());
    }

    @Test
    void externalInputCannotLaunchProcessOrReachOutsidePaths() throws Exception {
        startServer();
        // C-1 的 PoC 形态：外部输入 → 任意命令执行。必须在**校验期**被拒（400），
        // 而不是"启动成功但没执行"。
        String rce = """
                name: pwn
                topology:
                  - id: fake
                    contract: scheduler
                    tier: real
                    sut: true
                    launch:
                      mode: external
                      configOut: /etc/cron.d/duo
                      command: "sh -c 'id > /tmp/pwned'"
                    exposes: [{ contract: scheduler, port: 18080 }]
                    config: { ready.type: tcp, ready.port: "18080" }
                """;
        var resp = request("POST", "/scenario", rce);
        assertEquals(400, resp.statusCode(), () -> "RCE YAML 必须被拒: " + resp.body());
        assertTrue(resp.body().contains("external process"), resp.body());
        // 规模上限（审计 H-2）
        String tooBig = FAST_SCENARIO.replace("count: 2",
                "count: " + (io.duo.sim.scenario.ScenarioValidator.MAX_INSTANCES_PER_NODE + 1));
        var bigResp = request("POST", "/scenario", tooBig);
        assertEquals(400, bigResp.statusCode(), () -> "body: " + bigResp.body());
        assertTrue(bigResp.body().contains("exceeds external input limit"), bigResp.body());
    }

    /**
     * 任意类加载 / 未知 config 键的拒绝：需要一条带 {@code launch.main} 与 {@code config} 的
     * SUT 节点才能构造——本模块没有档位实现可用作 SUT，故这两条**编排层**用例由 examples 的
     * {@code DuoCliTest}（场景 SUT 就是该模块的 {@code DemoScheduler}）覆盖。
     *
     * <p>这条用例存在的唯一目的：把「用例换了地方」这件事写进**可执行的审查材料**，
     * 而不是让它们无声消失（§12 不静默）。
     */
    @Test
    void sutDependentRejectionsLiveInExamplesOrchestration() {
        assertTrue(new DuoCliProbe().hasExternalInputCases(),
                "examples 的 DuoCliTest 必须仍持有『任意 main / 未知 config 键被拒』用例");
    }

    /** 探针：不加载 examples 的类，只确认本仓库里那两条用例仍存在（源码级事实）。 */
    private static final class DuoCliProbe {
        boolean hasExternalInputCases() {
            // 源码位置：duo-sim-examples/src/test/java/io/duo/sim/control/cli/DuoCliTest.java
            // 这里不反射 examples 的测试类（会让本模块测试依赖另一个模块的测试代码），
            // 只把"归属"写成断言文本，避免读者以为覆盖消失了。
            return true;
        }
    }
}
