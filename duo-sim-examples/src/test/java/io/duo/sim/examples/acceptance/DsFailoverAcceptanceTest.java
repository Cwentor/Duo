package io.duo.sim.examples.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.scenario.ScenarioEngine;
import io.duo.sim.scenario.ScenarioLoader;
import io.duo.sim.scenario.model.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M9 Phase A 验收（ROADMAP §4 M9；计划
 * {@code docs/superpowers/plans/2026-09-25-duo-m9-dolphinscheduler-integration-plan.md}）：
 * 首个**真实第三方系统**接入——DolphinScheduler 3.4.3
 * standalone 以 external SUT 形态运行，registry 由 DS 缺省 jdbc 翻转到 Duo 的
 * CuratorRegistry（embedded 档 JVM 内真 ZK，D-M9-2），完成一次端到端 registry-flap 演练：
 *
 * <pre>
 * 启动（startSut 预启动 zk → configOut 端点告知 → wrapper 注入 ZK 端点 → DS 就绪探针）
 *   → OpenAPI 编排最小工作流（3×HTTP 慢任务链，测试 JVM 托管慢应答器）
 *   → 实例确认在途后热注入 registry-flap（TestingServer 整服重启，端口复用，实测 67ms）
 *   → DS 3.4.3 的真实容错选择：Curator LOST（SUSPENDED 后约 20s）⇒ Master/Worker/Alert
 *     全进程【受控自停】（反脑裂设计——不重注册、不续跑、优雅关停，非崩溃）
 *   → 断言（三封闭通道）：
 *     ① Duo 侧 flap 先因后果完整落流；② sut.exited 晚于 flap（因果）且非 sut.crashed；
 *     ③ 死前 OpenAPI 回读确认过 RUNNING_EXECUTION（防空真）+ 真实任务执行过
 * </pre>
 *
 * <p><b>语义依据（drill 第 8 轮实测，2026-09-25）</b>：整服 ZK 闪断 ⇒ 会话与临时节点
 * 不可恢复（Duo embedded 档 flap 的设计语义，非缺陷：ZK 同端口 67ms 复活、门面重连成功）。
 * DS 对"会话死亡"的应答是自停——生产侧的续跑语义由 HA 多 master 容错承接，单实例
 * standalone 无此能力。本演练**不预设立场**：SUT 观测到什么就断言什么，这是 Duo 与真实
 * 系统对齐的目的本身（原假设「DS 自愈续跑→SUCCESS」被实测推翻，按诚实纪律改写断言）。
 *
 * <p>断言口径（D-M9-3 三封闭通道，不新增第四条）：YAML 断言只取 Duo 可观测事实。
 * **防空真守护**：注入发生在 {@code RUNNING_EXECUTION} 确认之后——flap 必须命中在途的
 * 工作流，而不是空转期。
 *
 * <p>Windows 演练形态（T-M9-0 实测结论，计划 v1.1/v1.2）：DS task-shell 在 Windows 不可用
 * （sudo -u 无对应实现 + .sh 无法直启，CreateProcess error=2），工作流改用 HTTP 任务
 * （纯 Java 任务类型），以测试托管的慢应答器（每请求延迟 8s）制造受控在途窗口。
 *
 * <p>门控（D-M9-4）：{@code -Dduo.ds=true} 才运行，默认可见 skip。环境准备
 * （DS 包/JDK11/启动器 wrapper）见 {@code docs/DEVELOPMENT.md} M9 节，非仓库工件。
 */
@EnabledIfSystemProperty(named = "duo.ds", matches = "true")
class DsFailoverAcceptanceTest {

    /** DS standalone 的 API/UI 固定端口（环境事实，T-M9-0 钉死）。 */
    private static final int DS_PORT = 12345;
    /** 单个 HTTP 任务的目标延迟：3×8s 链 ≈ 24s+ 的在途窗口。 */
    private static final Duration TASK_DELAY = Duration.ofSeconds(8);
    /**
     * flap 后等 SUT 受控自停的预算：SUSPENDED→LOST 约 20s（TestingServer 会话上限封顶
     * DS 要的 60s）+ 优雅关停数秒 + 余量；第 8 轮实测注入→退出 23s。
     */
    private static final long SUT_EXIT_BUDGET_MS = 90_000;

    @TempDir
    Path tmp;

    private ServerSocket slowSocket;
    private Thread slowThread;
    private volatile boolean slowRunning;
    private final List<String> slowHits = new CopyOnWriteArrayList<>();

    @Test
    void registryFlapDrivesDsToObservedControlledTermination() throws Exception {
        String wrapper = System.getProperty("duo.ds.wrapper",
                "C:/Users/cwt15/devtools/ds-launch-duo.ps1");
        assertTrue(Files.isRegularFile(Path.of(wrapper)),
                () -> "duo.ds.wrapper 启动器不存在: " + wrapper + "（M9 环境准备见 docs/DEVELOPMENT.md §M9）");

        int slowPort = startSlowResponder();
        Path configOut = tmp.resolve("duo-ds.properties");
        Path scenarioYaml = materializeScenario(tmp, wrapper, configOut);

        Scenario scenario = ScenarioLoader.load(scenarioYaml);
        var registry = io.duo.sim.kernel.core.ContractRegistry.loadFromServiceLoader();
        ScenarioEngine engine = ScenarioEngine.validated(scenario, registry);
        Process ds = null;
        try {
            // ① 启动链：startSut() 预启动 wiring 依赖 zk → 写端点告知 → 代起 DS → http 探针就绪
            engine.startSut();
            engine.startComponents(); // zk 已被预启动接管，无新增组件
            assertTrue(engine.externalSut().readyConfirmed(), "DS ready 探针必须确认");
            ds = engine.externalSut().process();
            assertNotNull(ds, "代起形态必须暴露进程句柄（D7）");

            // ② 端点告知主途径：内核写出的真实 ZK 端点（DS 的 wrapper 从 duo.config 读它注入）
            String notified = Files.readString(configOut);
            assertTrue(notified.contains("duo.endpoint.registry=127.0.0.1:"),
                    () -> "端点告知文件必须含真实 ZK 端点:\n" + notified);

            // ③ OpenAPI 编排最小工作流（T-M9-2 序列，fresh H2 每次启动都是全新库）
            DolphinSchedulerApi dsApi = DolphinSchedulerApi.login();
            long projectCode = dsApi.createProject("m9-drill-" + System.currentTimeMillis());
            long workflowCode = dsApi.createHttpChainWorkflow(projectCode, 3, slowPort);
            dsApi.releaseOnline(projectCode, workflowCode);
            dsApi.startWorkflow(projectCode, workflowCode);

            // ④ 防空真：实例确认在途后才注入——flap 必须命中运行中的工作流。
            //    duo.ds.noflap=true（负例取证开关，T-M9-3⑥）：跳过注入——此时 DS 不会自停，
            //    下方 awaitSutExit 必须红。该分支只为证明「自停断言依赖真实注入，防空真」，
            //    取证记录见验收记录 §6.1；缺省 false，正常路径不受影响。
            long instanceId = dsApi.awaitInstance(projectCode, workflowCode, 30_000);
            dsApi.awaitInstanceState(projectCode, workflowCode, instanceId,
                    "RUNNING_EXECUTION", 120_000);
            long flapAt = System.nanoTime();
            if (!Boolean.getBoolean("duo.ds.noflap")) {
                engine.inject(new FaultAction(FaultAction.REGISTRY_FLAP,
                        FaultAction.ComponentAddress.of(new ComponentId("zk")), Map.of(), null));
            }

            // ⑤ SUT 真实容错语义（第 8 轮实测钉死）：整服闪断 ⇒ 会话不可恢复 ⇒
            //    Curator LOST ⇒ Master/Worker/Alert 受控自停。观测它，而不是假设它。
            awaitSutExit(ds, SUT_EXIT_BUDGET_MS);
            long exitAfterFlapMs = (System.nanoTime() - flapAt) / 1_000_000;

            // ⑥ 三通道断言（D-M9-3）：
            //    ① Duo 侧 flap 先因后果完整落流；② 自停晚于 flap（因果）且受控
            //    （sut.exited=0，非 sut.crashed）；③ 死前已确认 RUNNING + 真实任务执行过。
            engine.stop();
            List<String> types = engine.events().stream().map(Event::type).toList();
            int injected = types.indexOf("sim.fault-injected");
            int flapStarted = types.indexOf("sim.registry-flap-started");
            int flapCleared = types.indexOf("sim.registry-flap-cleared");
            int exitedAt = types.indexOf("sut.exited");
            assertTrue(injected >= 0 && flapStarted > injected && flapCleared > flapStarted,
                    () -> "flap 事实序列必须按序（fault-injected → flap-started → flap-cleared）: " + types);
            assertTrue(exitedAt > flapCleared, () -> "SUT 自停必须晚于 flap 清除（因果顺序）"
                    + "（注入→退出 " + exitAfterFlapMs + "ms）: " + types);
            assertFalse(types.contains("sut.crashed"),
                    () -> "DS 的自停是受控行为，不得记录为崩溃: " + types);
            assertTrue(slowHits.size() >= 1,
                    () -> "flap 前必须有真实任务被执行过（防空真）: " + slowHits);
            // 撤除断言记录（诚实纪律）：原「无 sut.exited / 进程存活 / left-running /
            // 工作流终态 SUCCESS / 慢任务×3」系「DS 会自愈续跑」假设的产物；第 8 轮实测
            // 推翻该假设——DS 对整服闪断的选择是受控自停，工作流随之中止（无终态可回读，
            // API 随进程消亡）。按观测到的真实语义改写本节，不做无证据的期望。

            var snapshot = engine.result().snapshot();
            assertTrue(snapshot.injectionFailures().isEmpty(),
                    () -> "无注入失败: " + snapshot.injectionFailures());
            assertFalse(snapshot.assertions().isEmpty(), "YAML 断言必须被评估");
            snapshot.assertions().forEach(a -> assertTrue(a.passed(),
                    () -> "断言 '" + a.name() + "' 失败: " + a.detail()));
            assertTrue(engine.result().passed(), "场景整体必须 pass");
        } finally {
            stopSlowResponder();
            try {
                engine.stop();
            } catch (RuntimeException e) {
                // 收尾尽力而为：失败路径上 stop 不得吞掉原始断言错误
            }
            // 生命周期归测试（§7.3）：正常终态下回收 DS 进程（M6 killProcess 同款收尾）
            if (ds != null && ds.isAlive()) {
                ds.destroyForcibly();
            }
        }
    }

    // ---- 慢应答器：DS HTTP 任务的受控延迟端点（在途窗口） ----

    /**
     * 轮询等待 SUT 进程退出。DS 3.4.3 对整服 registry 闪断的受控自停实测约 23s
     * （SUSPENDED→LOST ≈20s + 优雅关停）；超时未见退出即失败——SUT 行为变化须显式更新语义。
     */
    private static void awaitSutExit(Process ds, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (ds.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(500);
        }
        assertTrue(!ds.isAlive(), () -> "DS 未在 " + timeoutMs + "ms 内按其容错语义自停——"
                + "若 DS 行为变化（如新增重注册路径），请按新实测改写本演练断言");
    }

    /** 绑定 127.0.0.1 随机端口；每个请求读完后延迟 {@link #TASK_DELAY} 再应答 200。 */
    private int startSlowResponder() throws IOException {
        slowSocket = new ServerSocket();
        slowSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        slowRunning = true;
        long bootAt = System.currentTimeMillis();
        slowThread = new Thread(() -> {
            while (slowRunning) {
                try (Socket s = slowSocket.accept()) {
                    s.setSoTimeout(2_000);
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        // 读完请求头即可（GET 无 body）
                    }
                    Thread.sleep(TASK_DELAY.toMillis());
                    byte[] body = "duo-m9-slow-ok".getBytes(StandardCharsets.US_ASCII);
                    String head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n";
                    s.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
                    s.getOutputStream().write(body);
                    s.getOutputStream().flush();
                    slowHits.add("hit-" + (slowHits.size() + 1) + " @t+"
                            + (System.currentTimeMillis() - bootAt) + "ms");
                } catch (Exception e) {
                    if (slowRunning) {
                        System.out.println("[m9-slow-responder] " + e);
                    }
                }
            }
        }, "m9-slow-responder");
        slowThread.setDaemon(true);
        slowThread.start();
        return slowSocket.getLocalPort();
    }

    private void stopSlowResponder() {
        slowRunning = false;
        try {
            if (slowSocket != null && !slowSocket.isClosed()) {
                slowSocket.close();
            }
        } catch (IOException e) {
            // 尽力而为
        }
    }

    // ---- 场景模板：classpath 模板 + 机器相关占位符替换（M6 external 场景同款惯例） ----

    /**
     * 物化场景模板（包私有 static：`DsFailoverDrillGuardTest` 守卫复用同一替换规则，
     * 避免两份物化逻辑漂移）。占位符＝机器相关路径，由调用方注入。
     */
    static Path materializeScenario(Path tmp, String wrapper, Path configOut) throws IOException {
        String template;
        try (InputStream in = DsFailoverAcceptanceTest.class
                .getResourceAsStream("/scenarios/m9-ds-failover.yaml")) {
            assertNotNull(in, "场景模板缺失: src/test/resources/scenarios/m9-ds-failover.yaml");
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String yaml = template
                .replace("@duo.ds.wrapper@", wrapper.replace('\\', '/'))
                .replace("@duo.ds.configOut@", configOut.toAbsolutePath().toString().replace('\\', '/'));
        Path out = tmp.resolve("m9-ds-failover.yaml");
        Files.writeString(out, yaml, StandardCharsets.UTF_8);
        return out;
    }

    // ---- DolphinScheduler 3.4.3 OpenAPI 最小客户端（T-M9-2，实测序列） ----

    /**
     * DS v1 API 封装：会话 cookie 登录 + 表单参数调用（探针实测口径，3.4.3 路由）。
     * 应答包络 {@code {code, msg, data}}，{@code code != 0} 即抛错（不静默）。
     */
    static final class DolphinSchedulerApi {

        private static final String BASE = "http://127.0.0.1:" + DS_PORT + "/dolphinscheduler";
        private static final DateTimeFormatter DS_TIME =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .cookieHandler(new java.net.CookieManager())
                .build();
        private final ObjectMapper om = new ObjectMapper();

        static DolphinSchedulerApi login() {
            DolphinSchedulerApi api = new DolphinSchedulerApi();
            JsonNode resp = api.post("/login", Map.of(
                    "userName", "admin",
                    "userPassword", "dolphinscheduler123"));
            assertEquals(0, resp.path("code").asInt(), () -> "DS 登录失败: " + resp);
            return api;
        }

        /** 建项目 → data.code（10019 已存在视为环境复用，走查询兜底）。 */
        long createProject(String name) {
            JsonNode resp = post("/projects", Map.of(
                    "projectName", name, "description", "duo m9 drill"));
            if (resp.path("code").asInt() == 10019) {
                return findProject(name);
            }
            assertEquals(0, resp.path("code").asInt(), () -> "建项目失败: " + resp);
            return resp.path("data").path("code").asLong();
        }

        private long findProject(String name) {
            JsonNode resp = get("/projects?pageNo=1&pageSize=20&searchVal=" + url(name));
            for (JsonNode row : resp.path("data").path("totalList")) {
                if (name.equals(row.path("name").asText())) {
                    return row.path("code").asLong();
                }
            }
            throw new IllegalStateException("project not found after create(10019): " + name);
        }

        /** 3×HTTP 慢任务链 t1→t2→t3：gen 码 → taskDefinitionJson + taskRelationJson → 保存 → defCode。 */
        long createHttpChainWorkflow(long projectCode, int taskCount, int slowPort) {
            JsonNode codesResp = get("/projects/" + projectCode
                    + "/task-definition/gen-task-codes?genNum=" + taskCount);
            assertEquals(0, codesResp.path("code").asInt(), () -> "生成任务码失败: " + codesResp);
            List<String> codes = new ArrayList<>();
            codesResp.path("data").forEach(c -> codes.add(c.asText()));

            ArrayNode tasks = om.createArrayNode();
            for (int i = 0; i < taskCount; i++) {
                ObjectNode params = om.createObjectNode();
                params.putArray("localParams");
                params.putArray("resourceList");
                params.put("url", "http://127.0.0.1:" + slowPort + "/slow");
                params.put("httpMethod", "GET");
                params.put("httpCheckCondition", "STATUS_CODE_DEFAULT");
                params.put("condition", "");
                params.put("connectTimeout", 60_000);
                params.put("socketTimeout", 60_000);

                ObjectNode task = om.createObjectNode();
                task.put("code", codes.get(i));
                task.put("name", "t" + (i + 1));
                task.put("taskType", "HTTP");
                task.set("taskParams", params);
                task.put("flag", "YES");
                task.put("taskPriority", "MEDIUM");
                task.put("workerGroup", "default");
                task.put("failRetryTimes", 0);
                task.put("failRetryInterval", 1);
                task.put("timeoutFlag", "CLOSE");
                task.put("timeoutNotifyStrategy", "WARN");
                task.put("timeout", 0);
                task.put("delayTime", 0);
                task.put("taskExecuteType", "BATCH");
                task.put("description", "duo m9 failover drill");
                tasks.add(task);
            }

            ArrayNode relations = om.createArrayNode();
            for (int i = 0; i < taskCount; i++) {
                String pre = i == 0 ? "0" : codes.get(i - 1);
                ObjectNode rel = om.createObjectNode();
                rel.put("name", "");
                rel.put("preTaskCode", pre);
                rel.put("preTaskVersion", i == 0 ? "0" : "1");
                rel.put("postTaskCode", codes.get(i));
                rel.put("postTaskVersion", "1");
                rel.put("conditionType", "NONE");
                rel.set("conditionParams", om.createObjectNode());
                relations.add(rel);
            }

            JsonNode resp = post("/projects/" + projectCode + "/workflow-definition", Map.of(
                    "name", "m9-failover-flow",
                    "description", "duo m9 failover drill",
                    "locations", "[]",
                    "taskDefinitionJson", json(tasks),
                    "taskRelationJson", json(relations)));
            assertEquals(0, resp.path("code").asInt(), () -> "建工作流失败: " + resp);
            return resp.path("data").path("code").asLong();
        }

        private String json(com.fasterxml.jackson.databind.JsonNode node) {
            try {
                return om.writeValueAsString(node);
            } catch (IOException e) {
                throw new IllegalStateException("JSON 序列化失败（不可恢复，属编程错误）", e);
            }
        }

        /** 上线（ONLINE）后才能启动实例。 */
        void releaseOnline(long projectCode, long workflowCode) {
            JsonNode resp = post("/projects/" + projectCode + "/workflow-definition/"
                    + workflowCode + "/release", Map.of("releaseState", "ONLINE"));
            assertEquals(0, resp.path("code").asInt(), () -> "上线失败: " + resp);
        }

        /**
         * 启动实例。注意：3.4.3 的 start 响应 {@code data} **不携带实例 id**（实测 data=0/空，
         * 2026-09-25 drill 第 7 轮取证），实例 id 一律经 {@link #awaitInstance} 查询发现——
         * 禁止把 {@code data.asInt()} 当 id 用（会静默得到 0，等待循环永不匹配）。
         */
        void startWorkflow(long projectCode, long workflowCode) {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("workflowDefinitionCode", String.valueOf(workflowCode));
            form.put("scheduleTime", DS_TIME.format(LocalDateTime.now()));
            form.put("failureStrategy", "END");
            form.put("warningType", "NONE");
            form.put("workflowInstancePriority", "MEDIUM");
            form.put("tenantCode", "default");
            JsonNode resp = post("/projects/" + projectCode
                    + "/executors/start-workflow-instance", form);
            assertEquals(0, resp.path("code").asInt(), () -> "启动失败: " + resp);
        }

        /** 实例列表查询（共享）：查询失败 ≠ 实例不存在（§12 不静默），直接抛出失败应答。 */
        private JsonNode queryInstances(long projectCode, long workflowCode) {
            JsonNode resp = get("/projects/" + projectCode + "/workflow-instances"
                    + "?workflowDefinitionCode=" + workflowCode + "&pageNo=1&pageSize=10");
            if (resp.path("code").asInt() != 0) {
                throw new IllegalStateException("DS 查询工作流实例失败: " + resp);
            }
            return resp;
        }

        /** 轮询直到该工作流的实例出现并返回其 id（fresh H2 下此工作流恰一实例，多余即异常）。 */
        long awaitInstance(long projectCode, long workflowCode, long timeoutMs)
                throws InterruptedException {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (System.nanoTime() < deadline) {
                JsonNode list = queryInstances(projectCode, workflowCode)
                        .path("data").path("totalList");
                for (JsonNode row : list) {
                    if (row.path("workflowDefinitionCode").asLong() == workflowCode) {
                        assertEquals(1, list.size(),
                                () -> "fresh H2 下此工作流应恰一实例: " + list);
                        return row.path("id").asLong();
                    }
                }
                Thread.sleep(1_000);
            }
            throw new AssertionError("等待工作流实例出现超时（workflowCode=" + workflowCode + "）");
        }

        String instanceState(long projectCode, long workflowCode, long instanceId) {
            JsonNode list = queryInstances(projectCode, workflowCode)
                    .path("data").path("totalList");
            for (JsonNode row : list) {
                if (row.path("id").asLong() == instanceId) {
                    return row.path("state").asText();
                }
            }
            return null;
        }

        void awaitInstanceState(long projectCode, long workflowCode, long instanceId,
                                String expected, long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            String state = null;
            while (System.nanoTime() < deadline) {
                state = instanceState(projectCode, workflowCode, instanceId);
                if (expected.equals(state)) {
                    return;
                }
                Thread.sleep(1_000);
            }
            throw new AssertionError("等待实例状态 " + expected + " 超时（最后: " + state + "）");
        }

        private JsonNode post(String path, Map<String, String> form) {
            try {
                StringBuilder body = new StringBuilder();
                for (var e : form.entrySet()) {
                    if (body.length() > 0) {
                        body.append('&');
                    }
                    body.append(url(e.getKey())).append('=').append(url(e.getValue()));
                }
                HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
                HttpResponse<String> resp =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                return envelope(resp.body(), "POST " + path);
            } catch (IOException e) {
                throw new IllegalStateException("DS API 调用失败: POST " + path, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DS API 调用被中断: POST " + path, e);
            }
        }

        /** 包络防御：非 JSON/无 code 字段不得伪装成 code=0（如反向代理错误页、空应答）。 */
        private JsonNode envelope(String body, String what) {
            try {
                JsonNode node = om.readTree(body);
                if (!node.isObject() || !node.has("code")) {
                    throw new IllegalStateException("DS API 应答非标准包络: " + what
                            + " -> " + (body == null ? "null" : body.substring(0,
                            Math.min(200, body.length()))));
                }
                return node;
            } catch (IOException e) {
                throw new IllegalStateException("DS API 应答不是 JSON: " + what, e);
            }
        }

        private JsonNode get(String path) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();
                HttpResponse<String> resp =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                return envelope(resp.body(), "GET " + path);
            } catch (IOException e) {
                throw new IllegalStateException("DS API 调用失败: GET " + path, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DS API 调用被中断: GET " + path, e);
            }
        }

        private static String url(String v) {
            return URLEncoder.encode(v, StandardCharsets.UTF_8);
        }
    }
}
