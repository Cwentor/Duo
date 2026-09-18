package io.duo.sim.kernel.sut;

import io.duo.sim.kernel.util.Durations;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * external SUT 就绪探针（设计文档 §7.3 / M6 交付物 1）：{@code tcp} / {@code http} 两种形态，
 * 探针超时归启动失败路径（§12，不静默）。
 *
 * <p>声明位置：DSL {@code launch.ready.{type,host,port,path,timeout}}，由 {@code ScenarioLoader}
 * 展平为节点 {@code config} 的 {@code ready.*} 键（校验见 {@code ScenarioValidator} 规则 4）。
 *
 * <p>本类只做**单次探测**（{@link #once}）与声明解析（{@link #spec}）；轮询/超时/退出竞速由
 * {@link ExternalSutLauncher} 掌握——因为「进程在 ready 前退出」必须优先于「探针超时」报根因。
 */
public final class ReadyProbe {

    /** 探针默认超时（与 in-process 回调超时同口径，§7.3）。 */
    public static final long DEFAULT_TIMEOUT_MS = 60_000;
    /** 默认探测主机。 */
    public static final String DEFAULT_HOST = "127.0.0.1";
    /** 单次 TCP 连接超时。 */
    private static final int CONNECT_TIMEOUT_MS = 1_000;
    /** 单次 HTTP 请求超时。 */
    private static final int HTTP_TIMEOUT_MS = 2_000;

    /** 探针声明。 */
    public record Spec(String type, String host, int port, String path, long timeoutMs) {

        /** 人类可读描述（错误信息与事件载荷共用）。 */
        public String describe() {
            return "http".equals(type)
                    ? host + ":" + port + path
                    : host + ":" + port;
        }
    }

    private ReadyProbe() {
    }

    /**
     * 从节点 config 解析探针声明（缺 type / 未知 type / 无端口即抛，快速失败）。
     *
     * @param fallbackPort {@code ready.port} 缺省时的兜底端口（external 节点 {@code exposes} 的首个非 0 端口）
     */
    public static Spec spec(Map<String, String> config, int fallbackPort) {
        String type = config.get("ready.type");
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("external SUT requires a ready probe "
                    + "(launch.ready: {type: tcp|http, port: ..., timeout: ...})");
        }
        type = type.trim().toLowerCase();
        if (!"tcp".equals(type) && !"http".equals(type)) {
            throw new IllegalArgumentException("unsupported ready.type '" + type
                    + "' (expected tcp|http)");
        }
        String host = config.getOrDefault("ready.host", DEFAULT_HOST);
        int port = intOrDefault(config.get("ready.port"), fallbackPort);
        if (port <= 0) {
            throw new IllegalArgumentException("ready probe needs a port: declare launch.ready.port "
                    + "or a non-zero exposes port");
        }
        String path = config.getOrDefault("ready.path", "/");
        String timeout = config.get("ready.timeout");
        long timeoutMs = timeout == null || timeout.isBlank()
                ? DEFAULT_TIMEOUT_MS : Durations.parseMillis(timeout);
        return new Spec(type, host, port, path, timeoutMs);
    }

    /** 单次探测：目标可达＝true。任何连接/协议异常都归「未就绪」（由调用方按超时裁决）。 */
    public static boolean once(Spec spec) {
        return switch (spec.type()) {
            case "tcp" -> tcpOnce(spec);
            case "http" -> httpOnce(spec);
            default -> throw new IllegalArgumentException("unsupported ready.type: " + spec.type());
        };
    }

    private static boolean tcpOnce(Spec spec) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(spec.host(), spec.port()), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean httpOnce(Spec spec) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://" + spec.host() + ":" + spec.port() + spec.path()))
                .timeout(Duration.ofMillis(HTTP_TIMEOUT_MS))
                .GET()
                .build();
        try {
            HttpResponse<Void> response = client.send(request,
                    HttpResponse.BodyHandlers.discarding());
            // 有 HTTP 应答即视为就绪；5xx 表示服务在但未就绪（§7.3 探针语义）
            return response.statusCode() < 500;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static int intOrDefault(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ready probe port is not a number: '" + raw + "'");
        }
    }
}
