package io.duo.sim.components.support;

import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.FaultAction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享故障态单测（卡 8）：事件名/载荷与收敛前 VirtualWorker、VirtualEngine 的手写实现
 * **逐串一致**（既有组件测试零改动是行为裁判，这里钉住共享件本身的语义）——幂等跳过、
 * slow 倍数更新不叠加、非法倍数显式拒绝、restart 语义的三态清零。
 */
class FaultStateTest {

    private static FaultAction action(String type, Map<String, Object> params) {
        return new FaultAction(type,
                FaultAction.ComponentAddress.of(new ComponentId("workers")), params, null);
    }

    private final List<String> events = new ArrayList<>();
    private final List<Map<String, Object>> payloads = new ArrayList<>();
    private final FaultState faults = new FaultState("sim.worker", 4, 3.0,
            (e, p) -> {
                events.add(e);
                payloads.add(p);
            });

    @Test
    void freezeAndClearEmitPrefixedEventsOnce() {
        assertTrue(faults.inject(action(FaultAction.FREEZE, null)));
        assertTrue(faults.frozen());
        assertFalse(faults.inject(action(FaultAction.FREEZE, null)), "重复注入幂等：不迁移");
        assertEquals(List.of("sim.worker-frozen"), events);

        assertTrue(faults.clear(action(FaultAction.FREEZE, null)));
        assertFalse(faults.frozen());
        assertFalse(faults.clear(action(FaultAction.FREEZE, null)), "未冻结清除幂等");
        assertEquals(List.of("sim.worker-frozen", "sim.worker-resumed"), events);
    }

    @Test
    void slowUsesParamsFactorOverDefaultAndUpdatesWhileActive() {
        assertTrue(faults.inject(action(FaultAction.SLOW, Map.of("factor", 5.0))));
        assertEquals(List.of("sim.worker-slowed"), events);
        assertEquals(5.0, faults.activeSlowFactor());

        // 幂等：已慢速时只更新倍数，不叠加、不再发事件
        assertFalse(faults.inject(action(FaultAction.SLOW, Map.of("factor", 2.5))));
        assertEquals(2.5, faults.activeSlowFactor());
        assertEquals(1, events.size());

        assertTrue(faults.clear(action(FaultAction.SLOW, null)));
        assertEquals("sim.worker-speed-restored", events.get(1));
        assertFalse(faults.slow());
    }

    @Test
    void slowFactorDefaultComesFromConstructorWhenParamsAbsent() {
        faults.inject(action(FaultAction.SLOW, null));
        assertEquals(3.0, faults.activeSlowFactor());
        assertEquals(Map.of("factor", 3.0), payloads.get(0));
    }

    @Test
    void illegalSlowFactorIsRejectedLoudly() {
        assertThrows(IllegalArgumentException.class,
                () -> faults.inject(action(FaultAction.SLOW, Map.of("factor", 0.5))));
        assertFalse(faults.slow(), "非法倍数不得置位");
        assertTrue(events.isEmpty());
    }

    @Test
    void resourceExhaustCarriesSlotsPayloadAndReportsTransition() {
        assertTrue(faults.inject(action(FaultAction.RESOURCE_EXHAUST, null)));
        assertTrue(faults.exhausted());
        assertEquals("sim.worker-resource-exhausted", events.get(0));
        assertEquals(Map.of("slots", 4), payloads.get(0));

        assertTrue(faults.clear(action(FaultAction.RESOURCE_EXHAUST, null)));
        assertFalse(faults.exhausted());
        assertEquals("sim.worker-resource-restored", events.get(1));
    }

    @Test
    void unknownActionFailsExplicitlyAndResetClearsAllTiers() {
        assertThrows(UnsupportedOperationException.class,
                () -> faults.inject(action("task-kill", null)));
        assertThrows(UnsupportedOperationException.class,
                () -> faults.clear(action("task-kill", null)));

        faults.inject(action(FaultAction.FREEZE, null));
        faults.inject(action(FaultAction.SLOW, Map.of("factor", 2.0)));
        faults.inject(action(FaultAction.RESOURCE_EXHAUST, null));
        faults.reset();
        assertFalse(faults.frozen());
        assertFalse(faults.slow());
        assertFalse(faults.exhausted());
    }
}
