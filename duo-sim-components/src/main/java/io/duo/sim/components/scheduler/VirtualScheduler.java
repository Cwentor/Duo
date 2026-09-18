package io.duo.sim.components.scheduler;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.FaultInjectable;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.contract.SchedulerContract;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * scheduler 契约 virtual 档（M5 交付物 1）：**虚拟调度桩**——不写用户代码即可扮演调度侧，
 * 从而让 SUT 落在 worker 侧（real 档 worker 或第三方 worker），这是 real 档
 * {@code DemoScheduler} 无法提供的组合（real 档的 SUT 就是调度器本身）。
 *
 * <p>连接模型与 real 档**完全一致**（计划 §2）：绑定自身 DUO_PORT → 经 direct registry
 * 把端点写进 {@code /duo/endpoints/scheduler} → 接受 worker 拨号注册 → 按 freeSlots 派发
 * → 收状态回报。**调度语义与 real 档共用同一份 {@link SchedulerStateMachine} 与
 * {@link DispatchSelector}**（M5 起下沉到本模块），故 DAG 依赖、有界重试、失败转移、
 * G9「显式拒绝 → 回滚尝试重派 + 拒绝上限兜底」在两档之间不可能分叉。
 *
 * <p>事实面（tier parity，档位可换而断言不改）：
 * <ul>
 *   <li>{@code sut.*}＝**SUT 事实**——本桩是 SUT 的替身，故按 real 档同一口径发
 *       {@code sut.scheduler-started}、{@code sut.worker-registered}、{@code sut.heartbeat}、
 *       {@code sut.instance-lost}、{@code sut.task-dispatched/-status/-terminal/-retry}、
 *       {@code sut.task-rejected}、{@code sut.failover}、{@code sut.dag-terminal}；</li>
 *   <li>{@code sim.scheduler-*}＝**框架/组件事实**——started/stopped/crashed/restarted、
 *       register-failed、frozen/resumed。</li>
 * </ul>
 *
 * <p>配置项（节点 config）：{@code dag.tasks}（逗号分隔，缺省与 real 档同一套 5 任务演示拓扑）、
 * {@code dag.deps.<task>}（逗号分隔上游）、{@code heartbeat.eventSampleRate}（缺省 1）。
 *
 * <p>故障注入：{@code freeze}（派发停摆：不再派发新任务，但**保持**连接与状态回报读取，
 * 故在途任务仍可终态收敛——区别于 worker 的 freeze 是「心跳停 + 终态回报挂起」）；
 * 其余动作显式拒绝（§7.2 无降级）。
 *
 * <p>前置条件：**必须**有 registry 的 direct 绑定（否则 worker 无法发现本端点）——
 * 缺失即启动失败并说明原因（§12 不静默），不做「起了但没人找得到」的假成功。
 */
public final class VirtualScheduler implements VirtualComponent, SchedulerContract,
        FaultInjectable {

    /** 首帧/注册限时（半开连接不占资源）。 */
    private static final int REGISTER_TIMEOUT_MS = 10_000;
    /** 派发轮询间隔：与 real 档一致（50ms 级），保证档位互换后的时序量级相同。 */
    private static final long PUMP_INTERVAL_MS = 20;
    /** accept backlog：与 real 档一致（万级并发拨号下缺省 50 会拒连）。 */
    private static final int ACCEPT_BACKLOG = 4096;
    /** 缺省 DAG：与 real 档 {@code DemoScheduler} 同一套演示拓扑（档位互换时配置零改动）。 */
    private static final String DEFAULT_DAG =
            "load-orders,clean-orders,build-features,unstable-task,aggregate-report";

    private volatile ComponentId id;
    private volatile ComponentContext ctx;
    private volatile ServerSocket server;
    private volatile SchedulerStateMachine stateMachine;
    private volatile int heartbeatSampleRate = 1;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean frozen = new AtomicBoolean(false);
    private final AtomicBoolean dagTerminalFired = new AtomicBoolean(false);
    private final AtomicLong heartbeatCount = new AtomicLong();
    private final Map<String, WorkerFeed> feeds = new ConcurrentHashMap<>();
    private final List<FrameConnection> conns = new CopyOnWriteArrayList<>();
    private final DispatchSelector selector = new DispatchSelector();
    private volatile Thread acceptor;
    private volatile Thread pump;

    /** 单条 worker 连接（注册成功后建立）。 */
    private static final class WorkerFeed {
        final String name;
        final FrameConnection conn;

        WorkerFeed(String name, FrameConnection conn) {
            this.name = name;
            this.conn = conn;
        }
    }

    // ---- VirtualComponent ----

    @Override
    public ComponentId id() {
        return id;
    }

    @Override
    public void init(ComponentContext ctx) {
        this.id = ctx.id();
        this.ctx = ctx;
        this.heartbeatSampleRate = Integer.parseInt(ctx.config().getOrDefault(
                "heartbeat.eventSampleRate", "1"));
    }

    @Override
    public void start() throws ComponentException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        var registry = ctx.directRegistry().orElseThrow(() -> new ComponentException(
                "virtual scheduler requires direct registry binding to publish its endpoint"
                        + " (workers discover it there): " + id));
        try {
            server = new ServerSocket();
            server.bind(new InetSocketAddress("127.0.0.1", 0), ACCEPT_BACKLOG);
        } catch (IOException e) {
            running.set(false);
            throw new ComponentException("bind scheduler port failed: " + id, e);
        }
        int port = server.getLocalPort();
        registry.registerEndpoint("scheduler", "127.0.0.1:" + port);

        stateMachine = new SchedulerStateMachine(readDag(ctx.config()), new StateListener());
        dagTerminalFired.set(false);
        acceptor = Thread.ofVirtual().name(id.value() + "-accept").start(this::acceptLoop);
        pump = Thread.ofVirtual().name(id.value() + "-pump").start(this::pumpLoop);
        fire(Event.sut("sut.scheduler-started", id.value(),
                Map.of("tasks", String.join(",", stateMachine.topoTasks()))));
        fire(Event.sim("sim.scheduler-started", id.value(),
                Map.of("tasks", stateMachine.topoTasks().size(), "endpoint", "127.0.0.1:" + port)));
    }

    @Override
    public void stop(StopMode mode) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        closeAll();
        unregisterEndpoint();
        fire(Event.sim(mode == StopMode.CRASH ? "sim.scheduler-crashed"
                : "sim.scheduler-stopped", id.value(), Map.of("mode", mode.name())));
    }

    /**
     * 撤下 registry 里的 scheduler 端点节点：否则 worker 的发现查询会一直返回**死地址**，
     * 表现为「注册中心说调度器在，但连不上」——真实系统里这是陈旧注册的经典故障，模拟中
     * 属于噪声。失败不静默：发 {@code sim.scheduler-unregister-failed} 事实。
     *
     * <p>已知边界（如实记录）：{@code VirtualRegistry} 的端点**快照**不随之删除，
     * 若之后发生 {@code registry-flap}，快照重放会把该端点复活。需要彻底清理时应在场景中
     * 先停 registry 或改用带会话生命周期的 embedded 档。
     */
    private void unregisterEndpoint() {
        var registry = ctx == null ? null : ctx.directRegistry().orElse(null);
        if (registry == null) {
            return;
        }
        try {
            var session = registry.openSession(id.value() + "-unregister");
            session.delete("/duo/endpoints/scheduler");
            session.close();
        } catch (RuntimeException e) {
            fire(Event.sim("sim.scheduler-unregister-failed", id.value(),
                    Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    @Override
    public void restart() {
        // §7.1：身份保留、内部状态视为全新实例。端点端口重绑（随机端口）→ 重新注册，
        // 使 worker 的发现路径仍指向本实例的新端点。
        stop(StopMode.GRACEFUL);
        feeds.clear();
        heartbeatCount.set(0);
        frozen.set(false);
        start();
        fire(Event.sim("sim.scheduler-restarted", id.value(), Map.of()));
    }

    @Override
    public HealthReport health() {
        return running.get() && server != null && !server.isClosed()
                ? HealthReport.ok() : HealthReport.down("scheduler not running");
    }

    @Override
    public List<ExposedEndpoint> endpoints() {
        try {
            return server != null && server.isBound()
                    ? List.of(ExposedEndpoint.tcp(Contract.SCHEDULER, "127.0.0.1",
                            server.getLocalPort()))
                    : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    // ---- SchedulerContract（可观测点：在途任务数 / 各任务尝试次数）----

    @Override
    public int inFlightTasks() {
        SchedulerStateMachine sm = stateMachine;
        if (sm == null) {
            return 0;
        }
        int n = 0;
        for (String task : sm.topoTasks()) {
            if ("RUNNING".equals(sm.phaseOf(task))) {
                n++;
            }
        }
        return n;
    }

    @Override
    public int attemptCount(String taskId) {
        SchedulerStateMachine sm = stateMachine;
        return sm == null ? 0 : sm.attemptsOf(taskId);
    }

    @Override
    public EndpointShape declaredShape() {
        return EndpointShape.DUO_PORT;
    }

    // ---- FaultInjectable（M5-3：freeze＝派发停摆）----

    @Override
    public void inject(FaultAction action) {
        if (!FaultAction.FREEZE.equals(action.type())) {
            throw new UnsupportedOperationException("unsupported fault: " + action.type());
        }
        if (!frozen.compareAndSet(false, true)) {
            return; // 已在冻结中（幂等）
        }
        fire(Event.sim("sim.scheduler-frozen", id.value(), Map.of()));
    }

    @Override
    public void clear(FaultAction action) {
        if (!FaultAction.FREEZE.equals(action.type())) {
            throw new UnsupportedOperationException("unsupported fault: " + action.type());
        }
        if (!frozen.compareAndSet(true, false)) {
            return; // 未冻结（幂等）
        }
        fire(Event.sim("sim.scheduler-resumed", id.value(), Map.of()));
    }

    /** 是否处于派发停摆（测试/诊断用）。 */
    public boolean isFrozen() {
        return frozen.get();
    }

    // ---- 内部：accept / 读连接 ----

    private void acceptLoop() {
        try {
            while (running.get()) {
                Socket s = server.accept();
                // 每连接独立虚拟线程：注册/读取失败只影响该连接，不拖垮 accept 环
                Thread.ofVirtual().name(id.value() + "-conn").start(() -> handleConnection(s));
            }
        } catch (IOException e) {
            // server 关闭（停止/重启）
        }
    }

    private void handleConnection(Socket s) {
        WorkerFeed feed = null;
        try {
            s.setSoTimeout(REGISTER_TIMEOUT_MS);
            feed = register(s);
            s.setSoTimeout(0);
            readFeed(feed);
        } catch (IOException | RuntimeException e) {
            if (feed == null) {
                fire(Event.sut("sut.register-failed", id.value(),
                        Map.of("error", String.valueOf(e.getMessage()))));
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
        FrameConnection conn = new FrameConnection(s);
        var first = conn.read();
        if (!(first instanceof RegisterRequest req)) {
            conn.write(new RegisterResponse(false, "first frame must be register"));
            conn.close();
            throw new IOException("bad first frame");
        }
        conn.write(new RegisterResponse(true, null));
        WorkerFeed feed = new WorkerFeed(req.instanceName(), conn);
        conns.add(conn);
        feeds.put(req.instanceName(), feed);
        selector.register(req.instanceName());
        fire(Event.sut("sut.worker-registered", id.value(),
                Map.of("instance", req.instanceName())));
        return feed;
    }

    private void readFeed(WorkerFeed feed) {
        try {
            while (running.get()) {
                var msg = feed.conn.read();
                if (msg instanceof TaskStatus ts) {
                    // 线协议 TaskStatus 自带 instanceName：透传给状态事件（断言数据契约）
                    stateMachine.onStatus(ts.taskId(), ts.state(), ts.detail(),
                            ts.instanceName());
                } else if (msg instanceof HeartbeatReport hb) {
                    long n = heartbeatCount.incrementAndGet();
                    if (heartbeatSampleRate > 0 && n % heartbeatSampleRate == 0) {
                        fire(Event.sut("sut.heartbeat", id.value(),
                                Map.of("instance", hb.instanceName())));
                    }
                } else if (msg instanceof SlotReport sr) {
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
     * 连接丢失 → 崩溃转移：从 feeds 移除 + 其在途任务重置重派发（受重试上限约束）。
     * 检测可靠：worker 实例崩溃即关 socket，此处阻塞读立刻 IOException。
     */
    private void onFeedLost(WorkerFeed feed) {
        feeds.remove(feed.name);
        conns.remove(feed.conn);
        selector.onRemoved(feed.name);
        SchedulerStateMachine sm = stateMachine;
        if (sm == null) {
            return;
        }
        List<String> requeued = sm.onInstanceLost(feed.name, "connection lost");
        Map<String, Object> payload = new HashMap<>();
        payload.put("instance", feed.name);
        if (!requeued.isEmpty()) {
            payload.put("requeued", String.join(",", requeued));
        }
        fire(Event.sut("sut.instance-lost", id.value(), Map.copyOf(payload)));
        maybeFireDagTerminal();
    }

    /** 派发轮询：可派发任务 → 选实例（freeSlots 最大、平局轮转）→ 发 TaskDispatch。 */
    private void pumpLoop() {
        while (running.get()) {
            try {
                Thread.sleep(PUMP_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (frozen.get()) {
                continue; // freeze：派发停摆（在途任务仍按状态回报收敛）
            }
            SchedulerStateMachine sm = stateMachine;
            if (sm == null) {
                continue;
            }
            for (String task : sm.dispatchable()) {
                String target = selector.select();
                if (target == null) {
                    break; // 本轮无可用 worker（槽位耗尽或未上报槽位）
                }
                WorkerFeed feed = feeds.get(target);
                if (feed == null) {
                    selector.onRemoved(target);
                    continue;
                }
                try {
                    int attempt = sm.dispatch(task, feed.name);
                    selector.onDispatched(feed.name); // 本地递减；SlotReport 到达时权威刷新
                    feed.conn.write(new TaskDispatch(task, task, attempt, 1, 1));
                    // sut.task-dispatched 由 StateListener.onDispatch 单一来源发布（去重）
                } catch (IOException e) {
                    onFeedLost(feed);
                }
            }
            maybeFireDagTerminal();
        }
    }

    private void maybeFireDagTerminal() {
        SchedulerStateMachine sm = stateMachine;
        if (sm != null && sm.allTerminal() && dagTerminalFired.compareAndSet(false, true)) {
            fire(Event.sut("sut.dag-terminal", id.value(), Map.of()));
        }
    }

    private static Map<String, List<String>> readDag(Map<String, String> config) {
        String tasksCfg = config.getOrDefault("dag.tasks", DEFAULT_DAG);
        Map<String, List<String>> dag = new HashMap<>();
        for (String t : tasksCfg.split(",")) {
            String name = t.trim();
            if (name.isEmpty()) {
                continue;
            }
            String deps = config.get("dag.deps." + name);
            dag.put(name, deps == null || deps.isBlank()
                    ? List.of() : List.of(deps.split(",")));
        }
        if (dag.isEmpty()) {
            throw new IllegalArgumentException("empty DAG (check dag.tasks)");
        }
        return dag;
    }

    private void closeAll() {
        for (FrameConnection c : conns) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
        conns.clear();
        feeds.clear();
        Thread a = acceptor;
        if (a != null) {
            a.interrupt();
        }
        Thread p = pump;
        if (p != null) {
            p.interrupt();
        }
        if (server != null && !server.isClosed()) {
            try {
                server.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
    }

    private void fire(Event e) {
        if (ctx != null) {
            ctx.eventBus().publish(e);
        }
    }

    /** 状态机事实 → sut.* 事件（与 real 档同一口径；档位互换时断言不变）。 */
    private final class StateListener implements SchedulerStateMachine.Listener {

        @Override
        public void onDispatch(String taskId, String taskName, int attempt, String instance) {
            fire(Event.sut("sut.task-dispatched", id.value(),
                    Map.of("taskId", taskId, "attempt", attempt, "instance", instance)));
        }

        @Override
        public void onStatus(String taskId, String state, String detail, int attempt,
                             String instance) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("taskId", taskId);
            payload.put("state", state);
            payload.put("attempt", attempt);
            if (instance != null) {
                payload.put("instance", instance);
            }
            fire(Event.sut("sut.task-status", id.value(), Map.copyOf(payload)));
            if (!"RETRYING".equals(state)) {
                fire(Event.sut("sut.task-terminal", id.value(), Map.copyOf(payload)));
            }
        }

        @Override
        public void onRetry(String taskId, int nextAttempt) {
            fire(Event.sut("sut.task-retry", id.value(),
                    Map.of("taskId", taskId, "nextAttempt", nextAttempt)));
        }

        @Override
        public void onFailover(String taskId, String fromInstance) {
            fire(Event.sut("sut.failover", id.value(),
                    Map.of("taskId", taskId, "from", fromInstance)));
        }

        @Override
        public void onRejected(String taskId, String instanceName, String reason, int rejections) {
            fire(Event.sut("sut.task-rejected", id.value(),
                    Map.of("taskId", taskId, "instance", instanceName,
                            "reason", reason, "rejections", rejections)));
        }

        @Override
        public void onAllTerminal() {
            maybeFireDagTerminal();
        }
    }

    /** 当前已注册实例名（诊断/测试用）。 */
    public List<String> registeredInstances() {
        return new ArrayList<>(feeds.keySet());
    }
}
