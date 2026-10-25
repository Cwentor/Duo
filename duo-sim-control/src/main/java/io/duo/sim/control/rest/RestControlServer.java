package io.duo.sim.control.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import io.duo.sim.control.ScenarioHost;
import io.duo.sim.control.library.ScenarioLibrary;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 控制面 REST 服务（M3 T33）：JDK 内置 HttpServer（D1——不引 Web 框架），虚拟线程 executor。
 *
 * <p><b>端点管道</b>：认证/跨站校验、方法检查（405）、请求体上限（413）、异常→错误映射、
 * 错误消息掩码全部收敛在 {@link RestRoutes} 一处执行；本类只剩各端点的声明（路径 + 方法 +
 * 业务分支）与服务生命周期。新增端点＝一条 {@code routes.xxx(...)} 注册 + 业务分支本身，
 * 不再复制任何守卫阶梯（错误映射总表见 {@link RestRoutes} 类注释）。
 *
 * <p>端点清单（核心端点被 CLI 依赖，签名冻结；控制台专属面收进 {@code /api/} 前缀，M10）：
 *
 * <ul>
 *   <li>{@code POST /scenario}——body 为场景 YAML 文本，启动（校验失败→400；按外部输入档）</li>
 *   <li>{@code DELETE /scenario}——停止（立即返回，不等 SUT 收尾）</li>
 *   <li>{@code GET /scenario/status}——运行状态/断言/注入失败/告警（未启动→409）</li>
 *   <li>{@code GET /events?since=N}——事件增量（D3 序号语义；M10 起带 next 游标与 dropped）</li>
 *   <li>{@code POST /inject}——body 为 FaultAction JSON（未启动→409；target 不可解析→404）</li>
 *   <li>{@code GET /assertions} / {@code GET /topology}——断言明细 / 拓扑（事后审查可读）</li>
 *   <li>{@code GET /metrics}——Prometheus 文本格式指标（M8）</li>
 *   <li>{@code GET /health}——服务存活（唯一免认证端点，供探针）</li>
 *   <li>{@code GET /diagnose}——诊断链一等端点（M10）</li>
 *   <li>{@code /api/scenarios} 族——控制台场景库（M10；503＝未配置库）</li>
 *   <li>{@code GET /api/capabilities} / {@code GET /api/meta}——能力元数据 / serve 自述（M10）</li>
 *   <li>{@code POST /api/inject/clear}——手动清除一次热注入（M10）</li>
 *   <li>{@code GET /} 与 {@code /console/**}——SPA 静态托管（免令牌 + CSP + nosniff，M10）</li>
 * </ul>
 *
 * <p><b>安全边界（安全审计 2026-09-20 C-1/H-1/H-2/M-8 整改）</b>：控制面是**执行面**——
 * {@code POST /scenario} 能把一段 YAML 变成宿主机上的进程与文件写。信任模型与四层防线
 * 见决策 D10/D11 与 {@link RestRoutes} 类注释；输入信任分层（CONFIG / EXTERNAL_INPUT）
 * 见 {@link ScenarioHost.Trust} 与 {@code ScenarioValidator} 规则 9–11。
 */
public final class RestControlServer implements AutoCloseable {

    /** 认证方式：令牌 或 显式关闭（{@code --insecure-no-auth}）。两者必须显式选一。 */
    public enum Auth { TOKEN, INSECURE }

    /** 免认证端点（活性探针）。 */
    private static final String HEALTH_PATH = "/health";

    /** 请求体上限（1 MiB）：场景 YAML/FaultAction 都远小于此，超出即 413。 */
    public static final int MAX_BODY_BYTES = 1 << 20;

    private final ScenarioHost host;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final io.duo.sim.control.metrics.MetricsCollector metrics;
    private final Auth auth;
    private final byte[] token;
    /** M10：控制台场景库。{@code null} = 未配置（/api/scenarios 返回 503）。 */
    private ScenarioLibrary library;
    private HttpServer server;
    private ExecutorService executor;
    private RestRoutes routes;

    /** M10：控制台场景库。{@code null} = 未配置（/api/scenarios 返回 503）。 */
    private static final String LIBRARY_NOT_CONFIGURED =
            "scenario library not configured (pass --library-dir to serve)";

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
        this.library = null;
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

    /**
     * M10：带场景库的构造。库承载 {@code /api/scenarios} 端点族（设计 §6.1）；
     * 未配置库的实例上访问这些端点返回 503（显式可辨，不是静默 404）。
     */
    public RestControlServer(ScenarioHost host, Auth auth, String token, ScenarioLibrary library) {
        this(host, auth, token);
        this.library = library;
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

    /**
     * 后台等待 SUT 退出的上限（每个已受理的场景一份）。远超正常场景的时长，只为让
     * "SUT 永不退出"这类病态场景不至于把等待线程永久挂住；到点未退出则状态保持 RUNNING，
     * 由调用方用 {@code DELETE /scenario} 显式停止——这是可见的，不是静默的。
     */
    public static final Duration SCENARIO_FINISH_TIMEOUT = Duration.ofHours(1);

    /**
     * 启动（port 0 = 自动分配）。返回实际端口。
     *
     * <p>注意这里**不**启动后台等待者：它的生命周期跟着"一次 start 请求"走，见
     * {@code /scenario} 的 POST 分支。放在服务启动时是错的——那时还没有任何场景。
     */
    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        routes = new RestRoutes(server, mapper, auth, token);
        registerRoutes(routes);
        server.start();
        return server.getAddress().getPort();
    }

    /** 实际监听端口（未启动＝-1；start 传 port 0 时由此拿内核分配值）。 */
    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    // ---- 端点声明（守卫阶梯与错误映射在 RestRoutes，这里只有业务分支）----

    private void registerRoutes(RestRoutes routes) {
        // /health 免认证（活性探针）；其余端点一律先过认证 + 跨站防护（管道统一执行）。
        routes.publicGet(HEALTH_PATH, call -> call.ok(Map.of("ok", true)));

        routes.get("/scenario/status", call -> {
            call.require(host.hasResult() || host.isRunning(), 409, "scenario not started");
            call.ok(host.status());
        });

        routes.methods("/scenario", Set.of("POST", "DELETE"), call -> {
            if ("POST".equals(call.method())) {
                startScenario(call);
            } else {
                call.ok(host.stopWithoutAwait());
            }
        });

        routes.get("/events", this::events);
        routes.post("/inject", this::inject);

        routes.get("/assertions", call -> {
            call.require(host.hasResult() || host.isRunning(), 409, "scenario not started");
            call.ok(host.assertions());
        });
        routes.get("/topology", call -> {
            // FINISHED 状态也可读（事后审查是控制面核心用途之一，M3 验收 §1）
            call.require(host.isRunning() || host.hasResult(), 409, "scenario not started");
            call.ok(Map.of("nodes", host.topology()));
        });
        routes.get("/metrics", call ->
                call.text(200, metrics.scrape(), "text/plain; version=0.0.4; charset=utf-8"));

        // M10 W-API-7：能力元数据（注入面板「动作×契约」下拉的唯一数据源，前端不写死）。
        // 直接枚举 ServiceLoader provider——不实例化 ContractRegistry，IDLE 态同样可用。
        routes.get("/api/capabilities", call -> call.ok(Map.of("providers", capabilities())));
        // M10 W-API-9：serve 自述（关于页 + 前端能力探测）。不回显令牌本体——只回认证模式。
        routes.get("/api/meta", call -> call.ok(meta()));
        // M10 W-API-8：手动清除注入（与 /inject 同构的 body 与错误映射；薄委托 host.clear）。
        routes.post("/api/inject/clear", this::clearInjection);
        // M10 W-API-10：诊断链服务端一等化——复用 CLI `duo diagnose` 同一套 FaultDiagnostics，
        // 前端直接渲染四段报告（注入 → 组件反应 → SUT 事实 → 断言），断链段沿用 gaps→MISSING 语义。
        routes.get("/diagnose", this::diagnose);
        // M10 W-API-11：SPA 静态托管。静态资源是构建产物、不含机密 → 与 /health 同档免令牌
        //（管道仍做回环 Host/Origin 校验）；API 依旧全令牌。纵深防御：CSP + nosniff。
        routes.publicGet("/", call -> serveConsoleAsset(call.exchange(), call.path()));

        registerLibraryRoutes(routes);
    }

    /** {@code POST /scenario}：外部输入档启动 + 临时文件生命周期 + 后台等待终态。 */
    private void startScenario(HttpCall call) throws Exception {
        call.require(!host.isRunning(), 409, "scenario already running");
        String yaml = call.body();
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
            call.ok(host.start(tmp, io.duo.sim.scenario.InputTrust.EXTERNAL_INPUT,
                    tmp.getFileName().toString()));
            started = true;
        } catch (IllegalStateException e) {
            // 预检后、启动前被并发请求抢先：语义仍是"已在运行"→409（管道默认 ISE→405
            // 是给"模板只读"类端点的，不适用这里）。
            throw new RestRoutes.EndpointError(409, e.getMessage());
        } finally {
            // 场景失败 = 这份 YAML 从未成为 SUT ⇒ 立即删除（审计 M-6/L-1：临时文件泄漏）
            if (!started && tmp != null) {
                deleteQuietly(tmp);
            }
        }
        // serve 语义的一部分：此后由**后台**等待 SUT 退出并固化结果（评估断言 → 终态）。
        // 少了这一步，SUT 自行退出后 /scenario/status 会永远停在 RUNNING，
        // 而调用方看到的"还在跑"与"已经跑完"完全无法区分——那是静默的错误答案，
        // 不是"没实现"。放在启动成功之后：等待者是为这个场景而起的。
        if (started) {
            host.awaitFinishInBackground(SCENARIO_FINISH_TIMEOUT.toMillis());
        }
    }

    /** {@code GET /events?since=N}：增量切片 + 游标 + 丢弃计数（M3 D3 / M10 T2）。 */
    private void events(HttpCall call) throws Exception {
        int since = 0;
        String raw = call.query("since");
        if (raw != null) {
            try {
                since = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                call.error(400, "since must be an integer");
            }
            if (since < 0) {
                // 负数 since 会产生倒退游标（next = since + size），拒绝而非静默钳制（终审 M-6）
                call.error(400, "since must be >= 0");
            }
        }
        List<Map<String, Object>> events;
        try {
            events = host.eventsSince(since);
        } catch (RuntimeException e) {
            // 事件流本身坏了（如某组件发出未登记的类型）⇒ 客户端拿 500 + 根因摘要，
            // 而不是一个空 200（"没有事件"与"读事件失败"必须可区分，§12）
            call.json(500, Map.of("error", "cannot read event stream: "
                    + RestRoutes.sanitizeReason(e.getMessage())));
            return;
        }
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("since", since);
        body.put("next", since + events.size());   // M10：下一游标（eventsSince 语义 = 下标 >= since）
        body.put("dropped", host.droppedEvents()); // M10：丢弃计数可见（§12 不静默延伸到 UI）
        body.put("events", events);
        call.ok(body);
    }

    private void inject(HttpCall call) throws Exception {
        call.require(host.isRunning(), 409, "scenario not running");
        try {
            var action = mapper.readValue(call.body(), io.duo.sim.kernel.api.FaultAction.class);
            var result = host.inject(action);
            if (!result.success() && result.reason() != null
                    && result.reason().contains("unknown target")) {
                call.error(404, result.reason());
            } else {
                call.ok(Map.of("success", result.success(),
                        "reason", result.reason() == null ? "" : result.reason()));
            }
        } catch (NullPointerException e) {
            // 结构上缺 target 的 FaultAction 能通过 JSON 绑定，却在**下游**抛 NPE
            // （`FaultAction.target()` 为 null → 取 componentId()）。这仍然是"调用方输入
            // 不合法"，不是我们的 bug：按审计口径必须是 400，而不是让它落进兜底 500。
            call.error(400, "fault action is missing a required field: " + e.getMessage());
        }
    }

    private void clearInjection(HttpCall call) throws Exception {
        call.require(host.isRunning(), 409, "scenario not running");
        try {
            var action = mapper.readValue(call.body(), io.duo.sim.kernel.api.FaultAction.class);
            var result = host.clear(action);
            call.ok(Map.of("success", result.success(),
                    "reason", result.reason() == null ? "" : result.reason()));
        } catch (NullPointerException e) {
            call.error(400, "fault action is missing a required field: " + e.getMessage());
        }
    }

    /** {@code GET /diagnose}：四段诊断报告（注入 → 组件反应 → SUT 事实 → 断言）。 */
    @SuppressWarnings("unchecked")
    private void diagnose(HttpCall call) throws Exception {
        call.require(host.hasResult() || host.isRunning(), 409, "scenario not started");
        try {
            // host.assertions() 是 {"assertions":[...], "passed":...} Map——与 CLI 同进程
            // 路径同型的断言行抽取（DuoCli cmdDiagnose asRows 语义）
            var raw = host.assertions().get("assertions");
            List<Map<String, Object>> assertionRows = raw instanceof List<?> l
                    ? l.stream().filter(Map.class::isInstance)
                            .map(m -> (Map<String, Object>) m).toList()
                    : List.of();
            var report = io.duo.sim.control.FaultDiagnostics.analyze(
                    host.eventsSince(0), assertionRows);
            call.ok(reportToJson(report));
        } catch (RuntimeException e) {
            call.json(500, Map.of("error", "cannot analyze event stream: "
                    + RestRoutes.sanitizeReason(e.getMessage())));
        }
    }

    private List<Map<String, Object>> capabilities() {
        List<Map<String, Object>> providers = new java.util.ArrayList<>();
        for (var p : java.util.ServiceLoader.load(io.duo.sim.kernel.spi.ComponentProvider.class)) {
            var m = p.metadata();
            var row = new java.util.LinkedHashMap<String, Object>();
            // 契约/档位/端点形态按 DSL 方言输出小写（DSL 用 registry/virtual），前端直接消费
            row.put("contract", p.contract().name().toLowerCase(Locale.ROOT));
            row.put("tier", p.tier().name().toLowerCase(Locale.ROOT));
            row.put("impl", p.implName());
            row.put("default", p.isDefault());
            row.put("supportedFaults", m.supportedFaults());
            row.put("endpointShape", m.endpointShape().name().toLowerCase(Locale.ROOT));
            row.put("interfaceDirect", m.interfaceDirect());
            row.put("instanceControl", m.instanceControl());
            providers.add(row);
        }
        return providers;
    }

    private Map<String, Object> meta() {
        String version = RestControlServer.class.getPackage().getImplementationVersion();
        return Map.of(
                "version", version == null ? "0.1.0-SNAPSHOT" : version,
                "auth", auth.name(),
                "libraryDir", library == null ? "" : library.userDir().toString(),
                "eventBufferMax", io.duo.sim.scenario.EventRecorder.MAX_BUFFERED_EVENTS);
    }

    // ---- M10 W-API：场景库端点族（/api/ 前缀——控制台专属面，不触碰被 CLI 依赖的核心端点）----

    private void registerLibraryRoutes(RestRoutes routes) {
        routes.post("/api/scenarios/validate", call -> {
            call.require(library != null, 503, LIBRARY_NOT_CONFIGURED);
            List<String> errors = library.validateYaml(call.body());
            call.ok(Map.of("ok", errors.isEmpty(), "errors", errors));
        });
        routes.methods("/api/scenarios", Set.of("GET", "PUT", "DELETE", "POST"), call -> {
            call.require(library != null, 503, LIBRARY_NOT_CONFIGURED);
            // 路由前先解码并校验每一段：/api/scenarios/..%2F..%2Fsecret 解码后含路径段
            // 分隔，必须 400（穿越拒绝）而不是落进结构性 404（Review Focus #1）。
            // 解码包在 try 内：双重编码（foo%25zz）的 IAE 也要 400 而不是硬关连接（终审 I-1）
            String[] parts = librarySegments(call);
            switch (call.method()) {
                case "GET" -> libraryGet(call, parts);
                case "PUT" -> libraryPut(call, parts);
                case "DELETE" -> {
                    call.require(parts.length == 1, 404, "not found");
                    library.delete(parts[0]);
                    call.ok(Map.of("id", parts[0], "deleted", true));
                }
                case "POST" -> {
                    if (parts.length == 2 && "fork".equals(parts[1])) {
                        fork(call, parts[0]);
                    } else {
                        call.error(404, "not found");
                    }
                }
                default -> call.error(405, "method not allowed"); // 不可达：管道已按 allowed 过滤
            }
        });
    }

    /** 解码 + 白名单校验 {@code /api/scenarios} 之后的每个路径段（一次完成，业务分支直接用）。 */
    private static String[] librarySegments(HttpCall call) {
        String rest = call.pathTail("/api/scenarios");
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("/");
        for (int i = 0; i < parts.length; i++) {
            String decoded;
            try {
                decoded = java.net.URLDecoder.decode(parts[i], StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new RestRoutes.EndpointError(400, "invalid scenario id encoding");
            }
            if (!ScenarioLibrary.validId(decoded)) {
                throw new RestRoutes.EndpointError(400,
                        "invalid scenario id: only [A-Za-z0-9._-]");
            }
            parts[i] = decoded;
        }
        return parts;
    }

    private void libraryGet(HttpCall call, String[] parts) throws java.io.IOException {
        if (parts.length == 0) {
            var all = library.list();
            call.ok(Map.of(
                    "templates", all.stream().filter(ScenarioLibrary.Entry::template).toList(),
                    "user", all.stream().filter(e -> !e.template()).toList()));
        } else if (parts.length == 1) {
            var c = library.get(parts[0]);
            call.ok(Map.of("id", c.id(), "template", c.template(), "yaml", c.yaml()));
        } else {
            call.error(404, "not found");
        }
    }

    private void libraryPut(HttpCall call, String[] parts) throws Exception {
        call.require(parts.length == 1, 404, "not found");
        String yaml = call.body();
        List<String> errors = library.validateYaml(yaml);
        if (!errors.isEmpty()) {
            call.json(400, Map.of("errors", errors));
            return;
        }
        library.put(parts[0], yaml);
        call.ok(Map.of("id", parts[0], "saved", true));
    }

    /** fork：body 缺省 id ⇒ 按源 id 加后缀自动生成（规格 §6.1#5）。 */
    @SuppressWarnings("unchecked")
    private void fork(HttpCall call, String source) throws Exception {
        String body = call.body();
        String newId = null;
        if (!body.isBlank()) {
            var req = mapper.readValue(body, Map.class);
            Object newIdObj = req.get("id");
            newId = newIdObj instanceof String s && !s.isBlank() ? s : null;
        }
        if (newId == null) {
            newId = source + "-copy";
        }
        call.require(ScenarioLibrary.validId(newId), 400,
                "invalid scenario id: only [A-Za-z0-9._-]");
        String created = library.fork(source, newId);
        call.ok(Map.of("source", source, "id", created));
    }

    // ---- SPA 静态托管（M10 W-API-11）----

    private static final java.util.Map<String, String> ASSET_MIME = java.util.Map.ofEntries(
            java.util.Map.entry("html", "text/html; charset=utf-8"),
            java.util.Map.entry("js", "text/javascript; charset=utf-8"),
            java.util.Map.entry("css", "text/css; charset=utf-8"),
            java.util.Map.entry("svg", "image/svg+xml"),
            java.util.Map.entry("json", "application/json"),
            java.util.Map.entry("png", "image/png"),
            java.util.Map.entry("ico", "image/x-icon"),
            java.util.Map.entry("map", "application/json"),
            java.util.Map.entry("woff2", "font/woff2"));

    /** classpath 上的 SPA 资源根（构建产物由计划二输出到 {@code target/classes/console}）。 */
    private static final String CONSOLE_ROOT = "/console/";

    private void serveConsoleAsset(com.sun.net.httpserver.HttpExchange ex, String path)
            throws java.io.IOException {
        String rel = path.equals("/") ? "index.html" : path;
        if (rel.startsWith("/console/")) {
            rel = rel.substring("/console/".length());
        } else if (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        // 穿越：拒绝 .. 与反斜杠形态；其余按 classpath 资源解析（绝不触碰 java.io.File 路径语义）
        if (rel.contains("..") || rel.contains("\\") || rel.startsWith("/")) {
            routes.respond(ex, 400, Map.of("error", "invalid asset path"));
            return;
        }
        if (rel.isBlank()) {
            rel = "index.html";
        }
        String ext = rel.contains(".") ? rel.substring(rel.lastIndexOf('.') + 1) : "";
        byte[] bytes = null;
        try (var in = RestControlServer.class.getResourceAsStream(CONSOLE_ROOT + rel)) {
            if (in != null) {
                bytes = in.readAllBytes();
            }
        }
        if (bytes == null && ext.isEmpty()) {
            // SPA 路由 fallback：无扩展名路径回 index.html
            try (var in = RestControlServer.class.getResourceAsStream(CONSOLE_ROOT + "index.html")) {
                if (in != null) {
                    bytes = in.readAllBytes();
                }
            }
            ext = "html";
        }
        if (bytes == null) {
            routes.respond(ex, 404, Map.of("error", "asset not found"));
            return;
        }
        ex.getResponseHeaders().set("Content-Type",
                ASSET_MIME.getOrDefault(ext, "application/octet-stream"));
        ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, bytes.length);
        try (var os = ex.getResponseBody()) {
            os.write(bytes);
        } catch (IOException e) {
            // 客户端断开
        }
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

    /**
     * {@link io.duo.sim.control.FaultDiagnostics.Report} → JSON 安全 Map。
     *
     * <p>为什么不直接序列化 record：{@code Chain.componentReactions} 内嵌内核
     * {@code Event}，其 {@code timestamp} 是 {@code java.time.Instant}——Jackson 无
     * JSR-310 模块（零依赖纪律）抛 {@code InvalidDefinitionException}。转换与
     * {@code ScenarioHost.eventsSince} 同款（{@code timestamp.toString()}）。
     */
    private static Map<String, Object> reportToJson(io.duo.sim.control.FaultDiagnostics.Report r) {
        var chains = new java.util.ArrayList<Map<String, Object>>(r.chains().size());
        for (var c : r.chains()) {
            var chain = new java.util.LinkedHashMap<String, Object>();
            chain.put("injectedIndex", c.injectedIndex());
            chain.put("target", c.target());
            chain.put("action", c.action());
            chain.put("failedReason", c.failedReason());
            chain.put("cleared", c.cleared());
            var reactions = new java.util.ArrayList<Map<String, Object>>(c.componentReactions().size());
            for (io.duo.sim.kernel.api.Event e : c.componentReactions()) {
                reactions.add(io.duo.sim.kernel.api.EventProjections.toMap(e));
            }
            chain.put("componentReactions", reactions);
            chain.put("sutFacts", c.sutFacts());
            chain.put("sutFactTotal", c.sutFactTotal());
            chain.put("windowEnd", c.windowEnd());
            chains.add(chain);
        }
        var out = new java.util.LinkedHashMap<String, Object>();
        out.put("chains", chains);
        out.put("assertions", r.assertions());
        out.put("eventsTotal", r.eventsTotal());
        out.put("gaps", r.gaps());
        return out;
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
