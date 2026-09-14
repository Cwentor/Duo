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
    private static final long HEARTBEAT_INTERVAL_MS = 100;

    private volatile ComponentId id;
    private volatile ComponentContext ctx;
    private volatile int count = 1;
    private volatile int cpuPerInstance = 4;
    private volatile int memGbPerInstance = 8;
    private volatile int totalSlots = 4;
    private volatile BehaviorResolver behaviors;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean frozen = new AtomicBoolean(false);
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
        volatile int freeSlots;
        volatile Thread thread;
        volatile FrameConnection connection;
        volatile Thread readerThread;
        final List<TaskExecution> running = new CopyOnWriteArrayList<>();

        InstanceState(int index, String name, int slots) {
            this.index = index;
            this.name = name;
            this.freeSlots = slots;
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
        fire(Event.sim("sim.worker-started", id.value(), Map.of("instances", count)));
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
        int colon = masterAddr.lastIndexOf(':');
        String host = masterAddr.substring(0, colon);
        int port = Integer.parseInt(masterAddr.substring(colon + 1));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port));
            FrameConnection conn = new FrameConnection(socket);
            inst.connection = conn;
            conn.write(new RegisterRequest(inst.name, cpuPerInstance, memGbPerInstance));
            DuoMessage resp = conn.read();
            if (!(resp instanceof RegisterResponse r) || !r.accepted()) {
                inst.alive.set(false);
                return;
            }
            conn.write(new SlotReport(inst.name, inst.freeSlots, totalSlots));
            // 读下行线程（派发/取消）
            inst.readerThread = Thread.ofVirtual().name(inst.name + "-reader")
                    .start(() -> readDownstream(inst, conn, gen));
            // 心跳 + 槽位上报
            while (inst.alive.get() && running.get() && inst.generation.get() == gen) {
                if (frozen.get()) {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS); // freeze：不发心跳（注入通路属 M1）
                    continue;
                }
                conn.write(new HeartbeatReport(inst.name, heartbeatSeq.incrementAndGet()));
                conn.write(new SlotReport(inst.name, inst.freeSlots, totalSlots));
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 连接断开＝实例离线（master 侧感知）
        } finally {
            if (inst.generation.get() == gen) {
                inst.alive.set(false);
            }
            closeQuietly(inst);
        }
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
        if (inst.freeSlots <= 0) {
            return; // M0：满载即忽略（scheduler 侧排队由拓扑规模保证不发生）
        }
        inst.freeSlots--;
        try {
            conn.write(new TaskAck(d.taskId(), inst.name));
        } catch (IOException e) {
            return; // 连接断开：无法受理
        }
        fire(Event.sim("sim.worker-task-status", id.instanceSourceId(inst.index),
                Map.of("taskId", d.taskId(), "state", TaskStatus.RUNNING)));
        TaskExecution exec = new TaskExecution(d.taskId());
        inst.running.add(exec);
        Thread taskThread = Thread.ofVirtual().name(inst.name + "-task-" + d.taskId())
                .unstarted(() -> {
            try {
                var entry = behaviors.resolve(d.taskName());
                var profile = new BehaviorProfile(entry.durationMillis(),
                        entry.jitterRatio(), entry.successRate(),
                        entry.exceptionType(), entry.logLines(),
                        entry.failAtPercent(), entry.neverReport(), entry.progressMode());
                // M1 T17：progress 上报（periodic 模式按进度回调 → 事件）
                var result = profile.execute(d.taskName(), new Random(), pct ->
                        fire(Event.sim("sim.worker-task-progress",
                                id.instanceSourceId(inst.index),
                                Map.of("taskId", d.taskId(), "progress", pct))));
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
                inst.freeSlots++;
                inst.running.remove(exec);
            }
        });
        exec.worker = taskThread;
        taskThread.start();
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
        return s == null ? 0 : s.freeSlots;
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
        s.freeSlots = totalSlots;
        s.running.clear();
        String masterAddr = discoverMaster();
        Thread t = Thread.ofVirtual().name(s.name).start(() -> runInstance(s, masterAddr));
        s.thread = t;
        fire(Event.sim("sim.worker-instance-restarted", id.instanceSourceId(index), Map.of()));
    }

    @Override
    public void injectOnInstance(FaultAction action) {
        if (!FaultAction.TASK_KILL.equals(action.type())) {
            throw new UnsupportedOperationException("unsupported fault: " + action.type());
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

    // ---- FaultInjectable（T18：仅 task-kill，实例级；整组注入不支持）----

    @Override
    public void inject(FaultAction action) {
        throw new UnsupportedOperationException(
                "task-kill is instance-scoped; use injectOnInstance: " + action.type());
    }

    @Override
    public void clear(FaultAction action) {
        throw new UnsupportedOperationException("task-kill needs no clear: " + action.type());
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
