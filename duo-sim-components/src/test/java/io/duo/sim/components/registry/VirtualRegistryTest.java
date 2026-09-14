package io.duo.sim.components.registry;

import io.duo.sim.components.provider.VirtualRegistryProvider;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.EventBus;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.contract.RegistryContract;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.kernel.core.SimpleEventBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualRegistryTest {

    private VirtualRegistry newRegistry() {
        return newRegistry(new SimpleEventBus());
    }

    private VirtualRegistry newRegistry(EventBus bus) {
        VirtualRegistry r = new VirtualRegistry();
        ComponentContext ctx = new ComponentContext(new ComponentId("zk"), Map.of(),
                SimClock.real(), bus, Map.of(), Map.of());
        r.init(ctx);
        r.start();
        return r;
    }

    @Test
    void sessionCloseRemovesEphemerals() {
        VirtualRegistry r = newRegistry();
        RegistryContract.RegistrySession s = r.openSession("s-1");
        s.createEphemeral("/duo/endpoints/scheduler", "127.0.0.1:8123");
        assertEquals(List.of("127.0.0.1:8123"), r.discoverEndpoints("scheduler"));
        assertTrue(s.isAlive());

        s.close();
        assertFalse(s.isAlive());
        assertEquals(List.of(), r.discoverEndpoints("scheduler"));
    }

    @Test
    void crashHidesEphemeralEndpointsUntilRestart() {
        EventBus bus = new SimpleEventBus();
        List<String> types = new ArrayList<>();
        bus.subscribe(e -> types.add(e.type()));
        VirtualRegistry r = newRegistry(bus);
        RegistryContract.RegistrySession s = r.openSession("s-1");
        s.createEphemeral("/duo/endpoints/scheduler", "127.0.0.1:8123");

        r.stop(StopMode.CRASH);
        assertThrows(io.duo.sim.kernel.api.ComponentException.class,
                () -> r.discoverEndpoints("scheduler")); // 宕机后不可查
        r.restart();
        // restart 清空全部状态（§7.1 全新实例语义）——发现结果为空，需重新注册
        assertEquals(List.of(), r.discoverEndpoints("scheduler"));
        assertTrue(types.contains("sim.registry-crashed"));
        assertTrue(types.contains("sim.registry-restarted"));
    }

    @Test
    void watchFiresOnCreateAndDelete() {
        VirtualRegistry r = newRegistry();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger deleted = new AtomicInteger();
        r.watch("/duo/workers", ch -> {
            if (ch.kind() == RegistryContract.RegistryChange.ChangeKind.CREATED) {
                created.incrementAndGet();
            } else {
                deleted.incrementAndGet();
            }
        });
        RegistryContract.RegistrySession s = r.openSession("w-1");
        s.createEphemeral("/duo/workers", "workers-1");
        assertEquals(1, created.get());
        s.close();
        assertEquals(1, deleted.get());
    }

    @Test
    void endpointRegistrationRoundTrip() {
        VirtualRegistry r = newRegistry();
        r.registerEndpoint("scheduler", "127.0.0.1:9000");
        assertEquals(List.of("127.0.0.1:9000"), r.discoverEndpoints("scheduler"));
        assertEquals(EndpointShape.NONE, r.declaredShape());
    }

    // ---- T18：registry-flap ----

    @Test
    void flapInvalidatesAllSessionsAndEmitsWatchDeleted() {
        var bus = new SimpleEventBus();
        var types = new java.util.ArrayList<String>();
        bus.subscribe(e -> types.add(e.type()));
        var r = newRegistry(bus);
        r.registerEndpoint("scheduler", "127.0.0.1:9000");
        var session = r.openSession("w-1");
        session.createEphemeral("/duo/workers", "workers-1");
        var deleted = new java.util.concurrent.atomic.AtomicInteger();
        r.watch("/duo/endpoints/scheduler", ch -> {
            if (ch.kind() == RegistryContract.RegistryChange.ChangeKind.DELETED) {
                deleted.incrementAndGet();
            }
        });
        r.watch("/duo/workers", ch -> {
            if (ch.kind() == RegistryContract.RegistryChange.ChangeKind.DELETED) {
                deleted.incrementAndGet();
            }
        });

        r.inject(new FaultAction(FaultAction.REGISTRY_FLAP,
                FaultAction.ComponentAddress.of(new ComponentId("zk")), Map.of(), null));

        assertTrue(r.isFlapping());
        assertEquals(2, deleted.get(), "both ephemeral nodes must emit DELETED");
        assertTrue(types.contains("sim.registry-flap-started"));
        // flap 期间发现为空
        assertEquals(List.of(), r.discoverEndpoints("scheduler"));
        // flap 期间会话不存活
        assertFalse(session.isAlive());
        assertFalse(r.health().healthy());
    }

    @Test
    void flapRestoreReplaysEndpointSnapshotTransparently() {
        var r = newRegistry();
        r.registerEndpoint("scheduler", "127.0.0.1:9000");
        r.inject(new FaultAction(FaultAction.REGISTRY_FLAP,
                FaultAction.ComponentAddress.of(new ComponentId("zk")), Map.of(), null));
        assertEquals(List.of(), r.discoverEndpoints("scheduler"));

        r.clear(new FaultAction(FaultAction.REGISTRY_FLAP,
                FaultAction.ComponentAddress.of(new ComponentId("zk")), Map.of(), null));

        assertFalse(r.isFlapping());
        // 端点快照重放：对 SUT 透明，无需重新注册
        assertEquals(List.of("127.0.0.1:9000"), r.discoverEndpoints("scheduler"));
        assertTrue(r.health().healthy());
    }

    @Test
    void registerDuringFlapIsKeptInSnapshotAndVisibleAfterRestore() {
        var r = newRegistry();
        r.inject(new FaultAction(FaultAction.REGISTRY_FLAP,
                FaultAction.ComponentAddress.of(new ComponentId("zk")), Map.of(), null));
        // flap 期间注册（边界语义）：写入快照不丢，但当前节点表不含它
        r.registerEndpoint("scheduler", "127.0.0.1:7777");
        assertEquals(List.of(), r.discoverEndpoints("scheduler"), "invisible during flap");

        r.clear(new FaultAction(FaultAction.REGISTRY_FLAP,
                FaultAction.ComponentAddress.of(new ComponentId("zk")), Map.of(), null));
        assertEquals(List.of("127.0.0.1:7777"), r.discoverEndpoints("scheduler"),
                "flap-window registration must be visible after restore");
    }

    @Test
    void flapWithDurationAutoClears() throws Exception {
        var r = newRegistry();
        r.registerEndpoint("scheduler", "127.0.0.1:9000");
        r.inject(new FaultAction(FaultAction.REGISTRY_FLAP,
                FaultAction.ComponentAddress.of(new ComponentId("zk")), Map.of(), 120L));
        assertTrue(r.isFlapping());
        Thread.sleep(400);
        assertFalse(r.isFlapping(), "duration must auto-clear the flap");
        assertEquals(List.of("127.0.0.1:9000"), r.discoverEndpoints("scheduler"));
    }

    @Test
    void flapIsIdempotentWhileActive() {
        var r = newRegistry();
        r.registerEndpoint("scheduler", "127.0.0.1:9000");
        var action = new FaultAction(FaultAction.REGISTRY_FLAP,
                FaultAction.ComponentAddress.of(new ComponentId("zk")), Map.of(), null);
        r.inject(action);
        r.inject(action); // 幂等：不重复清空/不重复发事件
        assertTrue(r.isFlapping());
        r.clear(action);
        assertEquals(List.of("127.0.0.1:9000"), r.discoverEndpoints("scheduler"));
    }

    @Test
    void unsupportedFaultRejected() {
        var r = newRegistry();
        assertThrows(UnsupportedOperationException.class,
                () -> r.inject(new FaultAction("freeze",
                        FaultAction.ComponentAddress.of(new ComponentId("zk")), Map.of(), null)));
    }

    @Test
    void providerMetadataPassesConsistency() {
        var p = new VirtualRegistryProvider();
        CapabilityMetadata m = p.metadata();
        assertEquals(EndpointShape.NONE, m.endpointShape());
        assertTrue(m.interfaceDirect());
        // (REGISTRY, VIRTUAL) 恰好一个缺省：注册表校验通过
        ContractRegistry reg = new ContractRegistry();
        reg.register(p);
        reg.validateDefaults();
        assertEquals("virtual-registry", reg.resolve(Contract.REGISTRY, Tier.VIRTUAL, null)
                .implName());
    }
}
