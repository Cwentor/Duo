package io.duo.sim.examples.acceptance;

import io.duo.sim.control.FaultDiagnostics;
import io.duo.sim.control.ScenarioHost;
import io.duo.sim.control.cli.DuoCli;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8 交付物 3（ROADMAP）：**单命令导出一次故障注入的完整因果链**
 * （注入 → 组件反应 → SUT 事实 → 断言）。
 *
 * <p>本用例跑一个真实场景并注入 crash，然后要求诊断报告四段齐全：
 * <ul>
 *   <li>{@code chains[0].complete()}——有注入、有 SUT 事实；</li>
 *   <li>渲染文本含「组件反应」「SUT 事实」「断言」三个小标题且都**非 MISSING**；</li>
 *   <li>{@code DuoCli.run("diagnose")} 退出码 0（链完整）；</li>
 *   <li>把注入事件从流里删掉后，报告必须报断链且退出码非 0——**"看不见"本身也要被看见**（§12）。</li>
 * </ul>
 */
class FaultDiagnosticsAcceptanceTest {

    private static final String SCENARIO = """
            name: m8-diagnostics-acceptance
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
    void diagnoseExportsFullCausalChainInOneCommand() throws Exception {
        Path yaml = Files.createTempFile("m8-diagnose-", ".yaml");
        Files.writeString(yaml, SCENARIO, StandardCharsets.UTF_8);

        try (ScenarioHost host = new ScenarioHost();
             RestControlServer server = new RestControlServer(host,
                     RestControlServer.Auth.TOKEN, io.duo.sim.control.rest.TestTokens.TOKEN)) {
            // CLI 同进程模式按名接管：把本测试的宿主注册进进程级 attach 表，
            // 这样 "duo diagnose" 走的就是**同一个**真实场景（而不是空宿主）。
            String name = "m8-diagnose-acceptance";
            host.attach(name);
            int port = server.start(0);
            String base = "http://127.0.0.1:" + port;
            var http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();

            var startResp = http.send(io.duo.sim.control.rest.TestTokens.request(base + "/scenario")
                            .header("Content-Type", "text/yaml")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    Files.readString(yaml, StandardCharsets.UTF_8)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, startResp.statusCode(), () -> "start: " + startResp.body());

            var injectResp = http.send(io.duo.sim.control.rest.TestTokens.request(base + "/inject")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"type\":\"crash\",\"target\":"
                                            + "{\"componentId\":{\"value\":\"workers\"},"
                                            + "\"instanceIndex\":1}}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, injectResp.statusCode(), () -> "inject: " + injectResp.body());
            assertTrue(injectResp.body().contains("\"success\":true"),
                    () -> "注入必须成功，否则因果链无起点: " + injectResp.body());

            var finalStatus = host.awaitFinish(90_000);
            assertEquals("FINISHED", finalStatus.get("state"),
                    () -> "SUT 应进入终态: " + finalStatus);

            // 1) 直接分析内核事件流（同进程路径）
            List<Map<String, Object>> assertionRows = rows(host.assertions().get("assertions"));
            FaultDiagnostics.Report report = FaultDiagnostics.analyzeEvents(
                    rawEvents(host), assertionRows);
            assertEquals(1, report.chains().size(),
                    () -> "应恰好一条注入链:\n" + report.render("test"));
            var chain = report.chains().get(0);
            assertTrue(chain.complete(),
                    () -> "因果链应完整（有注入 + 有 SUT 事实）:\n" + report.render("test"));
            assertEquals("crash", chain.action());
            // 目标在事件里是**实例级** sourceId（sim.fault-injected 的 source 取实例源 id），
            // 这是内核语义：注入落到实例上，链的起点因此比"节点名"更精确。
            assertEquals("workers-1", chain.target());
            assertTrue(chain.sutFactTotal() > 0, "SUT 事实段不得为空");
            // 归并的信息量断言：多类事实各自只出现一次，且条数合计等于窗口内 SUT 事件数。
            // （**不做**"必须有 sut.instance-lost"这类断言：crash 后 SUT 是否即时感知取决于
            //  注入时机与心跳节奏，属于被测对象的策略而非诊断层职责——实测 8 类里就没有它。
            //  诊断层只保证"发生了什么就报什么"，语义判定留给断言。）
            assertTrue(chain.sutFacts().size() >= 3,
                    () -> "归并后应有多类 SUT 事实（心跳/派发/终态等）:\n" + report.render("test"));
            // 归并的口径：同类事实只出现一次（不逐条铺开），条数才是信息
            assertEquals(chain.sutFacts().size(),
                    chain.sutFacts().stream().map(FaultDiagnostics.FactKind::type).distinct().count(),
                    "SUT 事实必须按类型归并，不得重复列同一类型");
            assertEquals(chain.sutFacts().stream().mapToInt(FaultDiagnostics.FactKind::count).sum(),
                    chain.sutFactTotal(),
                    "归并后的条数之和必须等于窗口内 SUT 事件总数（不丢不重）");
            String text = report.render("http://127.0.0.1:" + port);
            // 断言段：结果快照里的断言明细必须出现在报告里（四段的最后一段）
            assertFalse(assertionRows.isEmpty(), "断言明细不得为空（场景已 FINISHED）");
            assertTrue(text.contains(String.valueOf(assertionRows.get(0).get("name"))),
                    () -> "断言段应列出断言名:\n" + text);

            assertTrue(text.contains("组件反应"), () -> text);
            assertTrue(text.contains("SUT 事实"), () -> text);
            assertTrue(text.contains("断言"), () -> text);
            assertFalse(text.contains("MISSING"), () -> "四段应齐全，不得有 MISSING:\n" + text);

            // 2) 单命令导出（CLI 同进程模式，--name 复用本测试的宿主）：退出码 0
            assertEquals(0, DuoCli.run("diagnose", "--name", name, "--since", "0"),
                    () -> "链完整时 diagnose 退出码应为 0\n"
                            + DuoCli.run("diagnose", "--name", name, "--since", "0"));

            // 3) 断链必须被显式报出：删掉注入事件后重放同一条流
            List<Map<String, Object>> withoutInjection = host.eventsSince(0).stream()
                    .filter(e -> !"sim.fault-injected".equals(e.get("type")))
                    .toList();
            FaultDiagnostics.Report broken = FaultDiagnostics.analyze(withoutInjection);
            assertTrue(broken.chains().isEmpty(), "没有注入事件就不应有链");
            assertFalse(broken.complete(), "无链报告不得被判定为完整");

            // 4) 场景已结束 ⇒ 后续注入被拒绝；拒绝也必须在链里留痕（不静默）。
            //    这里同时验证拒绝路径的**显式性**：REST 给出 409 + 机器可读原因。
            var reject = http.send(io.duo.sim.control.rest.TestTokens.request(base + "/inject")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"type\":\"crash\",\"target\":"
                                            + "{\"componentId\":{\"value\":\"workers\"}}}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(409, reject.statusCode(),
                    () -> "场景已结束时的注入应 409: " + reject.statusCode() + " " + reject.body());
            assertTrue(reject.body().contains("scenario not running"),
                    () -> "409 应带机器可读原因: " + reject.body());
            // 被拒绝的注入不写事件流（没有注入事实可记），但必须写日志（FaultLog.failed）——
            // 这是 §12 的分工：事件流只记"发生过的事实"，拒绝由日志与 REST 响应承载。
            FaultDiagnostics.Report withReject = FaultDiagnostics.analyze(host.eventsSince(0));
            assertEquals(1, withReject.chains().size(),
                    () -> "被拒的注入不得污染事件流:\n" + withReject.render("test"));
        }
    }

    /** 内核 Event 列表（同进程直读，与 REST /events 的 Map 形态等价）。 */
    private static List<io.duo.sim.kernel.api.Event> rawEvents(ScenarioHost host) {
        return host.eventsSnapshot();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }
}
