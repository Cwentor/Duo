package io.duo.sim.kernel.core;

import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.FaultInjectable;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.InstanceControl;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractRegistryTest {

    // ---- fixtures ----

    /** 仅实现 VirtualComponent 的最小组件。 */
    static class BareComponent implements VirtualComponent {
        @Override public ComponentId id() { return new ComponentId("bare"); }
        @Override public void init(ComponentContext ctx) { }
        @Override public void start() { }
        @Override public void stop(StopMode mode) { }
        @Override public void restart() { }
        @Override public HealthReport health() { return HealthReport.ok(); }
        @Override public List<ExposedEndpoint> endpoints() { return List.of(); }
    }

    /** 带 InstanceControl 的组件。 */
    static class InstancefulComponent extends BareComponent implements InstanceControl {
        @Override public void stopInstance(int index, StopMode mode) { }
        @Override public void restartInstance(int index) { }
        @Override public void injectOnInstance(io.duo.sim.kernel.api.FaultAction action) { }
    }

    /** 带 FaultInjectable 的组件。 */
    static class FaultfulComponent extends BareComponent implements FaultInjectable {
        @Override public void inject(io.duo.sim.kernel.api.FaultAction action) { }
        @Override public void clear(io.duo.sim.kernel.api.FaultAction action) { }
    }

    record TestProvider(io.duo.sim.kernel.api.Contract contract, Tier tier, String implName,
                        boolean isDefault, CapabilityMetadata metadata,
                        java.util.function.Supplier<VirtualComponent> factory)
            implements ComponentProvider {
        @Override public VirtualComponent newComponent() { return factory.get(); }
    }

    private static ComponentProvider provider(io.duo.sim.kernel.api.Contract c, Tier t,
                                              String name, boolean isDefault,
                                              CapabilityMetadata m) {
        return new TestProvider(c, t, name, isDefault, m, BareComponent::new);
    }

    // ---- 注册期一致性校验（§7.5）----

    @Test
    void rejectsNoneShapeWithoutInterfaceDirect() {
        ContractRegistry r = new ContractRegistry();
        var m = new io.duo.sim.kernel.api.CapabilityMetadata(
                EndpointShape.NONE, false, false, Set.of(), false);
        var e = assertThrows(ContractRegistry.RegistrationException.class,
                () -> r.register(provider(io.duo.sim.kernel.api.Contract.REGISTRY, Tier.VIRTUAL,
                        "bad", true, m)));
        assertTrue(e.getMessage().contains("interfaceDirect"));
    }

    @Test
    void rejectsInstanceControlClaimWithoutInterface() {
        ContractRegistry r = new ContractRegistry();
        var m = new io.duo.sim.kernel.api.CapabilityMetadata(
                EndpointShape.DUO_PORT, false, true, Set.of(), false);
        var e = assertThrows(ContractRegistry.RegistrationException.class,
                () -> r.register(provider(io.duo.sim.kernel.api.Contract.WORKER, Tier.VIRTUAL,
                        "liar", true, m)));
        assertTrue(e.getMessage().contains("InstanceControl"));
    }

    @Test
    void rejectsFaultsClaimWithoutInterface() {
        ContractRegistry r = new ContractRegistry();
        var m = new io.duo.sim.kernel.api.CapabilityMetadata(
                EndpointShape.DUO_PORT, false, false, Set.of("registry-flap"), false);
        var e = assertThrows(ContractRegistry.RegistrationException.class,
                () -> r.register(provider(io.duo.sim.kernel.api.Contract.REGISTRY, Tier.VIRTUAL,
                        "liar", true, m)));
        assertTrue(e.getMessage().contains("FaultInjectable"));
    }

    @Test
    void acceptsConsistentClaims() {
        ContractRegistry r = new ContractRegistry();
        r.register(new TestProvider(io.duo.sim.kernel.api.Contract.WORKER, Tier.VIRTUAL,
                "inst", true,
                new io.duo.sim.kernel.api.CapabilityMetadata(EndpointShape.DUO_PORT, false,
                        true, Set.of(), true),
                InstancefulComponent::new));
        r.register(new TestProvider(io.duo.sim.kernel.api.Contract.REGISTRY, Tier.VIRTUAL,
                "fault", true,
                new io.duo.sim.kernel.api.CapabilityMetadata(EndpointShape.NONE, true, false,
                        Set.of("registry-flap"), true),
                FaultfulComponent::new));
        r.validateDefaults(); // 不抛
        assertTrue(r.hasImplementation(io.duo.sim.kernel.api.Contract.WORKER, Tier.VIRTUAL));
    }

    // ---- 缺省唯一性（§7.5）----

    @Test
    void rejectsMultipleDefaults() {
        ContractRegistry r = new ContractRegistry();
        var m = io.duo.sim.kernel.api.CapabilityMetadata.inProcessDirect(Set.of());
        r.register(provider(io.duo.sim.kernel.api.Contract.REGISTRY, Tier.VIRTUAL, "a", true, m));
        r.register(provider(io.duo.sim.kernel.api.Contract.REGISTRY, Tier.VIRTUAL, "b", true, m));
        var e = assertThrows(ContractRegistry.RegistrationException.class, r::validateDefaults);
        assertTrue(e.getMessage().contains("multiple default"));
    }

    @Test
    void rejectsZeroDefaults() {
        ContractRegistry r = new ContractRegistry();
        var m = io.duo.sim.kernel.api.CapabilityMetadata.inProcessDirect(Set.of());
        r.register(provider(io.duo.sim.kernel.api.Contract.REGISTRY, Tier.VIRTUAL, "a", false, m));
        var e = assertThrows(ContractRegistry.RegistrationException.class, r::validateDefaults);
        assertTrue(e.getMessage().contains("no default"));
    }

    @Test
    void resolveUsesDefaultOrImplName() {
        ContractRegistry r = new ContractRegistry();
        var m = io.duo.sim.kernel.api.CapabilityMetadata.inProcessDirect(Set.of());
        r.register(provider(io.duo.sim.kernel.api.Contract.REGISTRY, Tier.VIRTUAL, "a", true, m));
        r.register(provider(io.duo.sim.kernel.api.Contract.REGISTRY, Tier.VIRTUAL, "b", false, m));
        assertEquals("a", r.resolve(io.duo.sim.kernel.api.Contract.REGISTRY, Tier.VIRTUAL, null)
                .implName());
        assertEquals("b", r.resolve(io.duo.sim.kernel.api.Contract.REGISTRY, Tier.VIRTUAL, "b")
                .implName());
        assertThrows(ContractRegistry.RegistrationException.class,
                () -> r.resolve(io.duo.sim.kernel.api.Contract.REGISTRY, Tier.EMBEDDED, null));
    }

    // ---- 事件总线（§7.4）----

    @Test
    void eventBusDeliversAndEnforcesPrefixes() {
        SimpleEventBus bus = new SimpleEventBus();
        List<Event> got = new ArrayList<>();
        bus.subscribe(got::add);

        bus.publish(Event.sim("sim.fault-injected", "workers-3", Map.of("type", "crash")));
        bus.publish(Event.sut("sut.task-retry", "master", Map.of("taskId", "t-1")));
        assertEquals(2, got.size());
        assertEquals("workers-3", got.get(0).sourceId());
        assertEquals("sut.task-retry", got.get(1).type());

        assertThrows(IllegalArgumentException.class,
                () -> Event.sim("sut.task-retry", "x", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> Event.sut("sim.component-crashed", "x", Map.of()));
    }

    @Test
    void componentIdInstanceSourceConvention() {
        ComponentId workers = new ComponentId("workers");
        assertEquals("workers-3", workers.instanceSourceId(3));
        assertThrows(IllegalArgumentException.class, () -> workers.instanceSourceId(0));
    }
}
