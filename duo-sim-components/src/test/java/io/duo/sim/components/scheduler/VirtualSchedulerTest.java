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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        volatile Function<TaskDispatch, String> policy = d -> TaskStatus.SUCCESS;
        volatile boolean closed;
        private final AtomicInteger heartbeats = new AtomicInteger();

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
                        String state = policy.apply(d);
                        TaskStatus status = new TaskStatus(d.taskId(), name, state,
                                TaskStatus.REJECTED.equals(state) ? "no free slot" : null);
                        reported.add(status);
                        conn.write(status);
                    }
                }
            } catch (Exception e) {
                // 连接关闭（停止/重启/主动断开）
            }
        }

        TaskDispatch awaitDispatch(long timeoutMs) throws InterruptedException {
            return dispatches.poll(timeoutMs, TimeUnit.MILLISECONDS);
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

    @Test
    void workerRejectionRollsBackAttemptAndRedispatches() throws Exception {
        startScheduler("job-a");
        FakeWorker w = new FakeWorker(schedulerAddress(), "workers-1", 2);
        workers.add(w);
        // 第一次派发显式拒绝（G9 语义），之后成功。计数用独立计数器——
        // 不能用 dispatches.size()：测试线程会 poll 队列，size 会变（首版用例的缺陷）
        var seen = new java.util.concurrent.atomic.AtomicInteger();
        w.policy = d -> seen.incrementAndGet() <= 1
                ? TaskStatus.REJECTED : TaskStatus.SUCCESS;

        TaskDispatch first = w.awaitDispatch(3_000);
        assertNotNull(first);
        Event rejected = awaitEvent("sut.task-rejected", 3_000);
        assertNotNull(rejected, "拒绝必须产生 sut.task-rejected 事实");
        assertEquals("no free slot", rejected.payload().get("reason"));

        TaskDispatch second = w.awaitDispatch(3_000);
        assertNotNull(second, "被拒任务必须重派（不得挂起）");
        assertEquals(1, second.attempt(), "准入失败不消耗重试额度：attempt 仍为 1");
        assertNotNull(awaitEvent("sut.dag-terminal", 3_000));
    }

    @Test
    void instanceLostRequeuesInFlightTaskToNewConnection() throws Exception {
        startScheduler("job-a");
        FakeWorker w1 = new FakeWorker(schedulerAddress(), "workers-1", 1);
        workers.add(w1);
        TaskDispatch d = w1.awaitDispatch(3_000);
        assertNotNull(d, "应先派发给 workers-1");

        w1.close(); // 实例失联（不回状态）
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

    @Test
    void providerMetadataMatchesDuoPortRules() {
        var p = new VirtualSchedulerProvider();
        assertEquals(Contract.SCHEDULER, p.contract());
        assertEquals(Tier.VIRTUAL, p.tier());
        assertEquals("virtual-scheduler", p.implName());
        assertTrue(p.isDefault());
        assertEquals(EndpointShape.DUO_PORT, p.metadata().endpointShape());
        assertEquals(java.util.Set.of(FaultAction.FREEZE), p.metadata().supportedFaults());
        assertTrue(!p.metadata().interfaceDirect(), "DUO_PORT ⇒ 非 interface-direct");
    }
}
