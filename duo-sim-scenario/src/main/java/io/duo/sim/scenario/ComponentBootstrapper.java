package io.duo.sim.scenario;

import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
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
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 拓扑装配与启动（{@link ScenarioEngine} 的实现侧）：把场景 YAML 变成活组件。
 * SUT 双形态启动（in-process / external）与内核托管组件的拓扑序启动**共用同一份装配核心**
 * （provider 解析 → 实例化 → config/capacity/count 合并 → behaviors 展平 → exposes → init）。
 *
 * <p>启动语义的关键不变量（改这里必须逐条守住）：
 * <ul>
 *   <li>SUT 先于内核组件启动：in-process 的 Duo 端点要写进 registry（worker 的发现语义依赖它），
 *       external 的 ready 探针同理；</li>
 *   <li>SUT 的直接依赖在此先起并 adopt 进统一拆除序列——{@link #startTopology()} 必须跳过
 *       它们，否则二次 start 会重建真实服务（CuratorRegistry 二次 start 重建 TestingServer ⇒
 *       已注册节点全丢，M2 修复的真实缺陷）；</li>
 *   <li>接线元数据按 (contract, tier) 小写索引**一次解析 O(1)** 查表——替代对 specById 的
 *       逐槽线性扫描（原实现的 O(n²) 热点）；</li>
 *   <li>SUT 直接依赖的 config **不展平 behaviors**、接线表为空（与组件路径的真实差异，
 *       保持原语义，不是遗漏）。</li>
 * </ul>
 */
final class ComponentBootstrapper {

    private final Scenario scenario;
    private final ContractRegistry registry;
    private final SimpleEventBus bus;
    private final ScenarioRuntime runtime;
    private final ComponentManager manager;
    /** 启动期组件索引（engine 与本类共用；含 SUT 直接依赖与内核托管组件）。 */
    private final Map<String, VirtualComponent> byId;
    private final List<String> warnings;
    /** SUT 生命周期事实回流（引擎侧再汇流进总线并触发场景结束信号）。 */
    private final Consumer<Event> sutEvents;
    /** SUT 协作停止器登记（引擎持有并注入 stop() 序列）。 */
    private final Consumer<ScenarioEngine.SutStopper> stopperSink;

    ComponentBootstrapper(Scenario scenario, ContractRegistry registry, SimpleEventBus bus,
                          ScenarioRuntime runtime, ComponentManager manager,
                          Map<String, VirtualComponent> byId, List<String> warnings,
                          Consumer<Event> sutEvents,
                          Consumer<ScenarioEngine.SutStopper> stopperSink) {
        this.scenario = scenario;
        this.registry = registry;
        this.bus = bus;
        this.runtime = runtime;
        this.manager = manager;
        this.byId = byId;
        this.warnings = warnings;
        this.sutEvents = sutEvents;
        this.stopperSink = stopperSink;
    }

    // ---- SUT 启动（in-process / external 两形态）----

    /**
     * 启动 SUT（M6）。调用时机：SUT 须在内核组件**之前**启动（见类注释第 1 条不变量）。
     * 两形态共用：① SUT 的依赖先起（纳入 manager 拆除序列）；② 按 wiring 槽把目标节点
     * **实际**端点解析为 SUT 端点清单（external 形态写入端点配置文件，§7.3 主途径）。
     */
    ScenarioEngine.SutLauncherHandle startSut(InputTrust inputTrust) {
        var spec = scenario.nodes().stream().filter(Scenario.NodeSpec::sut)
                .findFirst().orElseThrow(() -> new IllegalStateException("no SUT node"));
        Map<String, Scenario.NodeSpec> specById = indexSpecs();
        // A1（评审 2026-10-30）：依赖先起并 adopt，此后任一步失败都必须回滚——
        // 否则 engine.started 恒为 false 令 stop() 短路，这些组件在长驻 serve 进程里
        // 永不收摊（泄漏端口/连接，重试亦不可回收）。
        List<String> startedDeps = new ArrayList<>();
        try {
            startDirectDependencies(spec, specById, startedDeps);
            Map<String, String> sutEndpoints = sutEndpoints(spec, specById);

            if (NodeSpecs.isExternal(spec)) {
                return startExternalSut(spec, sutEndpoints, inputTrust);
            }
            if (spec.launch() == null || !"in-process".equals(spec.launch().mode())) {
                throw new IllegalStateException("SUT launch.mode must be in-process (with main) "
                        + "or external (with configOut): " + spec.id());
            }
            assertMainAllowed(spec.launch().main(), inputTrust);
            try {
                var cls = Class.forName(spec.launch().main());
                var main = (io.duo.sim.kernel.api.SutMain)
                        cls.getDeclaredConstructor().newInstance();
                var launcher = new io.duo.sim.kernel.sut.SutLauncher(
                        spec.id(), main, Map.copyOf(byId), spec.config(), sutEndpoints,
                        this::relaySutEvent,
                        java.nio.file.Path.of("build", "duo-sut-" + spec.id() + ".properties"),
                        readyTimeoutMs(spec.config()));
                launcher.start();
                warnIfSutMissedEndpoints(spec, sutEndpoints); // ready 已确认：SUT 看到的就是这张表
                stopperSink.accept(launcher::stop);
                return new ScenarioEngine.SutLauncherHandle(spec, launcher);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot launch SUT main: "
                        + spec.launch().main(), e);
            }
        } catch (RuntimeException e) {
            rollbackDirectDependencies(spec, startedDeps);
            throw e;
        }
    }

    /** SUT 事件回流（内存流与录制共用单一订阅路径，T21；生命周期事实在引擎侧触发结束信号）。 */
    private void relaySutEvent(Event e) {
        sutEvents.accept(e);
    }

    /** SUT 的依赖先起：实例化 + start + adopt（纳入统一拆除序列），供两形态共用。
     *  本次调用实际启动的 id 追加进 {@code startedDeps}（失败回滚的消费方，A1）。 */
    private void startDirectDependencies(Scenario.NodeSpec spec,
                                         Map<String, Scenario.NodeSpec> specById,
                                         List<String> startedDeps) {
        for (var slot : spec.wiring().values()) {
            String targetId = slot.node();
            if (byId.containsKey(targetId)) {
                continue;
            }
            var depSpec = specById.get(targetId);
            VirtualComponent dep = instantiate(targetId, depSpec, false,
                    null, specById, null);
            dep.start();
            byId.put(targetId, dep);
            manager.adopt(targetId, dep);
            startedDeps.add(targetId);
            runtime.registerTarget(targetId, new ScenarioRuntime.Target(
                    dep, targetId, depSpec.count() == null ? 1 : depSpec.count(),
                    metaOf(depSpec).supportedFaults()));
        }
    }

    /**
     * SUT 启动失败的回滚（评审 A1，2026-10-30）：与 {@link ComponentManager#startAll}
     * 的"失败逆序拆除"同语义——逆序停止本次启动的依赖、摘出统一拆除序列，并发一级事件。
     * 个别组件停止失败不吞（记 warnings）；{@code runtime} 里已登记的 Target 不摘除：
     * 注入三查要求 RUNNING 态，失败场景不可达，随旧引擎一并废弃。
     */
    private void rollbackDirectDependencies(Scenario.NodeSpec spec, List<String> startedIds) {
        if (startedIds.isEmpty()) {
            return;
        }
        for (int i = startedIds.size() - 1; i >= 0; i--) {
            String id = startedIds.get(i);
            byId.remove(id);
            try {
                manager.abandon(id);
            } catch (RuntimeException stopFailure) {
                warnings.add("SUT start rollback: component '" + id + "' stop failed: "
                        + stopFailure.getMessage());
            }
        }
        bus.publish(Event.sim("sim.sut-start-rollback", spec.id(),
                Map.of("rolledBack", String.join(",", startedIds))));
    }

    /** 节点的能力元数据（registry 查表；注册 Target 供运行期三查第②查消费）。 */
    private CapabilityMetadata metaOf(Scenario.NodeSpec n) {
        return registry.resolve(Contract.fromYaml(n.contract()),
                Tier.fromYaml(n.tier()), n.impl()).metadata();
    }

    /**
     * external SUT 启动（M6）：写端点配置文件 → 可选代起子进程 → ready 探针。
     * 生命周期归用户（§7.3）：场景结束不杀进程，句柄随 SutLauncherHandle 暴露。
     */
    private ScenarioEngine.SutLauncherHandle startExternalSut(Scenario.NodeSpec spec,
                                                              Map<String, String> sutEndpoints,
                                                              InputTrust inputTrust) {
        // 纵深防御（安全审计 C-1）：外部输入档下连「解析命令行」都不做——校验器已拒绝该形态，
        // 这里再挡一道，避免将来有人绕过 validated(...) 直接 new ScenarioEngine(...) 时失守。
        if (inputTrust == InputTrust.EXTERNAL_INPUT) {
            throw new IllegalArgumentException("external SUT process launch is not allowed for "
                    + "external input (launch.command / launch.allowExternalProcess): " + spec.id());
        }
        var launcher = new io.duo.sim.kernel.sut.ExternalSutLauncher(
                spec.id(), splitCommand(spec.launch().command()), spec.config(), sutEndpoints,
                this::relaySutEvent,
                spec.launch().configOut() == null
                        ? null : java.nio.file.Path.of(spec.launch().configOut()),
                NodeSpecs.firstExposedPort(spec));
        launcher.start();
        return new ScenarioEngine.SutLauncherHandle(spec, null, launcher);
    }

    /**
     * 任意类加载守卫（安全审计 H-3）：外部输入档只允许加载框架自身命名空间下的 SUT 主类。
     * 本机配置档不受限（与 {@code java -cp ... Main} 同级信任）。
     */
    private static void assertMainAllowed(String main, InputTrust inputTrust) {
        if (main == null || inputTrust != InputTrust.EXTERNAL_INPUT) {
            return;
        }
        if (ScenarioValidator.isTrustedMainClass(main)) {
            return;
        }
        throw new IllegalArgumentException("sut main class '" + main + "' is outside the framework "
                + "namespace; class loading from external input is restricted to io.duo.sim.* / "
                + "com.duo.* (run it from a local scenario file or CLI instead)");
    }

    /**
     * SUT 端点清单（T31(a)）：解析 SUT **能看到哪些端点**，两条来源，先到先得（同契约不覆盖）：
     * <ol>
     *   <li>自己 wiring 槽指向的目标节点（既有语义：wire 槽 → 目标 endpoints() 地址，
     *       如 embedded registry 的 ZK 端口）；</li>
     *   <li>自己 {@code exposes} 声明、且对应契约节点**已启动**时的实际端点。</li>
     * </ol>
     *
     * <p>注意此处**不可能**包含「SUT 启动之后才对端绑好的端点」：{@code SutLauncher} 在
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
        addExposedEndpoints(out, spec, specById);
        return out;
    }

    /** 把 {@code spec} 自己 exposes 的、对端已启动的端点并入 {@code out}（同契约不覆盖）。 */
    private void addExposedEndpoints(Map<String, String> out, Scenario.NodeSpec spec,
                                      Map<String, Scenario.NodeSpec> specById) {
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
     * 登记 SUT 可见的对端端点，并在**拿到 SUT 的登记结果**时如实告警：
     * 对端声明了 {@code exposes}、端点也真的绑上了，但 SUT 拿到的是空表或没含这个契约
     * —— 这说明"SUT 先起、对端后绑"的启动序吃掉了这条发现路径（§12 不静默）。
     *
     * <p>为什么告警放在这里：{@code SutLauncher#start()} 会阻塞到 {@code ctx.ready()} 之后，
     * SUT 的端点表**此刻已经写定**，读到的就是它真正看到的东西；等 {@link #startTopology()}
     * 之后再检查，只会得到"后来者可见"这种与 SUT 无关的结论。
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

    // ---- 内核托管组件的拓扑启动 ----

    /** 启动全部内核托管组件（SUT 与 external 节点不在此列；SUT 依赖已 adopt 的跳过）。 */
    void startTopology() {
        registry.validateDefaults();
        Map<String, Scenario.NodeSpec> specById = indexSpecs();
        Map<String, WiringResolver.NodeView> views = new LinkedHashMap<>();
        specById.values().forEach(n -> views.put(n.id(), toView(n)));
        Map<String, CapabilityMetadata> metadataIndex = metadataIndex(specById);

        List<String> order = WiringResolver.topoOrder(new ArrayList<>(views.values()));
        for (String id : order) {
            var n = specById.get(id);
            if (n.sut() || NodeSpecs.isExternal(n) || !startable(n) || byId.containsKey(id)
                    || !n.autoStart()) {
                continue; // byId 已含（startSut 预启动的依赖）则跳过
            }
            VirtualComponent c = instantiate(id, n, true, views, specById, metadataIndex);
            byId.put(id, c);
            runtime.registerTarget(id, new ScenarioRuntime.Target(
                    c, id, n.count() == null ? 1 : n.count(),
                    metadataIndex.get(metadataKey(n.contract(), n.tier())).supportedFaults()));
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
    }

    // ---- 装配核心（两条启动路径共用）----

    /**
     * 实例化并 init 一个节点。
     *
     * @param withWiringAndBehaviors 组件路径＝true（接线解析 + behaviors 展平）；
     *                               SUT 直接依赖＝false（无接线、不展平——保持既有语义）
     */
    private VirtualComponent instantiate(String id, Scenario.NodeSpec n,
                                         boolean withWiringAndBehaviors,
                                         Map<String, WiringResolver.NodeView> views,
                                         Map<String, Scenario.NodeSpec> specById,
                                         Map<String, CapabilityMetadata> metadataIndex) {
        var provider = registry.resolve(Contract.fromYaml(n.contract()),
                Tier.fromYaml(n.tier()), n.impl());
        VirtualComponent c = provider.newComponent();
        Map<String, String> cfg = new LinkedHashMap<>(n.config());
        cfg.putAll(n.capacity());
        if (n.count() != null) {
            cfg.put("count", String.valueOf(n.count()));
        }
        if (withWiringAndBehaviors) {
            mergeBehaviors(cfg, n, scenario);
            var wiring = WiringResolver.resolveNode(views.get(id), views,
                    (contract, tier) -> metadataIndex.get(metadataKey(contract, tier)),
                    (slot, targetId) -> byId.get(targetId),
                    (slot, targetId) -> endpointOf(specById.get(targetId)));
            c.init(new ComponentContext(new ComponentId(id), cfg, SimClock.real(), bus,
                    wiring, exposes(n)));
        } else {
            c.init(new ComponentContext(new ComponentId(id), cfg, SimClock.real(), bus,
                    Map.of(), exposes(n)));
        }
        return c;
    }

    /** 场景节点索引（id → spec；保持声明序）。 */
    private Map<String, Scenario.NodeSpec> indexSpecs() {
        Map<String, Scenario.NodeSpec> specById = new LinkedHashMap<>();
        scenario.nodes().forEach(n -> specById.put(n.id(), n));
        return specById;
    }

    /** (contract, tier)（小写）→ 能力元数据，一次解析 O(1) 查表（替代逐槽线性扫描）。 */
    private Map<String, CapabilityMetadata> metadataIndex(Map<String, Scenario.NodeSpec> specById) {
        Map<String, CapabilityMetadata> out = new LinkedHashMap<>();
        for (var n : specById.values()) {
            String key = metadataKey(n.contract(), n.tier());
            out.putIfAbsent(key, registry.resolve(Contract.fromYaml(n.contract()),
                    Tier.fromYaml(n.tier()), n.impl()).metadata());
        }
        return out;
    }

    private static String metadataKey(String contract, String tier) {
        return contract.toLowerCase(Locale.ROOT) + "\u0000" + tier.toLowerCase(Locale.ROOT);
    }

    /** 内核是否应启动该节点（in-process/real kernel-hosted 均为 VirtualComponent 路径）。 */
    private static boolean startable(Scenario.NodeSpec n) {
        return "in-process".equals(NodeSpecs.launchMode(n));
    }

    /** behaviors → config 展平（供组件 BehaviorResolver 消费，M0 两级 match 已满足）。 */
    static void mergeBehaviors(Map<String, String> cfg, Scenario.NodeSpec n, Scenario scenario) {
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

    private static WiringResolver.NodeView toView(Scenario.NodeSpec n) {
        var slots = new LinkedHashMap<String, WiringResolver.SlotView>();
        n.wiring().forEach((slot, w) -> slots.put(slot,
                new WiringResolver.SlotView(w.node(),
                        w.contract() == null ? slot : w.contract(), w.path())));
        return new WiringResolver.NodeView(n.id(), n.contract(),
                WiringResolver.TierView.valueOf(n.tier().toUpperCase()), slots,
                NodeSpecs.isExternal(n));
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

    /** {@code ready.timeout} 全链路消费（M6 交付物 2）：in-process 与 external 同口径。 */
    private static long readyTimeoutMs(Map<String, String> config) {
        String timeout = config.get("ready.timeout");
        return timeout == null || timeout.isBlank()
                ? io.duo.sim.kernel.sut.SutLauncher.DEFAULT_READY_TIMEOUT_MS
                : io.duo.sim.kernel.util.Durations.parseMillis(timeout);
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
        return tokens.stream().map(ComponentBootstrapper::expandPlaceholders).toList();
    }

    private static String expandPlaceholders(String token) {
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String javaBin = javaHome + java.io.File.separator + "bin" + java.io.File.separator
                + (windows ? "java.exe" : "java");
        return token.replace("${java}", javaBin).replace("${java.home}", javaHome);
    }
}
