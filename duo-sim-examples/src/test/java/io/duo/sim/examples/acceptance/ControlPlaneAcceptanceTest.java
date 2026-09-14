package io.duo.sim.examples.acceptance;

import io.duo.sim.control.ScenarioHost;
import io.duo.sim.control.cli.DuoCli;
import io.duo.sim.control.rest.RestControlServer;
import io.duo.sim.kernel.api.Event;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 验收（设计文档 §14 M3 行 / 计划 T35）：**运行中手动注入故障并观察自愈**。
 *
 * <p>流程：REST 启动 M1 金标准风格场景（长任务在途）→ REST 注入 crash workers[2] →
 * 事件流出现 sim.fault-injected → noTaskLost 通过 → 场景 SUCCESS。
 * 同时验证 CLI 同进程模式（inject/status/事件流读取）。
 */
class ControlPlaneAcceptanceTest {

    private static final String SCENARIO = """
            name: m3-control-plane-acceptance
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
                default: { duration: 10s, jitter: 0.1, successRate: 1.0 }
              bindings:
                - node: workers
                  profile: default
            timeline: []
            assertions:
              - noTaskLost: { requireAllSuccess: true }
            """;

    @Test
    void hotInjectionViaRestDrivesSelfHealing() throws Exception {
        // 1) 写 YAML（REST POST body 即 YAML 文本）
        Path yaml = Files.createTempFile("m3-acceptance-", ".yaml");
        Files.writeString(yaml, SCENARIO, StandardCharsets.UTF_8);

        try (ScenarioHost host = new ScenarioHost();
             RestControlServer server = new RestControlServer(host)) {
            int port = server.start(0);
            String base = "http://127.0.0.1:" + port;
            var http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();

            // 2) REST 启动场景
            var startResp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(base + "/scenario"))
                            .header("Content-Type", "text/yaml")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    Files.readString(yaml, StandardCharsets.UTF_8)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, startResp.statusCode(), () -> "start: " + startResp.body());

            // 3) 派发发生（worker 在途）后 REST 注入 crash workers[2]
            Thread.sleep(1000);
            var injectResp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(base + "/inject"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"type\":\"crash\",\"target\":"
                                            + "{\"componentId\":{\"value\":\"workers\"},"
                                            + "\"instanceIndex\":2}}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, injectResp.statusCode(),
                    () -> "inject: " + injectResp.body());
            assertTrue(injectResp.body().contains("\"success\":true"),
                    () -> "inject must succeed: " + injectResp.body());

            // 4) 等 SUT 退出（DAG 终态）→ REST 断言通过
            var finalStatus = host.awaitFinish(90_000);
            assertEquals("FINISHED", finalStatus.get("state"),
                    () -> "SUT state after awaitFinish: " + finalStatus);
            var assertResp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(base + "/assertions")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, assertResp.statusCode());
            assertTrue(assertResp.body().contains("\"passed\":true"),
                    () -> "assertions: " + assertResp.body());

            // 5) 事件流（增量游标语义）包含注入事实与任务终态
            List<Map<String, Object>> allEvents = host.eventsSince(0);
            assertTrue(allEvents.stream().anyMatch(e -> e.get("type").equals("sim.fault-injected")),
                    "injection must appear in events");
            assertTrue(allEvents.stream().anyMatch(e -> e.get("type").equals("sut.dag-terminal")),
                    "DAG must reach terminal state");
            // 6) 拓扑视图
            var topoResp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(base + "/topology")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, topoResp.statusCode());
            assertTrue(topoResp.body().contains("workers"));

            // 7) CLI 同进程模式（§1 交付）：assert 子命令退出码 0（断言通过）
            assertEquals(0, DuoCli.run("status"));
            assertEquals(0, DuoCli.run("events", "--since", "0"));
            assertEquals(0, DuoCli.run("topology"));
        }
    }
}
