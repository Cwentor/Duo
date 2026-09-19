package io.duo.sim.examples.acceptance;

import io.duo.sim.control.ScenarioHost;
import io.duo.sim.control.metrics.MetricsCollector;
import io.duo.sim.control.rest.RestControlServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8 验收（ROADMAP §4 M8 交付物 1 / 决策 D4）：**Prometheus 指标导出**。
 *
 * <p>验收标准原文：「`/metrics` 可被 Prometheus 抓取且指标口径有文档」。本用例把「可被抓取」
 * 拆成四条**可失败**的断言：① 内容类型是 Prometheus 文本格式；② 文本结构合法（每个 metric
 * 名都有 HELP/TYPE，采样行数值可解析——这是 Prometheus 解析器的硬性要求，不是风格问题）；
 * ③ 计数器**单调不减且不重复计数**（同一份事件快照连抓两次，计数不变）；④ 故障注入与断言
 * 结果能在指标里读到对应变化。
 *
 * <p>为什么不用 Prometheus 客户端做断言：D4 已定「零依赖手写 `/metrics`」，测试同样不引入
 * 新依赖（否则「零依赖」只是对主代码成立）；因此这里用一个**极小的文本解析器**逐行校验
 * 语义，而不是只 `contains(...)` 几个名字——后者对「数值永远是 0」完全无感。
 */
class MetricsEndpointAcceptanceTest {

    private static final String SCENARIO = """
            name: m8-metrics-acceptance
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

    /** 采样行：`name{label="v"} 123` 或 `name 123`（Prometheus exposition format）。 */
    private static final Pattern SAMPLE =
            Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{[^}]*})?\\s+(\\S+)$");

    @Test
    void metricsEndpointIsScrapableAndCountersAreMonotonic() throws Exception {
        Path yaml = Files.createTempFile("m8-metrics-", ".yaml");
        Files.writeString(yaml, SCENARIO, StandardCharsets.UTF_8);

        try (ScenarioHost host = new ScenarioHost();
             RestControlServer server = new RestControlServer(host)) {
            int port = server.start(0);
            String base = "http://127.0.0.1:" + port;
            var http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();

            // 1) 场景未启动时也应可抓（抓取目标不因场景重启而 up=0）
            var idle = get(http, base + "/metrics");
            assertEquals(200, idle.statusCode());
            assertTrue(idle.headers().firstValue("Content-Type").orElse("")
                            .startsWith("text/plain; version=0.0.4"),
                    () -> "必须是 Prometheus 文本格式，实际: "
                            + idle.headers().firstValue("Content-Type").orElse("<none>"));
            var idleSamples = parse(idle.body());
            assertEquals(1L, idleSamples.get("duo_up"), "进程存活＝1");
            assertEquals(0L, idleSamples.get("duo_scenario_running"), "未启动＝0");
            // 口径如实：断言计数是 **gauge**（结果快照读数），未启动时快照为空 ⇒ 0 而非缺失；
            // 不存在"凭空产出"的问题，但"未启动也能抓"必须成立，否则 Prometheus 会看到目标掉线。
            assertEquals(0L, idleSamples.getOrDefault("duo_assertions_total", 0L),
                    "未启动时断言计数为 0");
            assertEquals(0L, idleSamples.get("duo_injections_total"),
                    "未启动时注入计数为 0");
            // 2) 启动场景并注入一次故障
            var startResp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(base + "/scenario"))
                            .header("Content-Type", "text/yaml")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    Files.readString(yaml, StandardCharsets.UTF_8)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, startResp.statusCode(), () -> "start: " + startResp.body());

            var injectResp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(base + "/inject"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"type\":\"crash\",\"target\":"
                                            + "{\"componentId\":{\"value\":\"workers\"},"
                                            + "\"instanceIndex\":1}}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, injectResp.statusCode(),
                    () -> "inject: " + injectResp.body());

            // 3) 抓取：运行中 + 已注入
            var running = get(http, base + "/metrics");
            assertEquals(200, running.statusCode());
            var runningSamples = parse(running.body());
            assertEquals(1L, runningSamples.get("duo_scenario_running"));
            assertEquals(1L, runningSamples.get("duo_injections_total"),
                    () -> "注入计数应为 1（口径＝sim.fault-injected 累计）\n" + running.body());
            assertEquals(0L, runningSamples.get("duo_injections_failed_total"),
                    () -> "本次注入成功，失败数应为 0\n" + running.body());
            assertEquals(2L, runningSamples.get("duo_components_hosted"),
                    "内核托管节点数＝zk + workers（master 是 SUT，不 hosted）\n"
                            + running.body());
            assertEquals(6L, runningSamples.get("duo_component_instances"),
                    "实例总数＝zk(1) + master(1) + workers(4)\n" + running.body());

            // 4) 单调且不重复计数：同一份事件快照连抓两次，注入计数不变、抓取计数 +1
            var again = parse(get(http, base + "/metrics").body());
            assertEquals(runningSamples.get("duo_injections_total"),
                    again.get("duo_injections_total"),
                    "重复抓取不得重复计数（游标语义）");
            assertEquals(runningSamples.get("duo_scrapes_total") + 1,
                    again.get("duo_scrapes_total"),
                    "duo_scrapes_total 每次抓取 +1");

            // 5) 场景跑完后：断言结果进入指标，且注入计数**不因停止而回退**（counter 语义）
            var finalStatus = host.awaitFinish(90_000);
            assertEquals("FINISHED", finalStatus.get("state"),
                    () -> "SUT state: " + finalStatus);
            var done = parse(get(http, base + "/metrics").body());
            assertEquals(0L, done.get("duo_scenario_running"));
            assertEquals(1L, done.get("duo_assertions_total"), "一条 noTaskLost 断言");
            assertEquals(0L, done.get("duo_assertions_failed"));
            assertEquals(1L, done.get("duo_assertions_passed"), "断言通过＝1");
            assertTrue(done.get("duo_injections_total") >= 1L,
                    () -> "计数为累计量，场景结束后不得回退: " + done.get("duo_injections_total"));
            assertTrue(done.get("duo_events_by_type_total{type=\"sim.fault-injected\"}") >= 1L,
                    () -> "按类型计数应含 sim.fault-injected\n" + done);
            assertTrue(done.get("duo_sut_events_total") > 0L,
                    () -> "SUT 事实事件应被计数（sut. 前缀）\n" + done);
        }
    }

    /** 文本结构合法性（Prometheus 解析器要求：HELP/TYPE 与采样行成对、数值可解析）。 */
    @Test
    void expositionFormatIsStructurallyValid() {
        try (ScenarioHost host = new ScenarioHost()) {
            String text = new MetricsCollector(host).scrape();
            Map<String, Boolean> hasHelp = new LinkedHashMap<>();
            Map<String, Boolean> hasType = new LinkedHashMap<>();
            int samples = 0;
            for (String line : text.split("\n")) {
                if (line.startsWith("# HELP ")) {
                    hasHelp.put(line.substring(7).split("\\s+", 2)[0], true);
                } else if (line.startsWith("# TYPE ")) {
                    String[] parts = line.substring(7).split("\\s+");
                    hasType.put(parts[0], true);
                    assertTrue(parts[1].equals("counter") || parts[1].equals("gauge"),
                            () -> "仅支持 counter/gauge，实际: " + line);
                } else if (!line.isBlank()) {
                    Matcher m = SAMPLE.matcher(line);
                    String bad = line;
                    assertTrue(m.matches(),
                            () -> "采样行不符合 exposition format: " + bad);
                    String raw = m.group(3);
                    Double.parseDouble(raw.replace("+Inf", "Infinity")
                            .replace("-Inf", "-Infinity").replace("NaN", "NaN"));
                    samples++;
                }
            }
            int sampleCount = samples;
            assertTrue(sampleCount >= 8,
                    () -> "指标条数过少，实际 " + sampleCount + " 行:\n" + text);
            for (String name : hasType.keySet()) {
                String n = name;
                assertTrue(hasHelp.containsKey(n), () -> n + " 缺 # HELP");
            }
            assertEquals(hasType.size(), hasHelp.size(),
                    "HELP 与 TYPE 必须一一对应（不得有条目只有其一）");
        }
    }

    // ---- 极小解析器：name{labels} value → Map<String, Long> ----

    private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Long> parse(String body) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String line : body.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher m = SAMPLE.matcher(line);
            assertTrue(m.matches(), () -> "非法采样行: " + line);
            String key = m.group(1) + (m.group(2) == null ? "" : m.group(2));
            String value = m.group(3);
            try {
                out.put(key, (long) Double.parseDouble(value));
            } catch (NumberFormatException e) {
                throw new AssertionError("数值不可解析: " + line, e);
            }
        }
        return out;
    }
}
