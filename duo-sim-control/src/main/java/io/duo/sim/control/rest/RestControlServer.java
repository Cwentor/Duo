package io.duo.sim.control.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.duo.sim.control.ScenarioHost;
import io.duo.sim.kernel.core.ScenarioRuntime;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 */
public final class RestControlServer implements AutoCloseable {

    private final ScenarioHost host;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final io.duo.sim.control.metrics.MetricsCollector metrics;
    private HttpServer server;

    public RestControlServer(ScenarioHost host) {
        this.host = host;
        this.metrics = new io.duo.sim.control.metrics.MetricsCollector(host);
    }

    /** 启动（port 0 = 自动分配）。返回实际端口。 */
    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        registerRoutes();
        server.start();
        return server.getAddress().getPort();
    }

    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private void registerRoutes() {
        server.createContext("/health", ex -> respond(ex, 200, Map.of("ok", true)));
        server.createContext("/scenario/status", ex -> {
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
            switch (ex.getRequestMethod()) {
                case "POST" -> {
                    if (host.isRunning()) {
                        respond(ex, 409, Map.of("error", "scenario already running"));
                        return;
                    }
                    String yaml = new String(ex.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8);
                    try {
                        Path tmp = java.nio.file.Files.createTempFile("duo-rest-", ".yaml");
                        java.nio.file.Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
                        respond(ex, 200, host.start(tmp));
                    } catch (IllegalArgumentException
                            | com.fasterxml.jackson.core.JacksonException e) {
                        respond(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
                    } catch (IllegalStateException e) {
                        respond(ex, 409, Map.of("error", String.valueOf(e.getMessage())));
                    } catch (Exception e) {
                        respond(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
                    }
                }
                case "DELETE" -> respond(ex, 200, host.stop());
                default -> respond(ex, 405, Map.of("error", "method not allowed"));
            }
        });
        server.createContext("/events", ex -> {
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
            if (!"POST".equals(ex.getRequestMethod())) {
                respond(ex, 405, Map.of("error", "method not allowed"));
                return;
            }
            if (!host.isRunning()) {
                respond(ex, 409, Map.of("error", "scenario not running"));
                return;
            }
            try {
                String body = new String(ex.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
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
            } catch (com.fasterxml.jackson.core.JacksonException
                    | IllegalArgumentException e) {
                respond(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
            } catch (Exception e) {
                respond(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
            }
        });
        server.createContext("/assertions", ex -> {
            if (!host.hasResult() && !host.isRunning()) {
                respond(ex, 409, Map.of("error", "scenario not started"));
                return;
            }
            respond(ex, 200, host.assertions());
        });
        server.createContext("/topology", ex -> {
            // FINISHED 状态也可读（事后审查是控制面核心用途之一，M3 验收 §1）
            if (!host.isRunning() && !host.hasResult()) {
                respond(ex, 409, Map.of("error", "scenario not started"));
                return;
            }
            respond(ex, 200, Map.of("nodes", host.topology()));
        });
        server.createContext("/metrics", ex -> {
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
    }
}
