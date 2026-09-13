package io.duo.sim.components.registry;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.contract.RegistryContract;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * registry 契约的 virtual 档实现（§5/计划 T6）：会话/临时节点/watch 的内存状态机。
 *
 * <p>端点形态 NONE（供 interface-direct）；M0 不声明 registry-flap（内存闪断属 M1，
 * 设计文档 §14）；restart 清空全部状态（§7.1 重启语义）。
 */
public final class VirtualRegistry implements VirtualComponent, RegistryContract {

    private volatile ComponentId id;
    private volatile ComponentContext ctx;
    private volatile boolean running;

    /** path -> value（仅 ephemeral 节点参与）。 */
    private final Map<String, String> nodes = new ConcurrentHashMap<>();
    /** sessionId -> 该会话持有的路径。 */
    private final Map<String, List<String>> sessionPaths = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<RegistryChange>>> watchers = new ConcurrentHashMap<>();
    /** 系统会话：承载 registerEndpoint 写入的端点节点。 */
    private RegistrySession systemSession;

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
        running = true;
        systemSession = openSession("__system__");
        fire(Event.sim("sim.registry-restarted", id.value(), Map.of()));
    }

    @Override
    public HealthReport health() {
        return running ? HealthReport.ok() : HealthReport.down("registry not running");
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
        // 端点节点挂在系统会话下（ephemeral）：会话关闭/crash 即端点消失（真实 ZK 语义）
        systemSession.createEphemeral("/duo/endpoints/" + contract, address);
    }

    @Override
    public List<String> discoverEndpoints(String contract) {
        requireRunning();
        String value = nodes.get("/duo/endpoints/" + contract);
        return value == null ? List.of() : List.of(value);
    }

    @Override
    public void watch(String path, Consumer<RegistryChange> listener) {
        requireRunning();
        watchers.computeIfAbsent(path, k -> new CopyOnWriteArrayList<>()).add(listener);
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
            return running && sessionPaths.containsKey(sessionId);
        }
    }

    private void putNode(String path, String value, String sessionId) {
        boolean existed = nodes.put(path, value) != null;
        if (!existed) {
            notifyWatchers(path, value, RegistryChange.ChangeKind.CREATED);
        } else {
            notifyWatchers(path, value, RegistryChange.ChangeKind.CREATED); // M0：更新亦记 CREATED
        }
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
