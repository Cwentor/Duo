package io.duo.sim.control.rest;

import io.duo.sim.control.ScenarioHost;
import io.duo.sim.control.library.ScenarioLibrary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M10 计划一 Task 4：场景库端点契约（list/get/put/delete/fork/validate + 外部输入档 + 503）。 */
class RestConsoleApiTest {

    @TempDir
    Path libDir;

    private ScenarioHost host;
    private RestControlServer server;
    private HttpClient client;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        host = new ScenarioHost();
        server = new RestControlServer(host, RestControlServer.Auth.INSECURE, null,
                new ScenarioLibrary(libDir));
        port = server.start(0);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        server.close();
        host.close();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var req = switch (method) {
            case "GET" -> HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .GET().build();
            case "DELETE" -> HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .DELETE().build();
            case "PUT" -> HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            default -> HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body,
                            StandardCharsets.UTF_8)).build();
        };
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        return send(method, path, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(HttpResponse<String> resp) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(resp.body(), Map.class);
    }

    @Test
    void listSplitsTemplatesFromUserScenarios() throws Exception {
        send("PUT", "/api/scenarios/mine-01", VALID_YAML);
        var resp = send("GET", "/api/scenarios", null);
        assertEquals(200, resp.statusCode());
        var body = json(resp);
        var templates = (List<Map<String, Object>>) body.get("templates");
        var user = (List<Map<String, Object>>) body.get("user");
        assertTrue(templates.stream().anyMatch(t -> "worker-crash-failover".equals(t.get("id"))));
        assertTrue(user.stream().anyMatch(t -> "mine-01".equals(t.get("id"))));
    }

    @Test
    void putRejectsInvalidYamlWithPerIssueErrors() throws Exception {
        var resp = send("PUT", "/api/scenarios/bad-01", "name: [broken");
        assertEquals(400, resp.statusCode());
        assertTrue(((List<?>) json(resp).get("errors")).size() >= 1);
        // 外部输入档：launch.command 必须被拒
        var resp2 = send("PUT", "/api/scenarios/bad-02", """
                name: bad
                topology:
                  - id: m
                    contract: scheduler
                    tier: real
                    sut: true
                    launch: { mode: external, command: "calc.exe" }
                timeline: []
                assertions: []
                """);
        assertEquals(400, resp2.statusCode());
    }

    @Test
    void putToTemplateIs405AndDeleteTemplateIs405() throws Exception {
        assertEquals(405, send("PUT", "/api/scenarios/worker-crash-failover", VALID_YAML).statusCode());
        assertEquals(405, send("DELETE", "/api/scenarios/worker-crash-failover").statusCode());
    }

    @Test
    void traversalIdsAre400NeverTouchDisk() throws Exception {
        assertEquals(400, send("GET", "/api/scenarios/..%2F..%2Fsecret", null).statusCode());
        assertEquals(400, send("GET", "/api/scenarios/a%20b", null).statusCode());
        assertEquals(400, send("PUT", "/api/scenarios/..", VALID_YAML).statusCode());
    }

    @Test
    void forkCreatesUserCopyFromTemplate() throws Exception {
        var resp = send("POST", "/api/scenarios/worker-crash-failover/fork",
                "{\"id\":\"my-fork\"}");
        assertEquals(200, resp.statusCode(), resp.body());
        var got = send("GET", "/api/scenarios/my-fork", null);
        assertEquals(200, got.statusCode());
        assertTrue(((String) json(got).get("yaml")).contains("name:"));
    }

    @Test
    void validateEndpointReturnsOkFlag() throws Exception {
        var good = json(send("POST", "/api/scenarios/validate", VALID_YAML));
        assertEquals(Boolean.TRUE, good.get("ok"));
        var bad = json(send("POST", "/api/scenarios/validate", "name: [broken"));
        assertEquals(Boolean.FALSE, bad.get("ok"));
    }

    @Test
    void libraryNotConfiguredIs503() throws Exception {
        try (ScenarioHost h2 = new ScenarioHost();
             RestControlServer s2 = new RestControlServer(h2,
                     RestControlServer.Auth.INSECURE, null)) {
            int p2 = s2.start(0);
            var resp = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + p2 + "/api/scenarios")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, resp.statusCode());
        }
    }

    /** M10 计划一 Task 5：能力元数据（注入面板「动作×契约」下拉的唯一数据源）。 */
    @Test
    void capabilitiesListsProvidersWithSupportedFaults() throws Exception {
        var resp = send("GET", "/api/capabilities", null);
        assertEquals(200, resp.statusCode());
        var body = json(resp);
        var providers = (List<Map<String, Object>>) body.get("providers");
        // control 测试类路径上有 components 模块 ⇒ ServiceLoader 必然发现 virtual registry
        assertTrue(providers.stream().anyMatch(p ->
                "registry".equals(p.get("contract")) && "virtual".equals(p.get("tier"))));
        assertTrue(providers.stream().anyMatch(p ->
                p.get("supportedFaults") != null), "supportedFaults must be present");
    }

    /** M10 计划一 Task 5：serve 自述（关于页 + 前端能力探测）。 */
    @Test
    void metaDescribesServeEnvironment() throws Exception {
        var resp = send("GET", "/api/meta", null);
        assertEquals(200, resp.statusCode());
        var body = json(resp);
        assertEquals("INSECURE", body.get("auth"));
        assertTrue(body.containsKey("version"));
        assertTrue(new java.io.File((String) body.get("libraryDir")).isAbsolute(),
                "libraryDir must be an absolute path");
    }

    /** M10 计划一 Task 6：手动清除注入（freeze 为组件级动作，寻址不带 instanceIndex）。 */
    @Test
    void clearInjectionAfterInjecting() throws Exception {
        var startResp = send("POST", "/scenario", CLEAR_SCENARIO);
        assertEquals(200, startResp.statusCode(), startResp.body());
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && send("GET", "/scenario/status", null).statusCode() != 200) {
            Thread.sleep(100);
        }
        assertEquals(200, send("GET", "/scenario/status", null).statusCode());

        var faultBody = """
                {"type":"freeze",
                 "target":{"componentId":{"value":"workers"}},
                 "params":{}}
                """;
        // 注入（在组件注册完成前可能 unknown target：有界重试）
        boolean injected = false;
        for (int i = 0; i < 20 && !injected; i++) {
            var r = json(send("POST", "/inject", faultBody));
            injected = Boolean.TRUE.equals(r.get("success"));
            if (!injected) {
                Thread.sleep(100);
            }
        }
        assertTrue(injected, "inject freeze should succeed");
        var cleared = json(send("POST", "/api/inject/clear", faultBody));
        assertEquals(Boolean.TRUE, cleared.get("success"),
                "clear should succeed: " + cleared.get("reason"));
    }

    /** M10 计划一 Task 7：诊断链服务端一等化（复用 CLI duo diagnose 同一套 FaultDiagnostics）。 */
    @Test
    void diagnoseReturnsChainsAfterInjection() throws Exception {
        var startResp = send("POST", "/scenario", CLEAR_SCENARIO);
        assertEquals(200, startResp.statusCode());
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && send("GET", "/scenario/status", null).statusCode() != 200) {
            Thread.sleep(100);
        }
        String faultBody = """
                {"type":"freeze",
                 "target":{"componentId":{"value":"workers"}},
                 "params":{}}
                """;
        for (int i = 0; i < 20; i++) {
            if (Boolean.TRUE.equals(json(send("POST", "/inject", faultBody)).get("success"))) {
                break;
            }
            Thread.sleep(100);
        }
        // 给事件流一点时间落盘
        Thread.sleep(500);
        var resp = send("GET", "/diagnose", null);
        assertEquals(200, resp.statusCode());
        var body = json(resp);
        assertTrue(body.containsKey("chains"), "report must expose chains");
        assertTrue(((Number) body.get("eventsTotal")).intValue() >= 1);
    }

    /** M10 计划一 Task 8：SPA 静态托管（免令牌 + CSP + SPA fallback + 穿越拒绝）。 */
    @Test
    void staticAssetsServedWithCspAndTraversalRejected() throws Exception {
        var index = client.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/console/index.html")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, index.statusCode());
        assertEquals("text/html", index.headers().firstValue("Content-Type").orElse("")
                .split(";")[0].trim());
        assertEquals("default-src 'self'",
                index.headers().firstValue("Content-Security-Policy").orElse("").trim());
        assertEquals("nosniff",
                index.headers().firstValue("X-Content-Type-Options").orElse("").trim());
        assertTrue(index.body().contains("Duo Console"));

        // SPA fallback：无扩展名路径回退 index.html
        var route = client.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/console/library/my-drill")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, route.statusCode());

        // 穿越：.. 必须拒绝，绝不触碰文件路径语义
        var trav = client.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/console/../rest/RestControlServer.class")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(trav.statusCode() == 400 || trav.statusCode() == 404,
                "traversal must be rejected, got " + trav.statusCode());

        // 根路径也出控制台
        var root = client.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, root.statusCode());
        assertTrue(root.body().contains("Duo Console"));
    }

    private static final String CLEAR_SCENARIO = """
            name: clear-smoke
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

    private static final String VALID_YAML = """
            name: api-smoke
            topology:
              - id: zk
                contract: registry
                tier: virtual
              - id: master
                contract: scheduler
                tier: virtual
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
                default: { duration: 3s, jitter: 0.0, successRate: 1.0 }
              bindings:
                - node: workers
                  profile: default
            timeline: []
            assertions:
              - noTaskLost: { requireAllSuccess: true }
            """;
}
