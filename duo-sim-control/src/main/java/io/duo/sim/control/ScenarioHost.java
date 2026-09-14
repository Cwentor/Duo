package io.duo.sim.control;

import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.Tier;
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
    /** 事件游标：已分配给客户端的事件总数（D3）。 */
    private int emittedCursor;
    /** 序号映射：事件对象身份 → 序号（用 identity 避免 Event record 的 equals 折叠重复）。 */
    private final Map<Event, Integer> seqByEvent = new LinkedHashMap<>();

    // ---- 生命周期 ----

    /** 启动场景（同进程）。YAML 先经 {@code ScenarioEngine.validated} 校验（§8 快速失败）。 */
    public synchronized Map<String, Object> start(Path yaml) throws Exception {
        if (state == State.RUNNING) {
            throw new IllegalStateException("scenario already running");
        }
        try {
            Scenario loaded = ScenarioLoader.load(yaml);
            var registry = ContractRegistry.loadFromServiceLoader();
            engine = ScenarioEngine.validated(loaded, registry);
            scenario = loaded;
            seqByEvent.clear();
            emittedCursor = 0;
            engine.startSut();
            engine.startComponents();
            state = State.RUNNING;
            lastError = null;
            return status();
        } catch (RuntimeException e) {
            state = State.FAILED;
            lastError = String.valueOf(e.getMessage());
            throw e;
        }
    }

    /** 以 classpath 资源启动（CLI/测试便捷入口）。 */
    public synchronized Map<String, Object> startFromResource(String resourcePath) throws Exception {
        try (InputStream in = ScenarioHost.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("scenario resource not found: " + resourcePath);
            }
            Path tmp = Files.createTempFile("duo-scenario-", ".yaml");
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return start(tmp);
        }
    }

    /** 等待 SUT 退出并评估断言（若尚未停止）。 */
    public synchronized Map<String, Object> awaitFinish(long timeoutMs) throws InterruptedException {
        if (engine == null) {
            return status();
        }
        boolean exited = engine.awaitSutExit(timeoutMs);
        if (exited) {
            engine.stop();
            state = engine.result().passed() ? State.FINISHED : State.FAILED;
            if (!engine.result().passed()) {
                lastError = "assertions failed";
            }
        }
        return status();
    }

    /** 停止场景（幂等）。 */
    public synchronized Map<String, Object> stop() {
        if (engine != null) {
            engine.stop();
            state = engine.result().passed() ? State.FINISHED : State.FAILED;
        } else {
            state = State.IDLE;
        }
        return status();
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
            out.put("events", engine.events().size());
        }
        if (lastError != null) {
            out.put("error", lastError);
        }
        return out;
    }

    /**
     * 事件增量（D3）：返回自 {@code since}（不含）起的新事件，带单调序号。
     * 序号按本层首次见到该事件对象时分配；同一快照内不重不漏。
     */
    public synchronized List<Map<String, Object>> eventsSince(int since) {
        if (engine == null) {
            return List.of();
        }
        List<Event> snapshot = engine.events();
        // 为新出现的事件分配序号（用身份比较：record 的 equals 会把同内容事件折叠）
        for (Event e : snapshot) {
            if (!containsIdentity(e)) {
                seqByEvent.put(e, ++emittedCursor);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Event e : snapshot) {
            Integer seq = lookupIdentity(e);
            if (seq != null && seq > since) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("seq", seq);
                m.put("type", e.type());
                m.put("sourceId", e.sourceId());
                m.put("timestamp", e.timestamp().toString());
                m.put("payload", e.payload() == null ? Map.of() : e.payload());
                out.add(m);
            }
        }
        return out;
    }

    /** 注入转发（§10：纯外层包装，语义完全由内核决定）。 */
    public ScenarioRuntime.InjectionResult inject(FaultAction action) {
        if (engine == null || state != State.RUNNING) {
            return new ScenarioRuntime.InjectionResult(false, "scenario not running");
        }
        return engine.inject(action);
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
        }
    }

    private boolean containsIdentity(Event e) {
        for (Event k : seqByEvent.keySet()) {
            if (k == e) {
                return true;
            }
        }
        return false;
    }

    private Integer lookupIdentity(Event e) {
        for (var en : seqByEvent.entrySet()) {
            if (en.getKey() == e) {
                return en.getValue();
            }
        }
        return null;
    }
}
