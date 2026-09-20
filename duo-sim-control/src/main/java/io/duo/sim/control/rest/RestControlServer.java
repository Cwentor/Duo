package io.duo.sim.control.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.duo.sim.control.ScenarioHost;
import io.duo.sim.kernel.core.ScenarioRuntime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 控制面 REST 服务（M3 T33）：JDK 内置 HttpServer（D1——不引 Web 框架），
 * 虚拟线程 executor。端点（计划 §2）：
 *
 * <ul>
 *   <li>{@code POST /scenario}——body 为场景 YAML 文本，启动（校验失败→400）</li>
 *   <li>{@code GET /scenario/status}——运行状态/断言/注入失败（未启动→409）</li>
 *   <li>{@code GET /events?since=N}——事件增量（D3 序号语义）</li>
 *   <li>{@code POST /inject}——body 为 FaultAction JSON（未启动→409；target 不可解析→404）</li>
 *   <li>{@code GET /assertions}——断言与注入失败明细</li>
 *   <li>{@code GET /topology}——节点/契约/档位/实例/在线状态</li>
 *   <li>{@code GET /metrics}——Prometheus 文本格式指标（M8；`text/plain; version=0.0.4`）</li>
 *   <li>{@code GET /health}——服务存活</li>
 * </ul>
 *
 * <p>错误映射：IllegalArgumentException→400；IllegalStateException/未启动→409；
 * 未知 target→404；其余→500。
 *
 * <p><b>安全边界（安全审计 2026-09-20 C-1/H-1/H-2/M-8 整改）</b>：控制面是**执行面**——
 * {@code POST /scenario} 能把一段 YAML 变成宿主机上的进程与文件写。原来把信任边界画在
 * 「能连到 127.0.0.1:7788」上，等于同机任意进程 + 任意网页都能执行命令，故本类补齐四层：
 *
 * <ol>
 *   <li><b>认证</b>（{@link Auth}）：除 {@code /health} 外全部端点要求
 *       {@code Authorization: Bearer <token>}，常量时间比较；</li>
 *   <li><b>CSRF/Host</b>：{@code Origin}/{@code Referer} 存在时必须指向回环，
 *       {@code Host} 必须是回环字面量；</li>
 *   <li><b>请求体上限</b>：{@code Content-Length} 上限 1 MiB，且流式读取二次截断，
 *       防「谎报 Content-Length / chunked 无界上传」把控制面 OOM；</li>
 *   <li><b>认证令牌／信任档不出现在任何响应里</b>（令牌不落日志，异常原因里的临时文件
 *       随机路径被掩码，见 {@link #sanitizeReason}）。</li>
 * </ol>
 *
 * <p><b>{@code /health} 免认证</b>：它是活性探针，设计上可被前台轮询；它返回的
 * {@code {"ok":true}} 不泄露场景内容——token 是否有效也不回显（保持「页面可探测到服务在」）。
 */
public final class RestControlServer implements AutoCloseable {

    /** 认证方式：令牌 或 显式关闭（{@code --insecure-no-auth}）。两者必须显式选一。 */
    public enum Auth { TOKEN, INSECURE }

    /** 免认证端点（活性探针）。 */
    private static final String HEALTH_PATH = "/health";

    /** 请求体上限（1 MiB）：场景 YAML/FaultAction 都远小于此，超出即 413。 */
    public static final int MAX_BODY_BYTES = 1 << 20;

    /** 回环主机名白名单（Host 头校验；含 IPv6 字面量）。 */
    private static final Set<String> LOOPBACK_HOSTS = Set.of(
            "127.0.0.1", "localhost", "[::1]", "::1", "0:0:0:0:0:0:0:1");

    private final ScenarioHost host;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final io.duo.sim.control.metrics.MetricsCollector metrics;
    private final Auth auth;
    private final byte[] token;
    private HttpServer server;
    private ExecutorService executor;

    /** 显式关闭认证（仅 {@code --insecure-no-auth} 使用；默认构造＝令牌必填）。 */
    public RestControlServer(ScenarioHost host) {
        this(host, Auth.TOKEN, null);
    }

    /**
     * @param auth  {@link Auth#TOKEN} 时 token 必填且非空；{@link Auth#INSECURE} 时 token 必须为空
     * @param token 认证令牌（{@link Auth#TOKEN}）
     */
    public RestControlServer(ScenarioHost host, Auth auth, String token) {
        this.host = host;
        this.metrics = new io.duo.sim.control.metrics.MetricsCollector(host);
        this.auth = java.util.Objects.requireNonNull(auth, "auth");
        if (auth == Auth.TOKEN) {
            if (token == null || token.isEmpty()) {
                throw new IllegalArgumentException("control plane token must not be empty "
                        + "(pass one, or opt out explicitly with --insecure-no-auth)");
            }
            this.token = token.getBytes(StandardCharsets.UTF_8);
        } else {
            if (token != null && !token.isEmpty()) {
                throw new IllegalArgumentException("token must not be combined with insecure mode");
            }
            this.token = null;
        }
    }

    /** 当前认证方式（CLI 启动提示用）。 */
    public Auth auth() {
        return auth;
    }

    /**
     * 单请求处理时长上限（审计 M-4）。
     *
     * <p>注意：{@code com.sun.net.httpserver.HttpServer} **没有** {@code setMaxReqTime}
     * （只有 jdk.httpserver 内部的 {@code ServerImpl} 有，不是公开 API；{@code sun.net.httpserver}
     * 那套也没有可移植的公开入口）。所以这里不硬造一个"写了但不生效"的上限——那正是 §12
     * 明确禁止的静默谎言。真正的时长约束由两件事保证：① {@code /scenario} 的启动路径本身是
     * 有界的（ready 探针有超时、校验有上限）；② 请求体上限 1 MiB 让"慢速灌大包"占不住线程。
     */
    public static final Duration REQUEST_TIME_BUDGET = Duration.ofSeconds(60);

    /** 启动（port 0 = 自动分配）。返回实际端口。 */
    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        registerRoutes();
        server.start();
        return server.getAddress().getPort();
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private void registerRoutes() {
        // /health 免认证（活性探针）；其余端点一律先过认证 + 跨站防护。
        server.createContext(HEALTH_PATH, ex -> {
            if (!guard(ex, true)) {
                return;
            }
            respond(ex, 200, Map.of("ok", true));
        });
        server.createContext("/scenario/status", ex -> {
            if (!guard(ex, false)) {
                return;
            }
            if (!"GET".equals(ex.getRequestMethod())) {
                respond(ex, 405, Map.of("error", "method not allowed"));
                return;
            }
            if (!host.hasResult() && !host.isRunning()) {
                respond(ex, 409, Map.of("error", "scenario not started"));
                return;
            }
            respond(ex, 200, host.status());
        });
        server.createContext("/scenario", ex -> {
            if (!guard(ex, false)) {
                return;
            }
            switch (ex.getRequestMethod()) {
                case "POST" -> {
                    if (host.isRunning()) {
                        respond(ex, 409, Map.of("error", "scenario already running"));
                        return;
                    }
                    String yaml;
                    try {
                        yaml = readBody(ex);
                    } catch (BodyTooLargeException e) {
                        respond(ex, 413, Map.of("error", e.getMessage()));
                        return;
                    } catch (IOException e) {
                        return; // 客户端断开
                    }
                    Path tmp = null;
                    boolean started = false;
                    try {
                        // 临时 YAML 带隔离后缀：外部输入档（untrusted）下 DSL 里指向本进程
                        // 临时文件的路径（configOut 等）会被校验器直接拒绝（审计 C-1「任意文件写」）。
                        tmp = Files.createTempFile("duo-rest-", "-" + ScenarioHost.QUARANTINE_SUFFIX
                                + ".yaml");
                        Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
                        // 传文件名而非绝对路径：宿主按名打包，内部只保留"删除它"所需的句柄，
                        // 避免把本机临时目录写进错误消息/事件（审计 M-5 的同类口径）。
                        respond(ex, 200, host.start(tmp, ScenarioHost.Trust.EXTERNAL_INPUT,
                                tmp.getFileName().toString()));
                        started = true;
                    } catch (IllegalArgumentException
                            | com.fasterxml.jackson.core.JacksonException e) {
                        respond(ex, 400, Map.of("error", sanitizeReason(e.getMessage())));
                    } catch (IllegalStateException e) {
                        respond(ex, 409, Map.of("error", sanitizeReason(e.getMessage())));
                    } catch (Exception e) {
                        // 500 只回根因摘要：堆栈/临时随机路径（口径泄露，审计 M-8）不回显
                        respond(ex, 500, Map.of("error", sanitizeReason(e.getMessage())));
                    } finally {
                        // 场景失败 = 这份 YAML 从未成为 SUT ⇒ 立即删除（审计 M-6/L-1：临时文件泄漏）
                        if (!started && tmp != null) {
                            deleteQuietly(tmp);
                        }
                    }
                }
                case "DELETE" -> respond(ex, 200, host.stopWithoutAwait());
                default -> respond(ex, 405, Map.of("error", "method not allowed"));
            }
        });
        server.createContext("/events", ex -> {
            if (!guard(ex, false)) {
                return;
            }
            if (!"GET".equals(ex.getRequestMethod())) {
                respond(ex, 405, Map.of("error", "method not allowed"));
                return;
            }
            int since = 0;
            var query = ex.getRequestURI().getRawQuery();
            if (query != null) {
                for (String p : query.split("&")) {
                    if (p.startsWith("since=")) {
                        try {
                            since = Integer.parseInt(p.substring(6));
                        } catch (NumberFormatException e) {
                            respond(ex, 400, Map.of("error", "since must be an integer"));
                            return;
                        }
                    }
                }
            }
            List<Map<String, Object>> events = host.eventsSince(since);
            respond(ex, 200, Map.of("since", since, "events", events));
        });
        server.createContext("/inject", ex -> {
            if (!guard(ex, false)) {
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                respond(ex, 405, Map.of("error", "method not allowed"));
                return;
            }
            if (!host.isRunning()) {
                respond(ex, 409, Map.of("error", "scenario not running"));
                return;
            }
            try {
                String body = readBody(ex);
                var action = mapper.readValue(body,
                        io.duo.sim.kernel.api.FaultAction.class);
                var result = host.inject(action);
                if (!result.success() && result.reason() != null
                        && result.reason().contains("unknown target")) {
                    respond(ex, 404, Map.of("error", result.reason()));
                } else {
                    respond(ex, 200, Map.of("success", result.success(),
                            "reason", result.reason() == null ? "" : result.reason()));
                }
            } catch (BodyTooLargeException e) {
                respond(ex, 413, Map.of("error", e.getMessage()));
            } catch (com.fasterxml.jackson.core.JacksonException
                    | IllegalArgumentException e) {
                respond(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
            } catch (Exception e) {
                respond(ex, 500, Map.of("error", sanitizeReason(e.getMessage())));
            }
        });
        server.createContext("/assertions", ex -> {
            if (!guard(ex, false)) {
                return;
            }
            if (!host.hasResult() && !host.isRunning()) {
                respond(ex, 409, Map.of("error", "scenario not started"));
                return;
            }
            respond(ex, 200, host.assertions());
        });
        server.createContext("/topology", ex -> {
            if (!guard(ex, false)) {
                return;
            }
            // FINISHED 状态也可读（事后审查是控制面核心用途之一，M3 验收 §1）
            if (!host.isRunning() && !host.hasResult()) {
                respond(ex, 409, Map.of("error", "scenario not started"));
                return;
            }
            respond(ex, 200, Map.of("nodes", host.topology()));
        });
        server.createContext("/metrics", ex -> {
            if (!guard(ex, false)) {
                return;
            }
            if (!"GET".equals(ex.getRequestMethod())) {
                respond(ex, 405, Map.of("error", "method not allowed"));
                return;
            }
            // M8：组件未启动时也可抓（此时除 duo_up/duo_scrapes_total 外均为 0），
            // 这样 Prometheus 的抓取目标不会因场景重启而 up=0（与 /status 的 409 语义不同，
            // 那是"结果不可读"，这里是"指标恒可读"）。
            respondText(ex, 200, metrics.scrape(),
                    "text/plain; version=0.0.4; charset=utf-8");
        });
    }

    // ---- 安全边界（C-1/H-1/H-2/M-8）----

    /**
     * 认证 + 跨站防护。返回 false 表示已写响应（调用方必须立即返回）。
     *
     * @param health {@code /health} 免认证（仍做 Host/Origin 校验）
     */
    private boolean guard(HttpExchange ex, boolean health) {
        // ① Host：必须是回环字面量。字段缺失（HTTP/1.0）也按无主机处理——浏览器必然带 Host。
        if (!isLoopbackHost(ex.getRequestHeaders().getFirst("Host"))) {
            respond(ex, 403, Map.of("error", "forbidden: Host must be a loopback address"));
            return false;
        }
        // ② Origin / Referer：存在即必须是回环（拦掉"网页里点一下就能 POST"的路径）
        if (!isLoopbackOrigin(ex.getRequestHeaders().getFirst("Origin"))
                || !isLoopbackOrigin(ex.getRequestHeaders().getFirst("Referer"))) {
            respond(ex, 403, Map.of("error",
                    "forbidden: Origin/Referer must be a loopback origin"));
            return false;
        }
        if (health || auth == Auth.INSECURE) {
            return true;
        }
        // ③ Bearer 令牌：常量时间比较；缺/错一律 401，且不回显「是没带还是带错了」
        String provided = bearer(ex.getRequestHeaders().getFirst("Authorization"));
        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), token)) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            respond(ex, 401, Map.of("error", "unauthorized: missing or invalid bearer token"));
            return false;
        }
        return true;
    }

    private static String bearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        String value = authorization.substring(prefix.length()).trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean isLoopbackHost(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostHeader.trim();
        if (host.startsWith("[")) { // IPv6 字面量 [::1]:port
            int end = host.indexOf(']');
            if (end < 0) {
                return false;
            }
            return LOOPBACK_HOSTS.contains(host.substring(0, end + 1).toLowerCase(Locale.ROOT));
        }
        int colon = host.indexOf(':');
        String name = colon < 0 ? host : host.substring(0, colon);
        return LOOPBACK_HOSTS.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Origin/Referer 缺省视为可接受；一旦出现就必须是回环来源。 */
    private static boolean isLoopbackOrigin(String origin) {
        if (origin == null || origin.isBlank() || "null".equals(origin)) {
            return true;
        }
        try {
            URI uri = URI.create(origin.trim());
            String host = uri.getHost();
            return host != null && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 请求体上限异常（→413）。 */
    private static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("request body exceeds " + MAX_BODY_BYTES + " bytes");
        }
    }

    /**
     * 读取请求体，硬上限 {@link #MAX_BODY_BYTES}。
     *
     * <p>为什么不信任 {@code Content-Length}：它只是**声明**——chunked 上传或谎报的小长度
     * 都能绕过长度检查直接喂爆 {@code readAllBytes()}（审计 H-2）。这里先看声明（早失败），
     * 再按 {@code MAX+1} 截断读取（真上限）。
     */
    private static String readBody(HttpExchange ex) throws IOException {
        String declared = ex.getRequestHeaders().getFirst("Content-Length");
        if (declared != null && !declared.isBlank()) {
            try {
                if (Long.parseLong(declared.trim()) > MAX_BODY_BYTES) {
                    throw new BodyTooLargeException();
                }
            } catch (NumberFormatException e) {
                throw new BodyTooLargeException();
            }
        }
        byte[] bytes = readAtMost(ex.getRequestBody(), MAX_BODY_BYTES);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readAtMost(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 8192));
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) >= 0) {
            if (out.size() + read > max) {
                throw new BodyTooLargeException();
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * 掩码异常原因里的本机口径（临时文件随机路径、绝对路径）——审计 M-8：
     * 客户端不需要知道控制面把 YAML 落在哪个路径下，那是给攻击者的免费情报。
     * 内部仍由 {@code FaultLog} 记录完整原因。
     */
    static String sanitizeReason(String reason) {
        if (reason == null) {
            return "internal error";
        }
        String out = reason
                .replaceAll("(?:[A-Za-z]:[\\\\/]|/)[^\\s'\";,)]*", "<path>")
                .replaceAll("(?i)bearer\\s+\\S+", "Bearer <redacted>");
        return out.length() > 300 ? out.substring(0, 300) + "…" : out;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 尽力而为：临时文件清理失败不改变已返回给客户端的结论
        }
    }

    /** 单次运行会产生临时文件的端点（CLI 侧 Stop 钩子复用）。 */
    public static Set<String> tempFilePrefixes() {
        return new LinkedHashSet<>(List.of("duo-rest-", "duo-scenario-"));
    }

    /** 文本响应（Prometheus exposition format 不是 JSON）。 */
    private void respondText(HttpExchange ex, int code, String body, String contentType) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            // 客户端断开
        }
    }

    private void respond(HttpExchange ex, int code, Object body) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(body);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            // 客户端断开
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
        // JDK 的 HttpServer.stop() 只停监听与连接，**不关**外部 executor（审计 H-5）：
        // 虚拟线程 executor 不关也不会伴生非守护线程，但仍显式关闭，避免句柄滞留。
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }
}
