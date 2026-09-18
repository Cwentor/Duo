package io.duo.sim.components.worker;

import io.duo.sim.components.provider.BehaviorResolver;
import io.duo.sim.components.taskstub.BehaviorProfile;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.FaultInjectable;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.InstanceControl;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.contract.WorkerContract;
import io.duo.sim.protocol.DuoCodec;
import io.duo.sim.protocol.DuoMessage;
import io.duo.sim.protocol.FrameConnection;
import io.duo.sim.protocol.message.HeartbeatReport;
import io.duo.sim.protocol.message.RegisterRequest;
import io.duo.sim.protocol.message.RegisterResponse;
import io.duo.sim.protocol.message.SlotReport;
import io.duo.sim.protocol.message.TaskAck;
import io.duo.sim.protocol.message.TaskCancel;
import io.duo.sim.protocol.message.TaskDispatch;
import io.duo.sim.protocol.message.TaskStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * worker 契约 virtual 档（设计文档 §9/计划 T8）：每个逻辑实例一个虚拟线程，
 * 心跳 + 槽位上报 + 领取/执行任务（TaskStub 行为模型）+ 状态回报。
 *
 * <p>连接模型（计划 §2）：拨号 master（wire 槽 "scheduler"），每实例一条双向长连接；
 * 发现等待语义：查询 registry（direct 槽）未发现时重试 500ms×20，超限启动失败。
 * 自身 DUO_PORT 仅监听（就绪探测）。实现 InstanceControl（实例级 stop/restart）。
 */
public final class VirtualWorker implements VirtualComponent, WorkerContract,
        InstanceControl, FaultInjectable {

    private static final long DISCOVER_INTERVAL_MS = 500;
    private static final int DISCOVER_MAX_TRIES = 20;
    /** 心跳周期缺省值；可用 config {@code heartbeat.interval.ms} 覆盖（M4 压测：万级实例下调大）。 */
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 100;
    /** 连接重试（M4 压测 T36）：万级并发拨号下 accept 队列可能瞬时满，退避重试防误判离线。 */
    private static final int CONNECT_MAX_TRIES = 3;
    private static final long CONNECT_RETRY_BACKOFF_MS = 250;

    private volatile ComponentId id;
    private volatile ComponentContext ctx;
    private volatile int count = 1;
    private volatile int cpuPerInstance = 4;
    private volatile int memGbPerInstance = 8;
    private volatile int totalSlots = 4;
    private volatile long heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS;
    private volatile BehaviorResolver behaviors;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean frozen = new AtomicBoolean(false);
    /** slow（M5-3）：置位后执行时长乘以 {@link #slowFactor}。 */
    private final AtomicBoolean slowActive = new AtomicBoolean(false);
    /** resource-exhaust（M5-3）：置位后对外报 0 空闲槽位并**显式拒绝**全部新派发。 */
    private final AtomicBoolean resourceExhausted = new AtomicBoolean(false);
    private volatile double slowFactor = 3.0;
    /** 当前生效的 slow 倍数（注入时按 params.factor / config slow.factor 解析）。 */
    private volatile double activeSlowFactor = 3.0;
    private final Map<Integer, InstanceState> instances = new ConcurrentHashMap<>();
    private final AtomicLong heartbeatSeq = new AtomicLong();
    private volatile ServerSocket readinessSocket;

    /** 单实例运行态。 */
    static final class InstanceState {
        final int index;
        final String name;
        final AtomicBoolean alive = new AtomicBoolean(false);
        /** 生命周期代次：stop/restart 递增，readDownstream 据此退出旧连接。 */
        final AtomicLong generation = new AtomicLong();
        /**
         * 空闲槽位（G9 加固）：读线程受理派发时递减、任务线程结束时递增——**两条线程**，
         * 故必须是原子计数。旧的 {@code volatile int} 自减/自增会丢更新（完成与受理同时发生），
         * 槽位永久泄漏 → 实例被误判为长期满载，派发被静默丢弃（M7 CI 首跑挂起的候选根因之一）。
         */
        final AtomicInteger freeSlots = new AtomicInteger();
        volatile Thread thread;
        volatile FrameConnection connection;
        volatile Thread readerThread;
        final List<TaskExecution> running = new CopyOnWriteArrayList<>();

        InstanceState(int index, String name, int slots) {
            this.index = index;
            this.name = name;
            this.freeSlots.set(slots);
        }
    }

    static final class TaskExecution {
        final String taskId;
        volatile boolean cancelled;
        /** task-kill（T18）：终止执行线程并立即回报 CANCELLED。 */
        volatile Thread worker;

        TaskExecution(String taskId) {
            this.taskId = taskId;
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
        this.count = Integer.parseInt(ctx.config().getOrDefault("count", "1"));
        this.cpuPerInstance = Integer.parseInt(
                ctx.config().getOrDefault("capacity.cpu", "4"));
        this.memGbPerInstance = Integer.parseInt(
                ctx.config().getOrDefault("capacity.memGB", "8"));
        this.totalSlots = Integer.parseInt(
                ctx.config().getOrDefault("capacity.slots", String.valueOf(cpuPerInstance)));
        this.heartbeatIntervalMs = Long.parseLong(ctx.config().getOrDefault(
                "heartbeat.interval.ms", String.valueOf(DEFAULT_HEARTBEAT_INTERVAL_MS)));
        this.slowFactor = Double.parseDouble(ctx.config().getOrDefault("slow.factor", "3.0"));
        this.behaviors = BehaviorResolver.fromConfig(ctx.config());
        for (int i = 1; i <= count; i++) {
            instances.put(i, new InstanceState(i, id.instanceSourceId(i), totalSlots));
        }
    }

    @Override
    public void start() throws io.duo.sim.kernel.api.ComponentException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            readinessSocket = new ServerSocket();
            readinessSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        } catch (IOException e) {
            throw new io.duo.sim.kernel.api.ComponentException(
                    "bind readiness port failed: " + id, e);
        }
        // 发现等待语义（计划 §2/T8）：registry 里等 master 端点
        String masterAddr = discoverMaster();
        for (InstanceState inst : instances.values()) {
            Thread t = Thread.ofVirtual().name(inst.name).start(
                    () -> runInstance(inst, masterAddr));
            inst.thread = t;
        }
        fire(Event.sim("sim.worker-started", id.value(),
                Map.of("instances", count, "heartbeatIntervalMs", heartbeatIntervalMs)));
    }

    private String discoverMaster() {
        var registry = ctx.directRegistry()
                .orElseThrow(() -> new io.duo.sim.kernel.api.ComponentException(
                        "worker requires direct registry binding: " + id));
        for (int tries = 0; tries < DISCOVER_MAX_TRIES; tries++) {
            List<String> addrs = registry.discoverEndpoints("scheduler");
            if (!addrs.isEmpty()) {
                return addrs.get(0);
            }
            try {
                Thread.sleep(DISCOVER_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new io.duo.sim.kernel.api.ComponentException(
                "scheduler endpoint not discovered within " + DISCOVER_MAX_TRIES
                        + " tries: " + id);
    }

    /** 单实例主循环：连接 → 注册 → 心跳/槽位/读下行；崩溃即实例离线。 */
    private void runInstance(InstanceState inst, String masterAddr) {
        inst.alive.set(true);
        final long gen = inst.generation.incrementAndGet();
        // 拨号错峰（M4 压测）：实例号×1ms（封顶 10s）确定性错开握手洪峰
        try {
            Thread.sleep(Math.min(inst.index, DIAL_STAGGER_CAP_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        int colon = masterAddr.lastIndexOf(':');
        String host = masterAddr.substring(0, colon);
        int port = Integer.parseInt(masterAddr.substring(colon + 1));
        try {
            String failure = null;
            for (int attempt = 1; attempt <= HANDSHAKE_MAX_TRIES; attempt++) {
                try (Socket socket = new Socket()) {
                    connectWithRetry(socket, host, port);
                    socket.setSoTimeout(REGISTER_READ_TIMEOUT_MS); // 注册握手限时（§12 快速失败）
                    FrameConnection conn = new FrameConnection(socket);
                    inst.connection = conn;
                    conn.write(new RegisterRequest(inst.name, cpuPerInstance, memGbPerInstance));
                    DuoMessage resp = conn.read();
                    String reason = resp instanceof RegisterResponse rr
                            ? (rr.accepted() ? null : "register rejected: " + rr.reason())
                            // §12 不静默 + 可诊断：把「实际收到了什么」写进原因，
                            // 否则 `no register response` 无法区分「对端回了别的报文」与「对端回了 null」
                            : "no register response (got "
                                    + (resp == null ? "null" : resp.getClass().getSimpleName())
                                    + ")";
                    if (reason != null) {
                        failure = reason; // 明确拒绝：重试无意义，快速失败
                        break;
                    }
                    conn.write(new SlotReport(inst.name, inst.freeSlots.get(), totalSlots));
                    socket.setSoTimeout(0); // 稳态：下行读可无限期等待（读线程独立）
                    // 读下行线程（派发/取消）
                    inst.readerThread = Thread.ofVirtual().name(inst.name + "-reader")
                            .start(() -> readDownstream(inst, conn, gen));
                    // 心跳 + 槽位上报（周期可配，M4 T36）
                    while (inst.alive.get() && running.get() && inst.generation.get() == gen) {
                        if (frozen.get()) {
                            Thread.sleep(heartbeatIntervalMs); // freeze：不发心跳（M5-3 起可注入）
                            continue;
                        }
                        conn.write(new HeartbeatReport(inst.name, heartbeatSeq.incrementAndGet()));
                        reportSlots(inst, conn);
                        Thread.sleep(heartbeatIntervalMs);
                    }
                    return; // 正常退出循环（停止/代次更替）
                } catch (IOException e) {
                    failure = String.valueOf(e.getMessage()); // 连接/握手失败：退避重试
                    if (inst.generation.get() != gen || !running.get()) {
                        return; // 停止/重启中：属正常收敛，不算离线（不刷离线事件）
                    }
                    if (attempt < HANDSHAKE_MAX_TRIES) {
                        Thread.sleep(HANDSHAKE_RETRY_BACKOFF_MS * attempt);
                    }
                }
            }
            // 握手重试耗尽或明确拒绝＝实例离线（发射事件，M4 T36 诊断改进：
            // 此前静默吞掉，万级注册爬坡失败无从定位——注册类故障必须可观测，§12 精神）
            fire(Event.sim("sim.worker-instance-offline", inst.name,
                    Map.of("error", String.valueOf(failure))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (inst.generation.get() == gen) {
                inst.alive.set(false);
            }
            closeQuietly(inst);
        }
    }

    /** 连接重试（M4 压测 T36）：失败退避后重试，超限抛最后一次异常（实例离线由调用方 finally 收敛）。 */
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    /** 注册握手读超时（M4 压测）：master 无人应答的半开连接必须限时收敛（§12）。 */
    private static final int REGISTER_READ_TIMEOUT_MS = 10_000;

    /** 连接+注册握手整体重试（M4 压测）：万级并发握手洪峰下单次超时≠实例不可用，退避重试。 */
    private static final int HANDSHAKE_MAX_TRIES = 3;
    private static final long HANDSHAKE_RETRY_BACKOFF_MS = 500;
    /** 拨号错峰上限（M4 压测）：index×1ms、封顶 10s，平滑握手洪峰（确定性，无随机）。 */
    private static final long DIAL_STAGGER_CAP_MS = 10_000;

    private static void connectWithRetry(Socket socket, String host, int port)
            throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= CONNECT_MAX_TRIES; attempt++) {
            try {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                return;
            } catch (IOException e) {
                last = e;
                if (attempt < CONNECT_MAX_TRIES) {
                    Thread.sleep(CONNECT_RETRY_BACKOFF_MS);
                }
            }
        }
        throw last;
    }

    private void readDownstream(InstanceState inst, FrameConnection conn, long gen) {
        try {
            while (inst.alive.get() && running.get() && inst.generation.get() == gen) {
                DuoMessage msg = conn.read();
                if (msg instanceof TaskDispatch d) {
                    onDispatch(inst, conn, d);
                } else if (msg instanceof TaskCancel c) {
                    inst.running.stream().filter(t -> t.taskId.equals(c.taskId()))
                            .forEach(t -> t.cancelled = true);
                }
            }
        } catch (IOException e) {
            // 连接关闭或代次更替后旧连接失效
        }
    }

    private void onDispatch(InstanceState inst, FrameConnection conn, TaskDispatch d) {
        // 显式拒绝优先级（M5-3）：注入类故障优先于容量判定——故障语义必须可见，
        // 不能被「恰好还有空槽」掩盖（§12 不静默）。
        if (resourceExhausted.get()) {
            reject(conn, inst, d, "resource exhausted");
            return;
        }
        if (frozen.get()) {
            reject(conn, inst, d, "frozen");
            return;
        }
        if (inst.freeSlots.get() <= 0) {
            // G9 修复（§12 不静默）：满载时**显式拒绝**，不得静默丢弃。
            // 旧实现直接 return——调度侧仍视任务为 RUNNING，任务永久丢失、DAG 永不终态。
            // 触发场景（M7 CI 首跑暴露）：崩溃转移重派发时，调度侧的槽位视图可能滞后于本实例
            // 真实状态（SlotReport 与 TaskDispatch 双向异步），于是把任务派给已满实例。
            reject(conn, inst, d, "no free slot");
            return;
        }
        try {
            conn.write(new TaskAck(d.taskId(), inst.name));
        } catch (IOException e) {
            // 无法受理（连接不可写）：显式拒绝（尽力而为），且**不占用槽位**（旧实现先递减再写，
            // 写失败即泄漏槽位）。
            reject(conn, inst, d, "connection lost: " + e.getMessage());
            return;
        }
        inst.freeSlots.decrementAndGet(); // 受理成功后才占用槽位
        reportSlots(inst, conn); // G9 加固：状态变更即上报，缩小调度侧视图滞后窗口（原本要等心跳）
        fire(Event.sim("sim.worker-task-status", id.instanceSourceId(inst.index),
                Map.of("taskId", d.taskId(), "state", TaskStatus.RUNNING)));
        TaskExecution exec = new TaskExecution(d.taskId());
        inst.running.add(exec);
        Thread taskThread = Thread.ofVirtual().name(inst.name + "-task-" + d.taskId())
                .unstarted(() -> {
            try {
                var entry = behaviors.resolve(d.taskName());
                // slow（M5-3）：时长乘以 slow.factor（只在注入期间生效；倍数来自 config）
                long duration = slowActive.get()
                        ? (long) (entry.durationMillis() * activeSlowFactor) : entry.durationMillis();
                var profile = new BehaviorProfile(duration,
                        entry.jitterRatio(), entry.successRate(),
                        entry.exceptionType(), entry.logLines(),
                        entry.failAtPercent(), entry.neverReport(), entry.progressMode());
                // M1 T17：progress 上报（periodic 模式按进度回调 → 事件）
                var result = profile.execute(d.taskName(), new Random(), pct -> {
                    awaitUnfrozen(inst); // freeze：进展对外停摆（挂起回调）
                    fire(Event.sim("sim.worker-task-progress",
                            id.instanceSourceId(inst.index),
                            Map.of("taskId", d.taskId(), "progress", pct)));
                });
                awaitUnfrozen(inst); // freeze：日志与终态回报一并挂起（clear 后补报）
                emitLogs(inst, d, entry.logLines()); // G7：logLines 落流（终态之前，顺序确定）
                if (entry.neverReport()) {
                    // M1 T17：neverReport＝回报通道丢失——终态丢弃（槽位仍释放，供超时回收演练）
                    fire(Event.sim("sim.worker-task-unreported",
                            id.instanceSourceId(inst.index), Map.of("taskId", d.taskId())));
                } else if (exec.cancelled) {
                    conn.write(new TaskStatus(d.taskId(), inst.name,
                            TaskStatus.CANCELLED, "cancelled by master"));
                } else {
                    conn.write(new TaskStatus(d.taskId(), inst.name,
                            result.success() ? TaskStatus.SUCCESS : TaskStatus.FAILED,
                            result.errorMessage()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                try {
                    // task-kill（T18）与实例停止都会中断执行线程 → 立即回报 CANCELLED
                    conn.write(new TaskStatus(d.taskId(), inst.name,
                            TaskStatus.CANCELLED, exec.cancelled
                                    ? "cancelled by master" : "interrupted"));
                } catch (IOException ignored) {
                    // 连接已断
                }
            } catch (IOException e) {
                // 连接断开：状态回报丢失（master 失联检测兜底）
            } finally {
                inst.freeSlots.incrementAndGet();
                inst.running.remove(exec);
            }
        });
        exec.worker = taskThread;
        taskThread.start();
    }

    /**
     * 假日志落流（G7 修复）：{@code logLines} 逐行发 {@code sim.worker-log}（诊断/断言可见），
     * 支持 {@code {task}}/{@code {taskId}} 占位符。此前该字段被 {@code BehaviorResolver} 固定置空，
     * DSL 写了不生效（静默无效）。
     */
    private void emitLogs(InstanceState inst, TaskDispatch d, java.util.List<String> lines) {
        for (String line : lines) {
            fire(Event.sim("sim.worker-log", id.instanceSourceId(inst.index),
                    Map.of("taskId", d.taskId(),
                            "line", line.replace("{task}", d.taskName())
                                    .replace("{taskId}", d.taskId()))));
        }
    }

    /**
     * 立即上报当前槽位（G9 加固）：槽位变化（受理/拒绝）后立刻发 {@link SlotReport}，不等下一次心跳。
     * 调度侧的槽位视图因此在一个 RTT 内收敛到真实状态，而不是最多滞后一个心跳周期——这正是
     * 「陈旧视图 → 把任务派给已满实例」的窗口。写失败忽略：心跳周期上报仍会兜底。
     */
    private void reportSlots(InstanceState inst, FrameConnection conn) {
        try {
            conn.write(new SlotReport(inst.name, effectiveSlots(inst), totalSlots));
        } catch (IOException ignored) {
            // 连接不可写：心跳周期上报兜底
        }
    }

    /** 对外可见的空闲槽位：resource-exhaust 期间恒为 0（可观测面与准入判定同源）。 */
    private int effectiveSlots(InstanceState inst) {
        return resourceExhausted.get() ? 0 : inst.freeSlots.get();
    }

    /**
     * freeze 期间挂起（M5-3，协作式等待）：进展/日志/终态回报在冻结期对外不可见，clear 后补报。
     * 用轮询而非 wait/notify——stop/restart/实例下线都必须能收敛，漏唤醒会导致线程永久挂起。
     */
    private void awaitUnfrozen(InstanceState inst) {
        while (frozen.get() && running.get() && inst.alive.get()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 显式拒绝一次不可受理的派发（G9）：回报 {@link TaskStatus#REJECTED} + 发
     * {@code sim.worker-task-rejected} 事实（诊断面），**不占用槽位、不启动执行线程**。
     * 回报写失败时由调度侧的失联检测（feed 读失败 → onFeedLost）兜底重排。
     */
    private void reject(FrameConnection conn, InstanceState inst, TaskDispatch d, String reason) {
        try {
            conn.write(new TaskStatus(d.taskId(), inst.name, TaskStatus.REJECTED, reason));
        } catch (IOException ignored) {
            // 连接已断：拒绝回报丢失，调度侧失联检测兜底
        }
        reportSlots(inst, conn); // 拒绝同时刷新槽位视图，避免调度侧继续把任务派到本实例
        fire(Event.sim("sim.worker-task-rejected", id.instanceSourceId(inst.index),
                Map.of("taskId", d.taskId(), "reason", reason)));
    }

    @Override
    public void stop(StopMode mode) {
        running.set(false);
        for (InstanceState inst : instances.values()) {
            inst.alive.set(false);
            closeQuietly(inst);
            if (inst.thread != null) {
                inst.thread.interrupt();
            }
        }
        closeReadiness();
        fire(Event.sim(mode == StopMode.CRASH ? "sim.worker-crashed" : "sim.worker-stopped",
                id.value(), Map.of("mode", mode.name(), "instances", count)));
    }

    @Override
    public void restart() {
        // 端点与身份保留（readiness socket 重开随机端口不破坏"身份"），内部状态清空
        instances.clear();
        heartbeatSeq.set(0);
        frozen.set(false);
        slowActive.set(false);
        resourceExhausted.set(false);
        try {
            init(ctx);
        } catch (RuntimeException e) {
            throw new io.duo.sim.kernel.api.ComponentException("re-init failed: " + id, e);
        }
        try {
            start();
        } catch (io.duo.sim.kernel.api.ComponentException e) {
            throw new io.duo.sim.kernel.api.ComponentException("restart start failed: " + id, e);
        }
    }

    // ---- WorkerContract ----

    @Override
    public int instanceCount() {
        return count;
    }

    @Override
    public String instanceName(int index) {
        return id.instanceSourceId(index);
    }

    @Override
    public boolean isInstanceAlive(int index) {
        InstanceState s = instances.get(index);
        return s != null && s.alive.get();
    }

    @Override
    public int instanceFreeSlots(int index) {
        InstanceState s = instances.get(index);
        // 对外口径＝准入判定口径：resource-exhaust 期间恒为 0（与 SlotReport 一致），
        // 否则断言会看到「有槽位但派发被拒」的自相矛盾状态
        return s == null ? 0 : effectiveSlots(s);
    }

    @Override
    public EndpointShape declaredShape() {
        return EndpointShape.DUO_PORT;
    }

    // ---- InstanceControl（§7.2：实例级操作）----

    @Override
    public void stopInstance(int index, StopMode mode) {
        InstanceState s = requireInstance(index);
        s.alive.set(false);
        s.generation.incrementAndGet(); // 令旧连接上的读/心跳循环退出
        closeQuietly(s);
        if (s.thread != null) {
            s.thread.interrupt();
        }
        fire(Event.sim(mode == StopMode.CRASH ? "sim.worker-instance-crashed"
                        : "sim.worker-instance-stopped",
                id.instanceSourceId(index), Map.of("mode", mode.name())));
    }

    @Override
    public void restartInstance(int index) {
        InstanceState s = requireInstance(index);
        s.freeSlots.set(totalSlots);
        s.running.clear();
        String masterAddr = discoverMaster();
        Thread t = Thread.ofVirtual().name(s.name).start(() -> runInstance(s, masterAddr));
        s.thread = t;
        fire(Event.sim("sim.worker-instance-restarted", id.instanceSourceId(index), Map.of()));
    }

    @Override
    public void injectOnInstance(FaultAction action) {
        if (!FaultAction.TASK_KILL.equals(action.type())) {
            throw new UnsupportedOperationException("unsupported fault on instance: "
                    + action.type() + " (freeze/slow/resource-exhaust are component-scoped;"
                    + " use inject)");
        }
        int index = action.target().instanceIndex() == null ? 1 : action.target().instanceIndex();
        InstanceState inst = requireInstance(index);
        // task-kill（T18）：终止该实例全部在途任务的执行线程 → 任务线程回报 CANCELLED
        int killed = 0;
        for (TaskExecution exec : inst.running) {
            exec.cancelled = true;
            Thread w = exec.worker;
            if (w != null) {
                w.interrupt();
                killed++;
            }
        }
        fire(Event.sim("sim.worker-task-killed", id.instanceSourceId(index),
                Map.of("killed", killed)));
    }

    // ---- FaultInjectable（M5-3：freeze/slow/resource-exhaust 为组件级；task-kill 为实例级）----

    @Override
    public void inject(FaultAction action) {
        switch (action.type()) {
            case FaultAction.FREEZE -> {
                if (frozen.compareAndSet(false, true)) {
                    fire(Event.sim("sim.worker-frozen", id.value(), Map.of()));
                }
            }
            case FaultAction.SLOW -> {
                // 倍数优先级：动作 params.factor > 节点 config slow.factor（缺省 3.0）。
                // 幂等：已在慢速中只更新倍数，不叠加（叠加会让恢复语义不可逆）
                double factor = resolveSlowFactor(action);
                activeSlowFactor = factor;
                if (slowActive.compareAndSet(false, true)) {
                    fire(Event.sim("sim.worker-slowed", id.value(),
                            Map.of("factor", factor)));
                }
            }
            case FaultAction.RESOURCE_EXHAUST -> {
                if (resourceExhausted.compareAndSet(false, true)) {
                    fire(Event.sim("sim.worker-resource-exhausted", id.value(),
                            Map.of("slots", totalSlots)));
                    // 立即刷新全部实例的对外槽位视图（不等心跳），否则调度侧仍会把任务派过来
                    for (InstanceState inst : instances.values()) {
                        if (inst.connection != null) {
                            reportSlots(inst, inst.connection);
                        }
                    }
                }
            }
            default -> throw new UnsupportedOperationException("unsupported fault: "
                    + action.type() + " (task-kill is instance-scoped; use injectOnInstance)");
        }
    }

    @Override
    public void clear(FaultAction action) {
        switch (action.type()) {
            case FaultAction.FREEZE -> {
                if (frozen.compareAndSet(true, false)) {
                    fire(Event.sim("sim.worker-resumed", id.value(), Map.of()));
                }
            }
            case FaultAction.SLOW -> {
                if (slowActive.compareAndSet(true, false)) {
                    fire(Event.sim("sim.worker-speed-restored", id.value(), Map.of()));
                }
            }
            case FaultAction.RESOURCE_EXHAUST -> {
                if (resourceExhausted.compareAndSet(true, false)) {
                    fire(Event.sim("sim.worker-resource-restored", id.value(), Map.of()));
                    for (InstanceState inst : instances.values()) {
                        if (inst.connection != null) {
                            reportSlots(inst, inst.connection);
                        }
                    }
                }
            }
            default -> throw new UnsupportedOperationException(
                    "unsupported fault: " + action.type());
        }
    }

    /** 是否冻结（测试/诊断用）。 */
    public boolean isFrozen() {
        return frozen.get();
    }

    /** 是否处于慢速（测试/诊断用）。 */
    public boolean isSlow() {
        return slowActive.get();
    }

    /** 是否资源耗尽（测试/诊断用）。 */
    public boolean isResourceExhausted() {
        return resourceExhausted.get();
    }

    /**
     * slow 的生效倍数：动作 {@code params.factor} 优先，其次节点 {@code config slow.factor}。
     * 非法倍数（≤1.0）显式拒绝——否则「注入了但没变慢」会被当成静默无效。
     */
    private double resolveSlowFactor(FaultAction action) {
        Object raw = action.params() == null ? null : action.params().get("factor");
        double factor = raw == null ? slowFactor : Double.parseDouble(String.valueOf(raw));
        if (factor <= 1.0) {
            throw new IllegalArgumentException(
                    "slow factor must be > 1.0 (params.factor or config slow.factor), got "
                            + factor);
        }
        return factor;
    }

    // ---- misc ----

    @Override
    public HealthReport health() {
        return running.get() ? HealthReport.ok() : HealthReport.down("worker not running");
    }

    @Override
    public List<ExposedEndpoint> endpoints() {
        try {
            return readinessSocket != null && readinessSocket.isBound()
                    ? List.of(ExposedEndpoint.tcp(io.duo.sim.kernel.api.Contract.WORKER,
                            "127.0.0.1", readinessSocket.getLocalPort()))
                    : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private InstanceState requireInstance(int index) {
        InstanceState s = instances.get(index);
        if (s == null) {
            throw new io.duo.sim.kernel.api.ComponentException(
                    "no such instance: " + id.instanceSourceId(index));
        }
        return s;
    }

    private void closeQuietly(InstanceState inst) {
        if (inst.connection != null) {
            try {
                inst.connection.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
    }

    private void closeReadiness() {
        if (readinessSocket != null && !readinessSocket.isClosed()) {
            try {
                readinessSocket.close();
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
}
