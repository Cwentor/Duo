package io.duo.sim.control;

import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.kernel.core.ScenarioRuntime;
import io.duo.sim.scenario.ScenarioEngine;
import io.duo.sim.scenario.ScenarioLoader;
import io.duo.sim.scenario.model.Scenario;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 控制面内核适配层（M3 T32）：持有 {@link ScenarioEngine} 生命周期，向 REST/CLI 暴露
 * 统一操作面（启动/停止/状态/事件增量/注入转发/结果/拓扑）。
 *
 * <p><b>§10 约束：零内核改动</b>——本类只用 {@code ScenarioEngine} / {@code ScenarioRuntime} /
 * {@code ScenarioResult} 的既有公开 API，不新增/修改任何内核符号。事件增量序号（D3）在
 * 本层按 {@code lastSeenIndex} 游标分配；拓扑视图从 {@code components()} 与场景模型推导。
 */
public final class ScenarioHost implements AutoCloseable {

    /** 运行状态。 */
    public enum State { IDLE, RUNNING, FINISHED, FAILED }

    /**
     * 同进程 CLI 注册表（M3 D2）：{@code run --keep}把 host注册于此，
     * 后续 inject/status/events 等命令按名接管。进程内静态（单 JVM 工具语义）。
     */
    private static final Map<String, ScenarioHost> ATTACHED = new ConcurrentHashMap<>();

    /** 注册到进程级注册表（CLI 同进程模式）。 */
    public void attach(String name) {
        ATTACHED.put(name, this);
    }

    /** 按名取已注册的 host（无则 null）。 */
    public static ScenarioHost attached(String name) {
        return ATTACHED.get(name);
    }

    /** 从注册表移除（close 时自动）。 */
    public void detach(String name) {
        ATTACHED.remove(name, this);
    }

    private Scenario scenario;
    private ScenarioEngine engine;
    private State state = State.IDLE;
    private String lastError;
    /** custom-hook 注册表（M5/G5）：宿主级共享，每次 start 注入引擎；YAML 时间线可调用其中 hook。 */
    private final io.duo.sim.scenario.HookRegistry hooks = new io.duo.sim.scenario.HookRegistry();

    /** 注册自定义 hook（YAML 时间线 {@code custom-hook} 动作按名调用）。 */
    public io.duo.sim.scenario.HookRegistry hooks() {
        return hooks;
    }

    // ---- 生命周期 ----

    /**
     * 场景来源的信任级（安全审计 2026-09-20：把信任边界从「能连到控制面端口」
     * 重新画到「输入是谁给的」上）。
     *
     * <p>{@link #CONFIG}＝本机文件（CLI/测试/嵌入式）：与「启动一个进程」同级，
     * 能力**不缩水**；{@link #EXTERNAL_INPUT}＝控制面 POST body／任何远程来源：
     * 按不可信输入处理（禁外部进程启动、config 键白名单、规模上限）。
     */
    public enum Trust { CONFIG, EXTERNAL_INPUT }

    /** REST 落盘 YAML 的隔离后缀：外部输入档下，DSL 指向本进程临时文件的路径被校验器直接拒绝。 */
    public static final String QUARANTINE_SUFFIX = "untrusted";

    private Trust trust = Trust.CONFIG;

    /** 本轮场景由控制面落地的临时 YAML（外部输入隔离副本）；无则为 null（审计 M-6/L-1）。 */
    private String currentTempName;

    /**
     * 被 {@link #start} 顶替掉的旧引擎（安全审计 2026-09-20 复核 H-4）。
     *
     * <p>审计期 {@code start()} 直接 {@code engine = ...} 覆写引用，旧引擎从此不可达：它的
     * 组件/时间线/录制器没人收摊，控制面反复 {@code POST /scenario} 就是一条稳定的句柄泄漏路径。
     * 这里把旧引用保留到能安全收摊为止（见 {@link #retireEngine}）。
     */
    private ScenarioEngine retired;
    private long retiredAtMillis;
    /** 当前活动录制世代（＝现任引擎的 epoch）；旧引擎据此判断自己是否还被允许写盘。 */
    private volatile long activeEpoch;

    /** 退场引擎的保留时长：先让客户端把已产生的事件/状态读完，再真正收摊。 */
    public static final long RETIRED_ENGINE_GRACE_MILLIS = 30_000;

    /** 收摊计数（测试/诊断：确认旧引擎确实被关过，而不是"记了个账"）。 */
    private final java.util.concurrent.atomic.AtomicLong retiredEnginesClosed =
            new java.util.concurrent.atomic.AtomicLong();

    /** 收摊被延迟或失败时的可见记录（§12：跳过必须可见）。 */
    private final List<String> warnings = new ArrayList<>();

    /** 启动场景（同进程）。YAML 先经 {@code ScenarioEngine.validated} 校验（§8 快速失败）。 */
    public synchronized Map<String, Object> start(Path yaml) throws Exception {
        return start(yaml, Trust.CONFIG);
    }

    /**
     * 按信任级启动场景。默认入口 {@link #start(Path)} 为 {@link Trust#CONFIG}
     * （本机文件/测试/嵌入式），控制面 REST 显式传 {@link Trust#EXTERNAL_INPUT}。
     */
    public synchronized Map<String, Object> start(Path yaml, Trust trust) throws Exception {
        return start(yaml, trust, null);
    }

    /**
     * 按信任级启动场景，并登记「由调用方落地、需在收尾时删除」的临时文件名。
     *
     * <p>{@code tempName} 语义（审计 M-6/L-1）：控制面把外部输入写成临时 YAML 后再交给引擎，
     * 这份文件在场景结束后即为垃圾。宿主只留**文件名**（不留绝对路径，避免路径出现在
     * 错误消息与事件载荷里），收尾时用 {@code Files.createTempFile} 的同目录语义删除
     * ——即 {@code Path.of(System.getProperty("java.io.tmpdir"), tempName)}。
     *
     * @param tempName 临时文件名（非路径）；null ＝本机文件，不清理
     */
    public synchronized Map<String, Object> start(Path yaml, Trust trust, String tempName)
            throws Exception {
        if (state == State.RUNNING) {
            throw new IllegalStateException("scenario already running");
        }
        this.trust = trust;
        currentTempName = tempName;
        warnings.clear();
        try {
            Scenario loaded = ScenarioLoader.load(yaml);
            var registry = ContractRegistry.loadFromServiceLoader();
            // 世代闸门：宿主顶替场景时递增，旧引擎因此失去覆盖录制文件（同名场景重跑）的权限
            ScenarioEngine next = ScenarioEngine.validated(loaded, registry,
                    trust == Trust.EXTERNAL_INPUT
                            ? ScenarioEngine.InputPolicy.EXTERNAL_INPUT
                            : ScenarioEngine.InputPolicy.CONFIG)
                    .withHooks(hooks);
            activeEpoch = next.epoch();
            next.withEpochGuard(this::activeEpoch);
            // 先校验/构造完成后才顶替旧引擎：构造失败不摧毁正在跑的场景（§8 快速失败）
            retireEngine();
            engine = next;
            scenario = loaded;
            engine.startSut();
            engine.startComponents();
            state = State.RUNNING;
            lastError = null;
            return status();
        } catch (RuntimeException e) {
            // 启动失败＝这份临时 YAML 从未成为场景源，就地清理（审计 L-1）；成功路径则由
            // cleanupTempArtifacts() 在收尾时清理（文件在同一轮场景里仍是"当前源"）。
            deleteTempNow();
            state = State.FAILED;
            lastError = String.valueOf(e.getMessage());
            throw e;
        }
    }

    /**
     * 顶替旧引擎：立即置零引用，收摊交给后台（安全审计 2026-09-20 复核 H-4）。
     *
     * <p>为什么不在这里同步 {@code close()}：{@code stop()} 会跑
     * {@code ScenarioRuntime} 的资源回收，外部 SUT 收尾最长一整个
     * {@code STOP_TIMEOUT_MS}（10s），同步等会阻塞 {@code POST /scenario}——而调用方真正
     * 关心的是"起了没有/为什么没起"。故：**引用立即断开**（不会再有新事件写进旧引擎），
     * **收摊在后台虚拟线程执行**，结果与耗时都对宿主可见（{@link #retiredEnginesClosed()} +
     * {@link #warnings()}）。
     *
     * <p>旧引擎只被本地变量捕获一次，因此即使期间又发生一次 {@code start()} 也不会重复关。
     */
    private void retireEngine() {
        ScenarioEngine old = engine;
        if (old == null) {
            return;
        }
        engine = null;
        retired = old;
        retiredAtMillis = System.currentTimeMillis();
        Thread.ofVirtual().name("duo-host-retire").start(() -> retireEngineNow(old));
    }

    /** 实际收摊（幂等；{@code close()} 也会调一次，确保退出前不留半开引擎）。 */
    private void retireEngineNow(ScenarioEngine old) {
        long t0 = System.nanoTime();
        try {
            old.close();
        } catch (RuntimeException e) {
            // 收摊失败不得静默：旧引擎的句柄可能仍在（§12）
            synchronized (this) {
                warnings.add("retired engine close failed: " + e);
            }
        } finally {
            retiredEnginesClosed.incrementAndGet();
            long millis = (System.nanoTime() - t0) / 1_000_000;
            synchronized (this) {
                if (retired == old) {
                    retired = null;
                }
                if (millis >= RETIRED_ENGINE_GRACE_MILLIS) {
                    warnings.add("retired scenario took " + millis
                            + " ms to settle down (external SUT teardown is bounded by its "
                            + "stop timeout)");
                }
            }
        }
    }

    /**
     * 已被收摊的旧引擎数量（测试/诊断；启动失败路径不计入）。
     *
     * <p>公开 API：验收用例据此断言"顶替确实收摊了"，而不是只看引用有没有换。
     */
    public long retiredEnginesClosed() {
        return retiredEnginesClosed.get();
    }

    /** 当前活动的录制世代（现任引擎的 epoch）。 */
    private long activeEpoch() {
        return activeEpoch;
    }

    /** 立即删除已登记的临时 YAML（启动失败路径）。 */
    private void deleteTempNow() {
        String name = currentTempName;
        currentTempName = null;
        if (name == null) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(System.getProperty("java.io.tmpdir"), name));
        } catch (java.io.IOException e) {
            warnings.add("cannot delete temp scenario file: " + e.getMessage());
        }
    }

    /** 当前场景的来源信任级（控制面提示用）。 */
    public synchronized Trust trust() {
        return trust;
    }

    /** 本层累积的告警（如录制缓冲溢出、临时文件清理失败）；只读快照。 */
    public synchronized List<String> warnings() {
        return List.copyOf(warnings);
    }

    /**
     * 以 classpath 资源启动（CLI/测试便捷入口）：classpath 资源是本机字节码的一部分，
     * 属可信配置档，跑完即删临时文件（审计 M-6：临时 YAML 不长期滞留）。
     */
    public synchronized Map<String, Object> startFromResource(String resourcePath) throws Exception {
        Path tmp;
        try (InputStream in = ScenarioHost.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("scenario resource not found: " + resourcePath);
            }
            tmp = Files.createTempFile("duo-scenario-", ".yaml");
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        // 登记文件名：启动失败与场景收尾两条路径都会删除它（审计 L-1）
        return start(tmp, Trust.CONFIG, tmp.getFileName().toString());
    }

    /**
     * 等待 SUT 退出并评估断言（若尚未停止）。等待期间**不持锁**——
     * {@code serve} 模式下 REST 处理器需要同时读状态/事件。
     */
    public Map<String, Object> awaitFinish(long timeoutMs) throws InterruptedException {
        ScenarioEngine eng;
        synchronized (this) {
            eng = engine;
        }
        if (eng == null) {
            return status();
        }
        boolean exited = eng.awaitSutExit(timeoutMs);
        if (exited) {
            finalizeRun(eng);
        }
        return status();
    }

    /**
     * 后台等待 SUT 退出并固化结果（{@code serve} 模式：SUT 自行退出时自动评估断言，
     * 无需客户端触发停止）。返回的线程可被忽略。
     */
    public Thread awaitFinishInBackground(long timeoutMs) {
        return Thread.ofVirtual().name("duo-host-finish").start(() -> {
            try {
                awaitFinish(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** 停止并固化结果（幂等；重复调用只生效一次）。 */
    private synchronized void finalizeRun(ScenarioEngine eng) {
        if (eng != engine) {
            return; // 已被后续 start 替换
        }
        eng.stop();
        state = eng.result().passed() ? State.FINISHED : State.FAILED;
        if (!eng.result().passed() && lastError == null) {
            lastError = "assertions failed";
        }
    }

    /** 停止场景（幂等）。 */
    public synchronized Map<String, Object> stop() {
        if (engine != null) {
            engine.stop();
            cleanupTempArtifacts(); // 审计 M-6/L-1：场景收尾即清理本层产生的临时文件
            state = engine.result().passed() ? State.FINISHED : State.FAILED;
        } else {
            state = State.IDLE;
        }
        return status();
    }

    /** 本层临时产物的文件名前缀（回归用例据此在 tmpdir 里认领自己留下的文件）。 */
    public static final java.util.Set<String> TEMP_FILE_PREFIXES =
            java.util.Set.of("duo-rest-", "duo-scenario-", "duo-sut-");

    /**
     * 清理本层产生的临时产物（审计 M-6 / L-1）：
     * <ul>
     *   <li>控制面为外部输入落地的隔离 YAML（{@code duo-rest-*.untrusted.yaml}）；</li>
     *   <li>external SUT 的端点告知文件（{@code duo-sut-*.config}，内核写入、无人回读）。</li>
     * </ul>
     * 两者都是"启动的踏板"，场景结束后不再需要；留着只会把机器上的路径/拓扑信息
     * 长期暴露给同机其它进程（审计 L-1 的口径）。清理失败只告警，不影响停止语义。
     */
    private void cleanupTempArtifacts() {
        List<Path> victims = new ArrayList<>();
        if (currentTempName != null) {
            victims.add(Path.of(System.getProperty("java.io.tmpdir"), currentTempName));
        }
        io.duo.sim.kernel.sut.ExternalSutLauncher sut =
                engine == null ? null : engine.externalSut();
        Path configOut = sut == null ? null : sut.configFile();
        if (configOut != null) {
            victims.add(configOut);
        }
        for (Path p : victims) {
            try {
                Files.deleteIfExists(p);
            } catch (java.io.IOException e) {
                warnings.add("cannot delete temp artifact " + p.getFileName() + ": "
                        + e.getMessage());
            }
        }
        currentTempName = null;
        if (engine != null) {
            warnings.addAll(engine.warnings()); // 引擎侧告警（如录制缓冲溢出）汇总到宿主面
        }
    }

    /**
     * 停止场景并**立刻返回**（不等 SUT 收尾线程）。
     *
     * <p>{@code serve} 模式收到 {@code DELETE /scenario} 时用这个而不是 {@link #stop()}：
     * 真实 SUT 的 {@code engine.stop()} 可能长时间阻塞在等待子进程退出上（审计 H-5），
     * 客户端会一直挂着。这里把收尾交给后台虚拟线程，HTTP 立刻得到 200 + 当前状态；
     * 场景结果随后由 {@link #status()} 反映（语义仍是「已下达停止」）。
     */
    public Map<String, Object> stopWithoutAwait() {
        Thread.ofVirtual().name("duo-host-stop").start(this::stop);
        synchronized (this) {
            return status();
        }
    }

    // ---- 状态与观测 ----

    public synchronized Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", state.name());
        out.put("scenario", scenario == null ? null : scenario.name());
        if (engine != null) {
            var snap = engine.result().snapshot();
            out.put("injectionFailures", snap.injectionFailures().size());
            out.put("assertions", snap.assertions().stream()
                    .map(a -> Map.of("name", a.name(), "passed", a.passed(),
                            "detail", a.detail() == null ? "" : a.detail()))
                    .toList());
            out.put("passed", engine.result().passed());
            var rec = engine.recordingPath();
            out.put("recording", rec == null ? null : rec.toString());
            out.put("events", engine.eventCount());
        }
        if (lastError != null) {
            out.put("error", lastError);
        }
        return out;
    }

    /**
     * 事件增量（D3）：返回自 {@code since}（不含）起的新事件，带单调序号。
     *
     * <p>序号＝事件在 {@code engine.events()} 快照中的 1-based 下标。内核事件流是**只追加**的
     * {@code CopyOnWriteArrayList}，故下标稳定、天然不重不漏，无需身份映射表——
     * 直接按下标切片即为 O(新增)（早期版本用 LinkedHashMap+线性扫描防 record equals 折叠，
     * 整体 O(n²) 且永不释放，M4 万级规模下不可接受）。
     */
    public synchronized List<Map<String, Object>> eventsSince(int since) {
        if (engine == null) {
            return List.of();
        }
        List<Event> snapshot = engine.events();
        int from = Math.max(since, 0);
        if (from >= snapshot.size()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(snapshot.size() - from);
        for (int i = from; i < snapshot.size(); i++) {
            Event e = snapshot.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", i + 1);
            m.put("type", e.type());
            m.put("sourceId", e.sourceId());
            m.put("timestamp", e.timestamp().toString());
            m.put("payload", e.payload() == null ? Map.of() : e.payload());
            out.add(m);
        }
        return out;
    }

    /** 注入转发（§10：纯外层包装，语义完全由内核决定）。 */
    public ScenarioRuntime.InjectionResult inject(FaultAction action) {
        if (engine == null || state != State.RUNNING) {
            FaultLog.failed(action.target().componentId().value(), action.type(),
                    "scenario not running");
            return new ScenarioRuntime.InjectionResult(false, "scenario not running");
        }
        ScenarioRuntime.InjectionResult r = engine.inject(action);
        // M8：日志与事件流同源同序（内核判定之后），失败必记录（§12 不静默）
        if (r.success()) {
            FaultLog.injected(action.target().componentId().value(),
                    action.target().instanceIndex(), action.type());
        } else {
            FaultLog.failed(action.target().componentId().value(), action.type(), r.reason());
        }
        return r;
    }

    /**
     * 事件流快照（M8 指标层的取数口）。
     *
     * <p>与 {@link #eventsSince(int)} 的区别：这里返回**原始** {@code Event} 列表，
     * 供指标层按下标游标累计计数。内核事件流是只追加的 {@code CopyOnWriteArrayList}，
     * 快照即读数，无拷贝语义问题。**控制面零内核改动**（§10）：只用既有公开 API。
     */
    public List<Event> eventsSnapshot() {
        ScenarioEngine eng;
        synchronized (this) {
            eng = engine;
        }
        return eng == null ? List.of() : eng.events();
    }

    /** 断言结果。 */
    public synchronized Map<String, Object> assertions() {
        if (engine == null) {
            return Map.of("assertions", List.of(), "passed", false);
        }
        var snap = engine.result().snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", engine.result().passed());
        out.put("assertions", snap.assertions().stream()
                .map(a -> Map.of("name", a.name(), "passed", a.passed(),
                        "detail", a.detail() == null ? "" : a.detail()))
                .toList());
        out.put("injectionFailures", snap.injectionFailures().stream()
                .map(f -> Map.of("action", f.action(), "target", f.target(),
                        "reason", f.reason()))
                .toList());
        return out;
    }

    /** 拓扑视图（T35）：节点/契约/档位/实例数在线状态。 */
    public synchronized List<Map<String, Object>> topology() {
        if (scenario == null) {
            return List.of();
        }
        Map<String, io.duo.sim.kernel.api.VirtualComponent> comps =
                engine == null ? Map.of() : engine.components();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (var n : scenario.nodes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id());
            m.put("contract", n.contract());
            m.put("tier", n.tier());
            m.put("sut", n.sut());
            m.put("count", n.count() == null ? 1 : n.count());
            var comp = comps.get(n.id());
            m.put("hosted", comp != null);
            if (comp != null) {
                m.put("healthy", comp.health().healthy());
                m.put("endpoints", comp.endpoints().stream()
                        .map(ep -> ep.kind() + ":" + ep.address()).toList());
            } else if (n.sut()) {
                m.put("healthy", null); // SUT 由 SutLauncher 管理，不在 components()
            }
            nodes.add(m);
        }
        return nodes;
    }

    /** 契约/档位摘要（诊断用）。 */
    public static Map<String, Object> describe(FaultAction action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", action.type());
        m.put("componentId", action.target().componentId().value());
        m.put("instanceIndex", action.target().instanceIndex());
        m.put("durationMillis", action.durationMillis());
        return m;
    }

    /** 场景是否仍在运行。 */
    public synchronized boolean isRunning() {
        return state == State.RUNNING;
    }

    /** 是否已有可读结果（启动过且引擎存在）。 */
    public synchronized boolean hasResult() {
        return engine != null;
    }

    @Override
    public synchronized void close() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
        // 顶替掉的旧引擎也必须收摊：否则它持有的管道/时间线会活过宿主
        ScenarioEngine old = retired;
        retired = null;
        if (old != null) {
            retireEngineNow(old);
        }
    }
}