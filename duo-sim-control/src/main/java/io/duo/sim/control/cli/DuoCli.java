package io.duo.sim.control.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.duo.sim.control.ScenarioHost;
import io.duo.sim.control.rest.RestControlServer;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.util.Durations;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * CLI 主类（M3 T34）：子命令
 * {@code run / serve / stop / inject / status / events / assert / topology / metrics / help}。
 *
 * <p>两种模式（计划 D2）：
 * <ul>
 *   <li><b>同进程直连</b>（缺省）——{@code run --keep} 把 host 注册进进程级 attach 表，
 *       后续命令按名接管；{@code --inject-after} 提供"启动+注入+等待+报结果"单命令形态。</li>
 *   <li><b>REST 客户端</b>（{@code --url}）——{@code serve} 在独立进程拉起场景与
 *       {@link RestControlServer}，其余命令经 HTTP 操作运行中的场景（跨进程热注入）。</li>
 * </ul>
 *
 * <p>退出码：0=成功/断言通过；1=失败（含 REST 非 2xx）。供脚本化验收与 CI 使用。
 */
public final class DuoCli {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** 可测试入口：返回退出码。 */
    public static int run(String... args) {
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
            printHelp();
            return 0;
        }
        String cmd = args[0];
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            rest.add(args[i]);
        }
        try {
            return switch (cmd) {
                case "run" -> cmdRun(rest);
                case "serve" -> cmdServe(rest);
                case "stop" -> cmdStop(rest);
                case "inject" -> cmdInject(rest);
                case "status" -> cmdStatus(rest);
                case "events" -> cmdEvents(rest);
                case "assert" -> cmdAssert(rest);
                case "topology" -> cmdTopology(rest);
                case "metrics" -> cmdMetrics(rest);
                case "diagnose" -> cmdDiagnose(rest);
                default -> {
                    System.err.println("unknown command: " + cmd + " (try 'help')");
                    yield 1;
                }
            };
        } catch (Exception e) {
            // ConnectException 等的 getMessage() 常为 null，退回类型名避免 "error: null"
            System.err.println("error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return 1;
        }
    }

    // ---- run <yaml> [--keep] [--name N] [--wait] [--inject-after <dur> "<action> <target>"] ----

    private static int cmdRun(List<String> args) throws Exception {
        String yamlPath = arg(args, 0);
        if (yamlPath == null) {
            System.err.println("usage: run <scenario.yaml> [--keep] [--name N] [--wait]"
                    + " [--inject-after <dur> \"<action> <target>\"]");
            return 1;
        }
        boolean keep = args.contains("--keep");
        String name = opt(args, "--name") != null ? opt(args, "--name") : "default";
        String injectSpec = null;
        long injectAfterMs = 0;
        int injectAt = args.indexOf("--inject-after");
        if (injectAt >= 0) {
            if (injectAt + 2 >= args.size()) {
                System.err.println("--inject-after requires <duration> \"<action> <target>\"");
                return 1;
            }
            injectAfterMs = Durations.parseMillis(args.get(injectAt + 1));
            injectSpec = args.get(injectAt + 2);
        }
        // 定时注入必须等场景走完才有意义（否则 JVM 退出会连带杀掉场景）
        boolean wait = args.contains("--wait") || injectSpec != null;

        ScenarioHost host = new ScenarioHost();
        host.start(Path.of(yamlPath));
        if (keep) {
            host.attach(name); // 同进程模式：后续命令按名接管
            System.out.println("attached as '" + name + "' (pid-scoped)");
        }
        if (injectSpec != null) {
            FaultAction action = parseActionSpec(injectSpec, null);
            scheduleInjection(host, action, injectAfterMs);
        }
        if (wait) {
            host.awaitFinish(120_000);
            host.stop();
            host.detach(name);
        }
        Map<String, Object> st = host.status();
        printJson(st);
        if (!wait) {
            return 0;
        }
        return Boolean.TRUE.equals(st.get("passed")) ? 0 : 1;
    }

    /** 到点注入（虚拟线程；注入事实与失败原因都打印，不静默）。 */
    private static void scheduleInjection(ScenarioHost host, FaultAction action, long delayMs) {
        System.out.println("[inject-after] " + delayMs + "ms 后注入 " + action.type()
                + " " + action.target().componentId().value()
                + (action.target().instanceIndex() == null ? ""
                : "[" + action.target().instanceIndex() + "]"));
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(delayMs);
                var r = host.inject(action);
                System.out.println("[inject-after] " + (r.success() ? "OK" : "FAILED")
                        + (r.reason() == null ? "" : ": " + r.reason()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ---- serve <yaml> [--port N] [--token T | --token-file F | --insecure-no-auth] ----

    private static int cmdServe(List<String> args) throws Exception {
        String yamlPath = arg(args, 0);
        if (yamlPath == null) {
            System.err.println("usage: serve <scenario.yaml> [--port N]"
                    + " [--token T | --token-file F | --insecure-no-auth]");
            return 1;
        }
        String portArg = opt(args, "--port");
        int port = portArg == null ? 7788 : Integer.parseInt(portArg);

        // 认证材料解析（安全审计 2026-09-20 C-1）：令牌必填，缺省取环境变量 DUO_TOKEN，
        // 两处都没有就**拒绝启动**——默认裸奔是这个漏洞的根因，不能靠"记得加参数"。
        boolean insecure = hasFlag(args, "--insecure-no-auth");
        String token = opt(args, "--token");
        String tokenFile = opt(args, "--token-file");
        if (token == null) {
            token = System.getenv("DUO_TOKEN");
        }
        if (token == null && tokenFile != null) {
            token = Files.readString(Path.of(tokenFile), java.nio.charset.StandardCharsets.UTF_8)
                    .trim();
        }
        if (token == null || token.isEmpty()) {
            if (!insecure) {
                System.err.println("refusing to start: the control plane executes scenarios, so a"
                        + " bearer token is required.\n"
                        + "  pass --token <T>, or set DUO_TOKEN, or --token-file <F>.\n"
                        + "  (--insecure-no-auth disables authentication explicitly; only for"
                        + " throwaway local use)");
                return 1;
            }
            System.out.println("WARNING: --insecure-no-auth — the control plane is unauthenticated"
                    + " and any local process or web page can start scenarios on this machine.");
        }
        ScenarioHost host = new ScenarioHost();
        RestControlServer server = new RestControlServer(host,
                insecure ? RestControlServer.Auth.INSECURE : RestControlServer.Auth.TOKEN, token);
        host.start(Path.of(yamlPath));
        // SUT 自行退出时自动固化结果（客户端只需轮询 status，无需触发停止）
        host.awaitFinishInBackground(30 * 60_000L);
        int actual = server.start(port);
        // 脚本据此发现端口（--port 0 时为实际分配值）；令牌**不**回显（它已经在客户端手里，
        // 回显只会把它写进日志与终端历史——审计 M-8 的口径泄露同源问题）。
        System.out.println("listening on http://127.0.0.1:" + actual
                + (insecure ? " (auth: none)" : " (auth: bearer token)"));
        System.out.flush();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            host.close();
        }));
        new CountDownLatch(1).await(); // 阻塞至 SIGINT/SIGTERM
        return 0;
    }

    // ---- stop [--url X]：停止场景（REST DELETE /scenario） ----

    private static int cmdStop(List<String> args) throws Exception {
        initClientToken(args);
        String url = opt(args, "--url");
        if (url == null) {
            ScenarioHost host = attachedOrDefault(args);
            printJson(host.stop());
            return 0;
        }
        var resp = send("DELETE", url + "/scenario", null);
        System.out.println(resp.statusCode() + " " + resp.body());
        return resp.statusCode() / 100 == 2 ? 0 : 1;
    }

    // ---- inject <action> <target> [duration] [--url X] ----

    private static int cmdInject(List<String> args) {
        initClientToken(args);
        String action = arg(args, 0);
        String target = arg(args, 1);
        String url = opt(args, "--url");
        String duration = opt(args, "--duration") != null ? opt(args, "--duration")
                : (args.size() > 2 && !args.get(2).startsWith("--") ? args.get(2) : null);
        if (action == null || target == null) {
            System.err.println("usage: inject <action> <target> [duration] [--url X] [--name N]");
            return 1;
        }
        FaultAction fa = new FaultAction(action, parseTarget(target), Map.of(),
                duration == null ? null : Durations.parseMillis(duration));

        if (url != null) {
            try {
                var resp = send("POST", url + "/inject", MAPPER.writeValueAsString(fa));
                System.out.println(resp.statusCode() + " " + resp.body());
                return resp.statusCode() / 100 == 2 ? 0 : 1;
            } catch (Exception e) {
                System.err.println("error: " + (e.getMessage() == null
                        ? e.toString() : e.getMessage()));
                return 1;
            }
        }
        // 同进程模式：按名接管 run --keep 注册的场景
        String name = opt(args, "--name") == null ? "default" : opt(args, "--name");
        ScenarioHost host = ScenarioHost.attached(name);
        if (host == null) {
            System.err.println("no attached scenario '" + name
                    + "' (start one with: run <yaml> --keep --name " + name + ")");
            return 1;
        }
        var r = host.inject(fa);
        System.out.println((r.success() ? "OK" : "FAILED")
                + (r.reason() == null ? "" : ": " + r.reason()));
        return r.success() ? 0 : 1;
    }

    // ---- status / events / assert / topology ----

    private static int cmdStatus(List<String> args) throws Exception {
        initClientToken(args);
        String url = opt(args, "--url");
        if (url != null) {
            var resp = send("GET", url + "/scenario/status", null);
            System.out.println(resp.statusCode() + " " + resp.body());
            return resp.statusCode() / 100 == 2 ? 0 : 1;
        }
        // 不 close：attached host 的生命周期归 run 命令（close 会停掉运行中的场景）
        printJson(attachedOrDefault(args).status());
        return 0;
    }

    private static int cmdEvents(List<String> args) throws Exception {
        initClientToken(args);
        int since = 0;
        String sinceArg = opt(args, "--since");
        if (sinceArg != null) {
            since = Integer.parseInt(sinceArg);
        }
        String url = opt(args, "--url");
        if (url != null) {
            var resp = send("GET", url + "/events?since=" + since, null);
            if (resp.statusCode() / 100 != 2) {
                System.out.println(resp.statusCode() + " " + resp.body());
                return 1;
            }
            for (var e : MAPPER.readTree(resp.body()).get("events")) {
                System.out.println(e.get("seq").asInt() + " " + e.get("type").asText()
                        + " [" + e.get("sourceId").asText() + "] " + e.get("payload"));
            }
            return 0;
        }
        for (var e : attachedOrDefault(args).eventsSince(since)) {
            System.out.println(e.get("seq") + " " + e.get("type")
                    + " [" + e.get("sourceId") + "] " + e.get("payload"));
        }
        return 0;
    }

    private static int cmdAssert(List<String> args) throws Exception {
        initClientToken(args);
        String url = opt(args, "--url");
        if (url != null) {
            var resp = send("GET", url + "/assertions", null);
            System.out.println(resp.statusCode() + " " + resp.body());
            if (resp.statusCode() / 100 != 2) {
                return 1;
            }
            return MAPPER.readTree(resp.body()).path("passed").asBoolean(false) ? 0 : 1;
        }
        Map<String, Object> a = attachedOrDefault(args).assertions();
        printJson(a);
        return Boolean.TRUE.equals(a.get("passed")) ? 0 : 1;
    }

    private static int cmdTopology(List<String> args) throws Exception {
        initClientToken(args);
        String url = opt(args, "--url");
        if (url != null) {
            var resp = send("GET", url + "/topology", null);
            if (resp.statusCode() / 100 != 2) {
                System.out.println(resp.statusCode() + " " + resp.body());
                return 1;
            }
            for (var n : MAPPER.readTree(resp.body()).get("nodes")) {
                printTopologyRow(n.path("id").asText(), n.path("contract").asText(),
                        n.path("tier").asText(), n.path("count").asInt(1),
                        n.path("hosted").asBoolean(false), n.path("healthy"));
            }
            return 0;
        }
        for (var n : attachedOrDefault(args).topology()) {
            printTopologyRow(String.valueOf(n.get("id")), String.valueOf(n.get("contract")),
                    String.valueOf(n.get("tier")), ((Number) n.getOrDefault("count", 1)).intValue(),
                    Boolean.TRUE.equals(n.get("hosted")), n.get("healthy"));
        }
        return 0;
    }

    /** 拓扑表格行（两种模式共用输出格式）。 */
    private static void printTopologyRow(String id, String contract, String tier, int count,
                                         boolean hosted, Object healthy) {
        System.out.println(id + "  " + contract + "/" + tier + "  count=" + count
                + "  hosted=" + hosted + "  healthy="
                + (healthy == null || "null".equals(healthy) ? "-" : healthy));
    }

    // ---- metrics [--url X] [--summary]：Prometheus 文本 / 单行摘要（M8） ----

    private static int cmdMetrics(List<String> args) throws Exception {
        initClientToken(args);
        String url = opt(args, "--url");
        if (url != null) {
            var resp = send("GET", url + "/metrics", null);
            if (resp.statusCode() / 100 != 2) {
                System.err.println(resp.statusCode() + " " + resp.body());
                return 1;
            }
            System.out.print(resp.body());
            return 0;
        }
        ScenarioHost host = attachedOrDefault(args);
        if (hasFlag(args, "--summary")) {
            System.out.println(new io.duo.sim.control.metrics.MetricsCollector(host).summaryLine());
            return 0;
        }
        System.out.print(new io.duo.sim.control.metrics.MetricsCollector(host).scrape());
        return 0;
    }

    /** 布尔开关（无值标志）。 */
    private static boolean hasFlag(List<String> args, String flag) {
        return args.contains(flag);
    }

    // ---- diagnose [--url X]：单命令导出一次故障注入的完整因果链（M8 交付物 3） ----

    private static int cmdDiagnose(List<String> args) throws Exception {
        initClientToken(args);
        String url = opt(args, "--url");
        String sinceArg = opt(args, "--since");
        int since = sinceArg == null ? 0 : Integer.parseInt(sinceArg);
        List<Map<String, Object>> events;
        List<Map<String, Object>> assertions = new ArrayList<>();
        String where;
        if (url != null) {
            var resp = send("GET", url + "/events?since=" + since, null);
            if (resp.statusCode() / 100 != 2) {
                System.err.println(resp.statusCode() + " " + resp.body());
                return 1;
            }
            var node = MAPPER.readTree(resp.body()).get("events");
            events = new ArrayList<>();
            for (var e : node) {
                events.add(MAPPER.convertValue(e, new com.fasterxml.jackson.core.type
                        .TypeReference<Map<String, Object>>() {
                }));
            }
            where = url;
        } else {
            ScenarioHost host = attachedOrDefault(args);
            events = host.eventsSince(since);
            where = "in-process";
            assertions = new ArrayList<>(asRows(host.assertions().get("assertions")));
        }
        io.duo.sim.control.FaultDiagnostics.Report report =
                io.duo.sim.control.FaultDiagnostics.analyze(events, assertions);
        System.out.print(report.render(where));
        // 因果链断在中间时退出码非 0：脚本化验收可直接判定
        return report.complete() ? 0 : 1;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRows(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    /** 同进程接管：优先取 run --keep 注册的 host；无则空 host（状态报告 IDLE）。 */
    private static ScenarioHost attachedOrDefault(List<String> args) {
        String name = opt(args, "--name") == null ? "default" : opt(args, "--name");
        ScenarioHost attached = ScenarioHost.attached(name);
        return attached != null ? attached : new ScenarioHost();
    }

    private static HttpResponse<String> send(String method, String url, String body)
            throws Exception {
        return send(method, url, body, null);
    }

    /**
     * REST 调用。{@code tokenOverride} 为空时按序取 {@code DUO_TOKEN} 环境变量 →
     * {@code --token}/{@code --token-file}（由调用方注入 {@link #clientToken}）。
     *
     * <p>令牌只进请求头，不进命令行回显、不进日志（审计 M-8：凭据不得落到达不到的地方）。
     */
    private static HttpResponse<String> send(String method, String url, String body,
                                             String tokenOverride) throws Exception {
        var builder = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(30));
        String token = tokenOverride != null ? tokenOverride : clientToken.get();
        if (token != null && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 客户端令牌（进程级；来自 {@code DUO_TOKEN}，供各 RPC 子命令共用）。 */
    private static final ThreadLocal<String> clientToken = new ThreadLocal<>();

    /** 解析客户端令牌：显式参数 > {@code DUO_TOKEN} 环境变量 > {@code --token-file}。 */
    private static void initClientToken(List<String> args) {
        String token = opt(args, "--token");
        if (token == null) {
            token = System.getenv("DUO_TOKEN");
        }
        if (token == null) {
            String file = opt(args, "--token-file");
            if (file != null) {
                try {
                    token = Files.readString(Path.of(file),
                            java.nio.charset.StandardCharsets.UTF_8).trim();
                } catch (Exception e) {
                    System.err.println("cannot read --token-file: " + e.getMessage());
                    token = null;
                }
            }
        }
        clientToken.set(token);
    }

    // ---- 解析辅助 ----

    /** {@code workers[2]} → 实例级地址；{@code workers} → 整组。 */
    private static FaultAction.ComponentAddress parseTarget(String target) {
        int bracket = target.indexOf('[');
        if (bracket < 0) {
            return FaultAction.ComponentAddress.of(new ComponentId(target.trim()));
        }
        int idx = Integer.parseInt(target.substring(bracket + 1, target.length() - 1));
        return FaultAction.ComponentAddress.ofInstance(
                new ComponentId(target.substring(0, bracket)), idx);
    }

    /** 解析 {@code "crash workers[2]"} / {@code "registry-flap zk 5s"} 形式的动作规格。 */
    static FaultAction parseActionSpec(String spec, String durationOverride) {
        String[] parts = spec.trim().split("\\s+");
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "action spec must be '<action> <target> [duration]': " + spec);
        }
        String duration = durationOverride != null ? durationOverride
                : (parts.length >= 3 ? parts[2] : null);
        return new FaultAction(parts[0], parseTarget(parts[1]), Map.of(),
                duration == null ? null : Durations.parseMillis(duration));
    }

    private static String arg(List<String> args, int i) {
        return i < args.size() ? args.get(i) : null;
    }

    private static String opt(List<String> args, String flag) {
        for (int i = 0; i < args.size(); i++) {
            if (flag.equals(args.get(i)) && i + 1 < args.size()) {
                return args.get(i + 1);
            }
        }
        return null;
    }

    private static void printJson(Object o) {
        System.out.println(o);
    }

    private static void printHelp() {
        System.out.println("""
                duo —— Duo 仿真控制面 CLI（M3）

                子命令：
                  run <scenario.yaml> [--keep] [--name N] [--wait]
                        [--inject-after <dur> "<action> <target>"]
                                                    启动场景；--inject-after 到点自动注入并等待结果
                  serve <scenario.yaml> [--port N] [--token T | --token-file F | --insecure-no-auth]
                                                     独立进程承载场景 + REST 服务（跨进程模式服务端）；
                                                     令牌必填（缺省读环境变量 DUO_TOKEN），
                                                     --insecure-no-auth 显式关闭认证（仅本机临时用）
                  stop [--url X] [--token T]        停止场景
                  inject <action> <target> [duration] [--url X] [--token T]
                                                    注入故障（target 如 workers[2]）
                  status [--url X] [--token T]      场景状态
                  events [--since N] [--url X]      事件流（增量）
                  assert [--url X]                  断言结果（退出码反映通过与否）
                  topology [--url X]                拓扑视图
                  metrics [--url X] [--summary]     Prometheus 指标（--summary 单行摘要）
                  diagnose [--url X] [--since N]    导出一次注入的因果链（断链退出码 1）
                  help                              本帮助

                两种模式：
                  同进程（缺省）——run --keep 注册到进程级 attach 表，后续命令按名接管；
                  REST 客户端——serve 起服务端后，各命令加 --url http://127.0.0.1:<port>
                  操作运行中的独立进程场景（热注入）。REST 模式需带令牌：--token T 或
                  环境变量 DUO_TOKEN（凭据只进请求头，不进日志）。

                示例（单命令验收）：
                  duo run scenario.yaml --inject-after 3s "crash workers[2]" --wait
                  DUO_TOKEN=$(openssl rand -hex 16) duo serve scenario.yaml --port 7788 &
                  duo status --url http://127.0.0.1:7788 --token "$DUO_TOKEN\"""");
    }
}
