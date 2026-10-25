package io.duo.sim.components.worker;

import io.duo.sim.components.registry.VirtualRegistry;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.core.SimpleEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卡 5（T8）的确定性复现：冻结门挂起旧任务线程 → {@code restartInstance} 重置槽位 →
 * 解冻后旧线程的 finally 归还**新代次**的计数器 → 槽位超过 totalSlots。
 *
 * <p>停靠点是确定性的：任务执行完成后、终态回报前有**无条件**的 {@code awaitUnfrozen}
 * （freeze＝心跳停 + 终态回报挂起）——冻结期间任务线程必然停在那里，restartInstance
 * 的重置与旧线程的归还不存在「碰巧错开」的侥幸。修复后旧线程按**派发时捕获的代次**
 * 比对，过期即放弃归还（engine 侧同款教训回流，VirtualEngine L454 起）。
 */
class VirtualWorkerRestartRaceTest {

    private static final long TASK_DURATION_MS = 1_500;

    private final io.duo.sim.kernel.core.SimpleEventBus bus = new SimpleEventBus();
    private VirtualWorkerTest.FakeScheduler scheduler;
    private VirtualRegistry registry;
    private VirtualWorker worker;

    @BeforeEach
    void setUp() throws Exception {
        scheduler = new VirtualWorkerTest.FakeScheduler();
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

    private void startWorker() throws InterruptedException {
        Map<String, ComponentContext.WiringBinding> wiring = Map.of("registry",
                new ComponentContext.WiringBinding("registry", "zk", Contract.REGISTRY,
                        ComponentContext.ConnectionPath.DIRECT, registry, null));
        worker = new VirtualWorker();
        worker.init(new ComponentContext(new ComponentId("workers"), Map.of(
                "count", "1",
                "capacity.slots", "2",
                "behaviors.default.duration", String.valueOf(TASK_DURATION_MS),
                "behaviors.default.successRate", "1.0"),
                SimClock.real(), bus, wiring, Map.of()));
        worker.start();
        // 等拨号注册完成（alive 置位在注册帧落盘之前，派发要等 master 侧看到注册）
        await(() -> worker.isInstanceAlive(1), "instance alive");
        await(() -> scheduler.byInstance.containsKey("workers-1"), "scheduler sees registration");
    }

    @Test
    void restartInstanceDoesNotInflateSlotsFromStaleTaskReturns() throws Exception {
        startWorker();
        // 派发一个任务并确认受理（槽位 2→1）
        scheduler.dispatch("t1", "t1", 1);
        await(() -> worker.instanceFreeSlots(1) == 1, "task accepted (slots 2->1)");

        // 冻结：任务执行完毕后停靠在终态回报前的 awaitUnfrozen（无条件门）
        worker.inject(new FaultAction(FaultAction.FREEZE,
                FaultAction.ComponentAddress.of(new ComponentId("workers")), Map.of(), null));
        // 等任务线程确定停进冻结门：时长耗尽 + 余量
        Thread.sleep(TASK_DURATION_MS + 500);
        assertEquals(1, worker.instanceFreeSlots(1), "frozen task must not return its slot yet");

        // 重启实例：槽位重置为 2（旧任务线程仍挂在冻结门上）
        worker.restartInstance(1);
        assertEquals(2, worker.instanceFreeSlots(1), "restart must reset slots to total");

        // 解冻：旧任务线程苏醒、补报终态并执行 finally——修复后不得污染新代次的槽位
        worker.clear(new FaultAction(FaultAction.FREEZE,
                FaultAction.ComponentAddress.of(new ComponentId("workers")), Map.of(), null));
        Thread.sleep(800); // 旧线程苏醒/回报/finally 的余量

        assertTrue(worker.instanceFreeSlots(1) <= 2,
                "stale task return must not inflate slots beyond totalSlots, got "
                        + worker.instanceFreeSlots(1));
        assertEquals(2, worker.instanceFreeSlots(1),
                "slots must stay at total after the stale generation is discarded");
    }

    private static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(cond.getAsBoolean(), "timeout waiting for: " + what);
    }

    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
