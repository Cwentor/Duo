package io.duo.sim.components.engine;

import io.duo.sim.components.provider.BehaviorResolver;
import io.duo.sim.components.taskstub.BehaviorProfile;
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
import io.duo.sim.kernel.contract.EngineContract;
import io.duo.sim.protocol.FrameConnection;
import io.duo.sim.protocol.message.TaskCancel;
import io.duo.sim.protocol.message.TaskDispatch;
import io.duo.sim.protocol.message.TaskStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * engine 契约 virtual 档（M5 交付物 1）：计算引擎替身——**提交 → 受理 → 状态流转 → 终态 + 日志**，
 * 行为语义直接复用 {@code TaskStub} 的行为模型（{@link BehaviorResolver} + {@link BehaviorProfile}），
 * 故「同样的 behaviors 配置，在 worker 与 engine 两个契约上表现一致」（不另造一套假行为）。
 *
 * <p>与 worker 的连接模型差异（这是两个契约的真实差别，不是实现偷懒）：worker 是**客户端**
 * （拨号 scheduler 领任务），engine 是**服务端**（对外暴露 DUO_PORT，由调度侧/测试提交作业，
 * 对应 {@code spark-submit} 形态）。因此本实现：绑定 DUO_PORT → accept → 收
 * {@link TaskDispatch} → 受理（回 {@link TaskStatus#RUNNING}）→ 执行 → 终态回报；
 * 另实现 {@link EngineContract#submit} 供同进程（interface-direct）提交。
 *
 * <p>容量与显式拒绝（G9 纪律：**任何不可受理都必须显式失败**）：槽位由 config
 * {@code capacity.slots}（缺省＝{@code capacity.cpu}，再缺省 4）给出；满载时派发回报
 * {@link TaskStatus#REJECTED} + {@code sim.engine-task-rejected}，同进程 {@code submit}
 * 则抛 {@link ComponentException} 并带原因——都不静默丢弃。
 *
 * <p>故障注入（M5-3）：
 * <ul>
 *   <li>{@code freeze}：冻结——**拒收**新派发（显式 REJECTED，理由 {@code engine frozen}），
 *       且**挂起**在途任务的终态回报（进展对外停摆），{@code clear} 后补报；</li>
 *   <li>{@code slow}：变慢——执行时长乘以 {@code slow.factor}（缺省 3.0）；</li>
 *   <li>{@code resource-exhaust}：资源耗尽——**拒收**全部新派发（理由 {@code resource exhausted}），
 *       可观测面（{@code sim.engine-slot}）报 0 空闲。</li>
 * </ul>
 *
 * <p>配置项：{@code capacity.slots}、{@code capacity.cpu}、{@code capacity.memGB}、
 * {@code slow.factor}、以及 {@code behaviors.*}（行为剧本，语义同 worker）。
 */
public final class VirtualEngine implements VirtualComponent, EngineContract,
        FaultInjectable {

    private static final int ACCEPT_BACKLOG = 256;
    private static final long DISPATCH_READ_TIMEOUT_MS = 10_000;

    private volatile ComponentId id;
    private volatile ComponentContext ctx;
    private volatile ServerSocket server;
    private volatile int cpu = 4;
    private volatile int memGb = 8;
    private volatile int slots = 4;
    private volatile double slowFactor = 3.0;
    /** 当前生效的 slow 倍数（注入时按 params.factor / config slow.factor 解析）。 */
    private volatile double activeSlowFactor = 3.0;
    private volatile BehaviorResolver behaviors;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean frozen = new AtomicBoolean(false);
    private final AtomicBoolean resourceExhausted = new AtomicBoolean(false);
    /** slow（M5-3）：置位后执行时长乘以 {@link #slowFactor}（倍数来自 config，不叠加）。 */
    private final AtomicBoolean slowActive = new AtomicBoolean(false);
    /** 生命周期代际：stop/restart 递增，用于让上一代任务线程彻底沉默（不污染新一代计数与事实）。 */
    private final AtomicLong generation = new AtomicLong();
    private final AtomicInteger freeSlots = new AtomicInteger();
    private final AtomicLong taskSeq = new AtomicLong();
    private final AtomicLong progressSeq = new AtomicLong();
    private final List<FrameConnection> conns = new CopyOnWriteArrayList<>();
    private final List<TaskExecution> runningTasks = new CopyOnWriteArrayList<>();

    private volatile Thread acceptor;

    /** 在途执行。 */
    static final class TaskExecution {
        final String taskId;
        /** 提交侧连接（同进程提交为 null）；stop 时用它同步回写取消终态。 */
        final FrameConnection conn;
        volatile boolean cancelled;
        volatile Thread thread;

        TaskExecution(String taskId, FrameConnection conn) {
            this.taskId = taskId;
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
        this.cpu = Integer.parseInt(ctx.config().getOrDefault("capacity.cpu", "4"));
        this.memGb = Integer.parseInt(ctx.config().getOrDefault("capacity.memGB", "8"));
        this.slots = Integer.parseInt(ctx.config().getOrDefault("capacity.slots",
                String.valueOf(cpu)));
        this.slowFactor = Double.parseDouble(ctx.config().getOrDefault("slow.factor", "3.0"));
        this.behaviors = BehaviorResolver.fromConfig(ctx.config());
        this.freeSlots.set(slots);
    }

    @Override
    public void start() throws ComponentException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            server = new ServerSocket();
            server.bind(new InetSocketAddress("127.0.0.1", 0), ACCEPT_BACKLOG);
        } catch (IOException e) {
            running.set(false);
            throw new ComponentException("bind engine port failed: " + id, e);
        }
        acceptor = Thread.ofVirtual().name(id.value() + "-accept").start(this::acceptLoop);
        fire(Event.sim("sim.engine-started", id.value(),
                Map.of("slots", slots, "cpu", cpu, "memGB", memGb,
                        "port", server.getLocalPort())));
    }

    @Override
    public void stop(StopMode mode) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread a = acceptor;
        if (a != null) {
            a.interrupt();
        }
        for (FrameConnection c : conns) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
        conns.clear();
        // 先把代际推进再发取消终态：被唤醒的任务线程即使插到中间跑完 catch，
        // 也会看到 gen != generation ⇒ 不再重复发事实（§12：一条取消只记一条事实）
        generation.incrementAndGet();
        // **同步**为每个在途任务发一次 CANCELLED 终态事实：见 reportCancelledNow 的注释——
        // 若留给被中断的任务线程发，事实会偶发漂移到重启之后（CI run 35419372086 实测）
        for (TaskExecution exec : runningTasks) {
            reportCancelledNow(exec.conn, exec.taskId, "cancelled");
        }
        for (TaskExecution exec : runningTasks) {
            Thread t = exec.thread;
            if (t != null) {
                t.interrupt();
            }
        }
        runningTasks.clear();
        freeSlots.set(slots);
        if (server != null && !server.isClosed()) {
            try {
                server.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
        fire(Event.sim(mode == StopMode.CRASH ? "sim.engine-crashed" : "sim.engine-stopped",
                id.value(), Map.of("mode", mode.name())));
    }

    @Override
    public void restart() {
        // §7.1：身份保留、内部状态清空；端点端口重绑（随机端口）后重新对外服务
        stop(StopMode.GRACEFUL);
        frozen.set(false);
        resourceExhausted.set(false);
        slowActive.set(false);
        taskSeq.set(0);
        init(ctx);
        start();
        fire(Event.sim("sim.engine-restarted", id.value(), Map.of()));
    }

    @Override
    public HealthReport health() {
        return running.get() && server != null && !server.isClosed()
                ? HealthReport.ok() : HealthReport.down("engine not running");
    }

    @Override
    public List<ExposedEndpoint> endpoints() {
        try {
            return server != null && server.isBound()
                    ? List.of(ExposedEndpoint.tcp(Contract.ENGINE, "127.0.0.1",
                            server.getLocalPort()))
                    : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    // ---- EngineContract（同进程提交；不可受理时显式失败）----

    @Override
    public String submit(String taskName, int cpu, int memGB) {
        if (!running.get()) {
            throw new ComponentException("engine not running: " + id);
        }
        String reason = rejectionReason();
        if (reason != null) {
            fire(Event.sim("sim.engine-task-rejected", id.value(),
                    Map.of("taskId", "-", "taskName", taskName, "reason", reason)));
            throw new ComponentException("engine cannot accept submission ("
                    + reason + "): " + taskName);
        }
        if (!tryReserveSlot()) {
            // 判定与占槽之间的并发窗口：并发提交下**不得超发槽位**（G9 纪律）
            fire(Event.sim("sim.engine-task-rejected", id.value(),
                    Map.of("taskId", "-", "taskName", taskName, "reason", "no free slot")));
            throw new ComponentException("engine cannot accept submission ("
                    + "no free slot): " + taskName);
        }
        String taskId = id.value() + "-submit-" + taskSeq.incrementAndGet();
        fire(Event.sim("sim.engine-submitted", id.value(),
                Map.of("taskId", taskId, "taskName", taskName, "cpu", cpu, "memGB", memGB)));
        launch(null, taskId, taskName, cpu, memGB);
        return taskId;
    }

    @Override
    public EndpointShape declaredShape() {
        return EndpointShape.DUO_PORT;
    }

    // ---- FaultInjectable（M5-3）----

    @Override
    public void inject(FaultAction action) {
        switch (action.type()) {
            case FaultAction.FREEZE -> {
                if (frozen.compareAndSet(false, true)) {
                    fire(Event.sim("sim.engine-frozen", id.value(), Map.of()));
                }
            }
            case FaultAction.SLOW -> {
                // 倍数优先级：动作 params.factor > 节点 config slow.factor（缺省 3.0）；
                // 幂等：已在慢速中只更新倍数，不叠加
                double factor = resolveSlowFactor(action);
                activeSlowFactor = factor;
                if (slowActive.compareAndSet(false, true)) {
                    fire(Event.sim("sim.engine-slowed", id.value(),
                            Map.of("factor", factor)));
                }
            }
            case FaultAction.RESOURCE_EXHAUST -> {
                if (resourceExhausted.compareAndSet(false, true)) {
                    fire(Event.sim("sim.engine-resource-exhausted", id.value(),
                            Map.of("slots", slots)));
                    reportSlots();
                }
            }
            default -> throw new UnsupportedOperationException(
                    "unsupported fault: " + action.type());
        }
    }

    @Override
    public void clear(FaultAction action) {
        switch (action.type()) {
            case FaultAction.FREEZE -> {
                if (frozen.compareAndSet(true, false)) {
                    fire(Event.sim("sim.engine-resumed", id.value(), Map.of()));
                }
            }
            case FaultAction.SLOW -> {
                if (slowActive.compareAndSet(true, false)) {
                    fire(Event.sim("sim.engine-speed-restored", id.value(), Map.of()));
                }
            }
            case FaultAction.RESOURCE_EXHAUST -> {
                if (resourceExhausted.compareAndSet(true, false)) {
                    fire(Event.sim("sim.engine-resource-restored", id.value(), Map.of()));
                    reportSlots();
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

    /** 是否资源耗尽（测试/诊断用）。 */
    public boolean isResourceExhausted() {
        return resourceExhausted.get();
    }

    /** 空闲槽位（可观测点）。 */
    public int freeSlots() {
        return resourceExhausted.get() ? 0 : freeSlots.get();
    }

    /** 在途任务数（可观测点）。 */
    public int inFlightTasks() {
        return runningTasks.size();
    }

    // ---- 内部：协议服务端 ----

    private void acceptLoop() {
        try {
            while (running.get()) {
                Socket s = server.accept();
                Thread.ofVirtual().name(id.value() + "-conn").start(() -> handleConnection(s));
            }
        } catch (IOException e) {
            // server 关闭
        }
    }

    private void handleConnection(Socket s) {
        FrameConnection conn;
        try {
            conn = new FrameConnection(s);
        } catch (IOException e) {
            // 取流失败：仅丢弃该连接（不静默——记一条协议错误事实）
            fire(Event.sim("sim.engine-protocol-error", id.value(),
                    Map.of("error", "cannot open connection: " + e.getMessage())));
            return;
        }
        conns.add(conn);
        try {
            s.setSoTimeout((int) DISPATCH_READ_TIMEOUT_MS);
            // 首帧必须是派发（engine 无注册握手：它是被提交方，不是集群成员）
            var first = conn.read();
            s.setSoTimeout(0);
            if (first instanceof TaskDispatch d) {
                onDispatch(conn, d);
            } else {
                fire(Event.sim("sim.engine-protocol-error", id.value(),
                        Map.of("error", "first frame must be task-dispatch, got "
                                + (first == null ? "null" : first.getClass().getSimpleName()))));
                conn.close();
                return;
            }
            while (running.get()) {
                var msg = conn.read();
                if (msg instanceof TaskDispatch next) {
                    onDispatch(conn, next);
                } else if (msg instanceof TaskCancel c) {
                    runningTasks.stream().filter(t -> t.taskId.equals(c.taskId()))
                            .forEach(t -> t.cancelled = true);
                }
            }
        } catch (IOException e) {
            // 连接关闭（停止/重启/客户端断开）
        } finally {
            conns.remove(conn);
        }
    }

    private void onDispatch(FrameConnection conn, TaskDispatch d) {
        String reason = rejectionReason();
        if (reason != null) {
            reject(conn, d, reason);
            return;
        }
        if (!tryReserveSlot()) {
            // 判定与占槽之间被别的连接抢走（并发窗口）：仍然**显式拒绝**，不放行也不静默
            reject(conn, d, "no free slot");
            return;
        }
        try {
            conn.write(new TaskStatus(d.taskId(), id.value(), TaskStatus.RUNNING, null));
        } catch (IOException e) {
            freeSlots.incrementAndGet(); // 回报失败即未受理：不占槽位
            reject(conn, d, "connection lost: " + e.getMessage());
            return;
        }
        reportSlots();
        fire(Event.sim("sim.engine-task-status", id.value(),
                Map.of("taskId", d.taskId(), "state", TaskStatus.RUNNING)));
        launch(conn, d.taskId(), d.taskName(), d.cpu(), d.memGB());
    }

    /**
     * 不可受理原因（null＝可受理）。显式失败优先于静默丢弃（G9 纪律）。
     *
     * <p>只做**无副作用**的判定；槽位的实际占用走 {@link #tryReserveSlot()}——
     * 「先看有没有、再减一」在多连接并发下会让槽位计数变负（G9 在 worker 侧踩过同一坑）。
     */
    private String rejectionReason() {
        if (resourceExhausted.get()) {
            return "resource exhausted";
        }
        if (frozen.get()) {
            return "engine frozen";
        }
        return freeSlots.get() <= 0 ? "no free slot" : null;
    }

    /** 原子占槽：CAS 循环，杜绝并发下槽位计数变负（G9 纪律：计数必须原子化）。 */
    private boolean tryReserveSlot() {
        while (true) {
            int cur = freeSlots.get();
            if (cur <= 0) {
                return false;
            }
            if (freeSlots.compareAndSet(cur, cur - 1)) {
                return true;
            }
        }
    }

    private void reject(FrameConnection conn, TaskDispatch d, String reason) {
        try {
            conn.write(new TaskStatus(d.taskId(), id.value(), TaskStatus.REJECTED, reason));
        } catch (IOException ignored) {
            // 连接已断：拒绝回报丢失，提交侧按连接失败兜底
        }
        fire(Event.sim("sim.engine-task-rejected", id.value(),
                Map.of("taskId", d.taskId(), "taskName", d.taskName(), "reason", reason)));
    }

    /** 执行一个任务（conn 为 null＝同进程提交，无回报通道，仅事件）。 */
    private void launch(FrameConnection conn, String taskId, String taskName, int cpu, int memGB) {
        TaskExecution exec = new TaskExecution(taskId, conn);
        runningTasks.add(exec);
        // 代际快照：本任务只对**自己这一代**的计数与事实负责（stop/restart 后旧任务必须彻底沉默）
        final long gen = generation.get();
        Thread t = Thread.ofVirtual().name(id.value() + "-task-" + taskId).unstarted(() -> {
            try {
                var entry = behaviors.resolve(taskName);
                // slow（M5-3）：仅在**注入期间**乘以倍数；倍数来自 config（幂等，不叠加）
                long duration = slowActive.get()
                        ? (long) (entry.durationMillis() * activeSlowFactor) : entry.durationMillis();
                var profile = new BehaviorProfile(duration, entry.jitterRatio(),
                        entry.successRate(), entry.exceptionType(), entry.logLines(),
                        entry.failAtPercent(), entry.neverReport(), entry.progressMode());
                var result = profile.execute(taskName, new Random(), pct -> {
                    if (gen != generation.get()) {
                        return; // 旧代际：不再对外报进展
                    }
                    awaitUnfrozen(); // freeze：进展对外停摆（挂起回调）
                    fire(Event.sim("sim.engine-task-progress", id.value(),
                            Map.of("taskId", taskId, "progress", pct)));
                });
                if (gen != generation.get()) {
                    return; // stop/restart 已终结本代际：不再报日志/终态（事实由 stopped/crashed 记账）
                }
                emitLogs(taskId, taskName, entry.logLines());
                awaitUnfrozen(); // freeze：终态回报挂起（clear 后补报）
                if (gen != generation.get()) {
                    return;
                }
                if (entry.neverReport()) {
                    fire(Event.sim("sim.engine-task-unreported", id.value(),
                            Map.of("taskId", taskId)));
                } else {
                    // 与 catch 分支同一纪律：**以中止标志为准**。任务正常跑完时 stop()/cancel()
                    // 可能恰好正在推进代际，此时把 SUCCEEDED 报成终态是"事实与意图不符"
                    // （CI run 35419372086 实测：重启后仍出现 CANCELLED 终态事实）
                    if (exec.cancelled) {
                        return;
                    }
                    String state = result.success() ? TaskStatus.SUCCESS : TaskStatus.FAILED;
                    reportTerminal(conn, taskId, state,
                            exec.cancelled ? "cancelled" : result.errorMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 取消的终态事实已由 stop() 在**递增代际之后**同步发出（reportCancelledNow）；
                // 这里既不重复发事实（一条取消只记一条事实），也只在本代际仍有效时补连接回写
                if (gen == generation.get() && conn != null) {
                    try {
                        conn.write(new TaskStatus(taskId, id.value(), TaskStatus.CANCELLED,
                                exec.cancelled ? "cancelled" : "interrupted"));
                    } catch (IOException ignored) {
                        // 提交侧已断开
                    }
                }
            } finally {
                // 只有**当前**代际的任务才归还槽位：上一代的归还计入新一代会造成计数漂移
                // （CI 实测 freeSlots 2→3）。注意这里必须是「代际未变」而不是「代际相同」——
                // 任务在本代际内正常结束也要归还槽位（下面的 CAS 与判据都指向这一点）。
                if (gen == generation.get()) {
                    runningTasks.remove(exec);
                    freeSlots.incrementAndGet();
                    reportSlots();
                }
            }
        });
        exec.thread = t;
        t.start();
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

    /** 冻结期间挂起（协作式等待；stop/restart 也能收敛——不依赖 notify，避免漏唤醒死锁）。 */
    private void awaitUnfrozen() {
        while (frozen.get() && running.get()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void reportTerminal(FrameConnection conn, String taskId, String state, String detail) {
        if (conn != null) {
            try {
                conn.write(new TaskStatus(taskId, id.value(), state, detail));
            } catch (IOException ignored) {
                // 提交侧已断开：终态回报丢失（连接失败即事实）
            }
        }
        fire(Event.sim("sim.engine-task-status", id.value(),
                Map.of("taskId", taskId, "state", state)));
    }

    /**
     * 取消终态：**事实先在调用线程（＝stop/restart 的线程）落流**，再回写连接。
     *
     * <p>为什么必须与 {@link #reportTerminal} 分开：stop/restart 走的是
     * 「先中断任务线程 → 再 {@code generation.incrementAndGet()}」的顺序，被中断的任务线程
     * 在 {@code catch (InterruptedException)} 里先读到**旧代际**、于是进入上报分支，
     * 随后才执行 {@code fire}——这条窗口让旧代际的 CANCELLED 事实**偶发**落到重启之后
     * （CI run 35419372086 实测：断言"旧代际不得向新一代报终态"失败）。
     * 把事实挪到 stop 线程同步发射后，代际判定与事实发射之间不再有任何异步空隙。
     */
    private void reportCancelledNow(FrameConnection conn, String taskId, String detail) {
        fire(Event.sim("sim.engine-task-status", id.value(),
                Map.of("taskId", taskId, "state", TaskStatus.CANCELLED)));
        if (conn != null) {
            try {
                conn.write(new TaskStatus(taskId, id.value(), TaskStatus.CANCELLED, detail));
            } catch (IOException ignored) {
                // 同上：提交侧已断开
            }
        }
    }

    /** 假日志落流（与 worker 同口径：{@code logLines} 逐行、占位符可展开、终态之前）。 */
    private void emitLogs(String taskId, String taskName, List<String> lines) {
        for (String line : lines) {
            fire(Event.sim("sim.engine-task-log", id.value(),
                    Map.of("taskId", taskId, "line", line.replace("{task}", taskName)
                            .replace("{taskId}", taskId))));
        }
    }

    private void reportSlots() {
        long n = progressSeq.incrementAndGet();
        fire(Event.sim("sim.engine-slot", id.value(),
                Map.of("freeSlots", freeSlots(), "totalSlots", slots, "seq", n)));
    }

    private void fire(Event e) {
        if (ctx != null) {
            ctx.eventBus().publish(e);
        }
    }
}
