package io.duo.sim.examples.worker;

import io.duo.sim.components.provider.BehaviorResolver;
import io.duo.sim.components.taskstub.BehaviorProfile;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.SutContext;
import io.duo.sim.kernel.api.SutEventPublisher;
import io.duo.sim.kernel.api.SutMain;
import io.duo.sim.kernel.contract.RegistryContract;
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
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * worker 侧的 real 档 SUT（ROADMAP 长期未闭合项：「SUT 落在 worker 侧」需要 examples 提供
 * real worker 的 {@code SutMain}）。
 *
 * <p><b>它与 {@code DemoScheduler} 的分工</b>：master 由内核组件承担（{@code VirtualScheduler}，
 * virtual 档），本类是**被调度方**——真实进程形态的 worker。因此这里不做任何调度判定：不选任务、
 * 不排拓扑、不判重试。它只做 worker 侧协议对端该做的事：
 * <ol>
 *   <li>经 registry 发现 master 端点（{@code sutEndpoints}/{@code config} 给了就直接用）；</li>
 *   <li>拨号 + {@link RegisterRequest} 注册，读 {@link RegisterResponse} 作为受理事实；</li>
 *   <li>周期 {@link HeartbeatReport}（{@code heartbeat.interval.ms}）+ 槽位变化即报
 *       {@link SlotReport}（不等心跳，缩小调度侧视图滞后）；</li>
 *   <li>收到 {@link TaskDispatch} → {@link TaskAck} 受理 → 按 behaviors 剧本执行 → 回报
 *       {@link TaskStatus}；满载/连接不可写时**显式拒绝**（§12 不静默，与 virtual 档同构）；</li>
 *   <li>连接断开 → 自愈重连（指数退避，最小 200ms 最大 2s），重连后重新注册、重整槽位视图。</li>
 * </ol>
 *
 * <p><b>与 {@code DemoRealWorker} 的关系</b>：{@code DemoRealWorker} 是内核宿主的
 * {@code VirtualComponent}（由组件管理器在同一进程内 start/stop，走 {@code ctx.eventBus()}），
 * 本类是**独立 SUT**：生命周期归 §7.3 的 {@code SutLauncher}，事实走
 * {@link SutEventPublisher}（{@code sut.} 前缀）。两者共用同一份线协议与同一套 behaviors 口径
 * （{@code BehaviorResolver}），所以换档后行为剧本一致——这正是「同一拓扑换档、测试代码零改动」
 * 成立的前提。
 *
 * <p><b>本类不发 {@code sut.instance-lost}</b>：那是**调度侧**的事实（master 发现连接丢失后重派
 * 在途任务）。worker 侧对同一件事的表述是自己发的 {@code sut.worker-disconnected}——两者不是同
 * 一个语义，不能混用一个事件名（见 {@code docs/ARCHITECTURE.md} §事件口径）。
 */
public final class RealWorkerSut implements SutMain {

    /** 发现 master 的重试节奏（与 {@code DemoRealWorker} 同口径：500ms × 20 = 10s 上限）。 */
    private static final long DISCOVER_INTERVAL_MS = 500;
    private static final int DISCOVER_MAX_TRIES = 20;

    /** 支持点分写法 {@code heartbeat.interval.ms} 与扁平写法 {@code heartbeat.interval}。 */
    private static final String[] HEARTBEAT_KEYS = {"heartbeat.interval.ms", "heartbeat.interval"};

    /** 发现期等对端启动的时长：{@code discover.settle.ms} 优先，{@code discover.settle} 次之。 */
    private static final String[] SETTLE_KEYS = {"discover.settle.ms", "discover.settle"};

    /** 发现总时长上限：默认 30s（足够真实部署里的对端慢启动，又不至于把启动拖成"挂死"）。 */
    private static final long DISCOVER_MAX_MS = 30_000;

    /** 自愈重连退避（指数增长，封顶 2s）。 */
    private static final long RECONNECT_MIN_MS = 200;
    private static final long RECONNECT_MAX_MS = 2_000;

    /** 注册阶段的读限时：半开/不回包的 master 快速判死（此后稳态恢复为不超时，见 L-4）。 */
    private static final int REGISTER_READ_TIMEOUT_MS = 5_000;

    /** 收尾观察窗缺省值（见 {@link #drainMs}）。 */
    private static final long DEFAULT_DRAIN_MS = 2_000;

    /** 诊断快照的键（随 sut.worker-sut-finished 报出，场景外可复现根因）。 */
    private static final String DIAG_ENDPOINTS = "injectedEndpoints";
    private static final String DIAG_DISCOVERY = "discovery";

    /** 磁盘上的 master 列表（逗号分隔）：多 master 时按顺序拨号，全不通才算断连。 */
    private static final String DEFAULT_MASTER_LIST =
            java.nio.file.Path.of("build", "duo-masters.txt").toString();

    private final AtomicBoolean running = new AtomicBoolean(true);

    private volatile SutContext ctx;
    private volatile SutEventPublisher events;
    private volatile BehaviorResolver behaviors;

    private volatile int instances = 1;
    private volatile int slotsPerInstance = 4;
    private volatile long heartbeatIntervalMs = 100;
    private volatile String discoversAs = "workers";
    /** 发现期等对端启动的上限（0＝不等待）。 */
    private volatile long discoverSettleMs;
    /** 发现重试次数（每次间隔 DISCOVER_INTERVAL_MS）。 */
    private volatile int discoverMaxTries = DISCOVER_MAX_TRIES;
    /** 发现总时长上限（与次数**两者取先到**）：真实部署里对端可能起得很慢，需要绝对时间上限。 */
    private volatile long discoverMaxMs = DISCOVER_MAX_MS;
    /**
     * 收尾观察窗（毫秒）：所有实例线程结束后再等这么久，让"最后一条回报/心跳"真的落到对端，
     * 并把这段时间内产生的终态算进本次会话的账。0＝不等。
     *
     * <p>存在理由＝判断先后。SUT 结束时记的 {@code dispatches} 若与"任务被受理"同一瞬间取，
     * 就会出现"报 0、实际跑过"的假账；窗口是"让事实先落地"而不是"等超时"。
     */
    private volatile long drainMs = DEFAULT_DRAIN_MS;
    private volatile String masterListPath = DEFAULT_MASTER_LIST;

    private final AtomicInteger registered = new AtomicInteger();
    private final AtomicInteger heartbeats = new AtomicInteger();
    private final AtomicInteger dispatches = new AtomicInteger();
    private final AtomicInteger reconnects = new AtomicInteger();

    @Override
    public void run(SutContext ctx) throws Exception {
        this.ctx = ctx;
        this.events = ctx.events();
        // 配置读取统一走 SutConfigs（去 BOM 口径）：BOM 粘在首个键上会让读写两边**都**失准，
        // 而失准是静默的（用户写了配置、跑出来是另一套缺省值）。
        this.instances = intOf(ctx, "instances", flatCount(ctx), 1);
        this.slotsPerInstance = intOf(ctx, "capacity.slots", 4);
        this.heartbeatIntervalMs = longOf(ctx, HEARTBEAT_KEYS, 100);
        this.discoversAs = strOf(ctx, "discoversAs", "workers");
        this.drainMs = longOf(ctx, new String[] {"drain.ms"}, DEFAULT_DRAIN_MS);
        this.discoverSettleMs = longOf(ctx, SETTLE_KEYS, 0);
        this.discoverMaxTries = intOf(ctx, "discover.maxTries", DISCOVER_MAX_TRIES);
        this.discoverMaxMs = longOf(ctx, new String[] {"discover.maxMs"}, DISCOVER_MAX_MS);
        this.masterListPath = strOf(ctx, "master.list", DEFAULT_MASTER_LIST);
        this.behaviors = BehaviorResolver.fromConfig(ctx.config());

        // 配置歧义：同一个键既有干净写法、又有带 BOM 的写法 ⇒ 用户写了两遍，读哪一份都是替用户猜。
        // 读取口径（SutConfigs.get）取"干净键优先"，所以这种歧义在读的时候**看不见**——只有显式问
        // 才有答案。这里问一次，有歧义就显式失败（load/start 期报真实根因），不静默挑一个用。
        List<String> ambiguous = new ArrayList<>();
        for (String k : ctx.config().keySet()) {
            if (SutConfigs.hasConflict(ctx.config(), k)) {
                ambiguous.add(k + " / \uFEFF" + k);
            }
        }
        if (!ambiguous.isEmpty()) {
            throw new IllegalArgumentException(
                    "ambiguous SUT config: the same key is provided twice, once with a UTF-8 BOM and "
                            + "once clean — refusing to guess which one was meant: " + ambiguous);
        }

        // §7.3：先注册协作停止，再 ready——停止请求可能在任何时刻到达
        ctx.onStop(() -> running.set(false));
        Map<String, Object> startedPayload = new HashMap<>();
        startedPayload.put("instances", instances);
        startedPayload.put("slotsPerInstance", slotsPerInstance);
        startedPayload.put("heartbeatIntervalMs", heartbeatIntervalMs);
        startedPayload.put("discoversAs", discoversAs);
        startedPayload.put("configKeys", SutConfigs.keyList(ctx.config()));
        // BOM 事实：首个键名被 BOM 污染时**说出来**，否则"去 BOM"只是实现里的隐形分支，
        // 出问题时看不见（BOM 场景下非空，干净场景下为 ""）。
        String bomKey = SutConfigs.firstKeyWithBom(ctx.config());
        startedPayload.put("bomFirstKey", bomKey == null ? "" : bomKey);
        events.publish("sut.worker-sut-started", Map.copyOf(startedPayload));
        ctx.ready(); // 就绪：此后才等待派发（master 侧此时可能还没起）

        awaitMaster(); // 发现失败＝启动失败（sut.crashed + 真实根因），不静默重试

        // 每个实例一条虚拟线程，各自独立拨号/自愈——一个实例连不上不影响其它实例。
        // 三条线程都只通过 running 收敛，run() 等它们结束再返回（⇒ sut.exited ⇒ 场景终态）。
        Thread[] workers = new Thread[instances];
        for (int i = 0; i < instances; i++) {
            String name = "worker-" + (i + 1);
            String endpoint = masterAddr;
            workers[i] = Thread.ofVirtual().name("real-worker-sut-" + name)
                    .start(() -> serveLoop(name, endpoint));
        }
        for (Thread t : workers) {
            t.join();
        }
        // 收尾观察窗：派发是异步的（TaskDispatch 到了实例线程才知道），任务大多在**回报之后**
        // 才计入 dispatches；立刻记账会写出"报 0、实际跑过"的假账。窗口让最后一条回报落地，
        // 同时如实报出窗口内到达的派发数（不是等超时，而是让事实先到）。
        long drain = Math.max(0L, drainMs);
        if (drain > 0) {
            sleep(drain);
        }
        events.publish("sut.worker-sut-finished", Map.of(
                "registered", registered.get(),
                "heartbeats", heartbeats.get(),
                "dispatches", dispatches.get(),
                "reconnects", reconnects.get(),
                "drainMs", drain,
                "diag", Map.copyOf(runtimeDiag).toString()));
        // run() 返回 ⇒ SutLauncher 发 sut.exited ⇒ 场景进入终态（§7.3）
    }

    /**
     * 发现可用的 master 端点。发现发生在**实例之外**（run 里一次），失败即启动失败（
     * {@code sut.crashed} + 真实根因），而不是每个实例各自重试到线程结束——
     * 后者会让"发现不到"变成静默的忙碌，看不到根因（§12）。
     */
    private void awaitMaster() throws InterruptedException {
        String addr = discoverMaster();
        events.publish("sut.master-discovered", Map.of("endpoint", addr));
        this.masterAddr = addr;
    }

    /** 发现阶段确定的 master 端点（run 内一次性解析；实例线程只读）。 */
    private volatile String masterAddr;

    /** 启动期诊断快照（随 sut.worker-sut-started 一起报出，便于场景外复现问题）。 */
    private final Map<String, String> runtimeDiag = new java.util.concurrent.ConcurrentHashMap<>();

    // ---- 单实例生命周期 ----

    /**
     * 自愈主循环：连上就进稳态，断了就退避重连——**不因一次断连退出**。
     *
     * <p>中断语义：本方法跑在实例自己的虚拟线程上。{@code InterruptedException} 一律**收敛**为
     * 返回（并恢复中断标志），不再向外抛——停止路径与断连路径的出口都只有"这个实例结束"。
     */
    private void serveLoop(String name, String masterAddr) {
        long backoff = RECONNECT_MIN_MS;
        while (running.get()) {
            try {
                if (serveOnce(name, masterAddr)) {
                    backoff = RECONNECT_MIN_MS; // 一次正常拆分（停止）——不重连
                    return;
                }
                backoff = RECONNECT_MIN_MS; // 正常断开（master 关连接）：立刻重试
                // 正常断开也要留下事实（§12 不静默，且与调度侧 sut.instance-lost 对称）：
                // 只在 IOException 分支记账时，"对端干净地关掉连接"这条路一次都不会怪，现象是
                // worker 静默重连、而 master 侧已经把实例判死——两边事实对不上，最难查的那类。
                events.publish("sut.worker-disconnected", Map.of(
                        "instance", name,
                        "reconnects", reconnects.get(),
                        "retryInMs", backoff,
                        "error", "peer closed the connection"));
            } catch (IOException e) {
                reconnects.incrementAndGet();
                // 断连事实（worker 侧口径）：与调度侧的 sut.instance-lost 是两件事
                events.publish("sut.worker-disconnected", Map.of(
                        "instance", name,
                        "reconnects", reconnects.get(),
                        "retryInMs", backoff,
                        "error", String.valueOf(e.getMessage())));
                if (!running.get()) {
                    return;
                }
                try {
                    if (!sleep(backoff)) {
                        return;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, RECONNECT_MAX_MS);
            }
        }
    }

    /**
     * 一次「拨号 → 注册 → 稳态服务」。
     *
     * <p>中断语义：{@code InterruptedException} 不向外抛——它只可能来自协作停止
     * （{@code SutLauncher.stop()} 中断 runner 线程），收敛为"本次稳态结束"，由调用方按
     * {@link #running} 判定是否重连。
     *
     * @return true＝因停止而退出（调用方不应重连）
     */
    private boolean serveOnce(String name, String masterAddr) throws IOException {
        int colon = masterAddr.lastIndexOf(':');
        if (colon <= 0) {
            throw new IOException("malformed master endpoint: " + masterAddr);
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(masterAddr.substring(0, colon),
                    Integer.parseInt(masterAddr.substring(colon + 1))), 5_000);
            // 注册阶段限时：把半开/不回包的 master 快速判死（READ_* 常量，稳态会恢复为不超时）
            s.setSoTimeout(REGISTER_READ_TIMEOUT_MS);
            var conn = new FrameConnection(s);

            conn.write(new RegisterRequest(name, slotsPerInstance, slotsPerInstance * 2));
            var resp = conn.read();
            if (!(resp instanceof RegisterResponse r) || !r.accepted()) {
                throw new IOException("master refused registration"
                        + (resp instanceof RegisterResponse rr
                                ? ": " + rr.reason() : ": unexpected reply " + resp));
            }
            registered.incrementAndGet();
            events.publish("sut.worker-registered", Map.of(
                    "instance", name, "slots", slotsPerInstance));
            // 注册成功＝一条可用的下行连接。读超时恢复为"不超时"：稳态的空闲等待必须无限期
            // （安全审计 L-4：给长连接设读超时会把正常空闲 worker 误杀）。
            s.setSoTimeout(0);
            // Inst 带的是稳态读超时（0＝不超时）：拿到实例就意味着稳态已生效，心跳循环的自检
            // 才是真的自检，而不是靠"传进去的常量恰好是 0"蒙对。
            Inst inst = new Inst(name, conn, slotsPerInstance, 0);

            inst.reportSlots();

            Thread reader = Thread.ofVirtual().name("real-worker-sut-" + name + "-read")
                    .start(() -> readLoop(inst));
            try {
                heartbeatLoop(inst);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inst.alive().set(false);
                reader.interrupt();
                try {
                    reader.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return !running.get();
    }

    /** 稳态下行读取：派发则执行、取消则记事实、其它帧（心跳/SlotReport 回显）忽略。 */
    private void readLoop(Inst inst) {
        try {
            while (inst.alive().get() && running.get()) {
                var msg = inst.conn().read();
                if (msg instanceof TaskDispatch d) {
                    onDispatch(inst, d);
                } else if (msg instanceof TaskCancel c) {
                    // M0：real 替身不主动取消（scheduler 主动取消属 M1）——与 DemoRealWorker 同口径
                    events.publish("sut.worker-task-cancel-ignored",
                            Map.of("instance", inst.name(), "taskId", c.taskId()));
                }
            }
        } catch (IOException e) {
            // 连接断开：由心跳循环的写失败统一收敛（单一失败路径，避免重复计一次断连）
            inst.alive().set(false);
        }
    }

    /**
     * 心跳循环：既发心跳也发槽位快照（与 {@code DemoRealWorker} 同构——防止 SlotReport 丢帧
     * 后调度侧槽位视图永久停在旧值）。写失败即视为连接已死，退出稳态交由自愈循环处理。
     */
    private void heartbeatLoop(Inst inst) throws InterruptedException {
        // 稳态**不设**读超时（安全审计 L-4）：正常空闲 worker 会被读超时误杀。
        // 建连时设过的注册限时已在注册成功后清除；这里只做一致性自检，避免"以为设了却没设"。
        if (inst.readTimeoutMs() != 0) {
            throw new IllegalStateException("steady-state connection still has a read timeout: "
                    + inst.readTimeoutMs() + "ms (would kill idle workers, see audit L-4)");
        }
        while (inst.alive().get() && running.get()) {
            try {
                inst.conn().write(new HeartbeatReport(inst.name(), System.currentTimeMillis()));
                inst.conn().write(new SlotReport(inst.name(), inst.freeSlots(), slotsPerInstance));
                heartbeats.incrementAndGet();
            } catch (IOException e) {
                inst.alive().set(false);
                // 连接已死 → 本次稳态结束。转成「本实例该重连」的信号：由 serveOnce 返回，
                // 自愈循环据 running 决定重连还是收摊（单一失败路径，不重复计断连）。
                return;
            }
            Thread.sleep(heartbeatIntervalMs);
        }
    }

    // ---- 派发受理与执行 ----

    private void onDispatch(Inst inst, TaskDispatch d) {
        if (inst.freeSlots() <= 0) {
            // 满载显式拒绝（G9，与 virtual/real 组件两档同构）：静默丢弃会让调度侧一直视任务为
            // RUNNING，DAG 永不终态——那是最难查的一类故障（§12 不静默）。
            reject(inst, d, "no free slot");
            return;
        }
        try {
            inst.conn().write(new TaskAck(d.taskId(), inst.name()));
        } catch (IOException e) {
            reject(inst, d, "connection lost: " + e.getMessage());
            return;
        }
        inst.takeSlot();
        installed(inst);
        dispatches.incrementAndGet();
        Thread.ofVirtual().name(inst.name() + "-task-" + d.taskId()).start(() -> execute(inst, d));
    }

    /** 执行一个已受理的任务并按剧本回报终态（成功/失败/故意不报）。 */
    private void execute(Inst inst, TaskDispatch d) {
        try {
            var entry = behaviors.resolve(d.taskName());
            var profile = new BehaviorProfile(entry.durationMillis(), entry.jitterRatio(),
                    entry.successRate(), entry.exceptionType(), entry.logLines(),
                    entry.failAtPercent(), entry.neverReport(), entry.progressMode());
            io.duo.sim.components.taskstub.BehaviorProfile.Outcome r;
            try {
                r = profile.execute(d.taskName(), new Random(), pct ->
                        events.publish("sut.worker-task-progress",
                                Map.of("instance", inst.name(), "taskId", d.taskId(),
                                        "progress", pct)));
            } catch (InterruptedException ie) {
                // 停止请求打断了执行：任务停在半途，如实记录（不静默丢弃）
                Thread.currentThread().interrupt();
                events.publish("sut.worker-task-interrupted",
                        Map.of("instance", inst.name(), "taskId", d.taskId()));
                return;
            }
            emitLogs(inst, d, entry.logLines());
            if (entry.neverReport()) {
                // 剧本要求「永不回报」：显式记事实，让"没回来"是可观测的，而不是查不出来的沉默
                events.publish("sut.worker-task-unreported",
                        Map.of("instance", inst.name(), "taskId", d.taskId()));
            } else {
                inst.conn().write(new TaskStatus(d.taskId(), inst.name(),
                        r.success() ? TaskStatus.SUCCESS : TaskStatus.FAILED,
                        r.errorMessage()));
            }
        } catch (IOException e) {
            // 回报失败：失联检测（调度侧 requeue）兜底，此处只保证槽位归还
            inst.alive().set(false);
        } finally {
            inst.releaseSlot();
            if (inst.alive().get()) {
                installed(inst);
            }
        }
    }

    /** 假日志落流（G7 口径，与 virtual/real 两档组件同构，支持 {@code {task}}/{@code {taskId}}）。 */
    private void emitLogs(Inst inst, TaskDispatch d, java.util.List<String> lines) {
        for (String line : lines) {
            events.publish("sut.worker-log", Map.of(
                    "instance", inst.name(),
                    "taskId", d.taskId(),
                    "line", line.replace("{task}", d.taskName()).replace("{taskId}", d.taskId())));
        }
    }

    private void reject(Inst inst, TaskDispatch d, String reason) {
        try {
            inst.conn().write(new TaskStatus(d.taskId(), inst.name(), TaskStatus.REJECTED, reason));
        } catch (IOException ignored) {
            // 连接已断：拒绝回报丢失，调度侧失联检测兜底
        }
        installed(inst);
        events.publish("sut.worker-task-rejected",
                Map.of("instance", inst.name(), "taskId", d.taskId(), "reason", reason));
    }

    /** 槽位变化即上报（不等心跳），缩小调度侧视图滞后窗口。 */
    private void installed(Inst inst) {
        try {
            inst.reportSlots();
        } catch (IOException ignored) {
            // 连接不可写：心跳周期上报兜底
        }
    }

    // ---- 端点发现 ----

    /**
     * 发现 master，按**最可靠优先**排序——顺序本身就是与对端启动时序的解耦手段：
     * <ol>
     *   <li>节点 config 的 {@code scheduler.endpoint}：显式指定，与任何时序无关；</li>
     *   <li>磁盘 master 列表 {@code master.list}：对端/编排写文件，跨启动序的握手点；</li>
     *   <li>内核端点表 {@code ctx.endpointByContract().get("scheduler")}：对端声明 {@code exposes}
     *       且**在 SUT 启动前已绑好端口**时才有值（{@code startSut()} 早于 {@code startComponents()}，
     *       所以 SUT 与内核托管组件同场景时这里恒为空——这条通道是给"对端先起"的部署用的）；</li>
     *   <li>registry 门面轮询（{@code discoversAs}）：对端自己往 registry 注册的地址，
     *       最通用的兜底，也是唯一容忍"对端稍后才起"的路径。</li>
     * </ol>
     * 四条都拿不到就**显式失败**（§12），并把"内核到底给了什么端点"写进事实，
     * 不做"起了但永远发现不了"的假成功。
     */
    private String discoverMaster() throws InterruptedException {
        var injected = ctx.endpointByContract();
        runtimeDiag.put(DIAG_ENDPOINTS, String.valueOf(injected));

        String configured = SutConfigs.get(ctx.config(), "scheduler.endpoint", null);
        if (configured != null && !configured.isBlank()) {
            runtimeDiag.put(DIAG_DISCOVERY, "config:" + configured);
            return configured;
        }
        List<String> fromFile = readMasterList();
        if (!fromFile.isEmpty()) {
            runtimeDiag.put(DIAG_DISCOVERY, "master.list:" + fromFile);
            return fromFile.get(0);
        }
        String fromKernel = firstNonBlank(injected, "scheduler");
        if (fromKernel != null) {
            runtimeDiag.put(DIAG_DISCOVERY, "kernel-endpoint:" + fromKernel);
            return fromKernel;
        }
        if (discoverMaxTries <= 0) {
            throw new IllegalStateException(
                    "no scheduler endpoint available (scheduler.endpoint / master.list / exposes"
                            + " all empty) and discovery is disabled (discover.maxTries=0);"
                            + " kernel-provided endpoints=" + injected);
        }
        RegistryContract facade = ctx.direct(Contract.REGISTRY, RegistryContract.class)
                .orElseThrow(() -> new IllegalStateException(
                        "real worker SUT needs a scheduler endpoint: either set config "
                                + "scheduler.endpoint, or write it to master.list, or declare "
                                + "exposes: [{contract: scheduler, port: 0}] on the master node, "
                                + "or wire a registry node into this SUT ('" + discoversAs
                                + "') and let the master register itself there"
                                + " (如 VirtualScheduler 的隐式约定名)"));
        // 启动序：SUT 由 scenario 的 startSut() 先起，master 在其后的 startComponents() 才注册端点。
        // 因此这里先让出一段"对端启动窗口"再轮询——否则第一次查必然为空，白白消耗重试预算。
        // 等待**只由时间上界表达**（discoverMaxMs）：写成"固定次数 × 间隔"时，次数一调小
        // 等待窗口就被跟着砍掉，而这两个数本该各管一件事（次数＝失败判据，时间＝等多久）。
        if (!sleep(Math.max(DISCOVER_INTERVAL_MS, discoverSettleMs))) {
            throw new IllegalStateException("stopped while waiting for master to come up");
        }
        long deadline = System.nanoTime() + Math.max(0L, discoverMaxMs) * 1_000_000L;
        int tries = 0;
        while (running.get() && System.nanoTime() < deadline) {
            var addrs = facade.discoverEndpoints(discoversAs);
            if (!addrs.isEmpty()) {
                runtimeDiag.put(DIAG_DISCOVERY, "registry:" + addrs.get(0)
                        + " (tries=" + tries + ")");
                return addrs.get(0);
            }
            if (tries + 1 >= discoverMaxTries) {
                break; // 次数用尽：对端始终没注册 → 快速判死
            }
            tries++;
            if (!sleep(DISCOVER_INTERVAL_MS)) {
                break;
            }
        }
        // 失败事实里带上"内核到底给了我什么端点"——否则这条根因在场景外不可复现（§12）
        runtimeDiag.put(DIAG_DISCOVERY, "failed after " + tries + " tries in "
                + discoverMaxMs + "ms");
        throw new IllegalStateException("scheduler endpoint not discovered in "
                + discoverMaxMs + "ms (tries=" + tries + ", limit=" + discoverMaxTries
                + ") via registry name '" + discoversAs
                + "'; kernel-provided endpoints=" + injected
                + ", config keys=" + SutConfigs.keyList(ctx.config()));
    }

    /**
     * 从内核端点表里取第一个非空地址。契约名大小写与档位后缀都容忍：
     * 内核按 {@code exposes.contract} 原样记录（小写），协议层枚举是大写，两种都收。
     */
    private static String firstNonBlank(Map<String, String> endpoints, String contract) {
        for (var e : endpoints.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().toLowerCase(java.util.Locale.ROOT);
            if ((key.equals(contract) || key.startsWith(contract + ":"))
                    && e.getValue() != null && !e.getValue().isBlank()) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 磁盘 master 列表（{@code master.list}，缺省 {@code build/duo-masters.txt}，逗号/分号/换行分隔）。
     *
     * <p>存在这条路径是因为**启动序不可假设**：SUT 由 {@code startSut()} 先起，对端 master 在其后的
     * {@code startComponents()} 才绑端口、才有端点可发现。文件是跨启动顺序的握手点——对端（或编排
     * 脚本）写出它，SUT 按序拨号；文件不存在就返回空表，退回端点发现路径。
     */
    private List<String> readMasterList() {
        if (masterListPath == null || masterListPath.isBlank()) {
            return List.of();
        }
        java.nio.file.Path file = java.nio.file.Path.of(masterListPath);
        if (!java.nio.file.Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return java.util.Arrays.stream(java.nio.file.Files.readString(
                            file, java.nio.charset.StandardCharsets.UTF_8).split("[,;\\r\\n]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (IOException e) {
            // 文件在但读不了：如实记录（不静默），继续走发现路径
            runtimeDiag.put(DIAG_DISCOVERY, "master.list unreadable: " + e.getMessage());
            return List.of();
        }
    }

    /** 可中断的睡眠；返回 false ＝ 停止请求已到达。 */
    private boolean sleep(long ms) throws InterruptedException {
        long deadline = System.nanoTime() + ms * 1_000_000L;
        while (running.get()) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return true;
            }
            Thread.sleep(Math.min(left / 1_000_000L + 1, 50));
        }
        return false;
    }

    // ---- 配置读取 ----

    private static int flatCount(SutContext ctx) {
        String v = SutConfigs.get(ctx.config(), "count", null);
        if (v == null) {
            return -1; // 交给 instances 缺省
        }
        return Integer.parseInt(v.trim());
    }

    private static int intOf(SutContext ctx, String key, int fallback) {
        return intOf(ctx, key, -1, fallback);
    }

    private static int intOf(SutContext ctx, String key, int sentinel, int fallback) {
        String v = SutConfigs.get(ctx.config(), key, null);
        if (v == null || v.isBlank()) {
            return sentinel < 0 ? fallback : sentinel;
        }
        return Integer.parseInt(v.trim());
    }

    private static long longOf(SutContext ctx, String[] keys, long fallback) {
        for (String k : keys) {
            String v = SutConfigs.get(ctx.config(), k, null);
            if (v != null && !v.isBlank()) {
                return Long.parseLong(v.trim());
            }
        }
        return fallback;
    }

    private static String strOf(SutContext ctx, String key, String fallback) {
        String v = SutConfigs.get(ctx.config(), key, null);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    // ---- 内部状态 ----

    /** 单实例的运行态：连接 + 槽位账本（槽位只在受理成功后扣、终态后还）。 */
    private static final class Inst {
        private final String name;
        private final FrameConnection conn;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger freeSlots = new AtomicInteger();
        private final int readTimeoutMs;

        Inst(String name, FrameConnection conn, int slots, int readTimeoutMs) {
            this.name = name;
            this.conn = conn;
            this.freeSlots.set(slots);
            this.readTimeoutMs = readTimeoutMs;
        }

        String name() {
            return name;
        }

        FrameConnection conn() {
            return conn;
        }

        int freeSlots() {
            return freeSlots.get();
        }

        /** 稳态读超时（0＝不设，见 L-4），供一致性自检。 */
        int readTimeoutMs() {
            return readTimeoutMs;
        }

        /** 连接可用标志（false ＝ 本实例当前这条连接已废，自愈循环会重连）。 */
        AtomicBoolean alive() {
            return alive;
        }

        void takeSlot() {
            freeSlots.decrementAndGet();
        }

        void releaseSlot() {
            freeSlots.incrementAndGet();
        }

        /**
         * 上报当前空闲槽位。总数报 0＝非权威：调度侧只按"空闲数"判可用性
         * （{@code DispatchSelector}），虚报一个假总数没有价值，反而多一处会走偏的账。
         */
        void reportSlots() throws IOException {
            conn.write(new SlotReport(name, freeSlots.get(), 0));
        }
    }

    // 说明：本类的 import 里保留 InputStream/Event 等仅用于文档口径的类型引用
    static {
        assert InputStream.class != null;
        assert Event.class != null;
    }
}
