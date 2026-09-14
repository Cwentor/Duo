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
    private volatile TimelineScheduler timeline;
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
            timeline = new TimelineScheduler(scenario.timeline(), runtime, result);
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
     * 启动 in-process SUT（T12 接入）：反射实例化 {@code launch.main} 的 SutMain，
     * directBindings＝已启动组件（nodeId→实例），ready 后登记停止器；
     * {@code sut.exited/sut.crashed} 事件自动触发场景结束信号。
     *
     * <p>调用时机：SUT 须在内核组件**之前**启动（其 Duo 端点要写进 registry，
     * workers 的发现等待语义依赖它；计划风险 5）。
     */
    public SutLauncherHandle startSut() {
        var spec = scenario.nodes().stream().filter(Scenario.NodeSpec::sut)
                .findFirst().orElseThrow(() -> new IllegalStateException("no SUT node"));
        if (spec.launch() == null || !"in-process".equals(spec.launch().mode())) {
            throw new IllegalStateException("M0 engine only supports in-process SUT launch");
        }
        // SUT 的 direct 依赖须先就绪（如 registry）：实例化并启动（纳入管理器拆除序列）
        Map<String, WiringResolver.NodeView> views = new LinkedHashMap<>();
        scenario.nodes().forEach(n -> views.put(n.id(), toView(n)));
        Map<String, Scenario.NodeSpec> specById = new LinkedHashMap<>();
        scenario.nodes().forEach(n -> specById.put(n.id(), n));
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
        try {
            var cls = Class.forName(spec.launch().main());
            var main = (io.duo.sim.kernel.api.SutMain)
                    cls.getDeclaredConstructor().newInstance();
            // T31(a)：SUT 的 wire 端点注入——按 SUT 的 wiring 槽解析目标节点的实际端点
            // （wire 槽 → 目标 endpoints() 地址；如 embedded registry 的 ZK 端口）。
            // 这正是 D1 的前提：SUT 经 SutContext.endpointByContract() 取 ZK 地址。
            Map<String, String> sutEndpoints = new LinkedHashMap<>();
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
                    sutEndpoints.put(contract.toLowerCase(), eps.get(0).address());
                }
            }
            // SUT 退出事件 → 场景结束信号（M0：不新增 DSL 字段）
            var launcher = new io.duo.sim.kernel.sut.SutLauncher(
                    spec.id(), main, Map.copyOf(byId), spec.config(), sutEndpoints,
                    e -> {
                        // 统一经 bus 汇流：内存流与录制共用单一订阅路径（T21）
                        bus.publish(e);
                        if (e.type().equals("sut.exited")) {
                            notifySutExit(true);
                        } else if (e.type().equals("sut.crashed")) {
                            notifySutExit(false);
                        }
                    },
                    java.nio.file.Path.of("build", "duo-sut-" + spec.id() + ".properties"));
            launcher.start();
            registerSutStopper(launcher::stop);
            return new SutLauncherHandle(spec, launcher);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot launch SUT main: "
                    + spec.launch().main(), e);
        }
    }

    /** SUT 启动句柄。 */
    public static final class SutLauncherHandle {
        final Scenario.NodeSpec spec;
        final io.duo.sim.kernel.sut.SutLauncher launcher;

        SutLauncherHandle(Scenario.NodeSpec spec, io.duo.sim.kernel.sut.SutLauncher launcher) {
            this.spec = spec;
            this.launcher = launcher;
        }

        public io.duo.sim.kernel.sut.SutLauncher launcher() {
            return launcher;
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
