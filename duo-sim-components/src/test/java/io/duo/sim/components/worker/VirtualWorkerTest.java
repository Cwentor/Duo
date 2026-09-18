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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        /** 假 master 侧的动作日志（诊断用）。 */
        final BlockingQueue<String> pumpLog = new LinkedBlockingQueue<>();
        /** 已注册实例 → 其连接（重连后指向最新；派发必须发给**活动**连接）。 */
        final java.util.Map<String, FrameConnection> byInstance =
                new java.util.concurrent.ConcurrentHashMap<>();
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
                    if (m instanceof RegisterRequest rr) {
                        // **先应答，再发布「已收到」**：测试以 received 里的注册请求作为「注册完成」信号，
                        // 若先 add 后 write，测试可能在响应写出前就在同一连接上派发（两线程并发写同一连接）
                        // ——worker 的握手读会先读到 TaskDispatch，误判为「no register response」而离线。
                        c.write(new RegisterResponse(!rejectRegister,
                                rejectRegister ? "rejecting" : null));
                        pumpLog.add("sent-register-response");
                        if (!rejectRegister) {
                            byInstance.put(rr.instanceName(), c);
                        }
                    }
                    received.add(m);
                }
            } catch (Exception e) {
                // 连接结束
                pumpLog.add("pump-exit:" + e.getClass().getSimpleName() + ":" + e.getMessage());
            }
        }

        void dispatch(String taskId, String taskName, int instanceOrder) throws Exception {
            // 按实例名寻址**活动**连接：避免重连后把帧写到已死连接（旧实现按 accept 顺序取，脆弱）
            FrameConnection c = byInstance.get("workers-" + instanceOrder);
            if (c == null) {
                throw new IllegalStateException(
                        "instance workers-" + instanceOrder + " is not registered");
            }
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
    void logLinesAreEmittedAsFactsBeforeTerminalStatus() throws Exception {
        // G7：logLines 此前被 resolver 固定置空（DSL 写了不生效）——现应逐行落 sim.worker-log
        // 注意：四级匹配是**整条命中**（不逐字段合并），故 logLines 要挂在命中的那条上
        startWorker(1, "spark-etl", Map.of(
                "behaviors.named.spark-etl.logLines", "run {task} ({taskId}), done"));
        assertTrue(scheduler.received.poll(5, TimeUnit.SECONDS) instanceof RegisterRequest);
        scheduler.dispatch("t-log", "spark-etl", 1);

        // 等线协议终态回报（FAILED：named successRate=0）
        TaskStatus terminal = null;
        for (int i = 0; i < 200 && terminal == null; i++) {
            DuoMessage m = scheduler.received.poll(100, TimeUnit.MILLISECONDS);
            if (m instanceof TaskStatus ts && TaskStatus.FAILED.equals(ts.state())) {
                terminal = ts;
            }
        }
        assertNotNull(terminal, "未收到终态回报");
        // 占位符展开 + 逐行有序
        var lines = events.stream()
                .filter(e -> "sim.worker-log".equals(e.type()))
                .map(e -> String.valueOf(e.payload().get("line")))
                .toList();
        assertEquals(List.of("run spark-etl (t-log)", "done"), lines);
        // 顺序确定：收到终态回报时假日志**已经**落流（断言窗口可依赖）
        assertTrue(events.stream().anyMatch(e -> "sim.worker-log".equals(e.type())));
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

    private static FaultAction componentFault(String type) {
        return new FaultAction(type,
                FaultAction.ComponentAddress.of(new ComponentId("workers")), Map.of(), null);
    }

    /**
     * M5-3 {@code freeze}：冻结期间心跳停发 + 在途任务的日志/终态回报挂起（进展对外停摆），
     * clear 后补报。判据用**确定性事实**（无心跳、无终态），不用固定 sleep 猜测。
     */
    @Test
    void freezeStopsHeartbeatsAndHoldsTerminalReportUntilCleared() throws Exception {
        startWorker(1, null, Map.of("behaviors.default.duration", "300"));
        assertTrue(scheduler.received.poll(5, TimeUnit.SECONDS) instanceof RegisterRequest);
        for (int i = 0; i < 40 && worker.instanceFreeSlots(1) != 2; i++) {
            Thread.sleep(25);
        }
        scheduler.dispatch("t-freeze", "etl", 1);
        // RUNNING 是**事实事件**（wire 上受理只回 TaskAck；终态才走 TaskStatus 帧）——用事件判定
        assertTrue(waitForRunning("t-freeze", 3000), "受理后应出现 RUNNING 事实");

        worker.inject(componentFault(FaultAction.FREEZE));
        assertTrue(worker.isFrozen());
        // 排空既有帧，再观察冻结窗口：心跳与终态都必须消失
        Thread.sleep(50);
        scheduler.received.clear();
        assertNull(waitForStatus("t-freeze", TaskStatus.SUCCESS, 500),
                "冻结期间终态回报必须挂起");
        assertTrue(scheduler.received.stream().noneMatch(m -> m instanceof HeartbeatReport),
                "冻结期间不得发心跳");

        worker.clear(componentFault(FaultAction.FREEZE));
        assertNotNull(waitForStatus("t-freeze", TaskStatus.SUCCESS, 3000),
                "clear 后必须补报终态");
        assertTrue(events.stream().anyMatch(e -> "sim.worker-frozen".equals(e.type())));
        assertTrue(events.stream().anyMatch(e -> "sim.worker-resumed".equals(e.type())));
    }

    /** M5-3 {@code slow}：执行时长乘以 {@code slow.factor}；clear 后恢复。 */
    @Test
    void slowMultipliesExecutionDurationAndIsIdempotent() throws Exception {
        startWorker(1, null, Map.of("behaviors.default.duration", "120",
                "slow.factor", "3.0"));
        assertTrue(scheduler.received.poll(5, TimeUnit.SECONDS) instanceof RegisterRequest);
        for (int i = 0; i < 40 && worker.instanceFreeSlots(1) != 2; i++) {
            Thread.sleep(25);
        }

        worker.inject(componentFault(FaultAction.SLOW));
        worker.inject(componentFault(FaultAction.SLOW)); // 幂等：不叠加倍数
        assertTrue(worker.isSlow());
        assertEquals(1, events.stream().filter(e -> "sim.worker-slowed".equals(e.type())).count());

        long t0 = System.currentTimeMillis();
        scheduler.dispatch("t-slow", "etl", 1);
        assertNotNull(waitForStatus("t-slow", TaskStatus.SUCCESS, 5000));
        long slowElapsed = System.currentTimeMillis() - t0;
        assertTrue(slowElapsed >= 300, "slow(3.0)×120ms 应显著变慢，实测 " + slowElapsed + "ms");

        worker.clear(componentFault(FaultAction.SLOW));
        long t1 = System.currentTimeMillis();
        scheduler.dispatch("t-normal", "etl", 1);
        assertNotNull(waitForStatus("t-normal", TaskStatus.SUCCESS, 5000));
        long normalElapsed = System.currentTimeMillis() - t1;
        assertTrue(normalElapsed < slowElapsed,
                "恢复后应更快：normal=" + normalElapsed + "ms slow=" + slowElapsed + "ms");
    }

    /** M5-3 {@code resource-exhaust}：对外槽位归零 + 全部派发显式拒绝；clear 后恢复受理。 */
    @Test
    void resourceExhaustRejectsDispatchesExplicitlyAndRestores() throws Exception {
        startWorker(1, null, Map.of("behaviors.default.duration", "50"));
        assertTrue(scheduler.received.poll(5, TimeUnit.SECONDS) instanceof RegisterRequest);
        for (int i = 0; i < 40 && worker.instanceFreeSlots(1) != 2; i++) {
            Thread.sleep(25);
        }

        worker.inject(componentFault(FaultAction.RESOURCE_EXHAUST));
        assertTrue(worker.isResourceExhausted());
        assertEquals(0, worker.instanceFreeSlots(1), "对外可观测槽位必须为 0");

        scheduler.dispatch("t-exhaust", "etl", 1);
        TaskStatus rejected = waitForStatus("t-exhaust", TaskStatus.REJECTED, 3000);
        assertNotNull(rejected, "资源耗尽必须显式拒绝");
        assertEquals("resource exhausted", rejected.detail());

        worker.clear(componentFault(FaultAction.RESOURCE_EXHAUST));
        assertEquals(2, worker.instanceFreeSlots(1));
        scheduler.dispatch("t-after", "etl", 1);
        assertNotNull(waitForStatus("t-after", TaskStatus.SUCCESS, 3000), "恢复后应正常受理");
        assertThrows(UnsupportedOperationException.class,
                () -> worker.inject(componentFault("crash")));
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
        // 等**权威受理事实**（RUNNING 事件在槽位递减之后落流），而非固定时长轮询槽位：
        // 满载机器上 dispatch→accept 的延迟可以超过任何拍脑袋的等待预算（全量回归曾因此假失败）。
        assertTrue(waitForRunning("t-first", 20_000),
                () -> "首个任务应被受理；诊断 " + diagnostics());
        assertEquals(0, worker.instanceFreeSlots(1), "首个任务应占用槽位");

        scheduler.dispatch("t-second", "task-b", 1);
        assertNotNull(waitForStatus("t-second", TaskStatus.REJECTED, 10_000), "满载应被拒");

        assertNotNull(waitForStatus("t-first", TaskStatus.SUCCESS, 10_000), "首个任务应跑到终态");
        for (int i = 0; i < 60 && worker.instanceFreeSlots(1) == 0; i++) {
            Thread.sleep(50);
        }
        assertEquals(1, worker.instanceFreeSlots(1), "槽位必须释放（不得因拒绝/完成竞争泄漏）");

        scheduler.dispatch("t-second", "task-b", 1);
        assertNotNull(waitForStatus("t-second", TaskStatus.SUCCESS, 10_000),
                "重派的任务必须能跑到终态（旧实现会永久丢失）");
    }

    /** 等「该任务已在本实例开始执行」的本地事实（`sim.worker-task-status` RUNNING）。 */
    private boolean waitForRunning(String taskId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean running = events.stream().anyMatch(e ->
                    "sim.worker-task-status".equals(e.type())
                            && taskId.equals(e.payload().get("taskId"))
                            && TaskStatus.RUNNING.equals(e.payload().get("state")));
            if (running) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** 失败时的现场快照（连接数/收到的帧/本地事件/槽位）——诊断用，不在成功路径执行。 */
    private String diagnostics() {
        var frames = new ArrayList<String>();
        DuoMessage m;
        while ((m = scheduler.received.poll()) != null) {
            frames.add(m.getClass().getSimpleName()
                    + (m instanceof TaskStatus ts ? ":" + ts.state() : "")
                    + (m instanceof RegisterRequest ? "" : ""));
        }
        var types = events.stream().map(Event::type).distinct().toList();
        var offline = events.stream()
                .filter(e -> e.type().equals("sim.worker-instance-offline"))
                .map(e -> String.valueOf(e.payload())).toList();
        return "conns=" + scheduler.conns.size()
                + " frames=" + frames
                + " pumpLog=" + new ArrayList<>(scheduler.pumpLog)
                + " eventTypes=" + types
                + " offline=" + offline
                + " freeSlots=" + worker.instanceFreeSlots(1)
                + " alive=" + worker.isInstanceAlive(1);
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
