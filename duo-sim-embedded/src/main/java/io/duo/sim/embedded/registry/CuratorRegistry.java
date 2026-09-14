package io.duo.sim.embedded.registry;

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

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * registry 契约的 embedded 档实现（M2 T24）：JVM 内运行**真实 ZooKeeper**
 * （Curator TestingServer），暴露真实端口供 wire 客户端连接，同时提供**同进程门面**
 * 让框架自有组件（VirtualWorker 等，只 speak {@link RegistryContract}）经真实协议交互。
 *
 * <p><b>双面设计（计划 D1b）</b>：
 * <ul>
 *   <li><b>wire 面</b>：{@link #endpoints()} 返回 THIRD_PARTY 端口（127.0.0.1:&lt;port&gt;），
 *       SUT（如 demo-scheduler）用真实 Curator/ZK 客户端连它——M2 演练"SUT 在真实注册中心
 *       闪断下的自愈"；</li>
 *   <li><b>门面</b>：本类实现 {@link RegistryContract}，内部持有**独立 Curator 会话**转发到
 *       同一 TestingServer（真实协议往返，非内存状态机）——框架组件经 interface-direct 消费；
 *       门面会话与 SUT 会话相互独立（闪断时各自断开、各自重连，忠实于真实多客户端语义）。</li>
 * </ul>
 *
 * <p><b>与 virtual 档的语义差异（计划 D3）</b>：virtual 档 flap 后**快照重放**（对 SUT 透明）；
 * embedded 档 flap 后**会话与临时节点真实丢失**，任何消费方都必须自行重连并重建节点——
 * 这是真实 ZK 行为，也是 M2 要验证的 SUT 逻辑。见 {@link RegistryContract} javadoc。
 *
 * <p>节点路径与 virtual 档一致（{@code /duo/endpoints/&lt;contract&gt;}），端点写在
 * **持久节点**上由本适配器维护（门面会话持有）；SUT 侧自行注册的临时节点按真实 ZK 语义
 * 随会话消失。
 */
public final class CuratorRegistry implements VirtualComponent, RegistryContract,
        io.duo.sim.kernel.api.FaultInjectable {

    /** 端点根路径（与 VirtualRegistry 兼容）。 */
    public static final String ENDPOINTS_ROOT = "/duo/endpoints";

    private volatile ComponentId id;
    private volatile ComponentContext ctx;
    private volatile TestingServer server;
    private volatile CuratorFramework facadeClient;
    private volatile boolean running;
    /** 闪断中标志（embedded 档为瞬时动作，窗口极短）。 */
    private volatile boolean flapping;

    /** 门面会话注册表（sessionId -> 该会话创建的节点路径）。 */
    private final Map<String, List<String>> sessionPaths = new ConcurrentHashMap<>();
    /** watch 监听器（path -> listeners）。 */
    private final Map<String, List<Consumer<RegistryChange>>> watchers = new ConcurrentHashMap<>();
    /** 已注册端点快照（仅用于诊断/恢复对比；**不重放**——真实语义）。 */
    private final Map<String, String> endpoints = new ConcurrentHashMap<>();

    private final Path tempDir;

    public CuratorRegistry() {
        this(null);
    }

    /** @param tempDir TestingServer 数据目录（Windows 下建议显式指定，见计划风险 1） */
    public CuratorRegistry(Path tempDir) {
        this.tempDir = tempDir;
    }

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
    public void start() throws ComponentException {
        try {
            if (tempDir != null) {
                Files.createDirectories(tempDir);
            }
            server = specFor(tempDir);
            server.start();
            facadeClient = newFacadeClient(server.getConnectString());
            facadeClient.start();
            facadeClient.blockUntilConnected();
            ensurePath(ENDPOINTS_ROOT);
            running = true;
            fire(Event.sim("sim.registry-started", id.value(),
                    Map.of("kind", "embedded", "connectString", server.getConnectString())));
        } catch (Exception e) {
            throw new ComponentException("cannot start embedded registry (TestingServer): "
                    + e.getMessage(), e);
        }
    }

    private static CuratorFramework newFacadeClient(String connectString) {
        return CuratorFrameworkFactory.builder()
                .connectString(connectString)
                .retryPolicy(new ExponentialBackoffRetry(200, 5))
                .build();
    }

    @Override
    public void stop(StopMode mode) {
        running = false;
        closeQuietly(facadeClient);
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // 尽力而为
        }
        fire(Event.sim(mode == StopMode.CRASH ? "sim.registry-crashed" : "sim.registry-stopped",
                id.value(), Map.of("kind", "embedded")));
    }

    @Override
    public void restart() {
        stop(StopMode.GRACEFUL);
        start();
        fire(Event.sim("sim.registry-restarted", id.value(), Map.of("kind", "embedded")));
    }

    // ---- FaultInjectable（T25：registry-flap = TestingServer 整服闪断）----

    /**
     * registry-flap（embedded 档语义，计划 D2/D3）：**TestingServer 整服闪断**——
     * 所有会话失效、所有临时节点消失（真实 ZK 行为，**无快照重放**）。
     * 任何消费方（SUT 的 wire 客户端、框架组件的门面）都必须自行重连并重建节点。
     *
     * <p>实现：关闭 server（会话全部断开、数据目录临时内容按 spec.deleteDataDirectoryOnClose
     * 处理）+ 重建 server + 门面重连。端口按 spec 复用（InstanceSpec 固定则同端口；
     * 若端口漂移会记录在 {@code sim.registry-flap-started} 载荷，供诊断）。
     */
    @Override
    public void inject(io.duo.sim.kernel.api.FaultAction action) {
        if (!io.duo.sim.kernel.api.FaultAction.REGISTRY_FLAP.equals(action.type())) {
            throw new UnsupportedOperationException("unsupported fault: " + action.type());
        }
        requireRunning();
        if (flapping) {
            return; // 幂等
        }
        flapping = true;
        int oldPort = server == null ? -1 : server.getPort();
        try {
            // 1) 关停整服：会话断开、临时节点全部消失
            closeQuietly(facadeClient);
            facadeClient = null;
            if (server != null) {
                server.close();
            }
            fire(Event.sim("sim.registry-flap-started", id.value(),
                    Map.of("kind", "embedded", "oldPort", oldPort)));

            // 2) 重建整服（**复用旧端口**，D2：端口漂移会让 wire 客户端永远连不上）
            //    + 门面重连（新会话，无节点）
            server = serverOnPort(tempDir, oldPort);
            server.start();
            facadeClient = newFacadeClient(server.getConnectString());
            facadeClient.start();
            facadeClient.blockUntilConnected();
            ensurePath(ENDPOINTS_ROOT);
            flapping = false;
            fire(Event.sim("sim.registry-flap-cleared", id.value(),
                    Map.of("kind", "embedded", "newPort", server.getPort(),
                            "portStable", server.getPort() == oldPort)));
        } catch (Exception e) {
            flapping = false;
            throw new ComponentException("registry-flap (TestingServer restart) failed: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public void clear(io.duo.sim.kernel.api.FaultAction action) {
        // 闪断是瞬时动作（restart 即完成），无持续状态需清除；与 virtual 档语义不同
        // （virtual 档 flap 有持续窗口 + 快照重放）
        if (!io.duo.sim.kernel.api.FaultAction.REGISTRY_FLAP.equals(action.type())) {
            throw new UnsupportedOperationException("unsupported fault: " + action.type());
        }
    }

    /** 闪断中标志（测试用）。 */
    public boolean isFlapping() {
        return flapping;
    }

    private static TestingServer specFor(Path tempDir) throws Exception {
        return serverOnPort(tempDir, 0);
    }

    /**
     * 在指定端口创建 TestingServer（port=0 → 自动分配）。
     * flap 恢复时传入旧端口以保证 wire 客户端可重连（D2）。
     */
    private static TestingServer serverOnPort(Path tempDir, int port) throws Exception {
        var spec = new org.apache.curator.test.InstanceSpec(
                tempDir == null ? null : tempDir.toFile(),
                port, port == 0 ? 0 : port + 1, port == 0 ? 0 : port + 2, true, -1);
        return new TestingServer(spec, true);
    }

    @Override
    public HealthReport health() {
        if (!running || server == null) {
            return HealthReport.down("embedded registry not running");
        }
        try {
            // 端口可连即健康（TestingServer 无 isRunning API）
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", server.getPort()), 500);
            }
            return HealthReport.ok();
        } catch (IOException e) {
            return HealthReport.down("embedded registry port unreachable: " + e.getMessage());
        }
    }

    /** wire 面：真实 ZK 端口（供 SUT 的真实客户端连接）。 */
    @Override
    public List<ExposedEndpoint> endpoints() {
        if (server == null || !running) {
            return List.of();
        }
        return List.of(ExposedEndpoint.tcp(io.duo.sim.kernel.api.Contract.REGISTRY,
                "127.0.0.1", server.getPort()));
    }

    @Override
    public EndpointShape declaredShape() {
        return EndpointShape.THIRD_PARTY;
    }

    /** 真实 ZK 连接串（诊断/测试用）。 */
    public String connectString() {
        return server == null ? null : server.getConnectString();
    }

    /** 门面使用的 Curator 客户端（测试断言用）。 */
    public CuratorFramework facadeClient() {
        return facadeClient;
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
                throw new ComponentException("delete failed on real ZK: " + e.getMessage(), e);
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

    private void ensurePath(String path) throws Exception {
        if (facadeClient.checkExists().forPath(path) == null) {
            facadeClient.create().creatingParentsIfNeeded()
                    .withMode(CreateMode.PERSISTENT).forPath(path);
        }
    }

    private void notifyWatchers(String path, String value, RegistryChange.ChangeKind kind) {
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

    private void requireRunning() {
        if (!running) {
            throw new ComponentException("embedded registry is not running: " + id);
        }
    }

    private static void closeQuietly(CuratorFramework client) {
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // 尽力而为
            }
        }
    }

    private void fire(Event e) {
        if (ctx != null) {
            ctx.eventBus().publish(e);
        }
    }
}
