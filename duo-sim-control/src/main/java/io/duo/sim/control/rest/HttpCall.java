package io.duo.sim.control.rest;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 一次端点调用的上下文：业务分支只经它读请求（方法/路径/查询参数/受限请求体）与写响应。
 *
 * <p>守卫式中止用 {@link #require} / {@link #error}（抛 {@link RestRoutes.EndpointError}，
 * 由管道统一掩码并响应，业务分支不必也不可能自己拼错误响应）；响应体形状特殊时用
 * {@link #json} 直接写出（写出后紧跟 {@code return}，不中止语义）。
 */
final class HttpCall {
    private final RestRoutes routes;
    private final HttpExchange ex;

    HttpCall(RestRoutes routes, HttpExchange ex) {
        this.routes = routes;
        this.ex = ex;
    }

    String method() {
        return ex.getRequestMethod();
    }

    /** 解码后的路径（静态资源/库端点的段解析都在它上面做）。 */
    String path() {
        return ex.getRequestURI().getPath();
    }

    /** {@link #path()} 去掉 {@code prefix} 后的剩余部分（不含前导 {@code /}；无剩余为空串）。 */
    String pathTail(String prefix) {
        String p = path();
        return p.length() > prefix.length() ? p.substring(prefix.length() + 1) : "";
    }

    /** 查询参数原值（同名多次出现取最后一个；不解码——库端点的 id 解码有自己的穿越校验）。 */
    String query(String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        String found = null;
        String prefix = name + "=";
        for (String p : q.split("&")) {
            if (p.startsWith(prefix)) {
                found = p.substring(prefix.length());
            }
        }
        return found;
    }

    /**
     * 读取请求体，硬上限 {@link RestControlServer#MAX_BODY_BYTES}，超限抛
     * {@link BodyTooLargeException}（管道映射 413）。
     *
     * <p>为什么不信任 {@code Content-Length}：它只是**声明**——chunked 上传或谎报的小长度
     * 都能绕过长度检查直接喂爆 {@code readAllBytes()}（审计 H-2）。这里先看声明（早失败），
     * 再按 {@code MAX+1} 截断读取（真上限）。
     */
    String body() throws IOException {
        String declared = ex.getRequestHeaders().getFirst("Content-Length");
        if (declared != null && !declared.isBlank()) {
            try {
                if (Long.parseLong(declared.trim()) > RestControlServer.MAX_BODY_BYTES) {
                    throw new BodyTooLargeException();
                }
            } catch (NumberFormatException e) {
                throw new BodyTooLargeException();
            }
        }
        byte[] bytes = readAtMost(ex.getRequestBody(), RestControlServer.MAX_BODY_BYTES);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
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

    /** 守卫不满足即中止：管道按给定状态码与消息响应（消息会过 {@link RestRoutes#sanitizeReason}）。 */
    void require(boolean condition, int code, String message) {
        if (!condition) {
            throw new RestRoutes.EndpointError(code, message);
        }
    }

    /** 以 {@code {"error": message}} 中止（总是抛出）。 */
    void error(int code, String message) {
        throw new RestRoutes.EndpointError(code, message);
    }

    /** 200 JSON 响应。 */
    void ok(Object body) {
        json(200, body);
    }

    /** 直接写出 JSON 响应（不中止调用方；用于自定义响应体形状，写出后应紧跟 return）。 */
    void json(int code, Object body) {
        routes.respond(ex, code, body);
    }

    /** 文本响应（Prometheus exposition format 不是 JSON）。 */
    void text(int code, String body, String contentType) {
        routes.respondText(ex, code, body, contentType);
    }

    /** 特例面：静态资源服务需要原始交换（自定义响应头 + 字节体）。 */
    HttpExchange exchange() {
        return ex;
    }
}
