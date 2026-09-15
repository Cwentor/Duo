package io.duo.sim.embedded.registry;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.FaultInjectable;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.contract.RegistryContract;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * registry 契约的「真实 ZK 后端」公共骨架（M4 T38 抽取）：持有独立 Curator 门面会话，
 * 实现双面设计（D1b）——wire 面（THIRD_PARTY 端口供 SUT 真实客户端连接）+ 门面面
 * （{@link RegistryContract} 转发真实协议往返）。
 *
 * <p>子类只负责提供/更换 ZK 后端：
 * <ul>
 *   <li>{@link CuratorRegistry}——Curator TestingServer（JVM 内，M2 T24，embedded 档）；</li>
 *   <li>{@code ZookeeperContainerRegistry}——Testcontainers ZK 真容器（M4 T38，container 档）。</li>
 * </ul>
 *
 * <p>门面客户端连的是后端的连接串（{@link #connectString()}），因此会话语义、临时节点、
 * watch 对两个后端完全一致；flap 语义差异由子类覆写注入入口表达（TestingServer 重启 vs
 * 容器不可 flap——容器后端不支持 registry-flap，见 ZookeeperContainerRegistry 元数据）。
 */
abstract class ZkBackedRegistry implements VirtualComponent, RegistryContract, FaultInjectable {

    /** 端点根路径（与 VirtualRegistry 兼容）。 */
    public static final String ENDPOINTS_ROOT = "/duo/endpoints";

    protected volatile ComponentId id;
    protected volatile ComponentContext ctx;
    protected volatile CuratorFramework facadeClient;
    protected volatile boolean running;
    /** 闪断中标志（TestingServer 后端的瞬时动作窗口）。 */
    protected volatile boolean flapping;

    /** 门面会话注册表（sessionId -> 该会话创建的节点路径）。 */
    private final Map<String, List<String>> sessionPaths = new ConcurrentHashMap<>();
    /** watch 监听器（path -> listeners）。 */
    private final Map<String, List<Consumer<RegistryChange>>> watchers = new ConcurrentHashMap<>();
    /** 已注册端点快照（仅用于诊断/恢复对比；**不重放**——真实语义）。 */
    private final Map<String, String> endpoints = new ConcurrentHashMap<>();

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
    public final void start() throws ComponentException {
        try {
            startBackend();
            facadeClient = newFacadeClient(connectString());
            facadeClient.start();
            facadeClient.blockUntilConnected();
            ensurePath(ENDPOINTS_ROOT);
            running = true;
            fire(Event.sim("sim.registry-started", id.value(), backendInfo()));
        } catch (Exception e) {
            throw new ComponentException(
                    "cannot start ZK-backed registry (" + backendKind() + "): " + e.getMessage(), e);
        }
    }

    /** 启动 ZK 后端（TestingServer / 容器），完成后 {@link #connectString()} 必须可用。 */
    protected abstract void startBackend() throws Exception;

    /** 停止 ZK 后端（幂等；实现须自吞异常）。 */
    protected abstract void stopBackend();

    /** 真实 ZK 连接串（host:port，可多值逗号分隔——Curator 语义）。 */
    public abstract String connectString();

    /** 后端种类标识（事件载荷 kind：embedded / container）。 */
    protected abstract String backendKind();

    /** 后端启动信息（sim.registry-started 载荷）。 */
    protected Map<String, Object> backendInfo() {
        return Map.of("kind", backendKind(), "connectString", connectString());
    }

    protected static CuratorFramework newFacadeClient(String connectString) {
        return CuratorFrameworkFactory.builder()
                .connectString(connectString)
                .retryPolicy(new ExponentialBackoffRetry(200, 5))
                .build();
    }

    @Override
    public void stop(StopMode mode) {
        running = false;
        closeQuietly(facadeClient);
        stopBackend();
        fire(Event.sim(mode == StopMode.CRASH ? "sim.registry-crashed" : "sim.registry-stopped",
                id.value(), Map.of("kind", backendKind())));
    }

    @Override
    public void restart() {
        stop(StopMode.GRACEFUL);
        start();
        fire(Event.sim("sim.registry-restarted", id.value(), Map.of("kind", backendKind())));
    }

    @Override
    public HealthReport health() {
        if (!running || facadeClient == null) {
            return HealthReport.down(backendKind() + " registry not running");
        }
        try {
            // 端口可连即健康（后端无统一 isRunning API）
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port()), 500);
            }
            return HealthReport.ok();
        } catch (IOException e) {
            return HealthReport.down(backendKind() + " registry port unreachable: " + e.getMessage());
        }
    }

    /** ZK 端口（健康探测用；默认解析 connectString 首个 host:port）。 */
    protected int port() {
        String cs = connectString();
        String first = cs.split(",")[0];
        return Integer.parseInt(first.substring(first.lastIndexOf(':') + 1));
    }

    /** wire 面：真实 ZK 端口（供 SUT 的真实客户端连接）。 */
    @Override
    public List<ExposedEndpoint> endpoints() {
        if (!running) {
            return List.of();
        }
        return List.of(ExposedEndpoint.tcp(io.duo.sim.kernel.api.Contract.REGISTRY,
                "127.0.0.1", port()));
    }

    @Override
    public EndpointShape declaredShape() {
        return EndpointShape.THIRD_PARTY;
    }

    /** 门面使用的 Curator 客户端（测试断言用）。 */
    public CuratorFramework facadeClient() {
        return facadeClient;
    }

    /** 闪断中标志（测试用）。 */
    public boolean isFlapping() {
        return flapping;
    }

    // ---- RegistryContract（同进程门面：真实协议转发）----

    @Override
    public RegistrySession openSession(String sessionId) {
        requireRunning();
        sessionPaths.putIfAbsent(sessionId, new CopyOnWriteArrayList<>());
        fire(Event.sim("sim.registry-session-opened", id.value(),
                Map.of("sessionId", sessionId, "kind", "facade")));
        return new FacadeSession(sessionId);
    }

    @Override
    public void registerEndpoint(String contract, String address) {
        requireRunning();
        String path = ENDPOINTS_ROOT + "/" + contract;
        try {
            if (facadeClient.checkExists().forPath(path) == null) {
                facadeClient.create().creatingParentsIfNeeded()
                        .withMode(CreateMode.EPHEMERAL)
                        .forPath(path, address.getBytes(StandardCharsets.UTF_8));
            } else {
                facadeClient.setData().forPath(path, address.getBytes(StandardCharsets.UTF_8));
            }
            endpoints.put(contract, address);
            notifyWatchers(path, address, RegistryChange.ChangeKind.CREATED);
        } catch (Exception e) {
            throw new ComponentException("registerEndpoint failed on real ZK: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public List<String> discoverEndpoints(String contract) {
        requireRunning();
        try {
            String path = ENDPOINTS_ROOT + "/" + contract;
            if (facadeClient.checkExists().forPath(path) == null) {
                return List.of();
            }
            byte[] data = facadeClient.getData().forPath(path);
            return List.of(new String(data, StandardCharsets.UTF_8));
        } catch (KeeperException.NoNodeException e) {
            return List.of();
        } catch (Exception e) {
            throw new ComponentException("discoverEndpoints failed on real ZK: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public void watch(String path, Consumer<RegistryChange> listener) {
        requireRunning();
        watchers.computeIfAbsent(path, k -> new CopyOnWriteArrayList<>()).add(listener);
        // 真实 ZK watch：一次注册，触发后由回调重注册（Curator 语义下用 Watcher 包装）
        registerZkWatch(path, listener);
    }

    private void registerZkWatch(String path, Consumer<RegistryChange> listener) {
        try {
            if (facadeClient.checkExists().forPath(path) == null) {
                return; // 节点不存在：watch 由后续 registerEndpoint 的门面通知承担
            }
            facadeClient.getData().usingWatcher((Watcher) (WatchedEvent event) -> {
                RegistryChange.ChangeKind kind = switch (event.getType()) {
                    case NodeCreated -> RegistryChange.ChangeKind.CREATED;
                    case NodeDeleted -> RegistryChange.ChangeKind.DELETED;
                    default -> RegistryChange.ChangeKind.CREATED; // 数据变更视同 CREATED（与 virtual 档一致）
                };
                listener.accept(new RegistryChange(event.getPath(), null, kind));
                // 重注册（真实 ZK watch 一次性）
                if (kind != RegistryChange.ChangeKind.DELETED) {
                    registerZkWatch(path, listener);
                }
            }).forPath(path);
        } catch (Exception ignored) {
            // 节点可能刚被删除；watch 失效按真实 ZK 语义处理
        }
    }

    // ---- 门面会话 ----

    private final class FacadeSession implements RegistrySession {

        private final String sessionId;

        FacadeSession(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void createEphemeral(String path, String value) {
            requireRunning();
            try {
                if (facadeClient.checkExists().forPath(path) != null) {
                    facadeClient.setData().forPath(path, value.getBytes(StandardCharsets.UTF_8));
                } else {
                    facadeClient.create().creatingParentsIfNeeded()
                            .withMode(CreateMode.EPHEMERAL)
                            .forPath(path, value.getBytes(StandardCharsets.UTF_8));
                }
                sessionPaths.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(path);
                notifyWatchers(path, value, RegistryChange.ChangeKind.CREATED);
            } catch (Exception e) {
                throw new ComponentException("createEphemeral failed on real ZK: "
                        + e.getMessage(), e);
            }
        }

        @Override
        public void delete(String path) {
            requireRunning();
            try {
                facadeClient.delete().forPath(path);
                notifyWatchers(path, null, RegistryChange.ChangeKind.DELETED);
            } catch (KeeperException.NoNodeException ignored) {
                // 已不存在：幂等
            } catch (Exception e) {
                throw new ComponentException("delete failed on real ZK: "
                        + e.getMessage(), e);
            }
        }

        @Override
        public void close() {
            // 门面会话关闭＝删除该会话登记的临时节点（真实 ZK 下由会话结束自动清理；
            // 门面共享单一 Curator 客户端，故显式删除）
            List<String> paths = sessionPaths.remove(sessionId);
            if (paths != null) {
                for (String p : paths) {
                    try {
                        facadeClient.delete().forPath(p);
                        notifyWatchers(p, null, RegistryChange.ChangeKind.DELETED);
                    } catch (Exception ignored) {
                        // 已删除
                    }
                }
            }
            fire(Event.sim("sim.registry-session-closed", id.value(),
                    Map.of("sessionId", sessionId, "kind", "facade")));
        }

        @Override
        public boolean isAlive() {
            return running && facadeClient != null && facadeClient.isStarted()
                    && sessionPaths.containsKey(sessionId);
        }
    }

    // ---- internal ----

    protected void ensurePath(String path) throws Exception {
        if (facadeClient.checkExists().forPath(path) == null) {
            facadeClient.create().creatingParentsIfNeeded()
                    .withMode(CreateMode.PERSISTENT).forPath(path);
        }
    }

    protected void notifyWatchers(String path, String value, RegistryChange.ChangeKind kind) {
        // T26：节点变更统一发框架事件（§7.3 观测途径①embedded 版）——不依赖是否有
        // 局部 watch 订阅者，供断言与事件录制观测
        fire(Event.sim("sim.registry-node-changed", id.value(),
                Map.of("path", path, "kind", kind.name(),
                        "value", value == null ? "" : value)));
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

    protected void requireRunning() {
        if (!running) {
            throw new ComponentException(backendKind() + " registry is not running: " + id);
        }
    }

    protected static void closeQuietly(CuratorFramework client) {
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // 尽力而为
            }
        }
    }

    protected void fire(Event e) {
        if (ctx != null) {
            ctx.eventBus().publish(e);
        }
    }
}
