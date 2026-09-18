package io.duo.sim.components.engine;

import io.duo.sim.components.provider.VirtualEngineProvider;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
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
import io.duo.sim.protocol.message.TaskDispatch;
import io.duo.sim.protocol.message.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * engine 契约 virtual 档单测（M5 交付物 1 + M5-3 freeze/slow/resource-exhaust）。
 *
 * <p>覆盖两条提交通路：**线协议**（DUO_PORT：提交方发 {@link TaskDispatch}，引擎回报状态）
 * 与**同进程**（{@code EngineContract.submit}）。故障三动作逐一验证「显式可观测」——
 * 挂起/变慢/拒绝都必须能从事实流或回报帧上判定，不允许静默。
 */
class VirtualEngineTest {

    private final SimpleEventBus bus = new SimpleEventBus();
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private VirtualEngine engine;

    private static final String TASK = "etl-job";

    private VirtualEngine start(Map<String, String> extra) throws Exception {
        bus.subscribe(events::add);
        Map<String, String> config = new HashMap<>(Map.of(
                "capacity.slots", "2",
                "capacity.cpu", "4",
                "capacity.memGB", "8",
                "slow.factor", "3.0",
                "behaviors.default.duration", "50",
                "behaviors.default.successRate", "1.0"));
        config.putAll(extra);
        engine = new VirtualEngine();
        engine.init(new ComponentContext(new ComponentId("engines"), config,
                SimClock.real(), bus, Map.of(), Map.of()));
        engine.start();
        return engine;
    }

    private static FaultAction fault(String type) {
        return new FaultAction(type,
                FaultAction.ComponentAddress.of(new ComponentId("engines")), Map.of(), null);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop(StopMode.GRACEFUL);
        }
    }

    /** 线协议客户端：socket 与帧连接成对持有（测试要改读超时）。 */
    private record Wire(Socket socket, FrameConnection conn) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            conn.close();
        }
    }

    private Wire connect() throws Exception {
        String addr = engine.endpoints().get(0).address();
        int colon = addr.lastIndexOf(':');
        Socket s = new Socket();
        s.connect(new InetSocketAddress(addr.substring(0, colon),
                Integer.parseInt(addr.substring(colon + 1))), 3_000);
        return new Wire(s, new FrameConnection(s));
    }

    /** 轮询等待某个事件出现。 */
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

    @Test
    void wireDispatchGetsRunningThenTerminalOverDuoPort() throws Exception {
        start(Map.of());
        assertEquals(EndpointShape.DUO_PORT, engine.declaredShape());
        assertEquals(Contract.ENGINE, engine.endpoints().get(0).contract());

        try (Wire w = connect()) {
            w.conn().write(new TaskDispatch("t-1", TASK, 1, 1, 1));
            var first = w.conn().read();
            assertTrue(first instanceof TaskStatus, "首帧应为状态回报，实际 " + first);
            assertEquals(TaskStatus.RUNNING, ((TaskStatus) first).state());

            var terminal = w.conn().read();
            assertTrue(terminal instanceof TaskStatus, "应收到终态回报，实际 " + terminal);
            assertEquals(TaskStatus.SUCCESS, ((TaskStatus) terminal).state());
            assertEquals("t-1", ((TaskStatus) terminal).taskId());
        }
        assertNotNull(awaitEvent("sim.engine-task-status", 2_000));
        assertTrue(events.stream().anyMatch(e -> "sim.engine-started".equals(e.type())));
        // 终态后槽位归还
        for (int i = 0; i < 50 && engine.freeSlots() != 2; i++) {
            Thread.sleep(20);
        }
        assertEquals(2, engine.freeSlots());
    }

    @Test
    void inProcessSubmitRunsTaskAndEmitsSubmittedFact() throws Exception {
        start(Map.of("behaviors.default.duration", "20"));
        String taskId = engine.submit(TASK, 1, 1);
        assertNotNull(taskId);
        assertNotNull(awaitEvent("sim.engine-submitted", 1_000));
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline
                && events.stream().noneMatch(e -> "sim.engine-task-status".equals(e.type())
                        && "SUCCESS".equals(e.payload().get("state")))) {
            Thread.sleep(10);
        }
        assertTrue(events.stream().anyMatch(e -> "sim.engine-task-status".equals(e.type())
                && "SUCCESS".equals(e.payload().get("state"))), "同进程提交也应到达终态事实");
    }

    @Test
    void fullEngineRejectsSubmissionExplicitlyOnBothPaths() throws Exception {
        start(Map.of("capacity.slots", "1", "behaviors.default.duration", "400"));
        engine.submit(TASK, 1, 1); // 占满唯一槽位
        var ex = assertThrows(ComponentException.class, () -> engine.submit(TASK, 1, 1));
        assertTrue(ex.getMessage().contains("no free slot"), ex.getMessage());
        try (Wire w = connect()) {
            w.conn().write(new TaskDispatch("t-reject", TASK, 1, 1, 1));
            var resp = w.conn().read();
            assertTrue(resp instanceof TaskStatus, "实际 " + resp);
            assertEquals(TaskStatus.REJECTED, ((TaskStatus) resp).state());
            assertEquals("no free slot", ((TaskStatus) resp).detail());
        }
        assertNotNull(awaitEvent("sim.engine-task-rejected", 2_000));
    }

    @Test
    void freezeRejectsNewDispatchesAndHoldsTerminalReport() throws Exception {
        start(Map.of("behaviors.default.duration", "300"));
        engine.inject(fault(FaultAction.FREEZE));
        assertTrue(engine.isFrozen());
        assertNotNull(awaitEvent("sim.engine-frozen", 1_000));

        // 冻结期间：新派发被显式拒绝（不静默排队）
        try (Wire w = connect()) {
            w.conn().write(new TaskDispatch("t-frozen", TASK, 1, 1, 1));
            var resp = w.conn().read();
            assertEquals(TaskStatus.REJECTED, ((TaskStatus) resp).state());
            assertEquals("engine frozen", ((TaskStatus) resp).detail());
        }

        // 冻结期间：在途任务的终态回报被挂起
        engine.clear(fault(FaultAction.FREEZE));
        try (Wire w = connect()) {
            w.conn().write(new TaskDispatch("t-hold", TASK, 1, 1, 1));
            assertEquals(TaskStatus.RUNNING, ((TaskStatus) w.conn().read()).state());
            engine.inject(fault(FaultAction.FREEZE));
            w.socket().setSoTimeout(400);
            assertThrows(java.io.IOException.class, w.conn()::read,
                    "冻结期间终态回报必须挂起（进展对外停摆）");
            engine.clear(fault(FaultAction.FREEZE));
            w.socket().setSoTimeout(3_000);
            assertEquals(TaskStatus.SUCCESS, ((TaskStatus) w.conn().read()).state(),
                    "clear 后补报终态");
        }
        assertNotNull(awaitEvent("sim.engine-resumed", 1_000));
    }

    @Test
    void slowMultipliesExecutionDuration() throws Exception {
        start(Map.of("behaviors.default.duration", "120", "slow.factor", "3.0"));
        long normalStart = System.currentTimeMillis();
        engine.submit(TASK, 1, 1);
        long normalElapsed = waitTerminalSince(normalStart);

        engine.inject(fault(FaultAction.SLOW));
        assertNotNull(awaitEvent("sim.engine-slowed", 1_000));
        long slowStart = System.currentTimeMillis();
        engine.submit(TASK, 1, 1);
        long slowElapsed = waitTerminalSince(slowStart);
        // 判据与 VirtualWorkerTest 同口径：绝对下限（120ms×3 必 >300ms，未变慢跑不到）+ 相对比较。
        // 不用 `slow >= normal*2`——CI 上调度噪声会同时抬高两侧，比值判据会假红（引擎侧已有先例）
        assertTrue(slowElapsed >= 300,
                () -> "slow(3.0)×120ms 应显著变慢：实测 " + slowElapsed + "ms");
        assertTrue(slowElapsed > normalElapsed,
                () -> "变慢后应慢于正常：normal=" + normalElapsed + "ms slow="
                        + slowElapsed + "ms");
        engine.clear(fault(FaultAction.SLOW));
        assertNotNull(awaitEvent("sim.engine-speed-restored", 1_000));
    }

    private long waitTerminalSince(long since) throws InterruptedException {
        long before = events.stream()
                .filter(e -> "sim.engine-task-status".equals(e.type())
                        && "SUCCESS".equals(e.payload().get("state"))).count();
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            long now = events.stream()
                    .filter(e -> "sim.engine-task-status".equals(e.type())
                            && "SUCCESS".equals(e.payload().get("state"))).count();
            if (now > before) {
                return System.currentTimeMillis() - since;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("no terminal event within 5s");
    }

    @Test
    void resourceExhaustRejectsExplicitlyAndIsIdempotent() throws Exception {
        start(Map.of());
        engine.inject(fault(FaultAction.RESOURCE_EXHAUST));
        assertTrue(engine.isResourceExhausted());
        assertEquals(0, engine.freeSlots(), "耗尽期间对外可观测槽位为 0");
        engine.inject(fault(FaultAction.RESOURCE_EXHAUST)); // 幂等
        assertEquals(1, events.stream()
                .filter(e -> "sim.engine-resource-exhausted".equals(e.type())).count());
        var ex = assertThrows(ComponentException.class, () -> engine.submit(TASK, 1, 1));
        assertTrue(ex.getMessage().contains("resource exhausted"), ex.getMessage());

        engine.clear(fault(FaultAction.RESOURCE_EXHAUST));
        assertNotNull(awaitEvent("sim.engine-resource-restored", 1_000));
        engine.submit(TASK, 1, 1); // 恢复后可用
    }

    @Test
    void undeclaredFaultIsRejectedExplicitly() throws Exception {
        start(Map.of());
        assertThrows(UnsupportedOperationException.class,
                () -> engine.inject(fault(FaultAction.TASK_KILL)));
        assertThrows(UnsupportedOperationException.class,
                () -> engine.clear(fault("crash")));
    }

    @Test
    void restartResetsStateAndRebindsEndpoint() throws Exception {
        start(Map.of());
        engine.submit(TASK, 1, 1);
        String before = engine.endpoints().get(0).address();
        engine.restart();
        assertTrue(engine.health().healthy());
        assertEquals(2, engine.freeSlots(), "重启＝内部状态全新");
        assertTrue(engine.inFlightTasks() == 0);
        assertNotNull(engine.endpoints().get(0).address());
        assertTrue(events.stream().anyMatch(e -> "sim.engine-restarted".equals(e.type())));
        // 端点重绑后仍可服务（端口可能不同，但契约不变）
        try (Wire w = connect()) {
            w.conn().write(new TaskDispatch("t-after-restart", TASK, 1, 1, 1));
            assertEquals(TaskStatus.RUNNING, ((TaskStatus) w.conn().read()).state());
        }
        assertNotNull(before);
    }

    @Test
    void logLinesAreEmittedAsEngineFacts() throws Exception {
        start(Map.of("behaviors.named." + TASK + ".duration", "20",
                "behaviors.named." + TASK + ".logLines", "start {task},done {taskId}"));
        engine.submit(TASK, 1, 1);
        Event log = awaitEvent("sim.engine-task-log", 2_000);
        assertNotNull(log, "logLines 应逐行落 sim.engine-task-log");
        assertTrue(String.valueOf(log.payload().get("line")).contains(TASK));
    }

    /**
     * 并发提交不得超发槽位（G9 纪律：槽位计数必须原子化）。
     * 「先看有没有、再减一」的写法在多连接并发下会把计数减成负数——这里用 16 路并发钉住。
     */
    @Test
    void concurrentSubmissionsNeverOversubscribeSlots() throws Exception {
        start(Map.of("capacity.slots", "2", "behaviors.default.duration", "2000"));
        int n = 16;
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        var rejected = new java.util.concurrent.atomic.AtomicInteger();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        var gate = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    try {
                        engine.submit(TASK, 1, 1);
                        accepted.incrementAndGet();
                    } catch (ComponentException e) {
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            gate.countDown();
            for (var f : futures) {
                f.get(15, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, accepted.get(), "只有 2 个槽位可被受理，多受理即超发");
        assertEquals(n - 2, rejected.get(), "其余必须**显式拒绝**（不得静默丢弃）");
        assertEquals(2, engine.inFlightTasks());
        assertTrue(engine.freeSlots() >= 0, "槽位计数不得为负：" + engine.freeSlots());
        assertEquals(0, engine.freeSlots());
    }
    @Test
    void providerMetadataDeclaresThreeFaults() {
        var p = new VirtualEngineProvider();
        assertEquals(Contract.ENGINE, p.contract());
        assertEquals(Tier.VIRTUAL, p.tier());
        assertEquals("virtual-engine", p.implName());
        assertTrue(p.isDefault());
        assertEquals(EndpointShape.DUO_PORT, p.metadata().endpointShape());
        assertEquals(java.util.Set.of(FaultAction.FREEZE, FaultAction.SLOW,
                FaultAction.RESOURCE_EXHAUST), p.metadata().supportedFaults());
        assertTrue(!p.metadata().instanceControl(), "引擎无实例级操作语义");
    }
}
