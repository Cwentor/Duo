package io.duo.sim.components.worker;

import io.duo.sim.components.registry.VirtualRegistry;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.contract.RegistryContract;
import io.duo.sim.kernel.core.SimpleEventBus;
import io.duo.sim.protocol.DuoMessage;
import io.duo.sim.protocol.FrameConnection;
import io.duo.sim.protocol.message.HeartbeatReport;
import io.duo.sim.protocol.message.RegisterRequest;
import io.duo.sim.protocol.message.RegisterResponse;
import io.duo.sim.protocol.message.SlotReport;
import io.duo.sim.protocol.message.TaskAck;
import io.duo.sim.protocol.message.TaskDispatch;
import io.duo.sim.protocol.message.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualWorkerTest {

    private SimpleEventBus bus;
    private final List<Event> events = new ArrayList<>();
    private FakeScheduler scheduler;
    private VirtualWorker worker;

    /** 假 master：accept worker 拨号、收注册、回 ok、记录帧。 */
    static final class FakeScheduler implements AutoCloseable {
        final ServerSocket server = new ServerSocket();
        final BlockingQueue<FrameConnection> conns = new LinkedBlockingQueue<>();
        final BlockingQueue<DuoMessage> received = new LinkedBlockingQueue<>();
        volatile boolean rejectRegister;

        FakeScheduler() throws IOException {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread.ofVirtual().name("fake-scheduler").start(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        FrameConnection c = new FrameConnection(s);
                        conns.add(c);
                        Thread.ofVirtual().start(() -> pump(c));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        private void pump(FrameConnection c) {
            try {
                while (true) {
                    DuoMessage m = c.read();
                    received.add(m);
                    if (m instanceof RegisterRequest) {
                        c.write(new RegisterResponse(!rejectRegister,
                                rejectRegister ? "rejecting" : null));
                    }
                }
            } catch (Exception e) {
                // 连接结束
            }
        }

        void dispatch(String taskId, String taskName, int instanceOrder) throws Exception {
            FrameConnection c = conns.stream()
                    .filter(x -> true)
                    .toList().get(instanceOrder - 1);
            c.write(new TaskDispatch(taskId, taskName, 1, 1, 1));
        }

        String address() {
            return "127.0.0.1:" + server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private VirtualRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        bus = new SimpleEventBus();
        bus.subscribe(events::add);
        scheduler = new FakeScheduler();

        registry = new VirtualRegistry();
        registry.init(new ComponentContext(new ComponentId("zk"), Map.of(),
                SimClock.real(), bus, Map.of(), Map.of()));
        registry.start();
        registry.registerEndpoint("scheduler", scheduler.address());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (worker != null) {
            worker.stop(StopMode.GRACEFUL);
        }
        scheduler.close();
        registry.stop(StopMode.GRACEFUL);
    }

    private VirtualWorker startWorker(int count, String namedTaskConfigPrefix,
                                       Map<String, String> extra) throws Exception {
        Map<String, ComponentContext.WiringBinding> wiring = Map.of("registry",
                new ComponentContext.WiringBinding("registry", "zk", Contract.REGISTRY,
                        ComponentContext.ConnectionPath.DIRECT, registry, null));
        Map<String, String> config = new java.util.HashMap<>(Map.of(
                "count", String.valueOf(count),
                "capacity.slots", "2",
                "behaviors.default.duration", "20",
                "behaviors.default.successRate", "1.0"));
        if (namedTaskConfigPrefix != null) {
            config.put("behaviors.named." + namedTaskConfigPrefix + ".duration", "10");
            config.put("behaviors.named." + namedTaskConfigPrefix + ".successRate", "0.0");
        }
        config.putAll(extra);
        worker = new VirtualWorker();
        worker.init(new ComponentContext(new ComponentId("workers"), config,
                SimClock.real(), bus, wiring, Map.of()));
        worker.start();
        // 等拨号注册完成
        for (int i = 0; i < 40 && aliveCount() < count; i++) {
            Thread.sleep(50);
        }
        return worker;
    }

    private int aliveCount() {
        int n = 0;
        for (int i = 1; i <= (worker == null ? 0 : worker.instanceCount()); i++) {
            if (worker.isInstanceAlive(i)) {
                n++;
            }
        }
        return n;
    }

    @Test
    void discoveryThenRegisterAndHeartbeat() throws Exception {
        startWorker(2, null, Map.of());
        assertEquals(2, aliveCount());
        var got = drainFor(500);
        assertEquals(2, got.stream().filter(m -> m instanceof RegisterRequest).count());
        assertTrue(got.stream().anyMatch(m -> m instanceof HeartbeatReport));
    }

    @Test
    void discoveryWaitsUntilEndpointAppears() throws Exception {
        // 先清空端点（模拟 master 未启动），再注册后延迟放行——发现等待语义
        var session = registry.openSession("clear");
        session.delete("/duo/endpoints/scheduler");
        var w = new VirtualWorker();
        Map<String, ComponentContext.WiringBinding> wiring = Map.of("registry",
                new ComponentContext.WiringBinding("registry", "zk", Contract.REGISTRY,
                        ComponentContext.ConnectionPath.DIRECT, registry, null));
        w.init(new ComponentContext(new ComponentId("workers"),
                Map.of("count", "1", "behaviors.default.duration", "10"),
                SimClock.real(), bus, wiring, Map.of()));
        worker = w;
        Thread registrar = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
                registry.registerEndpoint("scheduler", scheduler.address());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        w.start(); // 应等待发现后成功
        registrar.join();
        assertEquals(1, w.instanceCount());
        for (int i = 0; i < 60 && !w.isInstanceAlive(1); i++) {
            Thread.sleep(50);
        }
        assertTrue(w.isInstanceAlive(1), "instance did not come up after late discovery");
    }

    @Test
    void discoveryFailureThrowsAfterMaxTries() {
        // 端点不存在 → 启动失败（500ms×20 次超时）；缩短策略通过删除端点验证抛错路径
        var session = registry.openSession("clear");
        session.delete("/duo/endpoints/scheduler");
        var w = new VirtualWorker();
        Map<String, ComponentContext.WiringBinding> wiring = Map.of("registry",
                new ComponentContext.WiringBinding("registry", "zk", Contract.REGISTRY,
                        ComponentContext.ConnectionPath.DIRECT, registry, null));
        w.init(new ComponentContext(new ComponentId("workers"),
                Map.of("count", "1", "behaviors.default.duration", "10"),
                SimClock.real(), bus, wiring, Map.of()));
        worker = w;
        long t0 = System.currentTimeMillis();
        assertThrows(io.duo.sim.kernel.api.ComponentException.class, w::start);
        assertTrue(System.currentTimeMillis() - t0 >= 9_000, "should exhaust retries");
    }

    @Test
    void dispatchRunsTaskAndReportsTerminalStatus() throws Exception {
        startWorker(1, "spark-etl", Map.of());
        assertTrue(scheduler.received.poll(5, TimeUnit.SECONDS) instanceof RegisterRequest);
        scheduler.dispatch("t-9", "spark-etl", 1);

        // 期待 ACK + 终态（named successRate=0 → FAILED）
        var got = new ArrayList<DuoMessage>();
        for (int i = 0; i < 200; i++) {
            DuoMessage m = scheduler.received.poll(100, TimeUnit.MILLISECONDS);
            if (m != null) {
                got.add(m);
                if (m instanceof TaskStatus ts
                        && (TaskStatus.SUCCESS.equals(ts.state())
                        || TaskStatus.FAILED.equals(ts.state()))) {
                    break;
                }
            }
        }
        assertTrue(got.stream().anyMatch(m -> m instanceof TaskAck
                && ((TaskAck) m).taskId().equals("t-9")));
        var status = got.stream().filter(m -> m instanceof TaskStatus
                && ((TaskStatus) m).taskId().equals("t-9"))
                .map(m -> (TaskStatus) m).findFirst().orElseThrow();
        assertEquals(TaskStatus.FAILED, status.state());
    }

    @Test
    void instanceStopAndRestartKeepsIdentity() throws Exception {
        startWorker(2, null, Map.of());
        assertTrue(worker.isInstanceAlive(2));
        worker.stopInstance(2, StopMode.CRASH);
        assertFalse(worker.isInstanceAlive(2));
        assertTrue(worker.isInstanceAlive(1)); // 第 1 实例不受影响（无降级）
        assertEquals("workers-2", worker.instanceName(2)); // 身份保留

        worker.restartInstance(2);
        for (int i = 0; i < 60 && !worker.isInstanceAlive(2); i++) {
            Thread.sleep(50);
        }
        assertTrue(worker.isInstanceAlive(2), "instance 2 not back after restart");
        // 实例级事件 sourceId 约定（§7.4）
        assertTrue(events.stream().anyMatch(e ->
                e.type().equals("sim.worker-instance-crashed")
                        && e.sourceId().equals("workers-2")));
    }

    // ---- T18：task-kill ----

    @Test
    void taskKillTerminatesInFlightTaskWithCancelledReport() throws Exception {
        // 长任务（5s）→ kill → 应立即收到 CANCELLED 而非等任务自然结束
        startWorker(1, null, Map.of("behaviors.default.duration", "5000"));
        assertTrue(scheduler.received.poll(5, TimeUnit.SECONDS) instanceof RegisterRequest);
        scheduler.dispatch("t-kill", "plain-task", 1);

        // 任务在途判定走事件总线（sim.worker-task-status RUNNING 是总线事件，非线协议报文）
        boolean running = false;
        for (int i = 0; i < 60 && !running; i++) {
            running = events.stream().anyMatch(e ->
                    e.type().equals("sim.worker-task-status")
                            && "t-kill".equals(e.payload().get("taskId"))
                            && TaskStatus.RUNNING.equals(e.payload().get("state")));
            if (!running) {
                Thread.sleep(50);
            }
        }
        assertTrue(running, "task should be running before kill");

        long t0 = System.currentTimeMillis();
        worker.injectOnInstance(new FaultAction(FaultAction.TASK_KILL,
                FaultAction.ComponentAddress.ofInstance(new ComponentId("workers"), 1),
                Map.of(), null));

        var cancelled = waitForStatus("t-kill", TaskStatus.CANCELLED, 3000);
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(cancelled != null, "must report CANCELLED after task-kill");
        assertTrue(elapsed < 3000, "kill must be immediate, not wait full 5s: " + elapsed);
        for (int i = 0; i < 40 && worker.instanceFreeSlots(1) == 0; i++) {
            Thread.sleep(50);
        }
        assertEquals(2, worker.instanceFreeSlots(1), "slot must be released after kill");
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.worker-task-killed")));
    }

    @Test
    void taskKillOnIdleInstanceIsNoop() throws Exception {
        startWorker(1, null, Map.of());
        worker.injectOnInstance(new FaultAction(FaultAction.TASK_KILL,
                FaultAction.ComponentAddress.ofInstance(new ComponentId("workers"), 1),
                Map.of(), null));
        assertTrue(worker.isInstanceAlive(1));
    }

    @Test
    void nonTaskKillFaultStillRejected() throws Exception {
        startWorker(1, null, Map.of());
        assertThrows(UnsupportedOperationException.class,
                () -> worker.injectOnInstance(new FaultAction("freeze",
                        FaultAction.ComponentAddress.ofInstance(new ComponentId("workers"), 1),
                        Map.of(), null)));
        // task-kill 是实例级：整组注入不支持
        assertThrows(UnsupportedOperationException.class,
                () -> worker.inject(new FaultAction(FaultAction.TASK_KILL,
                        FaultAction.ComponentAddress.of(new ComponentId("workers")),
                        Map.of(), null)));
    }

    private TaskStatus waitForStatus(String taskId, String state, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var m = scheduler.received.poll(100, TimeUnit.MILLISECONDS);
            if (m instanceof TaskStatus ts && ts.taskId().equals(taskId)
                    && state.equals(ts.state())) {
                return ts;
            }
        }
        return null;
    }

    /**
     * G9 修复（M7 CI 首跑暴露的间歇挂起）：满载实例收到派发必须**显式拒绝**
     * （{@code TaskStatus.REJECTED} + {@code sim.worker-task-rejected}），不得静默丢弃——
     * 旧实现直接 return，调度侧仍视任务为 RUNNING，任务永久丢失、DAG 永不终态。
     */
    @Test
    void dispatchToFullInstanceIsExplicitlyRejectedNotSilentlyDropped() throws Exception {
        // 单槽位 + 长任务（5s）：占满后第二次派发必然不可受理
        startWorker(1, null, Map.of("capacity.slots", "1",
                "behaviors.default.duration", "5000"));
        assertTrue(scheduler.received.poll(5, TimeUnit.SECONDS) instanceof RegisterRequest);

        scheduler.dispatch("t-busy", "task-busy", 1);
        // 受理 = TaskAck（wire）+ sim.worker-task-status RUNNING（事件）；终态前不写 RUNNING 帧
        var acked = new ArrayList<DuoMessage>();
        for (int i = 0; i < 50; i++) {
            DuoMessage m = scheduler.received.poll(100, TimeUnit.MILLISECONDS);
            if (m != null) {
                acked.add(m);
            }
            if (acked.stream().anyMatch(x -> x instanceof TaskAck a
                    && a.taskId().equals("t-busy"))) {
                break;
            }
        }
        assertTrue(acked.stream().anyMatch(x -> x instanceof TaskAck a
                && a.taskId().equals("t-busy")), "首个任务应被受理（TaskAck）");
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.worker-task-status")
                && "t-busy".equals(e.payload().get("taskId"))
                && TaskStatus.RUNNING.equals(e.payload().get("state"))), "首个任务应进入执行");
        assertEquals(0, worker.instanceFreeSlots(1), "槽位应已占用");

        scheduler.dispatch("t-overflow", "task-overflow", 1);
        var rejected = waitForStatus("t-overflow", TaskStatus.REJECTED, 5_000);
        assertNotNull(rejected, "满载派发必须显式拒绝（不得静默丢弃）");
        assertTrue(rejected.detail().contains("no free slot"),
                "拒绝原因须可诊断，实际：" + rejected.detail());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.worker-task-rejected")
                        && e.sourceId().equals("workers-1")),
                "拒绝必须发事实事件（诊断面）");
        assertEquals(0, worker.instanceFreeSlots(1), "拒绝不得占用槽位");
        assertFalse(events.stream().anyMatch(e -> e.type().equals("sim.worker-task-status")
                        && "t-overflow".equals(e.payload().get("taskId"))),
                "被拒任务不得进入执行（不得发 RUNNING 状态）");
    }

    /**
     * G9 修复的完整闭环：被拒任务在槽位释放后**可重新派发并跑到终态**——即「显式拒绝 → 调度侧重排」
     * 能把原本会永久挂起的任务救回来（旧实现静默丢弃后，任务永远不会有任何回报）。
     */
    @Test
    void rejectedTaskCanBeRedispatchedAfterSlotFrees() throws Exception {
        // 单槽位 + 1.5s 任务：占满期间派发必被拒；槽位释放后重派必能到终态
        startWorker(1, null, Map.of("capacity.slots", "1",
                "behaviors.default.duration", "1500",
                "behaviors.default.successRate", "1.0"));
        assertTrue(scheduler.received.poll(5, TimeUnit.SECONDS) instanceof RegisterRequest);

        scheduler.dispatch("t-first", "task-a", 1);
        for (int i = 0; i < 50 && worker.instanceFreeSlots(1) > 0; i++) {
            Thread.sleep(50);
        }
        assertEquals(0, worker.instanceFreeSlots(1), "首个任务应占用槽位");

        scheduler.dispatch("t-second", "task-b", 1);
        assertNotNull(waitForStatus("t-second", TaskStatus.REJECTED, 5_000), "满载应被拒");

        assertNotNull(waitForStatus("t-first", TaskStatus.SUCCESS, 5_000), "首个任务应跑到终态");
        for (int i = 0; i < 60 && worker.instanceFreeSlots(1) == 0; i++) {
            Thread.sleep(50);
        }
        assertEquals(1, worker.instanceFreeSlots(1), "槽位必须释放（不得因拒绝/完成竞争泄漏）");

        scheduler.dispatch("t-second", "task-b", 1);
        assertNotNull(waitForStatus("t-second", TaskStatus.SUCCESS, 5_000),
                "重派的任务必须能跑到终态（旧实现会永久丢失）");
    }

    @Test
    void injectedFaultOnUndeclaredActionFails() throws Exception {
        startWorker(1, null, Map.of());
        var action = new FaultAction("freeze",
                new FaultAction.ComponentAddress(new ComponentId("workers"), 1),
                Map.of(), null);
        assertThrows(UnsupportedOperationException.class,
                () -> worker.injectOnInstance(action));
    }

    @Test
    void readinessEndpointIsExposed() throws Exception {
        startWorker(1, null, Map.of());
        List<ExposedEndpoint> eps = worker.endpoints();
        assertEquals(1, eps.size());
        assertEquals(EndpointShape.DUO_PORT, worker.declaredShape());
        assertTrue(eps.get(0).address().startsWith("127.0.0.1:"));
    }

    /** 有界排空：心跳每 100ms 持续产生，必须按时间预算退出（不能 do-while 无界）。 */
    private List<DuoMessage> drainFor(long budgetMs) throws InterruptedException {
        List<DuoMessage> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            DuoMessage m = scheduler.received.poll(50, TimeUnit.MILLISECONDS);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }
}
