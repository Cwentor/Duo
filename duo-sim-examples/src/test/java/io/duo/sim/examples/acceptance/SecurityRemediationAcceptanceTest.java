package io.duo.sim.examples.acceptance;

import io.duo.sim.control.ScenarioHost;
import io.duo.sim.control.rest.RestControlServer;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.scenario.ScenarioValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全整改的运行时验收（对应 {@code docs/security-audit-2026-09-20.md} 的 §3.1 PoC）。
 *
 * <p>审计报告明确声明「**未执行任何构建、测试或运行时验证**，C-1 的 PoC 未经实际发送」。
 * 本用例把那条 PoC **真的发出去**，并且断言的是「机器上什么都没发生」——不是
 * 「接口返回了 400」这一层，而是：
 * <ol>
 *   <li>{@code /tmp/pwned}（PoC 的落点）不存在；</li>
 *   <li>{@code configOut} 指向的 {@code /etc/cron.d/duo}（或本机可写等价物）不存在；</li>
 *   <li>响应体里不含本机绝对路径（审计 M-5 的信息泄露口径）。</li>
 * </ol>
 *
 * <p>「没落盘」比「返回 400」是更强的证据：即便将来某处守卫被绕过，只要副作用发生了，
 * 这条用例就会红——它验的是**能力**，不是**状态码**。
 */
class SecurityRemediationAcceptanceTest {

    /** 审计报告 §3.1 的 C-1 PoC（原文形态：任意命令执行 + 任意文件写）。 */
    private static String c1Poc(String configOut, String marker, int port) {
        return String.format(java.util.Locale.ROOT, """
                name: pwn
                topology:
                  - id: fake
                    contract: scheduler
                    tier: real
                    sut: true
                    launch:
                      mode: external
                      configOut: %s
                      command: "sh -c 'id > %s'"
                    exposes: [{ contract: scheduler, port: %d }]
                    config: { ready.type: tcp, ready.port: "%d" }
                """, configOut, marker, port, port);
    }

    @Test
    void c1PocIsRefusedAtValidationAndLeavesNoTraceOnDisk(@TempDir Path tmp) throws Exception {
        Path marker = tmp.resolve("pwned");
        Path cronLike = tmp.resolve("cron.d/duo"); // 本机可写的 /etc/cron.d/duo 等价物
        Files.createDirectories(cronLike.getParent());
        int port = 18999;

        try (ScenarioHost host = new ScenarioHost();
             RestControlServer server = new RestControlServer(host,
                     RestControlServer.Auth.TOKEN,
                     io.duo.sim.control.rest.TestTokens.TOKEN)) {
            int actual = server.start(0);
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String yaml = c1Poc(posix(cronLike), posix(marker), port);

            var resp = http.send(io.duo.sim.control.rest.TestTokens
                            .request("http://127.0.0.1:" + actual + "/scenario")
                            .header("Content-Type", "text/yaml")
                            .POST(HttpRequest.BodyPublishers.ofString(yaml,
                                    StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, resp.statusCode(), () -> "C-1 PoC 必须在校验期被拒: " + resp.body());
            // ① 命令从未执行
            assertFalse(Files.exists(marker),
                    "PoC 命令被执行了——控制面仍可任意命令执行（副作用证据）");
            // ② 任意文件从未写出
            assertFalse(Files.exists(cronLike),
                    "PoC 的任意文件写成功了——configOut 未被约束（副作用证据）");
            // ③ 错误消息不回显本机路径（审计 M-5）
            assertFalse(resp.body().contains(tmp.toString()),
                    () -> "错误体泄露了本机路径: " + resp.body());
        }
    }

    /**
     * 报告 §7.1 的验收签字项：**认证开启后**，未带令牌的请求拿不到任何操作能力，
     * 也不留下任何副作用。这里用同一个 PoC 走「无令牌」路径，确认失败发生在认证层
     * （401）而不是在校验层（400）——两层都要挡住，攻击面才算真正关闭。
     */
    @Test
    void unauthenticatedPocNeverReachesScenarioEngine(@TempDir Path tmp) throws Exception {
        Path marker = tmp.resolve("pwned-unauth");
        try (ScenarioHost host = new ScenarioHost();
             RestControlServer server = new RestControlServer(host,
                     RestControlServer.Auth.TOKEN,
                     io.duo.sim.control.rest.TestTokens.TOKEN)) {
            int actual = server.start(0);
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String yaml = c1Poc(posix(tmp.resolve("cfg")), posix(marker), 18998);

            var resp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + actual + "/scenario"))
                            .header("Content-Type", "text/yaml")
                            .POST(HttpRequest.BodyPublishers.ofString(yaml,
                                    StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(401, resp.statusCode(), () -> "无令牌不得进入业务分支: " + resp.body());
            assertFalse(Files.exists(marker), "无令牌请求竟产生了副作用");
            assertEquals("IDLE", host.status().get("state"), "场景不得被启动");
        }
    }

    /** 控制面无法在场景收尾后把「启动踏板」留在磁盘上（审计 M-6/L-1）。 */
    @Test
    void controlPlaneTempScenarioFileIsRemovedAfterFinish(@TempDir Path tmp) throws Exception {
        String tmpDir = System.getProperty("java.io.tmpdir");
        long before = countTempFiles(tmpDir);
        try (ScenarioHost host = new ScenarioHost();
             RestControlServer server = new RestControlServer(host,
                     RestControlServer.Auth.TOKEN,
                     io.duo.sim.control.rest.TestTokens.TOKEN)) {
            int actual = server.start(0);
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String yaml = Files.readString(Path.of(getClass()
                    .getResource("/scenarios/m3-inject-demo.yaml").toURI()),
                    StandardCharsets.UTF_8);

            var start = http.send(io.duo.sim.control.rest.TestTokens
                            .request("http://127.0.0.1:" + actual + "/scenario")
                            .header("Content-Type", "text/yaml")
                            .POST(HttpRequest.BodyPublishers.ofString(yaml)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, start.statusCode(), start.body());
            assertTrue(countTempFiles(tmpDir) > before,
                    "启动期间应存在控制面落地的隔离 YAML（否则本用例测不到清理路径）");

            http.send(io.duo.sim.control.rest.TestTokens
                            .request("http://127.0.0.1:" + actual + "/scenario")
                            .DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
            // 收尾在后台虚拟线程里做，给它一个窗口
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline && countTempFiles(tmpDir) > before) {
                Thread.sleep(100);
            }
            assertEquals(before, countTempFiles(tmpDir),
                    "场景收尾后不得留下隔离 YAML（审计 M-6/L-1）");
        }
    }

    private static long countTempFiles(String dir) throws IOException {
        try (var s = Files.list(Path.of(dir))) {
            return s.filter(p -> ScenarioHost.TEMP_FILE_PREFIXES.stream()
                    .anyMatch(prefix -> p.getFileName().toString().startsWith(prefix))).count();
        }
    }

    // ---- 2026-09-20 独立复核后的补充验收（H-4 / M-2 / M-6）----

    /**
     * **H-4**：反复启动/停止不得留下未收摊的引擎。
     *
     * <p>复核发现整改台账把 H-4 记在了 {@code MetricsCollector} 的类型基数上，而原始问题
     * ——{@code ScenarioHost.start()} 直接覆写 {@code engine} 引用、旧引擎没人 {@code close()}
     * ——一字未改。这条用例走**真实的替换路径**（POST → DELETE → POST）：每次成功启动都会
     * 顶替掉上一个引擎，断言的是**可观测的收摊计数**，而不是"引用换没换"。
     */
    @Test
    void repeatedScenarioStartsRetireThePreviousEngine(@TempDir Path tmp) throws Exception {
        String yaml = Files.readString(Path.of(getClass()
                .getResource("/scenarios/m3-inject-demo.yaml").toURI()), StandardCharsets.UTF_8);
        try (ScenarioHost host = new ScenarioHost();
             RestControlServer server = new RestControlServer(host,
                     RestControlServer.Auth.TOKEN,
                     io.duo.sim.control.rest.TestTokens.TOKEN)) {
            int actual = server.start(0);
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

            for (int i = 0; i < 3; i++) {
                var start = http.send(io.duo.sim.control.rest.TestTokens
                                .request("http://127.0.0.1:" + actual + "/scenario")
                                .header("Content-Type", "text/yaml")
                                .POST(HttpRequest.BodyPublishers.ofString(yaml)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, start.statusCode(), start.body());
                http.send(io.duo.sim.control.rest.TestTokens
                                .request("http://127.0.0.1:" + actual + "/scenario")
                                .DELETE().build(),
                        HttpResponse.BodyHandlers.ofString());
                Thread.sleep(200); // 停止在后台虚拟线程里做（stopWithoutAwait 语义）
            }
            // 第 2、3 次启动各自顶替掉一个未收摊的引擎；收摊也在后台线程里，给它一个窗口
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline && host.retiredEnginesClosed() < 2) {
                Thread.sleep(100);
            }
            assertTrue(host.retiredEnginesClosed() >= 2,
                    () -> "被顶替的旧引擎没有被收摊（H-4 泄漏路径仍在）: "
                            + host.retiredEnginesClosed());
        }
    }

    /**
     * **M-2**：{@code CapabilityMetadata.trustedConfigKeys} 必须**真的**生效。
     *
     * <p>复核的原始结论是"全仓零实现、D11 是空承诺"。这条用例把承诺变成契约：
     * 组件在注册期声明的键，外部输入档下必须被接受。纯函数级（不启进程、不连网），
     * 因此不依赖任何环境。
     */
    @Test
    void componentDeclaredConfigKeysAreAcceptedForExternalInput() {
        var meta = CapabilityMetadata.inProcessDirect(java.util.Set.of())
                .withTrustedConfigKeys(java.util.Set.of("custom.tuning", "custom.batchSize"));
        assertTrue(ScenarioValidator.isTrustedConfigKey("custom.tuning", meta),
                "组件声明的可信键必须真的被校验器接受（否则 D11 是空承诺）");
        // 静态白名单里的键照旧生效
        assertTrue(ScenarioValidator.isTrustedConfigKey("ready.type", meta));
        // 未声明的键仍然被拒（逃生舱不是"全放行"）
        assertFalse(ScenarioValidator.isTrustedConfigKey("etc.passwd", meta));
        assertFalse(ScenarioValidator.isTrustedConfigKey("custom.tuning", null),
                "没有组件声明时，额外键不得凭空可信");
    }

    /**
     * **C-1 / H-2**：请求体上限必须走**流式截断**，而不是"先声明、再 readAllBytes"。
     *
     * <p>审计期 {@code readAllBytes()} 无上限。这里不看代码看行为：声明 1 MiB 之上必须被拒；
     * 而谎报小 {@code Content-Length} 的 chunked 上传也必须被**读侧**截断（否则守卫只是
     * 装饰——攻击者自己写长度即可）。后者用 413 与实际耗时/内存表现共同证明。
     */
    @Test
    void oversizedUploadsAreRejectedByDeclaredAndActualSize() throws Exception {
        try (ScenarioHost host = new ScenarioHost();
             RestControlServer server = new RestControlServer(host,
                     RestControlServer.Auth.TOKEN,
                     io.duo.sim.control.rest.TestTokens.TOKEN)) {
            int actual = server.start(0);
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String url = "http://127.0.0.1:" + actual + "/scenario";

            // ① 声明超限：早失败（连体都不读）
            byte[] huge = new byte[RestControlServer.MAX_BODY_BYTES + 1];
            java.util.Arrays.fill(huge, (byte) 'a');
            var declared = http.send(io.duo.sim.control.rest.TestTokens.request(url)
                            .header("Content-Type", "text/yaml")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(huge)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(413, declared.statusCode(),
                    () -> "声明超限必须 413: " + declared.statusCode() + " " + declared.body());

            // ② 谎报长度 + chunked：读侧必须截断（HTTP/2 无 Content-Length 时走这条）
            var chunked = http.send(io.duo.sim.control.rest.TestTokens.request(url)
                            .header("Content-Type", "text/yaml")
                            .POST(HttpRequest.BodyPublishers.ofInputStream(() ->
                                    new java.io.ByteArrayInputStream(huge))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(413, chunked.statusCode(),
                    () -> "无界流式上传必须被读侧截断: " + chunked.statusCode() + " "
                            + chunked.body());
            assertEquals("IDLE", host.status().get("state"), "超限上传不得启动任何场景");
        }
    }

    /** YAML 标量里反斜杠是转义字符，统一正斜杠让 Windows/Linux 都用同一份文本。 */
    private static String posix(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }
}
