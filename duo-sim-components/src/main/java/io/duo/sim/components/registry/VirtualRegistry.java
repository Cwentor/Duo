package io.duo.sim.components.registry;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.FaultInjectable;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.contract.RegistryContract;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * registry 契约的 virtual 档实现（§5/计划 T6、T18）：会话/临时节点/watch 的内存状态机。
 *
 * <p>端点形态 NONE（供 interface-direct）。M1 T18 实现 {@link FaultInjectable} 并声明
 * {@code registry-flap}（内存会话闪断）：
 * <ul>
 *   <li>flap 波及**全部**会话（含内部 {@code __system__}）：期间所有临时节点失效、
 *       watch 收 DELETED、发现查询返回空；</li>
 *   <li>**端点快照重放**：registerEndpoint 写入的端点同时记入内存快照（快照即事实源），
 *       flap 结束时自动重建 {@code __system__} 并按快照重放节点——**对 SUT 透明**，
 *       无需 demo-scheduler 参与恢复；</li>
 *   <li>**flap 期间 registerEndpoint 的边界语义**：写入快照（不丢），但当前节点表不含它
 *       （flap 期间发现为空）；flap 结束统一按快照重建后即可见。</li>
 * </ul>
 * restart 清空全部状态（§7.1 重启语义）；flap 到期由场景引擎（§7.2）或内部计时器自动 clear。
 */
public final class VirtualRegistry implements VirtualComponent, RegistryContract,
        FaultInjectable {

    /** flap 期间挂起的会话。 */
    private volatile boolean flapping = false;

    private volatile ComponentId id;
    private volatile ComponentContext ctx;
    private volatile boolean running;

    /** path -> value（仅 ephemeral 节点参与）。 */
    private final Map<String, String> nodes = new ConcurrentHashMap<>();
    /** sessionId -> 该会话持有的路径。 */
    private final Map<String, List<String>> sessionPaths = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<RegistryChange>>> watchers = new ConcurrentHashMap<>();
    /** 端点快照（contract -> address）：flap 恢复时重放的事实源，SUT 透明。 */
    private final Map<String, String> endpointSnapshot = new ConcurrentHashMap<>();
    /** 系统会话：承载 registerEndpoint 写入的端点节点。 */
    private volatile RegistrySession systemSession;
    /** flap 内部计时器（§7.2 duration 自动 clear；场景引擎 clear 亦可）。 */
    private final ScheduledExecutorService flapTicker =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "duo-registry-flap");
                t.setDaemon(true);
                return t;
            });

    @Override
    public ComponentId id() {
        return id;
    }

    @Override
    public void init(ComponentContext ctx) {
        this.ctx = ctx;
        this.id = ctx.id();
    }

    @Override
    public void start() {
        running = true;
        flapping = false;
        systemSession = openSession("__system__");
        fire(Event.sim("sim.registry-started", id.value(), Map.of()));
    }

    @Override
    public void stop(StopMode mode) {
        running = false;
        fire(Event.sim(mode == StopMode.CRASH ? "sim.registry-crashed" : "sim.registry-stopped",
                id.value(), Map.of("mode", mode.name())));
    }

    @Override
    public void restart() {
        nodes.clear();
        sessionPaths.clear();
        watchers.clear();
        endpointSnapshot.clear();
        flapping = false;
        running = true;
        systemSession = openSession("__system__");
        fire(Event.sim("sim.registry-restarted", id.value(), Map.of()));
    }

    @Override
    public HealthReport health() {
        if (!running) {
            return HealthReport.down("registry not running");
        }
        return flapping ? HealthReport.down("registry flapping") : HealthReport.ok();
    }

    @Override
    public List<ExposedEndpoint> endpoints() {
        return List.of(); // NONE
    }

    @Override
    public EndpointShape declaredShape() {
        return EndpointShape.NONE;
    }

    // ---- RegistryContract ----

    @Override
    public RegistrySession openSession(String sessionId) {
        requireRunning();
        sessionPaths.putIfAbsent(sessionId, new CopyOnWriteArrayList<>());
        fire(Event.sim("sim.registry-session-opened", id.value(), Map.of("sessionId", sessionId)));
        return new SessionHandle(sessionId);
    }

    @Override
    public void registerEndpoint(String contract, String address) {
        requireRunning();
        // 快照即事实源：flap 期间也写（不丢），当前节点表按 flap 状态决定是否可见
        endpointSnapshot.put(contract, address);
        if (!flapping) {
            systemSession.createEphemeral("/duo/endpoints/" + contract, address);
        }
        // flap 期间：节点表不含它 → discoverEndpoints 返回空；flap 结束按快照重建后可见
    }

    @Override
    public List<String> discoverEndpoints(String contract) {
        requireRunning();
        if (flapping) {
            return List.of(); // flap 期间发现为空
        }
        String value = nodes.get("/duo/endpoints/" + contract);
        return value == null ? List.of() : List.of(value);
    }

    @Override
    public void watch(String path, Consumer<RegistryChange> listener) {
        requireRunning();
        watchers.computeIfAbsent(path, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    // ---- FaultInjectable（T18：registry-flap）----

    @Override
    public void inject(FaultAction action) {
        requireRunning();
        if (!FaultAction.REGISTRY_FLAP.equals(action.type())) {
            throw new UnsupportedOperationException("unsupported fault: " + action.type());
        }
        if (flapping) {
            return; // 已在闪断中（幂等）
        }
        flapping = true;
        // 1) 全部会话失效：临时节点清空 + watch DELETED
        List<String> paths = new java.util.ArrayList<>(nodes.keySet());
        nodes.clear();
        sessionPaths.clear();
        for (String p : paths) {
            notifyWatchers(p, null, RegistryChange.ChangeKind.DELETED);
        }
        fire(Event.sim("sim.registry-flap-started", id.value(),
                Map.of("invalidatedNodes", paths.size())));
        // 2) duration 到期自动 clear（§7.2；场景引擎亦可显式 clear）
        if (action.durationMillis() != null && action.durationMillis() > 0) {
            flapTicker.schedule(() -> clear(action), action.durationMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void clear(FaultAction action) {
        if (!flapping) {
            return;
        }
        flapping = false;
        // 3) 端点快照重放：重建系统会话 + 把快照里的端点写回节点表（对 SUT 透明）
        systemSession = openSession("__system__");
        endpointSnapshot.forEach((contract, address) -> {
            systemSession.createEphemeral("/duo/endpoints/" + contract, address);
        });
        fire(Event.sim("sim.registry-flap-cleared", id.value(),
                Map.of("restoredEndpoints", endpointSnapshot.size())));
    }

    /** flap 是否进行中（测试用）。 */
    public boolean isFlapping() {
        return flapping;
    }

    // ---- internal ----

    private final class SessionHandle implements RegistrySession {

        private final String sessionId;

        SessionHandle(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void createEphemeral(String path, String value) {
            requireRunning();
            sessionPaths.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(path);
            putNode(path, value, sessionId);
        }

        @Override
        public void delete(String path) {
            requireRunning();
            String removed = nodes.remove(path);
            if (removed != null) {
                notifyWatchers(path, null, RegistryChange.ChangeKind.DELETED);
            }
        }

        @Override
        public void close() {
            List<String> paths = sessionPaths.remove(sessionId);
            if (paths != null) {
                for (String p : paths) {
                    String removed = nodes.remove(p);
                    if (removed != null) {
                        notifyWatchers(p, null, RegistryChange.ChangeKind.DELETED);
                    }
                }
            }
            fire(Event.sim("sim.registry-session-closed", id.value(),
                    Map.of("sessionId", sessionId)));
        }

        @Override
        public boolean isAlive() {
            return running && !flapping && sessionPaths.containsKey(sessionId);
        }
    }

    private void putNode(String path, String value, String sessionId) {
        boolean existed = nodes.put(path, value) != null;
        notifyWatchers(path, value, RegistryChange.ChangeKind.CREATED);
    }

    private void notifyWatchers(String path, String value, RegistryChange.ChangeKind kind) {
        List<Consumer<RegistryChange>> ls = watchers.getOrDefault(path, List.of());
        RegistryChange change = new RegistryChange(path, value, kind);
        for (Consumer<RegistryChange> l : ls) {
            try {
                l.accept(change);
            } catch (RuntimeException e) {
                fire(Event.sim("sim.registry-watch-error", id.value(),
                        Map.of("path", path, "error", String.valueOf(e.getMessage()))));
            }
        }
    }

    private void requireRunning() {
        if (!running) {
            throw new ComponentException("registry is not running: " + id);
        }
    }

    private void fire(Event e) {
        if (ctx != null) {
            ctx.eventBus().publish(e);
        }
    }
}
