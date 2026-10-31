package io.duo.sim.scenario;

import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.core.ComponentManager;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.kernel.core.ScenarioRuntime;
import io.duo.sim.kernel.core.SimpleEventBus;
import io.duo.sim.scenario.model.Scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 场景最小执行（计划 T11）：校验 → SUT 启动 → 拓扑启动 → 时间线 → 统一收摊。
 *
 * <p>M0 结束条件＝SUT 正常退出（{@link #notifySutExit(boolean)}）或显式 {@link #stop()}；
 * timeline 自 M1 T16 起由 {@link TimelineScheduler} 自动执行（以启动为 t0，duration 到期自动
 * clear），注入失败/警告计入 {@link #result()}；热注入走 {@link #inject(FaultAction)}。
 * 不新增 DSL 字段（计划 §2/T11）。
 *
 * <p><b>实现分三个内部模块</b>（各自的接缝上有单测，编排本体不再是一整块）：
 * <ul>
 *   <li>{@link EventJournal}——内存事件流 + JSONL 录制 + 世代闸门（{@code events()} 快照
 *       与收尾告警的归属地）；</li>
 *   <li>{@link ComponentBootstrapper}——SUT 双形态启动与拓扑序启动共用的装配核心
 *       （provider 解析 / config 合并 / behaviors 展平 / 接线解析 / 端点表）；</li>
 *   <li>本类——编排顺序与对外接口面：validated → startSut → startComponents → stop。</li>
 * </ul>
 */
public final class ScenarioEngine implements AutoCloseable {

    /** SUT 停止回调（T12 接入协作停止；未接入时 M0 场景结束直接跳过）。 */
    public interface SutStopper {
        void stopSut();
    }

    private final Scenario scenario;
    private final ContractRegistry registry;
    private final SimpleEventBus bus = new SimpleEventBus();
    private final EventJournal journal;
    private final ComponentManager manager = new ComponentManager();
    /** 注入事件经 bus 汇流（与内存流/录制共用单一订阅路径，T21）。 */
    private final ScenarioRuntime runtime = new ScenarioRuntime(bus::publish);
    private final ScenarioResult result = ScenarioResult.create();
    private final Map<String, VirtualComponent> byId = new ConcurrentHashMap<>();
    private final ComponentBootstrapper boot;
    private final List<String> warnings = new ArrayList<>();
    private final CountDownLatch sutExit = new CountDownLatch(1);
    private volatile boolean started;
    private volatile SutStopper sutStopper;
    /** external SUT 启动器（M6；in-process 形态为 null）——生命周期归用户，故公开句柄。 */
    private volatile io.duo.sim.kernel.sut.ExternalSutLauncher externalSut;
    private volatile TimelineScheduler timeline;
    /** custom-hook 注册表（M5/G5）：可用 {@link #withHooks} 注入，默认空表。 */
    private HookRegistry hookRegistry = new HookRegistry();
    /** 输入信任档（默认＝本机文件/classpath 配置）。全链路唯一类型：{@link InputTrust}（卡 6）。 */
    private InputTrust inputTrust = InputTrust.CONFIG;

    /** 构造引擎本体（不做校验——加载即校验走 {@link #validated}；总线/结果/装配器在此接线）。 */
    public ScenarioEngine(Scenario scenario, ContractRegistry registry) {
        this.scenario = scenario;
        this.registry = registry;
        this.journal = new EventJournal(bus, scenario.name());
        this.boot = new ComponentBootstrapper(scenario, registry, bus, runtime, manager,
                byId, warnings, this::onSutEvent, this::registerSutStopper);
    }

    /** 本引擎的录制世代号（宿主用它做"现任/历史"判定）。 */
    public long epoch() {
        return journal.epoch();
    }

    /**
     * 绑定宿主的活动世代供应器（控制面用）：返回的号 ≠ {@link #epoch()} 时，本引擎已不是
     * 现任场景，不再拥有覆盖录制文件的权限。
     */
    public ScenarioEngine withEpochGuard(LongSupplier activeEpoch) {
        journal.withEpochGuard(activeEpoch);
        return this;
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
    public InputTrust inputTrust() {
        return inputTrust;
    }

    /** 加载即校验（§8 快速失败：errors 非空抛 IllegalArgumentException）。 */
    public static ScenarioEngine validated(Scenario scenario, ContractRegistry registry) {
        return validated(scenario, registry, InputTrust.CONFIG);
    }

    /** 校验 + 绑定输入信任档（EXTERNAL_INPUT 时按不可信输入收窄能力，D10/D11）。 */
    public static ScenarioEngine validated(Scenario scenario, ContractRegistry registry,
                                           InputTrust trust) {
        var report = new ScenarioValidator(registry, trust).validate(scenario);
        if (!report.ok()) {
            throw new IllegalArgumentException("scenario validation failed: "
                    + String.join("; ", report.errors()));
        }
        ScenarioEngine e = new ScenarioEngine(scenario, registry);
        e.inputTrust = trust;
        e.warnings.addAll(report.warnings());
        return e;
    }

    /**
     * 启动 SUT（M6：in-process 与 external 两形态）。
     *
     * <p>调用时机：SUT 须在内核组件**之前**启动（in-process 的 Duo 端点要写进 registry，
     * workers 的发现等待语义依赖它；external 的 ready 探针同理——计划风险 5）。装配细节
     * （直接依赖先起、SUT 端点表、主类守卫、命令行切分）见 {@link ComponentBootstrapper}。
     * {@code sut.exited/sut.crashed} 事件自动触发场景结束信号。
     */
    public SutLauncherHandle startSut() {
        var handle = boot.startSut(inputTrust);
        if (handle.isExternal()) {
            this.externalSut = handle.external();
        }
        return handle;
    }

    /**
     * 启动全部内核托管组件（SUT 与 external 节点不在此列；SUT 依赖已由 startSut 启动的跳过）。
     * 拓扑序、接线解析与元数据索引见 {@link ComponentBootstrapper#startTopology()}。
     */
    public void startComponents() {
        boot.startTopology();
        started = true;
        // 时间线执行器（T16）：全部组件启动完成的当前时刻为 t0，非空才启动
        if (!scenario.timeline().isEmpty()) {
            timeline = new TimelineScheduler(scenario.timeline(), runtime, result, hookRegistry);
            timeline.start();
        }
        bus.publish(Event.sim("sim.scenario-started", scenario.name(), Map.of()));
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

        /** 是否 external 形态（{@link #external()} 非空即 true）。 */
        public boolean isExternal() {
            return external != null;
        }
    }

    /** SUT 退出信号（M0 场景结束条件）。 */
    public void notifySutExit(boolean normal) {
        bus.publish(Event.sim(normal ? "sim.sut-exited" : "sim.sut-crashed", "sut", Map.of()));
        sutExit.countDown();
    }

    /** 等待 SUT 退出信号（{@link #notifySutExit} 或 sut.exited/crashed 事实触发；超时返回 false）。 */
    public boolean awaitSutExit(long timeoutMs) throws InterruptedException {
        return sutExit.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** 场景至今的全部事件（机器事实源，§11；下标语义与 {@link #eventsSince} 一致）。 */
    public List<Event> events() {
        return journal.events();
    }

    /**
     * 增量切片（卡 7）：只含下标 &gt;= {@code since} 的事件（O(新增)，锁内尾部拷贝）——
     * 控制面 /events 轮询的高频路径，不再整表快照。下标语义与 {@link #events()} 一致（M3 D3）。
     */
    public List<Event> eventsSince(int since) {
        return journal.eventsSince(since);
    }

    /** 事件数（免整表拷贝的自省入口；{@code /status} 的 {@code events} 用它）。 */
    public int eventCount() {
        return journal.eventCount();
    }

    /** 因内存流上限而被丢弃的事件数（0 ＝ 未溢出）。 */
    public long droppedEvents() {
        return journal.droppedEvents();
    }

    /** 警告列表拷贝（校验期 + 运行期双来源；不判场景成败）。 */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** 内核注入通路（热注入经 {@link #inject} 即它的转发；控制面 diagnose 也从这里取数）。 */
    public ScenarioRuntime runtime() {
        return runtime;
    }

    /** 场景结果收集器（T16）：注入失败/断言评估（T20 写入）/警告；结束经 snapshot() 固化。 */
    public ScenarioResult result() {
        return result;
    }

    /** 内核托管组件表快照（id → 活组件；SUT 与 external 节点不在此列）。 */
    public Map<String, VirtualComponent> components() {
        return Map.copyOf(byId);
    }

    /** 本引擎的事件总线（订阅侧经它接收全部事实；订阅在发布线程同步执行）。 */
    public SimpleEventBus bus() {
        return bus;
    }

    /** 热注入（T10）。 */
    public ScenarioRuntime.InjectionResult inject(FaultAction action) {
        return runtime.inject(action);
    }

    /** 注册 SUT 协作停止回调（不直接调用——收摊时经 {@link #stop()} 并入管理器拆除序列，T12）。 */
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

    /** 告警双写（warnings 与 result 同步，收摊期语义：两处消费面都要看得到）。 */
    private void warn(String hint) {
        warnings.add(hint);
        result.recordWarning(hint);
    }

    /**
     * 统一收摊（幂等，未启动为 no-op）：关时间线（取消未触发动作）→ 管理器逆序拆除
     * （SUT 停止并入）→ external 进程留活提示（生命周期归用户）→ 结束录制 + YAML 断言评估（T20）。
     */
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
        // A4（评审 2026-10-30）：组件停止失败不再被吞——warn 双写 warnings 与 result，
        // 经 /scenario/status 的 warnings 浮出到控制台（§12 跳过必须可见）
        manager.stopAll().forEach(this::warn);
        // external SUT 生命周期归用户（§7.3）：只拆接线，**不杀**进程——终态发事件 + 警告提示
        if (externalSut != null && externalSut.leftRunning()) {
            Long pid = externalSut.pid();
            String hint = "external SUT process (pid " + pid + ") is still running: lifecycle "
                    + "belongs to the user (§7.3) — terminate it manually";
            bus.publish(Event.sim("sim.external-process-left-running",
                    scenario.nodes().stream().filter(Scenario.NodeSpec::sut)
                            .findFirst().map(Scenario.NodeSpec::id).orElse("sut"),
                    Map.of("pid", pid == null ? -1L : pid)));
            warn(hint);
        }
        bus.publish(Event.sim("sim.scenario-finished", scenario.name(), Map.of()));
        started = false;
        journal.finish(warnings, result);
        evaluateAssertions(); // T20：YAML 内置评估写入 ScenarioResult（场景结束判定）
    }

    /** 录制文件路径（场景启动后可用；供审查/比对，T21）。 */
    public java.nio.file.Path recordingPath() {
        return journal.recordingPath();
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
