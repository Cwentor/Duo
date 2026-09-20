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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T33 单测：REST 端点往返与错误映射（JDK HttpClient）。
 *
 * <p>安全审计 2026-09-20 之后新增：认证（401）、跨站来源（403）、请求体上限（413）、
 * 外部输入档的能力收窄（400）——控制面是执行面，这些不是"加固选项"而是默认行为。
 */
class RestControlServerTest {

    private static final String FAST_SCENARIO = """
            name: rest-smoke
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
                default: { duration: 3s, jitter: 0.0, successRate: 1.0 }
              bindings:
                - node: workers
                  profile: default
            timeline: []
            assertions:
              - noTaskLost: { requireAllSuccess: true }
            """;

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

        // 启动（POST YAML）
        var startResp = request("POST", "/scenario", FAST_SCENARIO);
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
        assertEquals(409, request("POST", "/scenario", FAST_SCENARIO).statusCode());

        // DELETE 停止 → 200（异步收尾：立刻回状态，不阻塞在 SUT 收尾上）
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

    @Test
    void injectTargetUnresolvableIs404() throws Exception {
        startServer();
        request("POST", "/scenario", FAST_SCENARIO);
        // 等场景真正进入 RUNNING（POST 返回即 RUNNING，但组件派发仍需窗口）
        assertTrue(awaitRunning(), "scenario should reach RUNNING");
        Thread.sleep(500);
        // 未知组件 target → 内核注入失败（unknown target）→ 404
        var resp = request("POST", "/inject",
                "{\"type\":\"crash\",\"target\":{\"componentId\":{\"value\":\"ghost\"}}}");
        assertEquals(404, resp.statusCode(), () -> "body: " + resp.body());
    }

    private boolean awaitRunning() throws Exception {
        for (int i = 0; i < 40; i++) {
            var st = request("GET", "/scenario/status", null);
            if (st.statusCode() == 200 && st.body().contains("RUNNING")) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    @Test
    void malformedJsonIs400() throws Exception {
        startServer();
        // 需先启动场景（未启动时 /inject 走 409 分支，先于 JSON 解析）
        assertEquals(200, request("POST", "/scenario", FAST_SCENARIO).statusCode());
        var resp = request("POST", "/inject", "{not json");
        assertEquals(400, resp.statusCode());
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

    @Test
    void topologyReadableAfterFinishForPostmortem() throws Exception {
        startServer();
        assertEquals(200, request("POST", "/scenario", FAST_SCENARIO).statusCode());
        // 场景跑完后拓扑仍可读（事后审查是控制面核心用途）
        assertEquals(200, request("DELETE", "/scenario", null).statusCode());
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
        // 任意类加载同样被拒
        String arbitraryMain = FAST_SCENARIO.replace(
                "main: io.duo.sim.examples.scheduler.DemoScheduler",
                "main: java.lang.ProcessBuilder");
        var mainResp = request("POST", "/scenario", arbitraryMain);
        assertEquals(400, mainResp.statusCode(), () -> "body: " + mainResp.body());
        assertTrue(mainResp.body().contains("framework namespace"), mainResp.body());
        // 未知 config 键被拒（白名单，审计 M-1/M-2）
        String unknownKey = FAST_SCENARIO.replace("config: { dag.tasks: \"a,b\" }",
                "config: { totally.unknown.key: \"x\" }");
        var keyResp = request("POST", "/scenario", unknownKey);
        assertEquals(400, keyResp.statusCode(), () -> "body: " + keyResp.body());
        assertTrue(keyResp.body().contains("not accepted for external input"), keyResp.body());
        // 规模上限（审计 H-2）
        String tooBig = FAST_SCENARIO.replace("count: 2",
                "count: " + (io.duo.sim.scenario.ScenarioValidator.MAX_INSTANCES_PER_NODE + 1));
        var bigResp = request("POST", "/scenario", tooBig);
        assertEquals(400, bigResp.statusCode(), () -> "body: " + bigResp.body());
        assertTrue(bigResp.body().contains("exceeds external input limit"), bigResp.body());
    }
}
