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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T33 单测：REST 端点往返与错误映射（JDK HttpClient）。 */
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
    private final RestControlServer server = new RestControlServer(host);
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

    private HttpResponse<String> request(String method, String path, String body)
            throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (body != null) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        if ("DELETE".equals(method)) {
            builder = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + path))
                    .DELETE();
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

        // DELETE 停止 → 200
        assertEquals(200, request("DELETE", "/scenario", null).statusCode());
        var stopped = request("GET", "/scenario/status", null);
        assertEquals(200, stopped.statusCode());
        assertTrue(stopped.body().contains("FINISHED") || stopped.body().contains("FAILED"),
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
        Thread.sleep(300); // 派发窗口
        // 未知组件 target → 内核注入失败（unknown target）→ 404
        var resp = request("POST", "/inject",
                "{\"type\":\"crash\",\"target\":{\"componentId\":{\"value\":\"ghost\"}}}");
        assertEquals(404, resp.statusCode(), () -> "body: " + resp.body());
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
}
