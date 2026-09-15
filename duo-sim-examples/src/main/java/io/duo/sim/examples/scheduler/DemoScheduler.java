package io.duo.sim.examples.scheduler;

import io.duo.sim.kernel.api.SutContext;
import io.duo.sim.kernel.api.SutMain;
import io.duo.sim.kernel.contract.RegistryContract;
import io.duo.sim.protocol.FrameConnection;
import io.duo.sim.protocol.message.HeartbeatReport;
import io.duo.sim.protocol.message.RegisterRequest;
import io.duo.sim.protocol.message.RegisterResponse;
import io.duo.sim.protocol.message.SlotReport;
import io.duo.sim.protocol.message.TaskAck;
import io.duo.sim.protocol.message.TaskDispatch;
import io.duo.sim.protocol.message.TaskStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * demo-scheduler（计划 T13，SUT 参考实现）：in-process SUT（实现 SutMain）。
 *
 * <p>职责：向 registry 注册自身 Duo 端点（临时节点，计划 §2 发现路径）→ 接受 worker
 * 拨号注册 → 驱动 SchedulerStateMachine（DAG + 有界重试 + 失败转移，负载均衡取 freeSlots
 * 最大的实例）→ 发布 sut.* 事实 → DAG 全部终态后 {@code run()} 返回（场景结束信号，
 * 计划 §2/§7.3）。onStop 注册协作停止。
 *
 * <p>配置项（节点 config）：{@code dag.tasks}（逗号分隔）、
 * {@code dag.deps.<task>}（逗号分隔上游，可省略）。M0 固定 5 任务演示拓扑由默认值给出。
 */
public final class DemoScheduler implements SutMain {

    /** 注册阶段（首帧）读超时：半开连接不得无限占用资源（M4 压测）。 */
    private static final int REGISTER_TIMEOUT_MS = 10_000;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket server;
    private final List<FrameConnection> workerConns = new CopyOnWriteArrayList<>();
    private final Map<String, WorkerFeed> feeds = new ConcurrentHashMap<>();
    private SchedulerStateMachine stateMachine;
    private SutContext ctx;
    /** 派发选择器（T19(a)）：freeSlots 视图 + 最大者/轮转。 */
    private final DispatchSelector selector = new DispatchSelector();
    /** registry 接入（D1：direct 门面 或 wire 真实 ZK 客户端）。 */
    private RegistryAccess registryAccess;
    /** 选主代次（每次成功注册递增；载荷进 sut.leader-elected）。 */
    private final java.util.concurrent.atomic.AtomicInteger leaderEpoch =
            new java.util.concurrent.atomic.AtomicInteger();
    /**
     * 心跳事件采样率（M4 压测 D3）：config {@code heartbeat.eventSampleRate}，缺省 1＝全量。
     * 每 N 条心跳发 1 条 sut.heartbeat 事件——万级实例 × 1s 心跳即 1 万事件/s，
     * 事件流/录制体积随事件量线性涨，聚合语义上可采样（断言库无逐心跳断言需求）。
     */
    private volatile int heartbeatEventSampleRate = 1;
    /** 收到的心跳总数（含被采样丢弃的）——吞吐 meter 的事实源。 */
    private final java.util.concurrent.atomic.AtomicLong heartbeatTotal =
            new java.util.concurrent.atomic.AtomicLong();
    /** 当前 meter 窗口内的心跳数。 */
    private final java.util.concurrent.atomic.AtomicLong heartbeatWindow =
            new java.util.concurrent.atomic.AtomicLong();
    /** 已 accept 的连接数（M4 压测诊断：区分「未拨号/被拒」与「accept 后注册卡死」）。 */
    private final java.util.concurrent.atomic.AtomicLong accepted =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile Thread heartbeatMeter;

    /** worker 连接的下行推送。 */
    private static final class WorkerFeed {
        final String name;
        final FrameConnection conn;

        WorkerFeed(String instanceName, FrameConnection conn) {
            this.name = instanceName;
            this.conn = conn;
        }
    }

    @Override
    public void run(SutContext ctx) throws Exception {
        this.ctx = ctx;
        running.set(true);

        // 1) 绑定自身 Duo 端点并注册到 registry（发现路径，§2）
        server = new ServerSocket();
        // M4 T36：accept backlog 调大（万级并发拨号下缺省 50 会拒连；SUT 自身变更，D1）
        server.bind(new InetSocketAddress("127.0.0.1", 0), 4096);
        // M2 D1：两种接入路径——direct（virtual 档门面，M0/M1 路径）或 wire（embedded 档
        // 真实 ZK，SUT 代码切 ZK 客户端）。按 wiring 配置与上下文自动选择。
        registryAccess = resolveRegistryAccess();
        registryAccess.register(server.getLocalPort());
        ctx.ready(); // 就绪：端点已可被发现

        // 2) DAG（config 可覆盖；M0 默认 5 任务演示拓扑，含不稳定任务）
        Map<String, List<String>> dag = readDag(ctx.config());
        if (dag.isEmpty()) {
            throw new IllegalStateException("demo-scheduler: empty DAG (check dag.tasks)");
        }
        stateMachine = new SchedulerStateMachine(dag, new StateListener());
        heartbeatEventSampleRate = Integer.parseInt(ctx.config().getOrDefault(
                "heartbeat.eventSampleRate", "1"));
        ctx.events().publish("sut.scheduler-started",
                Map.of("tasks", String.join(",", stateMachine.topoTasks())));

        // 3) accept worker 拨号
        Thread acceptor = Thread.ofVirtual().name("demo-scheduler-accept").start(this::acceptLoop);
        startHeartbeatMeter();
        try {
            // 4) 等全部任务终态（状态机驱动；run() 返回即 sut.exited）
            while (running.get() && !stateMachine.allTerminal()) {
                pumpDispatches();
                Thread.sleep(50);
            }
            ctx.events().publish("sut.dag-terminal", Map.of());
        } finally {
            running.set(false);
            acceptor.interrupt();
            stopHeartbeatMeter();
            closeAll();
        }
    }

    /**
     * 心跳吞吐 meter（M4 T36）：每 5s 发布一条 {@code sut.heartbeat-meter} 事件
     * （窗口心跳数 / 速率 / 累计）——压测吞吐的真实事实源（不随采样率失真，D3 配套）。
     */
    private void startHeartbeatMeter() {
        heartbeatMeter = Thread.ofVirtual().name("demo-scheduler-hb-meter").start(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long window = heartbeatWindow.getAndSet(0);
                long total = heartbeatTotal.get();
                if (window > 0 || total > 0) {
                    ctx.events().publish("sut.heartbeat-meter", Map.of(
                            "windowHeartbeats", window,
                            "ratePerSec", window / 5.0,
                            "total", total,
                            "accepted", accepted.get(),
                            "feeds", feeds.size()));
                }
            }
        });
    }

    private void stopHeartbeatMeter() {
        Thread m = heartbeatMeter;
        if (m != null) {
            m.interrupt();
        }
    }

    private void acceptLoop() {
        try {
            while (running.get()) {
                Socket s = server.accept();
                accepted.incrementAndGet();
                // 每连接独立虚拟线程（M4 压测修复）：注册/读取的任何失败只影响该连接，
                // 不拖垮 accept 环——旧实现 register 在 accept 线程内串行执行且只捕
                // IOException，一个坏连接即可让整个 acceptor 静默死亡（万级爬坡下
                // ~800 条已建立连接永远无人 accept，客户端无超时则永久挂起、无离线事件）
                Thread.ofVirtual().name("demo-scheduler-conn").start(() -> handleConnection(s));
            }
        } catch (IOException e) {
            // server 关闭
        }
    }

    /** 单连接处理：注册（限时）→ 稳态读取。注册阶段失败仅丢弃该连接（§12 不静默）。 */
    private void handleConnection(Socket s) {
        WorkerFeed feed = null;
        try {
            s.setSoTimeout(REGISTER_TIMEOUT_MS); // 首帧/注册限时：半开连接不阻塞资源
            feed = register(s);
            s.setSoTimeout(0); // 稳态读可无限期等待下行
            readFeed(feed);
        } catch (IOException | RuntimeException e) {
            // 注册阶段失败（feed==null）：仅丢弃该连接并记一级事件；
            // 稳态 IOException 已在 readFeed 内处理（onFeedLost）；
            // 稳态 RuntimeException（状态机异常等）→ 防御性按失联收敛
            if (feed == null) {
                ctx.events().publish("sut.register-failed", Map.of(
                        "error", String.valueOf(e.getMessage())));
            } else if (running.get()) {
                onFeedLost(feed);
            }
            try {
                s.close();
            } catch (IOException ignored) {
                // 尽力而为
            }
        }
    }

    private WorkerFeed register(Socket s) throws IOException {
        var conn = new FrameConnection(s);
        var first = conn.read();
        if (!(first instanceof RegisterRequest req)) {
            conn.write(new RegisterResponse(false, "first frame must be register"));
            conn.close();
            throw new IOException("bad first frame");
        }
        conn.write(new RegisterResponse(true, null));
        var feed = new WorkerFeed(req.instanceName(), conn);
        workerConns.add(conn);
        feeds.put(req.instanceName(), feed);
        selector.register(req.instanceName());
        ctx.events().publish("sut.worker-registered",
                Map.of("instance", req.instanceName()));
        return feed;
    }

    private void readFeed(WorkerFeed feed) {
        try {
            while (running.get()) {
                var msg = feed.conn.read();
                if (msg instanceof TaskStatus ts) {
                    // T19(c)：线协议 TaskStatus 自带 instanceName——透传给状态事件（断言数据契约）
                    stateMachine.onStatus(ts.taskId(), ts.state(), ts.detail(),
                            ts.instanceName());
                } else if (msg instanceof HeartbeatReport hb) {
                    long n = heartbeatTotal.incrementAndGet();
                    heartbeatWindow.incrementAndGet();
                    if (n % heartbeatEventSampleRate == 0) {
                        ctx.events().publish("sut.heartbeat",
                                Map.of("instance", hb.instanceName()));
                    }
                } else if (msg instanceof SlotReport sr) {
                    // T19(a)：维护调度侧槽位视图（此前被静默丢弃）
                    if (feed.name.equals(sr.instanceName())) {
                        selector.onSlotReport(feed.name, sr.freeSlots());
                    }
                } else if (msg instanceof TaskAck) {
                    // 受理即视为在途，无需额外动作
                }
            }
        } catch (IOException e) {
            onFeedLost(feed);
        }
    }

    /**
     * 连接丢失 → 崩溃转移（T19(b)）：从 feeds 移除 + 其在途任务重置重派发。
     * 检测可靠：worker 实例崩溃时 closeQuietly 关 socket，此处阻塞读即刻 IOException。
     */
    private void onFeedLost(WorkerFeed feed) {
        feeds.remove(feed.name);
        workerConns.remove(feed.conn);
        selector.onRemoved(feed.name);
        if (stateMachine == null) {
            return;
        }
        List<String> requeued = stateMachine.onInstanceLost(feed.name, "connection lost");
        if (!requeued.isEmpty()) {
            ctx.events().publish("sut.instance-lost",
                    Map.of("instance", feed.name, "requeued", String.join(",", requeued)));
        } else {
            ctx.events().publish("sut.instance-lost", Map.of("instance", feed.name));
        }
    }

    /**
     * 派发选择（T19(a)）：freeSlots &gt; 0 且最大者；平局按 dispatchCursor 轮转
     * （消除"永远落到迭代序第一个 feed"的旧缺陷）。无可用实例则跳过本轮。
     */
    private void pumpDispatches() {
        for (String task : stateMachine.dispatchable()) {
            String target = selector.select();
            if (target == null) {
                return; // 本轮无可用 worker（slots 耗尽或未上报槽位）
            }
            WorkerFeed best = feeds.get(target);
            if (best == null) {
                selector.onRemoved(target);
                continue;
            }
            try {
                int attempt = stateMachine.dispatch(task, best.name);
                selector.onDispatched(best.name); // 本地递减；SlotReport 到达时权威刷新
                best.conn.write(new TaskDispatch(task, task, attempt, 1, 1));
                // 注意：sut.task-dispatched 由 StateListener.onDispatch 单一来源发布（T19(c) 去重）
            } catch (IOException e) {
                onFeedLost(best);
            }
        }
    }

    /**
     * registry 接入解析（M2 D1）：
     * <ul>
     *   <li>{@code registry.mode=zk}（或 config 给出 {@code registry.connectString}）→
     *       **wire 路径**：用真实 Curator 客户端连 embedded registry 的 ZK 端口，
     *       注册临时节点 + 监听连接状态，断线重连后**重新注册**并发布
     *       {@code sut.leader-elected}（这正是 M2 要验证的 SUT 自愈逻辑）；</li>
     *   <li>否则 → **direct 路径**：用注入的 {@link RegistryContract} 门面（M0/M1 路径）。</li>
     * </ul>
     */
    private RegistryAccess resolveRegistryAccess() {
        String connectString = ctx.config().get("registry.connectString");
        if (connectString == null) {
            connectString = ctx.endpointByContract().get("registry");
        }
        String mode = ctx.config().getOrDefault("registry.mode",
                connectString == null ? "direct" : "zk");
        if ("zk".equals(mode) && connectString != null) {
            return new ZkRegistryAccess(connectString);
        }
        RegistryContract facade = ctx.direct(io.duo.sim.kernel.api.Contract.REGISTRY,
                RegistryContract.class)
                .orElseThrow(() -> new IllegalStateException(
                        "demo-scheduler requires registry access: neither wire connectString "
                                + "nor direct facade available"));
        return new DirectRegistryAccess(facade);
    }

    /** registry 接入抽象（D1 双路径）。 */
    private interface RegistryAccess {
        void register(int duoPort);
    }

    /** direct 路径：注入门面（virtual 档；M0/M1 行为保持）。 */
    private final class DirectRegistryAccess implements RegistryAccess {
        private final RegistryContract facade;

        DirectRegistryAccess(RegistryContract facade) {
            this.facade = facade;
        }

        @Override
        public void register(int duoPort) {
            facade.registerEndpoint("scheduler", "127.0.0.1:" + duoPort);
            ctx.events().publish("sut.leader-elected",
                    Map.of("epoch", leaderEpoch.incrementAndGet(),
                            "endpoint", "127.0.0.1:" + duoPort, "mode", "direct"));
        }
    }

    /**
     * wire 路径（M2 D1）：真实 Curator 客户端。连接状态监听：LOST → 会话失效；
     * RECONNECTED → **重新注册端点**（临时节点随会话消失，真实 ZK 语义）+ 发布
     * {@code sut.leader-elected}（epoch 递增）——"重新选主"事实。
     */
    private final class ZkRegistryAccess implements RegistryAccess {
        private final org.apache.curator.framework.CuratorFramework client;
        private final String connectString;
        private volatile int duoPort;

        ZkRegistryAccess(String connectString) {
            this.connectString = connectString;
            this.client = org.apache.curator.framework.CuratorFrameworkFactory.builder()
                    .connectString(connectString)
                    .retryPolicy(new org.apache.curator.retry.ExponentialBackoffRetry(200, 10))
                    // 缩短会话超时：闪断后更快判定会话失效并重连（默认 60s 会让
                    // 恢复窗口远超 DAG 时长）
                    .sessionTimeoutMs(3000)
                    .connectionTimeoutMs(2000)
                    .build();
        }

        /** 自愈巡检（真实 SUT 的常见做法）：周期校验端点节点是否存在，缺失即重注册。 */
        private volatile Thread selfHeal;

        @Override
        public void register(int port) {
            this.duoPort = port;
            client.getConnectionStateListenable().addListener((c, state) -> {
                if (state == org.apache.curator.framework.state.ConnectionState.RECONNECTED
                        || state == org.apache.curator.framework.state.ConnectionState.CONNECTED) {
                    ensureEndpointNode("state=" + state.name());
                }
            });
            client.start();
            try {
                if (!client.blockUntilConnected(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("ZK client cannot connect: " + connectString);
                }
                writeEndpointNode();
                ctx.events().publish("sut.leader-elected",
                        Map.of("epoch", leaderEpoch.incrementAndGet(),
                                "endpoint", "127.0.0.1:" + duoPort, "mode", "zk"));
                // 自愈巡检：闪断（整服重启）后临时节点消失、且 Curator 可能不重发
                // RECONNECTED（同端口重建时会话判定可能保持）——周期校验保证"重新选主"
                startSelfHeal();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ZK connect interrupted", e);
            } catch (Exception e) {
                throw new IllegalStateException("ZK registration failed: " + e.getMessage(), e);
            }
        }

        private void startSelfHeal() {
            selfHeal = Thread.ofVirtual().name("demo-scheduler-zk-selfheal").start(() -> {
                while (running.get()) {
                    try {
                        Thread.sleep(300);
                        // 不预判连接状态：直接尝试（连接未恢复时操作抛异常，下轮重试）——
                        // 闪断后 Curator 的连接状态判定可能滞后，预判会错过恢复窗口
                        ensureEndpointNode("self-heal");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (RuntimeException ignored) {
                        // 连接暂不可用：下轮再试
                    }
                }
            });
        }

        /** 确保端点节点存在（缺失即创建/刷新并发布 leader-elected）。 */
        private void ensureEndpointNode(String reason) {
            try {
                String path = "/duo/endpoints/scheduler";
                boolean missing = client.checkExists().forPath(path) == null;
                writeEndpointNode();
                if (missing) {
                    ctx.events().publish("sut.leader-elected",
                            Map.of("epoch", leaderEpoch.incrementAndGet(),
                                    "endpoint", "127.0.0.1:" + duoPort,
                                    "mode", "zk", "reason", reason));
                }
            } catch (Exception e) {
                ctx.events().publish("sut.leader-election-failed",
                        Map.of("error", String.valueOf(e.getMessage()), "reason", reason));
            }
        }

        private void writeEndpointNode() throws Exception {
            String path = "/duo/endpoints/scheduler";
            if (client.checkExists().forPath("/duo/endpoints") == null) {
                client.create().creatingParentsIfNeeded().forPath("/duo/endpoints");
            }
            byte[] data = ("127.0.0.1:" + duoPort)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // checkExists→create 之间存在竞态（另一路径可能刚创建）：
            // 按真实 ZK 惯用法捕获 NodeExists 后改走 setData
            try {
                client.create().forPath(path, data);
            } catch (org.apache.zookeeper.KeeperException.NodeExistsException e) {
                client.setData().forPath(path, data);
            }
        }

        void close() {
            if (selfHeal != null) {
                selfHeal.interrupt();
            }
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // 尽力而为
            }
        }
    }

    private Map<String, List<String>> readDag(Map<String, String> config) {
        String tasksCfg = config.getOrDefault("dag.tasks",
                "load-orders,clean-orders,build-features,unstable-task,aggregate-report");
        List<String> tasks = List.of(tasksCfg.split(","));
        Map<String, List<String>> dag = new HashMap<>();
        for (String t : tasks) {
            String deps = config.get("dag.deps." + t.trim());
            dag.put(t.trim(), deps == null || deps.isBlank() ? List.of()
                    : List.of(deps.split(",")));
        }
        return dag;
    }

    /** 状态机事实 → sut.* 事件（§7.3；T19(c) 状态/终态事件带 instance 归属）。 */
    private final class StateListener implements SchedulerStateMachine.Listener {

        @Override
        public void onDispatch(String taskId, String taskName, int attempt, String instance) {
            ctx.events().publish("sut.task-dispatched",
                    Map.of("taskId", taskId, "attempt", attempt, "instance", instance));
        }

        @Override
        public void onStatus(String taskId, String state, String detail, int attempt,
                             String instance) {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("taskId", taskId);
            payload.put("state", state);
            payload.put("attempt", attempt);
            if (instance != null) {
                payload.put("instance", instance); // T19(c)：断言归属核对的数据契约
            }
            ctx.events().publish("sut.task-status", Map.copyOf(payload));
            if (!"RETRYING".equals(state)) {
                ctx.events().publish("sut.task-terminal", Map.copyOf(payload));
            }
        }

        @Override
        public void onRetry(String taskId, int nextAttempt) {
            ctx.events().publish("sut.task-retry",
                    Map.of("taskId", taskId, "nextAttempt", nextAttempt));
        }

        @Override
        public void onFailover(String taskId, String fromInstance) {
            ctx.events().publish("sut.failover",
                    Map.of("taskId", taskId, "from", fromInstance));
        }

        @Override
        public void onAllTerminal() {
            ctx.events().publish("sut.dag-terminal", Map.of());
        }
    }

    private void closeAll() {
        if (registryAccess instanceof ZkRegistryAccess zk) {
            zk.close();
        }
        for (var c : workerConns) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
        workerConns.clear();
        if (server != null && !server.isClosed()) {
            try {
                server.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
