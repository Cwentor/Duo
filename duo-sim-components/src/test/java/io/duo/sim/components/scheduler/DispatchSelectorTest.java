package io.duo.sim.components.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T19(a) 派发选择单测。核心判据（计划 v3/v4）：**4 任务 × 4 实例（slots=1）→ 各持恰好 1 条**。
 * 旧实现的缺陷在此直接暴露：busy CAS 派发瞬间释放 + 无槽位视图 → 4 条全落同一 worker。
 */
class DispatchSelectorTest {

    /** 4 实例 slots=1：4 次派发必须各落一个实例（轮转而非堆叠）。 */
    @Test
    void fourTasksOnFourInstancesWithSingleSlotSpreadEvenly() {
        var sel = new DispatchSelector();
        for (int i = 1; i <= 4; i++) {
            sel.register("workers-" + i);
            sel.onSlotReport("workers-" + i, 1);
        }
        var counts = new java.util.HashMap<String, Integer>();
        for (int t = 0; t < 4; t++) {
            String target = sel.select();
            assertTrue(target != null, "task " + t + " should find a target");
            sel.onDispatched(target);
            counts.merge(target, 1, Integer::sum);
        }
        assertEquals(4, counts.size(), "4 tasks must spread across 4 instances: " + counts);
        counts.values().forEach(c -> assertEquals(1, c, "each instance holds exactly 1: " + counts));
        // 第 5 次无可用实例（slots=1 全耗尽）
        assertNull(sel.select(), "no free slot after 4 dispatches");
    }

    /** 旧缺陷回归：无轮转时同一实例会被连续选中——本实现必须不出现。 */
    @Test
    void rotationPreventsStackingOnSingleInstance() {
        var sel = new DispatchSelector();
        for (int i = 1; i <= 4; i++) {
            sel.register("workers-" + i);
            sel.onSlotReport("workers-" + i, 4); // 每实例 4 槽
        }
        var counts = new java.util.HashMap<String, Integer>();
        for (int t = 0; t < 8; t++) {
            String target = sel.select();
            sel.onDispatched(target);
            counts.merge(target, 1, Integer::sum);
        }
        // 8 次派发在 4 实例上应均匀（各 2 条），而不是堆叠
        assertEquals(4, counts.size(), "should use all instances: " + counts);
        counts.values().forEach(c -> assertEquals(2, c, "even spread expected: " + counts));
    }

    /** 最大 freeSlots 优先：异构容量下先填大槽位实例。 */
    @Test
    void prefersInstanceWithMostFreeSlots() {
        var sel = new DispatchSelector();
        sel.register("small");
        sel.onSlotReport("small", 1);
        sel.register("big");
        sel.onSlotReport("big", 4);
        assertEquals("big", sel.select());
        sel.onDispatched("big");
        assertEquals("big", sel.select(), "big still has most free slots");
    }

    /** 未上报槽位的实例不参与选择（避免派给未就绪连接）。 */
    @Test
    void unknownInstancesAreNotSelectable() {
        var sel = new DispatchSelector();
        sel.register("workers-1"); // 无 SlotReport
        assertNull(sel.select(), "unreported instance must not be selected");
        sel.onSlotReport("workers-1", 1);
        assertEquals("workers-1", sel.select());
        assertEquals(1, sel.knownCount());
    }

    /** 实例移除后不再被选中（崩溃转移前提）。 */
    @Test
    void removedInstanceIsNoLongerSelected() {
        var sel = new DispatchSelector();
        sel.register("workers-1");
        sel.onSlotReport("workers-1", 2);
        sel.onRemoved("workers-1");
        assertNull(sel.select());
    }

    /** SlotReport 权威刷新覆盖本地递减（worker 释放槽位后恢复可选）。 */
    @Test
    void slotReportAuthoritativelyRefreshesLocalDecrement() {
        var sel = new DispatchSelector();
        sel.register("workers-1");
        sel.onSlotReport("workers-1", 1);
        assertEquals("workers-1", sel.select());
        sel.onDispatched("workers-1");
        assertNull(sel.select(), "local decrement exhausted the slot");
        // worker 任务完成 → SlotReport 报回 1 → 权威刷新恢复
        sel.onSlotReport("workers-1", 1);
        assertEquals("workers-1", sel.select());
    }

    /** 本地递减不会把 freeSlots 压到负数。 */
    @Test
    void localDecrementNeverGoesNegative() {
        var sel = new DispatchSelector();
        sel.register("workers-1");
        sel.onSlotReport("workers-1", 1);
        sel.onDispatched("workers-1");
        sel.onDispatched("workers-1");
        sel.onDispatched("workers-1");
        assertEquals(0, sel.freeSlotsOf("workers-1"));
    }
}
