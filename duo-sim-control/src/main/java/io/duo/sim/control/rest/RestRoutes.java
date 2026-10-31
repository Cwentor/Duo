package io.duo.sim.control.rest;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 控制面端点管道：每个端点只声明「路径 + 允许的方法 + 业务分支」，其余一切——认证与跨站
 * 校验（D10 三层防线）、方法检查（405）、请求体上限（413）、异常→HTTP 错误映射、错误消息
 * 掩码（{@link #sanitizeReason}）——只在本类发生一次。新端点不再复制任何守卫阶梯。
 *
 * <p><b>安全边界（安全审计 2026-09-20 C-1/H-1/H-2/M-8 整改的执行点）</b>：控制面是执行面，
 * 四层防线在这里集中执行：
 *
 * <ol>
 *   <li><b>认证</b>：除免令牌路由（{@code /health}、静态资源）外全部端点要求
 *       {@code Authorization: Bearer <token>}，常量时间比较，缺/错不可区分（401）；</li>
 *   <li><b>CSRF/Host</b>：{@code Origin}/{@code Referer} 存在时必须指向回环，
 *       {@code Host} 必须是回环字面量（403，且在认证之前判）；</li>
 *   <li><b>请求体上限</b>：{@code Content-Length} 上限 1 MiB，且流式截断读取二次设防
 *       （413，先有界排空再响应以保证必达）；</li>
 *   <li><b>口径不外泄</b>：一切错误消息经 {@link #sanitizeReason}（路径/Bearer 掩码）；
 *       认证令牌不出现在任何响应里。</li>
 * </ol>
 *
 * <p><b>错误映射（异常 → 响应）</b>，按捕获顺序：
 *
 * <ol>
 *   <li>{@link EndpointError}——业务分支经 {@code HttpCall.error/require} 给定的码与消息；</li>
 *   <li>{@link BodyTooLargeException} → 413（有界排空后必达）；</li>
 *   <li>{@link NoSuchFileException} → 404（场景库条目不存在）；</li>
 *   <li>{@link JacksonException} / {@link IllegalArgumentException} → 400（调用方输入不合法；
 *       消息一律 {@link #sanitizeReason}——终审 M-1 记账的「/inject 未掩码」类漏网自此
 *       结构性消失，不存在第二个未掩码分支）；</li>
 *   <li>{@link IllegalStateException} → 405（资源存在但方法不被允许，如模板只读）。
 *       例外：{@code POST /scenario} 的并发「已在运行」在业务分支自行映射 409；</li>
 *   <li>{@link IOException} → 500（客户端断开时响应写不出去，静默）；</li>
 *   <li>其余 → 500（我们的 bug；只回根因摘要，不回堆栈）。</li>
 * </ol>
 */
final class RestRoutes {

    /** 端点业务分支：只经 {@link HttpCall} 说话；守卫式中止抛 {@link EndpointError}。 */
    @FunctionalInterface
    interface EndpointAction {
        void run(HttpCall call) throws Exception;
    }

    /** 业务分支显式中止：携带最终状态码与消息，由管道统一掩码并响应。 */
    static final class EndpointError extends RuntimeException {
        final int code;

        EndpointError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 回环主机名白名单（Host 头校验；含 IPv6 字面量——isLoopbackHost 的括号分支按
     *  {@code [::1]} 形态整串比对，故裸 IPv6 条目不可达，评审 P3 清理）。 */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");

    /**
     * 413 排空上限：拒绝超限上传前先读并丢弃的字节预算。正常超限上传（≈1 MiB 量级，
     * {@link RestControlServer#MAX_BODY_BYTES} 级别）必在此预算内读到 EOF；超出预算的滥用流
     * 放弃排空（宁可它丢 413 响应，也不陪跑无限流）。
     */
    private static final long MAX_DRAIN_BYTES = 64L << 20;

    private final HttpServer server;
    private final ObjectMapper mapper;
    private final RestControlServer.Auth auth;
    private final byte[] token;
    private final boolean allowRemoteHosts;

    RestRoutes(HttpServer server, ObjectMapper mapper, RestControlServer.Auth auth, byte[] token,
               boolean allowRemoteHosts) {
        this.server = server;
        this.mapper = mapper;
        this.auth = auth;
        this.token = token;
        this.allowRemoteHosts = allowRemoteHosts;
    }

    void get(String path, EndpointAction action) {
        route(path, Set.of("GET"), false, action);
    }

    void post(String path, EndpointAction action) {
        route(path, Set.of("POST"), false, action);
    }

    /** 免令牌路由（仍做回环 Host/Origin 校验）：{@code /health} 与控制台静态资源。 */
    void publicGet(String path, EndpointAction action) {
        route(path, Set.of("GET"), true, action);
    }

    /** 多方法端点（如 {@code /scenario} 的 POST/DELETE）：不在 {@code allowed} 内的方法统一 405。 */
    void methods(String path, Set<String> allowed, EndpointAction action) {
        route(path, allowed, false, action);
    }

    private void route(String path, Set<String> allowed, boolean freeAuth, EndpointAction action) {
        server.createContext(path, ex -> dispatch(ex, freeAuth, allowed, action));
    }

    private void dispatch(HttpExchange ex, boolean freeAuth, Set<String> allowed,
                          EndpointAction action) {
        if (!guard(ex, freeAuth)) {
            return;
        }
        if (!allowed.contains(ex.getRequestMethod())) {
            respond(ex, 405, Map.of("error", "method not allowed"));
            return;
        }
        try {
            action.run(new HttpCall(this, ex));
        } catch (EndpointError e) {
            respond(ex, e.code, Map.of("error", sanitizeReason(e.getMessage())));
        } catch (BodyTooLargeException e) {
            rejectTooLarge(ex, e);
        } catch (NoSuchFileException e) {
            respond(ex, 404, Map.of("error", "scenario not found: " + e.getMessage()));
        } catch (JacksonException | IllegalArgumentException e) {
            // IllegalArgumentException 覆盖 Jackson 2.13 起改为继承 IOException 的
            // MismatchedInputException（缺字段/类型不符在绑定阶段抛它）——漏掉这一支，
            // 这类"调用方输入不合法"就会落到底下的 Exception → 500（审计口径：500 是我们的 bug）。
            respond(ex, 400, Map.of("error", sanitizeReason(String.valueOf(e.getMessage()))));
        } catch (IllegalStateException e) {
            respond(ex, 405, Map.of("error", sanitizeReason(e.getMessage())));
        } catch (IOException e) {
            respond(ex, 500, Map.of("error", sanitizeReason(String.valueOf(e.getMessage()))));
        } catch (Exception e) {
            respond(ex, 500, Map.of("error", sanitizeReason(e.getMessage())));
        }
    }

    // ---- 安全边界（C-1/H-1/H-2/M-8）----

    /**
     * 认证 + 跨站防护。返回 false 表示已写响应（调用方必须立即返回）。
     *
     * @param freeAuth 免令牌路由（{@code /health}、静态资源；仍做 Host/Origin 校验）
     */
    private boolean guard(HttpExchange ex, boolean freeAuth) {
        // ① Host：缺省必须是回环字面量。字段缺失（HTTP/1.0）也按无主机处理——浏览器必然带 Host。
        //    操作者显式绑定非回环地址时（--bind 0.0.0.0，容器/局域网部署）Host 守卫同步放开：
        //    否则远程访问在 token 校验之前就被 403 挡死，--bind 成了空承诺（评审 2026-10-30 P1）。
        //    放开的只是这层白名单；跨站防护（Origin ②）与鉴权（③）原样生效。
        if (!allowRemoteHosts && !isLoopbackHost(ex.getRequestHeaders().getFirst("Host"))) {
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
        if (freeAuth || auth == RestControlServer.Auth.INSECURE) {
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

    /**
     * 413 的确定性投递（CI 间歇红实证：2026-09-23
     * {@code RestControlServerTest.oversizedBodyIsRejectedWith413} 与 2026-09-25
     * {@code SecurityRemediationAcceptanceTest.oversizedUploadsAreRejectedByDeclaredAndActualSize}，
     * 签名同为客户端「HTTP/1.1 header parser received no bytes」，同一代码一次红一次绿）。
     *
     * <p>机理（JDK 21.0.12 {@code sun.net.httpserver.ServerImpl} 源码实证）：响应写完后若请求体
     * 未读至 EOF，{@code t.close = true} → {@code c.close()} **硬关连接**（JDK 不排空剩余体）。
     * 超限体（>1 MiB）在响应写完时客户端往往仍在发送——带未读接收数据 close 触发内核 RST，
     * RST 使客户端读侧立刻 ECONNRESET，**已到达但未读的 413 响应字节一并作废**。小体量的
     * 401/403/405/400 拒绝用例从不闪红：客户端早已发完转入读态，响应在 RST 前已被消费。
     * 修法：先有界排空请求体再响应——读到 EOF 后 JDK 不再硬关（连接优雅收尾），413 必达。
     * Windows 本地不复现、Linux CI 间歇命中，与本机 RST 时序差异一致。
     */
    private void rejectTooLarge(HttpExchange ex, BodyTooLargeException e) {
        drainUpTo(ex.getRequestBody(), MAX_DRAIN_BYTES);
        respond(ex, 413, Map.of("error", e.getMessage()));
    }

    /** 有界排空：读并丢弃至 EOF 或 {@code cap} 字节；客户端中途断开则无事可做。 */
    private static void drainUpTo(InputStream in, long cap) {
        byte[] buf = new byte[8192];
        long drained = 0;
        try {
            for (int read; drained < cap && (read = in.read(buf)) >= 0; drained += read) {
                // 丢弃即可
            }
        } catch (IOException e) {
            // 客户端断开：413 已无处投递
        }
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

    /** JSON 响应。序列化失败绝不吞：吞掉 = 客户端永远等不到响应（Task 7 实测挂死，§12）。 */
    void respond(HttpExchange ex, int code, Object body) {
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (JacksonException e) {
            // 降级为错误 JSON——此路径的 body 恒可序列化，不会递归。
            String msg = sanitizeReason(String.valueOf(e.getMessage()))
                    .replaceAll("[\"\\r\\n]", "'");
            bytes = ("{\"error\":\"response serialization failed: " + msg + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            code = 500;
        }
        try {
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            // 客户端断开
        }
    }

    /** 文本响应（Prometheus exposition format 不是 JSON）。 */
    void respondText(HttpExchange ex, int code, String body, String contentType) {
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
}
