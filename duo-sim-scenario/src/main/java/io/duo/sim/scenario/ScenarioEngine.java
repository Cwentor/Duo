package io.duo.sim.scenario;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.core.ComponentManager;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.kernel.core.ScenarioRuntime;
import io.duo.sim.kernel.core.SimpleEventBus;
import io.duo.sim.kernel.core.WiringResolver;
import io.duo.sim.scenario.model.Scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 场景最小执行（计划 T11）：校验 → 实例化 → 拓扑排序 → 启动 → 运行 → 统一清理。
 *
 * <p>M0 结束条件＝SUT 正常退出（{@link #notifySutExit(boolean)}）或显式 {@link #stop()}；
 * timeline 自 M1 T16 起由 {@link TimelineScheduler} 自动执行（以启动为 t0，duration 到期自动
 * clear），注入失败/警告计入 {@link #result()}；热注入走 {@link #inject(FaultAction)}。
 * 不新增 DSL 字段（计划 §2/T11）。
 */
public final class ScenarioEngine implements AutoCloseable {

    /** SUT 停止回调（T12 接入协作停止；未接入时 M0 场景结束直接跳过）。 */
    public interface SutStopper {
        void stopSut();
    }

    private final Scenario scenario;
    private final ContractRegistry registry;
    private final SimpleEventBus bus = new SimpleEventBus();
    /** 事件流（多线程写入：SUT sink / 时间线线程 / 门面 watch）→ 必须线程安全。 */
    private final List<Event> recorded = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ComponentManager manager = new ComponentManager();
    /** 注入事件经 bus 汇流（与内存流/录制共用单一订阅路径，T21）。 */
    private final ScenarioRuntime runtime = new ScenarioRuntime(bus::publish);
    private final ScenarioResult result = ScenarioResult.create();
    private final Map<String, VirtualComponent> byId = new ConcurrentHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final CountDownLatch sutExit = new CountDownLatch(1);
    private volatile boolean started;
    private volatile SutStopper sutStopper;
    /** external SUT 启动器（M6；in-process 形态为 null）——生命周期归用户，故公开句柄。 */
    private volatile io.duo.sim.kernel.sut.ExternalSutLauncher externalSut;
    private volatile TimelineScheduler timeline;
    /** custom-hook 注册表（M5/G5）：可用 {@link #withHooks} 注入，默认空表。 */
    private HookRegistry hookRegistry = new HookRegistry();
    private final EventRecorder recorder;

    public ScenarioEngine(Scenario scenario, ContractRegistry registry) {
        this.scenario = scenario;
        this.registry = registry;
        // 录制与内存流同一订阅点（构造即订阅）：保证两条流事件数一致（T21）
        this.recorder = EventRecorder.to(java.nio.file.Path.of("build", "scenarios",
                scenario.name(), "events.jsonl"));
        bus.subscribe(recorded::add);
        bus.subscribe(recorder::onEvent);
    }

    /**
     * 注入自定义 hook 注册表（M5/G5 修复，DSL §3）：YAML 时间线的 {@code custom-hook} 动作据此执行。
     * 此前引擎内部固定 {@code new HookRegistry()}，用户无处注册，动作必然「no hook registered」失败。
     * 返回 {@code this} 便于 {@code ScenarioEngine.validated(...).withHooks(h)} 链式调用。
     */
    public ScenarioEngine withHooks(HookRegistry hooks) {
        this.hookRegistry = java.util.Objects.requireNonNull(hooks, "hooks");
        return this;
    }

    /** 当前 hook 注册表（默认空表；已注册的 hook 可被 YAML 时间线调用）。 */
    public HookRegistry hooks() {
        return hookRegistry;
    }

    /** 加载即校验（§8 快速失败：errors 非空抛 IllegalArgumentException）。 */
    public static ScenarioEngine validated(Scenario scenario, ContractRegistry registry) {
        var report = new ScenarioValidator(registry).validate(scenario);
        if (!report.ok()) {
            throw new IllegalArgumentException("scenario validation failed: "
                    + String.join("; ", report.errors()));
        }
        ScenarioEngine e = new ScenarioEngine(scenario, registry);
        e.warnings.addAll(report.warnings());
        return e;
    }

    /** 启动全部内核托管组件（SUT 与 external 节点不在此列；SUT 依赖已由 startSut 启动的跳过）。 */
    public void startComponents() {
        registry.validateDefaults();
        Map<String, Scenario.NodeSpec> specById = new LinkedHashMap<>();
        scenario.nodes().forEach(n -> specById.put(n.id(), n));
        Map<String, WiringResolver.NodeView> views = new LinkedHashMap<>();
        specById.values().forEach(n -> views.put(n.id(), toView(n)));

        List<String> order = WiringResolver.topoOrder(new ArrayList<>(views.values()));
        for (String id : order) {
            var n = specById.get(id);
            if (n.sut() || isExternal(n) || !startable(n) || byId.containsKey(id)) {
                continue; // byId 已含（startSut 预启动的依赖）则跳过
            }
            var provider = registry.resolve(Contract.fromYaml(n.contract()),
                    Tier.fromYaml(n.tier()), n.impl());
            VirtualComponent c = provider.newComponent();
            Map<String, String> cfg = new LinkedHashMap<>(n.config());
            cfg.putAll(n.capacity());
            if (n.count() != null) {
                cfg.put("count", String.valueOf(n.count()));
            }
            mergeBehaviors(cfg, n);
            var wiring = WiringResolver.resolveNode(views.get(id), views,
                    (contract, tier) -> {
                        var t = specById.values().stream()
                                .filter(x -> x.contract().equalsIgnoreCase(contract)
                                        && x.tier().equalsIgnoreCase(tier))
                                .findFirst().orElse(null);
                        return t == null ? null : registry.resolve(
                                Contract.fromYaml(t.contract()),
                                Tier.fromYaml(t.tier()), t.impl()).metadata();
                    },
                    (slot, targetId) -> byId.get(targetId),
                    (slot, targetId) -> endpointOf(specById.get(targetId)));
            c.init(new ComponentContext(new ComponentId(id), cfg, SimClock.real(), bus,
                    wiring, exposes(n)));
            byId.put(id, c);
            runtime.registerTarget(id, new ScenarioRuntime.Target(
                    c, id, n.count() == null ? 1 : n.count()));
        }
        specById.values().stream().filter(Scenario.NodeSpec::sut)
                .findFirst().ifPresent(s -> runtime.markSut(s.id()));

        // 注意：startSut 预启动的依赖（如 registry）已 adopt 进 manager 且已 start；
        // 这里必须排除它们，否则会被二次 start（CuratorRegistry 二次 start 会重建
        // TestingServer → 已注册节点全丢，worker 发现失败）
        manager.startAll(order.stream()
                .filter(byId::containsKey)
                .filter(id -> !manager.live().containsKey(id))
                .toList(), byId::get);
        started = true;
        // 时间线执行器（T16）：全部组件启动完成的当前时刻为 t0，非空才启动
        if (!scenario.timeline().isEmpty()) {
            timeline = new TimelineScheduler(scenario.timeline(), runtime, result, hookRegistry);
            timeline.start();
        }
        bus.publish(Event.sim("sim.scenario-started", scenario.name(), Map.of()));
    }

    /** 内核是否应启动该节点（in-process/real kernel-hosted 均为 VirtualComponent 路径）。 */
    private static boolean startable(Scenario.NodeSpec n) {
        String mode = nzMode(n);
        return "in-process".equals(mode);
    }

    private static boolean isExternal(Scenario.NodeSpec n) {
        return "external".equals(nzMode(n));
    }

    private static String nzMode(Scenario.NodeSpec n) {
        return n.launch() == null ? "in-process" : n.launch().mode();
    }

    /**
     * 启动 SUT（M6：in-process 与 external 两形态）。
     *
     * <p>调用时机：SUT 须在内核组件**之前**启动（in-process 的 Duo 端点要写进 registry，
     * workers 的发现等待语义依赖它；external 的 ready 探针同理——计划风险 5）。
     *
     * <p>两形态共用：① SUT 的依赖先起（纳入 manager 拆除序列）；② 按 wiring 槽把目标节点
     * **实际**端点解析为 SUT 端点清单（external 形态写入端点配置文件，§7.3 主途径）。
     * {@code sut.exited/sut.crashed} 事件自动触发场景结束信号。
     */
    public SutLauncherHandle startSut() {
        var spec = scenario.nodes().stream().filter(Scenario.NodeSpec::sut)
                .findFirst().orElseThrow(() -> new IllegalStateException("no SUT node"));
        Map<String, Scenario.NodeSpec> specById = new LinkedHashMap<>();
        scenario.nodes().forEach(n -> specById.put(n.id(), n));
        startDirectDependencies(spec, specById);
        Map<String, String> sutEndpoints = sutEndpoints(spec, specById);

        if (isExternal(spec)) {
            return startExternalSut(spec, sutEndpoints);
        }
        if (spec.launch() == null || !"in-process".equals(spec.launch().mode())) {
            throw new IllegalStateException("SUT launch.mode must be in-process (with main) "
                    + "or external (with configOut): " + spec.id());
        }
        try {
            var cls = Class.forName(spec.launch().main());
            var main = (io.duo.sim.kernel.api.SutMain)
                    cls.getDeclaredConstructor().newInstance();
            var launcher = new io.duo.sim.kernel.sut.SutLauncher(
                    spec.id(), main, Map.copyOf(byId), spec.config(), sutEndpoints,
                    this::onSutEvent,
                    java.nio.file.Path.of("build", "duo-sut-" + spec.id() + ".properties"),
                    readyTimeoutMs(spec.config()));
            launcher.start();
            registerSutStopper(launcher::stop);
            return new SutLauncherHandle(spec, launcher);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot launch SUT main: "
                    + spec.launch().main(), e);
        }
    }

    /** SUT 的依赖先起：实例化 + start + adopt（纳入统一拆除序列），供两形态共用。 */
    private void startDirectDependencies(Scenario.NodeSpec spec,
                                         Map<String, Scenario.NodeSpec> specById) {
        for (var slot : spec.wiring().values()) {
            String targetId = slot.node();
            if (byId.containsKey(targetId)) {
                continue;
            }
            var depSpec = specById.get(targetId);
            var provider = registry.resolve(Contract.fromYaml(depSpec.contract()),
                    Tier.fromYaml(depSpec.tier()), depSpec.impl());
            VirtualComponent dep = provider.newComponent();
            Map<String, String> cfg = new LinkedHashMap<>(depSpec.config());
            cfg.putAll(depSpec.capacity());
            if (depSpec.count() != null) {
                cfg.put("count", String.valueOf(depSpec.count()));
            }
            dep.init(new ComponentContext(new ComponentId(targetId), cfg, SimClock.real(),
                    bus, Map.of(), exposes(depSpec)));
            dep.start();
            byId.put(targetId, dep);
            manager.adopt(targetId, dep);
            runtime.registerTarget(targetId, new ScenarioRuntime.Target(
                    dep, targetId, depSpec.count() == null ? 1 : depSpec.count()));
        }
    }

    /**
     * SUT 端点清单（T31(a)）：按 SUT 的 wiring 槽解析目标节点的实际端点
     * （wire 槽 → 目标 endpoints() 地址；如 embedded registry 的 ZK 端口）。
     */
    private Map<String, String> sutEndpoints(Scenario.NodeSpec spec,
                                             Map<String, Scenario.NodeSpec> specById) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var slot : spec.wiring().entrySet()) {
            var slotSpec = slot.getValue();
            var targetNode = specById.get(slotSpec.node());
            if (targetNode == null) {
                continue;
            }
            String contract = slotSpec.contract() == null ? slot.getKey()
                    : slotSpec.contract();
            VirtualComponent target = byId.get(targetNode.id());
            if (target == null) {
                continue;
            }
            var eps = target.endpoints();
            if (!eps.isEmpty()) {
                out.put(contract.toLowerCase(), eps.get(0).address());
            }
        }
        return out;
    }

    /**
     * external SUT 启动（M6）：写端点配置文件 → 可选代起子进程 → ready 探针。
     * 生命周期归用户（§7.3）：场景结束不杀进程，句柄经 {@link #externalSut()} 暴露。
     */
    private SutLauncherHandle startExternalSut(Scenario.NodeSpec spec,
                                               Map<String, String> sutEndpoints) {
        var launcher = new io.duo.sim.kernel.sut.ExternalSutLauncher(
                spec.id(), splitCommand(spec.launch().command()), spec.config(), sutEndpoints,
                this::onSutEvent,
                spec.launch().configOut() == null
                        ? null : java.nio.file.Path.of(spec.launch().configOut()),
                firstExposedPort(spec));
        launcher.start();
        this.externalSut = launcher;
        return new SutLauncherHandle(spec, null, launcher);
    }

    /** SUT 事件汇流（内存流与录制共用单一订阅路径，T21）+ 生命周期事实 → 场景结束信号。 */
    private void onSutEvent(Event e) {
        bus.publish(e);
        if (e.type().equals("sut.exited")) {
            notifySutExit(true);
        } else if (e.type().equals("sut.crashed")) {
            notifySutExit(false);
        }
    }

    /** {@code ready.timeout} 全链路消费（M6 交付物 2）：in-process 与 external 同口径。 */
    private static long readyTimeoutMs(Map<String, String> config) {
        String timeout = config.get("ready.timeout");
        return timeout == null || timeout.isBlank()
                ? io.duo.sim.kernel.sut.SutLauncher.DEFAULT_READY_TIMEOUT_MS
                : io.duo.sim.kernel.util.Durations.parseMillis(timeout);
    }

    /** external 节点的兜底探针端口（首个非 0 expose 端口）。 */
    private static int firstExposedPort(Scenario.NodeSpec n) {
        return n.exposes().stream()
                .filter(e -> e.port() != null && e.port() > 0)
                .mapToInt(Scenario.ExposeSpec::port)
                .findFirst()
                .orElse(0);
    }

    /**
     * 命令行切分（M6 决策 D7）：按空白切分，支持单/双引号包裹的参数，并展开
     * {@code ${java}}（当前 JVM 的 java 可执行文件）与 {@code ${java.home}} 占位符——
     * 使场景 YAML 不写死本机 JDK 路径（跨机器/跨 CI 可复用）。
     */
    static List<String> splitCommand(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens.stream().map(ScenarioEngine::expandPlaceholders).toList();
    }

    private static String expandPlaceholders(String token) {
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String javaBin = javaHome + java.io.File.separator + "bin" + java.io.File.separator
                + (windows ? "java.exe" : "java");
        return token.replace("${java}", javaBin).replace("${java.home}", javaHome);
    }

    /** SUT 启动句柄（M6：in-process 与 external 两形态各持一侧，互斥）。 */
    public static final class SutLauncherHandle {
        final Scenario.NodeSpec spec;
        final io.duo.sim.kernel.sut.SutLauncher launcher;
        final io.duo.sim.kernel.sut.ExternalSutLauncher external;

        SutLauncherHandle(Scenario.NodeSpec spec, io.duo.sim.kernel.sut.SutLauncher launcher) {
            this(spec, launcher, null);
        }

        SutLauncherHandle(Scenario.NodeSpec spec, io.duo.sim.kernel.sut.SutLauncher launcher,
                          io.duo.sim.kernel.sut.ExternalSutLauncher external) {
            this.spec = spec;
            this.launcher = launcher;
            this.external = external;
        }

        /** in-process 启动器（external 形态为 null）。 */
        public io.duo.sim.kernel.sut.SutLauncher launcher() {
            return launcher;
        }

        /** external 启动器（in-process 形态为 null）。 */
        public io.duo.sim.kernel.sut.ExternalSutLauncher external() {
            return external;
        }

        public boolean isExternal() {
            return external != null;
        }
    }

    private static WiringResolver.NodeView toView(Scenario.NodeSpec n) {
        var slots = new LinkedHashMap<String, WiringResolver.SlotView>();
        n.wiring().forEach((slot, w) -> slots.put(slot,
                new WiringResolver.SlotView(w.node(),
                        w.contract() == null ? slot : w.contract(), w.path())));
        return new WiringResolver.NodeView(n.id(), n.contract(),
                WiringResolver.TierView.valueOf(n.tier().toUpperCase()), slots, isExternal(n));
    }

    /** behaviors → config 展平（供组件 BehaviorResolver 消费，M0 两级 match 已满足）。 */
    private void mergeBehaviors(Map<String, String> cfg, Scenario.NodeSpec n) {
        var beh = scenario.behaviors();
        for (var b : beh.bindings()) {
            if (!n.id().equals(b.node())) {
                continue;
            }
            var p = beh.profiles().get(b.profile());
            if (p == null) {
                continue;
            }
            String prefix;
            if ("default".equals(b.profile()) || (b.taskName() == null && b.label() == null)) {
                prefix = "behaviors.default.";
            } else if (b.label() != null) {
                prefix = "behaviors.by-label." + b.label() + ".";
            } else {
                prefix = "behaviors.named." + b.taskName() + ".";
            }
            p.forEach((k, v) -> cfg.put(prefix + k, v));
        }
    }

    private static Map<Contract, ExposedEndpoint> exposes(Scenario.NodeSpec n) {
        Map<Contract, ExposedEndpoint> out = new LinkedHashMap<>();
        for (var e : n.exposes()) {
            var c = Contract.fromYaml(e.contract());
            out.put(c, ExposedEndpoint.tcp(c,
                    e.addr() == null ? "127.0.0.1" : e.addr(),
                    e.port() == null ? 0 : e.port()));
        }
        return out;
    }

    /** 接线用的目标端点（exposes 首项）。port 0 的 in-process 节点在 start 后才有真实端口。 */
    private String endpointOf(Scenario.NodeSpec target) {
        var c = byId.get(target.id());
        if (c != null) {
            var eps = c.endpoints();
            if (!eps.isEmpty()) {
                return eps.get(0).address();
            }
        }
        var e = target.exposes().stream().findFirst().orElse(null);
        if (e == null) {
            return null;
        }
        return (e.addr() == null ? "127.0.0.1" : e.addr())
                + ":" + (e.port() == null ? 0 : e.port());
    }

    /** SUT 退出信号（M0 场景结束条件）。 */
    public void notifySutExit(boolean normal) {
        bus.publish(Event.sim(normal ? "sim.sut-exited" : "sim.sut-crashed", "sut", Map.of()));
        sutExit.countDown();
    }

    public boolean awaitSutExit(long timeoutMs) throws InterruptedException {
        return sutExit.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public List<Event> events() {
        return List.copyOf(recorded);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public ScenarioRuntime runtime() {
        return runtime;
    }

    /** 场景结果收集器（T16）：注入失败/断言评估（T20 写入）/警告；结束经 snapshot() 固化。 */
    public ScenarioResult result() {
        return result;
    }

    public Map<String, VirtualComponent> components() {
        return Map.copyOf(byId);
    }

    public SimpleEventBus bus() {
        return bus;
    }

    /** 热注入（T10）。 */
    public ScenarioRuntime.InjectionResult inject(FaultAction action) {
        return runtime.inject(action);
    }

    public void registerSutStopper(SutStopper s) {
        this.sutStopper = s;
    }

    /**
     * external SUT 启动器句柄（M6 决策 D7）：生命周期归用户，内核不杀进程，
     * 调用方经此拿到 {@code process()} 自行终止（attach 形态为 null）。
     */
    public io.duo.sim.kernel.sut.ExternalSutLauncher externalSut() {
        return externalSut;
    }

    public void stop() {
        if (!started) {
            return;
        }
        // 场景终止后不再注入：先关停时间线，取消尚未触发的排定动作（T16）
        if (timeline != null) {
            timeline.close();
        }
        if (sutStopper != null) {
            manager.registerExtraStop("sut", sutStopper::stopSut);
        }
        manager.stopAll();
        // external SUT 生命周期归用户（§7.3）：只拆接线，**不杀**进程——终态发事件 + 警告提示
        if (externalSut != null && externalSut.leftRunning()) {
            Long pid = externalSut.pid();
            String hint = "external SUT process (pid " + pid + ") is still running: lifecycle "
                    + "belongs to the user (§7.3) — terminate it manually";
            bus.publish(Event.sim("sim.external-process-left-running",
                    scenario.nodes().stream().filter(Scenario.NodeSpec::sut)
                            .findFirst().map(Scenario.NodeSpec::id).orElse("sut"),
                    Map.of("pid", pid == null ? -1L : pid)));
            warnings.add(hint);
            result.recordWarning(hint);
        }
        bus.publish(Event.sim("sim.scenario-finished", scenario.name(), Map.of()));
        started = false;
        if (recorder != null) {
            recorder.flush(); // T21：录制落盘（审查材料）
        }
        evaluateAssertions(); // T20：YAML 内置评估写入 ScenarioResult（场景结束判定）
    }

    /** 录制文件路径（场景启动后可用；供审查/比对，T21）。 */
    public java.nio.file.Path recordingPath() {
        return recorder == null ? null : recorder.outputFile();
    }

    /** YAML assertions 内置评估（T20）：逐条评估写入 result；解析失败即一级失败（不静默）。 */
    private void evaluateAssertions() {
        if (scenario.assertions() == null || scenario.assertions().isEmpty()) {
            return;
        }
        List<io.duo.sim.kernel.assertion.Assertion> assertions;
        try {
            assertions = io.duo.sim.kernel.assertion.AssertionParser
                    .parse(scenario.assertions());
        } catch (RuntimeException e) {
            result.recordAssertion("<parse>", false, e.getMessage());
            return;
        }
        List<Event> snapshot = List.copyOf(recorded);
        for (var a : assertions) {
            var outcome = a.evaluate(snapshot);
            result.recordAssertion(outcome.name(), outcome.passed(), outcome.detail());
        }
    }

    @Override
    public void close() {
        stop();
    }
}
