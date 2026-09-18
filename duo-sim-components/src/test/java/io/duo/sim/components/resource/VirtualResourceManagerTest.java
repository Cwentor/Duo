package io.duo.sim.components.resource;

import io.duo.sim.components.provider.VirtualResourceProvider;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * resource 契约 virtual 档单测（M5 交付物 2 + M5-3 resource-exhaust）。
 */
class VirtualResourceManagerTest {

    private final SimpleEventBus bus = new SimpleEventBus();
    private final List<Event> events = new ArrayList<>();
    private VirtualResourceManager rm;

    private VirtualResourceManager start(Map<String, String> config) throws Exception {
        bus.subscribe(events::add);
        rm = new VirtualResourceManager();
        rm.init(new ComponentContext(new ComponentId("resources"), config,
                SimClock.real(), bus, Map.of(), Map.of()));
        rm.start();
        return rm;
    }

    private static FaultAction fault(String type) {
        return new FaultAction(type,
                FaultAction.ComponentAddress.of(new ComponentId("resources")), Map.of(), null);
    }

    @AfterEach
    void tearDown() {
        if (rm != null) {
            rm.stop(StopMode.GRACEFUL);
        }
    }

    @Test
    void allocateAndReleaseKeepQuotaAccounting() throws Exception {
        start(Map.of("capacity.cpu", "8", "capacity.memGB", "16"));
        var a = rm.allocate(2, 4);
        assertEquals(6, rm.remainingCpu());
        assertEquals(12, rm.remainingMemGB());
        assertEquals(1, rm.allocations().size());
        rm.release(a.handle());
        assertEquals(8, rm.remainingCpu());
        assertEquals(16, rm.remainingMemGB());
        assertTrue(rm.allocations().isEmpty());
        assertTrue(events.stream().anyMatch(e -> "sim.resource-allocated".equals(e.type())));
        assertTrue(events.stream().anyMatch(e -> "sim.resource-released".equals(e.type())));
    }

    @Test
    void insufficientQuotaIsRejectedWithNumbers() throws Exception {
        start(Map.of("capacity.cpu", "4", "capacity.memGB", "8"));
        rm.allocate(3, 6);
        var ex = assertThrows(ComponentException.class, () -> rm.allocate(2, 2));
        assertTrue(ex.getMessage().contains("insufficient resource"), ex.getMessage());
        assertTrue(ex.getMessage().contains("remaining cpu=1"), ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> rm.allocate(0, 1));
        assertEquals(1, rm.remainingCpu(), "被拒的申请不得扣减配额");
    }

    @Test
    void injectedExhaustionRejectsAllocationsAndIsIdempotent() throws Exception {
        start(Map.of("capacity.cpu", "8", "capacity.memGB", "16"));
        rm.inject(fault(FaultAction.RESOURCE_EXHAUST));
        assertTrue(rm.isExhausted());
        assertEquals(0, rm.remainingCpu(), "耗尽期间对外可观测配额为 0");
        assertEquals(0, rm.remainingMemGB());
        var ex = assertThrows(ComponentException.class, () -> rm.allocate(1, 1));
        assertTrue(ex.getMessage().contains("resource exhausted"), ex.getMessage());
        rm.inject(fault(FaultAction.RESOURCE_EXHAUST)); // 幂等：不产生第二条事实
        assertEquals(1, events.stream()
                .filter(e -> "sim.resource-exhausted".equals(e.type())).count());

        rm.clear(fault(FaultAction.RESOURCE_EXHAUST));
        assertFalse(rm.isExhausted());
        assertEquals(8, rm.remainingCpu());
        rm.clear(fault(FaultAction.RESOURCE_EXHAUST)); // 幂等
        assertEquals(1, events.stream()
                .filter(e -> "sim.resource-restored".equals(e.type())).count());
        rm.allocate(1, 1); // 恢复后可用
    }

    @Test
    void undeclaredFaultAndUnknownHandleFailExplicitly() throws Exception {
        start(Map.of());
        assertThrows(UnsupportedOperationException.class, () -> rm.inject(fault(FaultAction.FREEZE)));
        assertThrows(UnsupportedOperationException.class,
                () -> rm.clear(fault(FaultAction.SLOW)));
        assertThrows(ComponentException.class, () -> rm.release("no-such-handle"));
    }

    @Test
    void stopAndRestartClearAllocations() throws Exception {
        start(Map.of("capacity.cpu", "4", "capacity.memGB", "8"));
        rm.allocate(2, 4);
        rm.restart();
        assertTrue(rm.allocations().isEmpty());
        assertEquals(4, rm.remainingCpu());
        assertTrue(events.stream().anyMatch(e -> "sim.resource-restarted".equals(e.type())));

        rm.allocate(2, 4);
        rm.stop(StopMode.GRACEFUL);
        assertTrue(rm.allocations().isEmpty());
        assertTrue(events.stream().anyMatch(e -> "sim.resource-stopped".equals(e.type())));
    }

    @Test
    void providerMetadataDeclaresResourceExhaust() {
        var p = new VirtualResourceProvider();
        assertEquals(Contract.RESOURCE, p.contract());
        assertEquals(Tier.VIRTUAL, p.tier());
        assertEquals("virtual-resource", p.implName());
        assertTrue(p.isDefault());
        assertEquals(EndpointShape.NONE, p.metadata().endpointShape());
        assertTrue(p.metadata().interfaceDirect());
        assertEquals(java.util.Set.of(FaultAction.RESOURCE_EXHAUST),
                p.metadata().supportedFaults());
    }
}
