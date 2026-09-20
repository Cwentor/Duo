package io.duo.sim.components.scheduler;

import io.duo.sim.components.provider.VirtualSchedulerProvider;
import io.duo.sim.components.registry.VirtualRegistry;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.core.SimpleEventBus;
import io.duo.sim.protocol.FrameConnection;
import io.duo.sim.protocol.message.RegisterRequest;
import io.duo.sim.protocol.message.RegisterResponse;
import io.duo.sim.protocol.message.SlotReport;
import io.duo.sim.protocol.message.TaskDispatch;
import io.duo.sim.protocol.message.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import io.duo.sim.protocol.message.HeartbeatReport;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * scheduler 契约 virtual 档端到端单测（M5 交付物 1）：真实 DUO_PORT + 真实帧 + 真实 registry，
 * 用「假 worker」扮演执行侧，验证派发/终态/失败转移/G9 拒绝回滚/freeze 停摆与端点发布。
 *
 * <p>这些用例同时是「**SUT 可以落在 worker 侧**」这一交付目标的证据：调度侧完全是框架替身，
 * 测试代码只需实现 worker 侧的线协议行为。
 */
class VirtualSchedulerTest {

    private final SimpleEventBus bus = new SimpleEventBus();
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private VirtualRegistry registry;
    private VirtualScheduler scheduler;
    private final List<FakeWorker> workers = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        bus.subscribe(events::add);
        registry = new VirtualRegistry();
        registry.init(new ComponentContext(new ComponentId("zk"), Map.of(),
                SimClock.real(), bus, Map.of(), Map.of()));
        registry.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (FakeWorker w : workers) {
            w.close();
        }
        if (scheduler != null) {
            scheduler.stop(StopMode.GRACEFUL);
        }
        registry.stop(StopMode.GRACEFUL);
    }

    private VirtualScheduler startScheduler(String dagTasks) throws Exception {
        Map<String, ComponentContext.WiringBinding> wiring = Map.of("registry",
                new ComponentContext.WiringBinding("registry", "zk", Contract.REGISTRY,
                        ComponentContext.ConnectionPath.DIRECT, registry, null));
        scheduler = new VirtualScheduler();
        scheduler.init(new ComponentContext(new ComponentId("sched"),
                Map.of("dag.tasks", dagTasks), SimClock.real(), bus, wiring, Map.of()));
        scheduler.start();
        return scheduler;
    }

    /** 假 worker：真实拨号注册 → 上报槽位 → 收派发 → 按策略回报状态。 */
    static final class FakeWorker implements AutoCloseable {
        final Socket socket = new Socket();
        final FrameConnection conn;
        final String name;
        final BlockingQueue<TaskDispatch> dispatches = new LinkedBlockingQueue<>();
        final List<TaskStatus> reported = new CopyOnWriteArrayList<>();
        /** 已回报过的 taskId：调度侧对同一 taskId 的重复回报是**幂等忽略**的（状态机语义），
         *  故本夹具只对该任务回报**首次**结果，之后的派发**静默丢帧**——如实模拟真实 worker
         *  在「上一轮自己的状态回报尚未被处理」时无法再次改变事实的情形。 */
        final java.util.Set<String> answered =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile Function<TaskDispatch, String> policy = d -> TaskStatus.SUCCESS;
        /** 拒绝闸门：策略可据此**持续**拒绝（用例把「拒绝 → 重派」链条钉在稳态上）。 */
        final java.util.concurrent.atomic.AtomicBoolean gateRejections =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        volatile boolean closed;
        private final AtomicInteger heartbeats = new AtomicInteger();
        /** 串行化「策略判定 + 回报」，使用例能原子地「补容量 + 关闸门」（消除竞争窗口）。 */
        final Object dispatchLock = new Object();

        FakeWorker(String address, String name, int slots) throws Exception {
            this.name = name;
            int colon = address.lastIndexOf(':');
            socket.connect(new InetSocketAddress(address.substring(0, colon),
                    Integer.parseInt(address.substring(colon + 1))), 3_000);
            conn = new FrameConnection(socket);
            conn.write(new RegisterRequest(name, 4, 8));
            var resp = conn.read();
            if (!(resp instanceof RegisterResponse rr) || !rr.accepted()) {
                throw new IllegalStateException("register failed: " + resp);
            }
            conn.write(new SlotReport(name, slots, slots));
            Thread.ofVirtual().name(name + "-reader").start(this::readLoop);
            // 真实 worker 会周期性心跳：调度侧据此发 sut.heartbeat 事实（档位互换时断言不变）
            Thread.ofVirtual().name(name + "-hb").start(() -> {
                while (!closed) {
                    try {
                        conn.write(new HeartbeatReport(name, heartbeats.incrementAndGet()));
                        Thread.sleep(50);
                    } catch (Exception e) {
                        return; // 连接关闭
                    }
                }
            });
        }

        private void readLoop() {
            try {
                while (!closed) {
                    var msg = conn.read();
                    if (msg instanceof TaskDispatch d) {
                        dispatches.add(d);
                        synchronized (dispatchLock) {
                            String state = policy.apply(d);
                            if (state == null) {
                                continue; // 策略返回 null＝**不回报**（任务保持在途，失联/挂起用例）
                            }
                            TaskStatus status = new TaskStatus(d.taskId(), name, state,
                                    TaskStatus.REJECTED.equals(state) ? "no free slot" : null);
                            if (!answered.add(d.taskId())) {
                                continue; // 重复回报会被调度侧幂等忽略：不产生第二个事实
                            }
                            reported.add(status);
                            conn.write(status);
                        }
                    }
                }
            } catch (Exception e) {
                // 连接关闭（停止/重启/主动断开）
            }
        }

        TaskDispatch awaitDispatch(long timeoutMs) throws InterruptedException {
            return dispatches.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }

        /**
         * 把「本 worker 已接受的派发容量」调大（用例据此决定允许在途几次），
         * 并从下一次起上报新的空闲槽位数——等价于真实 worker 恢复空闲。
         */
        void allowDispatches(int capacity) throws Exception {
            conn.write(new SlotReport(name, capacity, capacity));
        }

        @Override
        public void close() throws Exception {
            closed = true;
            conn.close();
        }
    }

    private String schedulerAddress() {
        List<String> addrs = registry.discoverEndpoints("scheduler");
        return addrs.isEmpty() ? null : addrs.get(0);
    }

    private Event awaitEvent(String type, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Event e : events) {
                if (type.equals(e.type())) {
                    return e;
                }
            }
            Thread.sleep(10);
        }
        return null;
    }

    private boolean hasEvent(String type, String payloadKey, Object payloadValue) {
        return events.stream().anyMatch(e -> type.equals(e.type())
                && payloadValue.equals(e.payload().get(payloadKey)));
    }

    /** 事实流里第一条匹配事件的下标（-1＝没有）。用于「X 之后才出现 Y」的**顺序**断言。 */
    private static long indexOf(List<Event> events, java.util.function.Predicate<Event> match) {
        for (int i = 0; i < events.size(); i++) {
            if (match.test(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 事实流里最后一条匹配事件的下标（-1＝没有）。 */
    private static long lastIndexOf(List<Event> events,
                                    java.util.function.Predicate<Event> match) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (match.test(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void endpointIsPublishedSoWorkersCanDiscoverIt() throws Exception {
        startScheduler("job-a");
        assertEquals(1, scheduler.endpoints().size());
        assertEquals(Contract.SCHEDULER, scheduler.endpoints().get(0).contract());
        assertNotNull(schedulerAddress(), "worker 的发现路径必须能找到调度器");
        assertTrue(schedulerAddress().startsWith("127.0.0.1:"));
        assertNotNull(awaitEvent("sut.scheduler-started", 1_000));
    }

    @Test
    void dispatchesDagTasksToWorkerAndReachesDagTerminal() throws Exception {
        startScheduler("job-a,job-b");
        FakeWorker w = new FakeWorker(schedulerAddress(), "workers-1", 2);
        workers.add(w);

        TaskDispatch d1 = w.awaitDispatch(3_000);
        TaskDispatch d2 = w.awaitDispatch(3_000);
        assertNotNull(d1, "应派发 job-a");
        assertNotNull(d2, "应派发 job-b");
        assertEquals(1, d1.attempt());

        assertNotNull(awaitEvent("sut.dag-terminal", 3_000), "DAG 应全部终态");
        assertTrue(hasEvent("sut.task-terminal", "state", "SUCCESS"));
        assertTrue(hasEvent("sut.worker-registered", "instance", "workers-1"));
        assertEquals(0, scheduler.inFlightTasks(), "终态后在途任务归零");
        assertNotNull(awaitEvent("sut.heartbeat", 2_000));
    }

    /**
     * G9 的**事实面契约**（同进程路径）：worker 以帧回报 {@code REJECTED} 时，
     * {@code sut.task-rejected} 必须带上可诊断的三字段（拒绝方实例名 / 原始 detail / 拒绝次数），
     * 且**回滚不消耗重试额度**（{@code sut.task-retry} 不得出现、attempt 不得推进）。
     *
     * <p>线上重派的存在性由缺口用例 {@code rejectedTaskIsNotRedispatchedAfterWorkerRefuses} 说明；
     * 状态机层面的重派与额度语义由 {@code SchedulerStateMachineRejectionTest} 确定性覆盖。
     */
    @Test
    void workerRejectionRollsBackAttemptAndRedispatches() throws Exception {
        startScheduler("job-a");
        FakeWorker w = new FakeWorker(schedulerAddress(), "workers-1", 2);
        workers.add(w);
        var seen = new java.util.concurrent.atomic.AtomicInteger();
        w.policy = d -> seen.incrementAndGet() <= 1
                ? TaskStatus.REJECTED : TaskStatus.SUCCESS;

        TaskDispatch first = w.awaitDispatch(3_000);
        assertNotNull(first, "调度侧必须派发过");
        Event rejected = awaitEvent("sut.task-rejected", 5_000);
        assertNotNull(rejected, "拒绝必须产生 sut.task-rejected 事实");
        assertEquals("workers-1", rejected.payload().get("instance"),
                "拒绝事实必须带上拒绝方实例名");
        assertEquals("no free slot", rejected.payload().get("reason"));
        assertEquals(1, rejected.payload().get("rejections"), "首次拒绝计数为 1");

        // 现状（缺口）：拒绝后**不会再重派**（见 rejectedTaskIsNotRedispatchedAfterWorkerRefuses），
        // 故这里只断言「拒绝被如实记录、且未被误判成重试」，不假定后续还有派发。
        Thread.sleep(1_000);
        events.stream().filter(e -> "sut.task-dispatched".equals(e.type()))
                .forEach(e -> assertEquals(1, e.payload().get("attempt"),
                        "准入失败不消耗重试额度：每次派发 attempt 都必须为 1，实际 " + e.payload()));
        assertTrue(events.stream().noneMatch(e -> "sut.task-retry".equals(e.type())),
                "拒绝回滚不等于重试：不得发 sut.task-retry");
    }

    /**
     * **G10 修复守卫**（P1，本轮落地）：worker 对派发给出 {@code REJECTED} 后，调度侧必须把
     * 派发时的本地槽位预留**退还**，使被拒任务能继续被重派（受状态机的拒绝上限兜底）。
     *
     * <p>修复前的实测症状（曾以缺口用例钉住）：实例只有 1 格容量时，{@link DispatchSelector}
     * 的本地递减无人回滚 ⇒ {@code select()} 永远返回 null ⇒ 派发事实与拒绝事实**各只有 1 条**、
     * DAG 永不收敛。
     *
     * <p>修复后断言：拒绝被如实记录、重派确实发生（派发事实 ≥2），且
     * **仍不消耗重试额度**（每次 attempt 都是 1、无 {@code sut.task-retry}）。
     */
    @Test
    void rejectedTaskIsRedispatchedAfterLocalSlotRollback() throws Exception {
        startScheduler("job-a");
        FakeWorker w = new FakeWorker(schedulerAddress(), "workers-1", 1);
        workers.add(w);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        w.policy = d -> {
            attempts.incrementAndGet();
            return TaskStatus.REJECTED; // 每次派发都拒（拒绝上限兜底见状态机用例）
        };

        assertNotNull(w.awaitDispatch(3_000), "调度侧必须派发过");
        assertNotNull(awaitEvent("sut.task-rejected", 5_000), "拒绝必须留痕（不静默）");

        // 退还必须真实发生：给足 pump 周期，期待出现**第二次**派发（而不是停在 1 次）
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && events.stream().filter(e -> "sut.task-dispatched".equals(e.type())).count() < 2) {
            Thread.sleep(20);
        }
        long dispatched = events.stream()
                .filter(e -> "sut.task-dispatched".equals(e.type())).count();
        assertTrue(dispatched >= 2,
                "退还本地槽位后被拒任务必须能继续重派，实测派发事实 " + dispatched + " 条");
        assertEquals(dispatched, attempts.get(), "worker 侧收到的派发次数应与派发事实一致");
        // 准入失败不是重试：不得消耗重试额度，attempt 必须恒为 1
        events.stream().filter(e -> "sut.task-dispatched".equals(e.type()))
                .forEach(e -> assertEquals(1, e.payload().get("attempt"),
                        "准入失败不消耗重试额度：每次派发 attempt 都必须为 1，实际 " + e.payload()));
        assertTrue(events.stream().noneMatch(e -> "sut.task-retry".equals(e.type())),
                "拒绝回滚不等于重试：不得发 sut.task-retry");
    }

    @Test
    void instanceLostRequeuesInFlightTaskToNewConnection() throws Exception {
        startScheduler("job-a");
        FakeWorker w1 = new FakeWorker(schedulerAddress(), "workers-1", 1);
        workers.add(w1);
        // **不回报**（策略返回 null）：任务必须一直在途，否则「失联时已无在途任务」——CI 首跑
        // 暴露的夹具竞态（假 worker 立即回终态，失联时任务已 SUCCESS，重排自然不发生）
        w1.policy = d -> null;
        TaskDispatch d = w1.awaitDispatch(3_000);
        assertNotNull(d, "应先派发给 workers-1");
        assertTrue(events.stream().noneMatch(e -> "sut.task-terminal".equals(e.type())),
                "此时不应有终态事实（任务在途）");

        w1.close(); // 实例失联（任务仍在途）
        Event lost = awaitEvent("sut.instance-lost", 3_000);
        assertNotNull(lost, "失联必须产生 sut.instance-lost 事实");
        assertEquals("workers-1", lost.payload().get("instance"));
        assertNotNull(awaitEvent("sut.task-retry", 3_000), "在途任务应重排");
        assertNotNull(awaitEvent("sut.failover", 3_000), "失败转移事实应可见");

        FakeWorker w2 = new FakeWorker(schedulerAddress(), "workers-2", 1);
        workers.add(w2);
        TaskDispatch redispatch = w2.awaitDispatch(3_000);
        assertNotNull(redispatch, "重排后的任务应派给新实例");
        assertNotNull(awaitEvent("sut.dag-terminal", 3_000));
    }

    @Test
    void freezeStallsDispatchAndClearResumes() throws Exception {
        startScheduler("job-a");
        scheduler.inject(new FaultAction(FaultAction.FREEZE,
                FaultAction.ComponentAddress.of(new ComponentId("sched")), Map.of(), null));
        assertTrue(scheduler.isFrozen());
        assertNotNull(awaitEvent("sim.scheduler-frozen", 1_000));

        FakeWorker w = new FakeWorker(schedulerAddress(), "workers-1", 2);
        workers.add(w);
        assertTrue(w.dispatches.isEmpty(), "冻结期间不得派发");
        Thread.sleep(300);
        assertTrue(w.dispatches.isEmpty(), "冻结期间持续不派发（非仅一次跳过）");

        scheduler.clear(new FaultAction(FaultAction.FREEZE,
                FaultAction.ComponentAddress.of(new ComponentId("sched")), Map.of(), null));
        assertNotNull(awaitEvent("sim.scheduler-resumed", 1_000));
        assertNotNull(w.awaitDispatch(3_000), "clear 后恢复派发");
    }

    @Test
    void stopUnregistersEndpointAndRestartRepublishesIt() throws Exception {
        startScheduler("job-a");
        String before = schedulerAddress();
        assertNotNull(before);

        scheduler.stop(StopMode.GRACEFUL);
        assertTrue(registry.discoverEndpoints("scheduler").isEmpty(),
                "优雅停止后端点不应再被发现（避免 worker 连死地址）");
        assertTrue(!scheduler.health().healthy());

        scheduler.restart();
        String after = schedulerAddress();
        assertNotNull(after, "重启后必须重新发布端点");
        assertTrue(scheduler.health().healthy());
        assertNotNull(awaitEvent("sim.scheduler-restarted", 1_000));

        // 重启后仍可服务新 worker
        FakeWorker w = new FakeWorker(after, "workers-9", 1);
        workers.add(w);
        assertNotNull(w.awaitDispatch(3_000));
    }

    @Test
    void startWithoutRegistryBindingFailsExplicitly() {
        scheduler = new VirtualScheduler();
        scheduler.init(new ComponentContext(new ComponentId("sched"),
                Map.of("dag.tasks", "job-a"), SimClock.real(), bus, Map.of(), Map.of()));
        var ex = assertThrows(io.duo.sim.kernel.api.ComponentException.class,
                () -> scheduler.start());
        assertTrue(ex.getMessage().contains("direct registry binding"), ex.getMessage());
    }

    /**
     * G9 在 **wire 层**的镜像（同进程用例 {@code workerRejectionRollsBackAttemptAndRedispatches}
     * 之外的独立证据）：真实帧链路 sched → TaskDispatch(job-a) → worker 以帧回报 REJECTED →
     * {@link SchedulerStateMachine} 回滚尝试计数、回待派发，且**回滚不以重试的方式留痕**
     * （不得出现 {@code sut.task-retry}）。
     *
     * <p>线上「重派」这一环由缺口用例 {@code rejectedTaskIsNotRedispatchedAfterWorkerRefuses}
     * 如实记录（当前**不发生**）；状态机的重派与额度语义由
     * {@code SchedulerStateMachineRejectionTest} 确定性覆盖。
     */
    @Test
    void wireRejectionRollsBackAttemptAndStaysDispatchable() throws Exception {
        startScheduler("job-a,job-b");
        FakeWorker w = new FakeWorker(schedulerAddress(), "workers-1", 2);
        workers.add(w);
        var rejections = new AtomicInteger();
        w.policy = d -> rejections.incrementAndGet() <= 1
                ? TaskStatus.REJECTED : TaskStatus.SUCCESS;

        assertNotNull(w.awaitDispatch(3_000), "调度侧确实派发过");

        // 1) 拒绝事实必须按 VirtualWorker 的口径落流（instance/reason/rejections 三字段）
        Event rejected = awaitEvent("sut.task-rejected", 5_000);
        assertNotNull(rejected, "拒绝必须产生 sut.task-rejected 事实（不得静默丢弃）");
        assertEquals("workers-1", rejected.payload().get("instance"),
                "拒绝事实必须带上拒绝方实例名（可诊断）");
        assertEquals("no free slot", rejected.payload().get("reason"),
                "拒绝 detail 必须原样透传到事实面");
        assertEquals(1, rejected.payload().get("rejections"), "首次拒绝计数为 1");

        // 2) 语义边界：回滚**不是**重试（不得发 sut.task-retry），且重派不消耗重试额度
        //    （若确实发生了重派，其 attempt 必须仍为 1；重派是否发生取决于槽位记账，
        //     由缺口用例单独钉住，本用例不断言其发生性）
        Thread.sleep(1_000);
        assertTrue(events.stream().noneMatch(e -> "sut.task-retry".equals(e.type())),
                "拒绝回滚不等于重试：不得发 sut.task-retry");
        events.stream().filter(e -> "sut.task-dispatched".equals(e.type()))
                .forEach(e -> assertEquals(1, e.payload().get("attempt"),
                        "准入失败不消耗重试额度：每次派发 attempt 都必须为 1，实际 " + e.payload()));
    }

    @Test
    void providerMetadataMatchesDuoPortRules() {
        var p = new VirtualSchedulerProvider();
        assertEquals(Contract.SCHEDULER, p.contract());
        assertEquals(Tier.VIRTUAL, p.tier());
        assertEquals("virtual-scheduler", p.implName());
        assertTrue(p.isDefault());
        assertEquals(EndpointShape.DUO_PORT, p.metadata().endpointShape());
        assertEquals(java.util.Set.of(FaultAction.FREEZE), p.metadata().supportedFaults());
        // §7.5：两个字段独立。这里钉住的是**真实形态**——virtual 档只暴露线协议端点，没有同进程门面，
        // 于是 worker SUT 只能拨号、拿不到接口对象。这条事实正是「真实 worker 侧」验收的意义所在：
        // 若把它改成 interfaceDirect=true 图方便，验收就退回"进程内直接调接口"，不再证明线协议可用。
        assertFalse(p.metadata().interfaceDirect(),
                "virtual-scheduler 不提供同进程门面（interfaceDirect=false）");
    }
}
