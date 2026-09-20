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
    /**
     * 内存事件流（多线程写入：SUT sink / 时间线线程 / 门面 watch）→ 必须线程安全。
     *
     * <p><b>有界 + O(1) 追加</b>（安全审计 2026-09-20 复核 H-5）：早先用
     * {@code CopyOnWriteArrayList} 记录，每次 {@code add} 都整表复制 ⇒ 单场景 O(n²)，
     * 万级 worker 的百万级事件会把控制面拖垮；且**无上限**。现改为默认同步的
     * {@link ArrayList} 加显式锁（追加 O(1)、读路径 O(1) 取下标），并复用
     * {@link EventRecorder#MAX_BUFFERED_EVENTS} 作为同一道内存闸门——事件流本就要进
     * 有界录制缓冲，内存流再单独放一个更大的上限只会让"谁先 OOM"不可预期。
     * 超出后停止累积并计数，经 {@link #droppedEvents()} / 告警显式上报（§12 不静默）。
     *
     * <p>两道门的宽度一致 ⇒ 同一份事件在两个接收端**要么都留、要么都丢**，
     * 不会出现"录制说没有、内存说有"的分叉。
     */
    private final List<Event> recorded = new ArrayList<>();
    private final Object recordedLock = new Object();
    private long recordedDropped;
    private final ComponentManager manager = new ComponentManager();
    /** 注入事件经 bus 汇流（与内存流/录制共用单一订阅路径，T21）。 */
    private final ScenarioRuntime runtime = new ScenarioRuntime(bus::publish);
    private final ScenarioResult result = ScenarioResult.create();
    private final Map<String, VirtualComponent> byId = new ConcurrentHashMap<>();
    /** 启动期节点索引（T31(a) 端点解析用；{@link #startSut()} 时建立）。 */
    private Map<String, Scenario.NodeSpec> specById = Map.of();
    private final List<String> warnings = new ArrayList<>();
    private final CountDownLatch sutExit = new CountDownLatch(1);
    private volatile boolean started;
    private volatile SutStopper sutStopper;
    /** external SUT 启动器（M6；in-process 形态为 null）——生命周期归用户，故公开句柄。 */
    private volatile io.duo.sim.kernel.sut.ExternalSutLauncher externalSut;
    private volatile TimelineScheduler timeline;
    /** custom-hook 注册表（M5/G5）：可用 {@link #withHooks} 注入，默认空表。 */
    private HookRegistry hookRegistry = new HookRegistry();
    /** 输入信任档（默认＝本机文件/classpath 配置）。 */
    private InputPolicy inputPolicy = InputPolicy.CONFIG;
    private final EventRecorder recorder;
    /**
     * 录制世代号（控住 {@code build/scenarios/<name>/events.jsonl} 的覆盖权限）。
     *
     * <p>同名场景重跑时，新旧引擎写**同一个文件**；控制面顶替旧引擎是异步收摊的（H-4），
     * 若旧引擎的 flush 落在新的之后，就会把新录制整份覆盖成旧的。故引擎保留
     * {@link java.util.function.LongSupplier}（由宿主提供"当前活动世代"），写入前比对，
     * 过期即放弃落盘并告警。默认供应器恒返回 {@link #epoch} ⇒ 单引擎场景行为不变。
     */
    private java.util.function.LongSupplier epochGuard = this::currentEpoch;
    private final long epoch = EPOCHS.incrementAndGet();
    private static final java.util.concurrent.atomic.AtomicLong EPOCHS =
            new java.util.concurrent.atomic.AtomicLong();

    private long currentEpoch() {
        return epoch;
    }

    public ScenarioEngine(Scenario scenario, ContractRegistry registry) {
        this.scenario = scenario;
        this.registry = registry;
        // 录制与内存流同一订阅点（构造即订阅）：保证两条流事件数一致（T21）
        this.recorder = EventRecorder.to(java.nio.file.Path.of("build", "scenarios",
                scenario.name(), "events.jsonl"));
        bus.subscribe(this::recordEvent);
        bus.subscribe(recorder::onEvent);
    }

    /** 本引擎的录制世代号（宿主用它做"现任/历史"判定）。 */
    public long epoch() {
        return epoch;
    }

    /**
     * 绑定宿主的活动世代供应器（控制面用）：返回的号 ≠ {@link #epoch()} 时，本引擎已不是
     * 现任场景，不再拥有覆盖录制文件的权限。
     */
    public ScenarioEngine withEpochGuard(java.util.function.LongSupplier activeEpoch) {
        this.epochGuard = java.util.Objects.requireNonNull(activeEpoch, "activeEpoch");
        return this;
    }

    /**
     * 内存事件流追加（唯一写入口）。达到 {@link EventRecorder#MAX_BUFFERED_EVENTS} 后停止累积
     * 并计数——静默丢事件会让 {@code /events} 与断言评估看到的事件流"缺一块却不说话"。
     */
    private void recordEvent(Event e) {
        synchronized (recordedLock) {
            if (recorded.size() >= EventRecorder.MAX_BUFFERED_EVENTS) {
                recordedDropped++;
                return;
            }
            recorded.add(e);
        }
    }

    /** 因内存流上限而被丢弃的事件数（0 ＝ 未溢出）。 */
    public long droppedEvents() {
        synchronized (recordedLock) {
            return recordedDropped;
        }
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

    /** 当前输入信任档。 */
    public InputPolicy inputPolicy() {
        return inputPolicy;
    }

    /** 加载即校验（§8 快速失败：errors 非空抛 IllegalArgumentException）。 */
    public static ScenarioEngine validated(Scenario scenario, ContractRegistry registry) {
        return validated(scenario, registry, InputPolicy.CONFIG);
    }

    /**
     * 输入来源的信任档（安全审计 2026-09-20）。
     *
     * <p>{@link #CONFIG}＝场景 YAML 来自本机文件/classpath：与「启动一个进程」同级，
     * 校验按**配置**对待，能力不缩水；{@link #EXTERNAL_INPUT} 由控制面把外部输入显式降级：
     * 禁止派生外部进程、config 键必须白名单、拓扑有界。
     */
    public enum InputPolicy { CONFIG, EXTERNAL_INPUT }

    /** 校验 + 绑定输入信任档（EXTERNAL_INPUT 时按不可信输入收窄能力）。 */
    public static ScenarioEngine validated(Scenario scenario, ContractRegistry registry,
                                           InputPolicy policy) {
        var report = new ScenarioValidator(registry,
                policy == InputPolicy.EXTERNAL_INPUT
                        ? ScenarioValidator.InputTrust.EXTERNAL
                        : ScenarioValidator.InputTrust.CONFIG)
                .validate(scenario);
        if (!report.ok()) {
            throw new IllegalArgumentException("scenario validation failed: "
                    + String.join("; ", report.errors()));
        }
        ScenarioEngine e = new ScenarioEngine(scenario, registry);
        e.inputPolicy = policy;
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
            if (n.sut() || isExternal(n) || !startable(n) || byId.containsKey(id)
                    || !n.autoStart()) {
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
        this.specById = specById;
        startDirectDependencies(spec, specById);
        Map<String, String> sutEndpoints = sutEndpoints(spec, specById);

        if (isExternal(spec)) {
            return startExternalSut(spec, sutEndpoints);
        }
        if (spec.launch() == null || !"in-process".equals(spec.launch().mode())) {
            throw new IllegalStateException("SUT launch.mode must be in-process (with main) "
                    + "or external (with configOut): " + spec.id());
        }
        assertMainAllowed(spec.launch().main());
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
            warnIfSutMissedEndpoints(spec, sutEndpoints); // ready 已确认：SUT 看到的就是这张表
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
     * 登记 SUT 可见的对端端点，并在**拿到 SUT 的登记结果**时如实告警：
     * 对端声明了 {@code exposes}、端点也真的绑上了，但 SUT 拿到的是空表或没含这个契约
     * —— 这说明"SUT 先起、对端后绑"的启动序吃掉了这条发现路径（§12 不静默）。
     *
     * <p>为什么告警放在这里：{@link io.duo.sim.kernel.sut.SutLauncher#start()} 会阻塞到
     * {@code ctx.ready()} 之后，SUT 的端点表**此刻已经写定**，读到的就是它真正看到的东西；
     * 等 {@code startComponents()} 之后再检查，只会得到"后来者可见"这种与 SUT 无关的结论。
     *
     * @param sut spec {@link Scenario.NodeSpec#exposes()}：SUT 侧（通常为空）
     * @param sutEndpoints 交给 SUT 的端点表
     */
    private void warnIfSutMissedEndpoints(Scenario.NodeSpec sut, Map<String, String> sutEndpoints) {
        for (var node : scenario.nodes()) {
            if (node.sut() || node.exposes().isEmpty() || node.id().equals(sut.id())) {
                continue;
            }
            for (var expose : node.exposes()) {
                String contract = expose.contract();
                if (contract != null && !sutEndpoints.containsKey(contract.toLowerCase())) {
                    warnings.add("node " + node.id() + " exposes '" + contract + "' but the SUT '"
                            + sut.id() + "' was already started when the endpoint was bound,"
                            + " so SutContext.endpointByContract() cannot carry it"
                            + " (wiring is resolved at SUT start; use it, or let the peer register"
                            + " itself in a registry the SUT watches)");
                }
            }
        }
    }

    /**
     * SUT 端点清单（T31(a)）：解析 SUT **能看到哪些端点**，两条来源，先到先得（同契约不覆盖）：
     * <ol>
     *   <li>自己 wiring 槽指向的目标节点（既有语义：wire 槽 → 目标 endpoints() 地址，
     *       如 embedded registry 的 ZK 端口）；</li>
     *   <li>自己 {@code exposes} 声明、且对应契约节点**已启动**时的实际端点。</li>
     * </ol>
     *
     * <p>注意此处**不可能**包含"SUT 启动之后才对端绑好的端点"：{@code SutLauncher} 在
     * {@code start()} 里就把本表写进 {@code SutContext} 并落成端点配置文件，此后不再变。
     * 「SUT 先起、对端后起」的关系必须由**其它通道**闭合——对端把自己注册进 registry，
     * SUT 侧轮询发现（如 {@code VirtualScheduler} 的约定名 {@code scheduler}）。
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
                out.putIfAbsent(contract.toLowerCase(), eps.get(0).address());
            }
        }
        addExposedEndpoints(out, spec);
        return out;
    }

    /** 把 {@code spec} 自己 exposes 的、对端已启动的端点并入 {@code out}（同契约不覆盖）。 */
    private void addExposedEndpoints(Map<String, String> out, Scenario.NodeSpec spec) {
        // exposes 是"本节点对外暴露什么"，不是"本节点要连谁"：SUT 声明 exposes 时，
        // 按契约找**当前已启动**的同类节点取端点；没有则留空（由调用方如实告警）。
        for (var expose : spec.exposes()) {
            String contract = expose.contract() == null ? null : expose.contract();
            if (contract == null) {
                continue;
            }
            for (var target : specById.values()) {
                if (target.sut()) {
                    continue;
                }
                VirtualComponent c = byId.get(target.id());
                if (c == null || c.endpoints().isEmpty()) {
                    continue;
                }
                if (target.contract().equalsIgnoreCase(contract)) {
                    out.putIfAbsent(contract.toLowerCase(), c.endpoints().get(0).address());
                    break;
                }
            }
        }
    }

    /**
     * external SUT 启动（M6）：写端点配置文件 → 可选代起子进程 → ready 探针。
     * 生命周期归用户（§7.3）：场景结束不杀进程，句柄经 {@link #externalSut()} 暴露。
     */
    private SutLauncherHandle startExternalSut(Scenario.NodeSpec spec,
                                               Map<String, String> sutEndpoints) {
        // 纵深防御（安全审计 C-1）：外部输入档下连「解析命令行」都不做——校验器已拒绝该形态，
        // 这里再挡一道，避免将来有人绕过 validated(...) 直接 new ScenarioEngine(...) 时失守。
        if (inputPolicy == InputPolicy.EXTERNAL_INPUT) {
            throw new IllegalArgumentException("external SUT process launch is not allowed for "
                    + "external input (launch.command / launch.allowExternalProcess): " + spec.id());
        }
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

    /**
     * external 节点的兜底探针端口（首个非 0 expose 端口）。
     */
    /**
     * 任意类加载守卫（安全审计 H-3）：外部输入档只允许加载框架自身命名空间下的 SUT 主类。
     * 本机配置档不受限（与 {@code java -cp ... Main} 同级信任）。
     */
    private void assertMainAllowed(String main) {
        if (main == null || inputPolicy != InputPolicy.EXTERNAL_INPUT) {
            return;
        }
        if (ScenarioValidator.isTrustedMainClass(main)) {
            return;
        }
        throw new IllegalArgumentException("sut main class '" + main + "' is outside the framework "
                + "namespace; class loading from external input is restricted to io.duo.sim.* / "
                + "com.duo.* (run it from a local scenario file or CLI instead)");
    }

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
        synchronized (recordedLock) {
            return List.copyOf(recorded);
        }
    }

    /** 事件数（免整表拷贝的自省入口；{@code /status} 的 {@code events} 用它）。 */
    public int eventCount() {
        synchronized (recordedLock) {
            return recorded.size();
        }
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
        long memoryDropped = droppedEvents();
        if (memoryDropped > 0) {
            // 内存流与录制缓冲同一道闸门：这里溢出说明事件流被截断，必须说出来（§12）
            String hint = "in-memory event stream limit reached: " + memoryDropped
                    + " events dropped (same limit as " + EventRecorder.MAX_BUFFERED_EVENTS
                    + "; /events and assertion review see a truncated stream)";
            warnings.add(hint);
            result.recordWarning(hint);
        }
        if (recorder != null) {
            // 录制的覆盖权限归"现任"引擎：过期引擎放弃写盘，避免抹掉新场景的录制（H-4 复核）
            if (!recorder.flushIfCurrent(epochGuard, epoch)) {
                String hint = "event recording not written: this scenario was superseded by a "
                        + "newer one running with the same name (build/scenarios/"
                        + scenario.name() + "/events.jsonl belongs to the current run)";
                warnings.add(hint);
                result.recordWarning(hint);
            }
            if (recorder.droppedEvents() > 0) {
                // 有界缓冲的可见代价（审计 H-4 / §12）：丢了多少必须说出来，不静默
                String hint = "event recording buffer limit reached: "
                        + recorder.droppedEvents() + " events dropped from build/scenarios/"
                        + scenario.name() + "/events.jsonl";
                warnings.add(hint);
                result.recordWarning(hint);
            }
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
        List<Event> snapshot = events();
        for (var a : assertions) {
            var outcome = a.evaluate(snapshot);
            result.recordAssertion(outcome.name(), outcome.passed(), outcome.detail());
        }
    }

    /**
     * 关闭引擎。契约：{@link #stop()} 幂等，{@link #close()} 亦幂等——控制面在场景启动前失败
     * 或宿主被关闭时会重复调用（安全审计 2026-09-20 复核 H-4：旧引擎必须能安全收摊）。
     */
    @Override
    public void close() {
        stop();
    }
}
