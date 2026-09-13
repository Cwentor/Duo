package io.duo.sim.kernel.core;

import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.api.EventBus;
import io.duo.sim.kernel.spi.ComponentProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioRuntimeTest {

    private final List<Event> events = new ArrayList<>();
    private final ScenarioRuntime runtime = new ScenarioRuntime(events::add);

    private void register(String id, FaultFixture f, int count) {
        runtime.registerTarget(id, new ScenarioRuntime.Target(f, id, count));
    }

    @Test
    void crashLifecycleDispatchesAndEmitsEventWithSourceId() {
        var f = new FaultFixture("workers", 3, Set.of());
        register("workers", f, 3);
        var r = runtime.inject(new FaultAction(FaultAction.CRASH,
                FaultAction.ComponentAddress.ofInstance(new ComponentId("workers"), 2),
                Map.of(), null));
        assertTrue(r.success());
        assertEquals(List.of("stopInstance:2:CRASH"), f.actions);
        var injected = events.stream().filter(e -> e.type().equals("sim.fault-injected"))
                .findFirst().orElseThrow();
        assertEquals("workers-2", injected.sourceId()); // 实例级 sourceId（§7.4）
    }

    @Test
    void groupLevelCrashUsesComponentStop() {
        var f = new FaultFixture("workers", 3, Set.of());
        register("workers", f, 3);
        assertTrue(runtime.inject(new FaultAction(FaultAction.CRASH,
                FaultAction.ComponentAddress.of(new ComponentId("workers")),
                Map.of(), null)).success());
        assertEquals(List.of("stop:CRASH"), f.actions);
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.fault-injected")
                && e.sourceId().equals("workers")));
    }

    @Test
    void sutTargetRejectedAsInjectionFailure() {
        var sut = new FaultFixture("master", 1, Set.of());
        register("master", sut, 1);
        runtime.markSut("master");
        var r = runtime.inject(new FaultAction(FaultAction.CRASH,
                FaultAction.ComponentAddress.of(new ComponentId("master")), Map.of(), null));
        assertFalse(r.success());
        assertTrue(r.reason().contains("must not be SUT"));
        assertTrue(sut.running.get()); // 未派发
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.fault-inject-failed")));
    }

    @Test
    void indexOutOfRangeRejected() {
        register("workers", new FaultFixture("workers", 3, Set.of()), 3);
        var r = runtime.inject(new FaultAction(FaultAction.CRASH,
                FaultAction.ComponentAddress.ofInstance(new ComponentId("workers"), 4),
                Map.of(), null));
        assertFalse(r.success());
        assertTrue(r.reason().contains("out of range"));
    }

    @Test
    void unknownTargetRejected() {
        var r = runtime.inject(new FaultAction(FaultAction.CRASH,
                FaultAction.ComponentAddress.of(new ComponentId("ghost")), Map.of(), null));
        assertFalse(r.success());
        assertTrue(r.reason().contains("unknown target"));
    }

    @Test
    void noDegradationComponentWithoutInstanceControlFailsNotSilent() {
        // 组件未实现 InstanceControl → 实例级请求必须失败，不得整组生效（§7.2 无降级）
        var bare = new BareFixture("workers", 3);
        runtime.registerTarget("workers", new ScenarioRuntime.Target(bare, "workers", 3));
        var r = runtime.inject(new FaultAction(FaultAction.CRASH,
                FaultAction.ComponentAddress.ofInstance(new ComponentId("workers"), 1),
                Map.of(), null));
        assertFalse(r.success());
        assertFalse(bare.stoppedWhole); // 整组 stop 没有被触发
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.fault-inject-failed")));
    }

    @Test
    void faultInjectableDispatchWhenDeclared() {
        var f = new FaultFixture("workers", 3, Set.of("registry-flap"));
        register("workers", f, 3);
        // supportedFaults 校验在 T11 校验器中按元数据；运行期直接分发
        assertTrue(runtime.inject(new FaultAction("registry-flap",
                FaultAction.ComponentAddress.of(new ComponentId("workers")),
                Map.of(), null)).success());
        assertEquals(List.of("inject:registry-flap"), f.actions);
    }

    /** 未实现 InstanceControl 的整组组件。 */
    static class BareFixture implements VirtualComponent {
        volatile boolean stoppedWhole;
        private final ComponentId id;
        private final int count;

        BareFixture(String id, int count) {
            this.id = new ComponentId(id);
            this.count = count;
        }

        @Override public ComponentId id() {
            return id;
        }
        @Override public void init(io.duo.sim.kernel.api.ComponentContext ctx) { }
        @Override public void start() { }
        @Override public void stop(StopMode mode) {
            stoppedWhole = true;
        }
        @Override public void restart() { }
        @Override public io.duo.sim.kernel.api.HealthReport health() {
            return io.duo.sim.kernel.api.HealthReport.ok();
        }
        @Override public List<io.duo.sim.kernel.api.ExposedEndpoint> endpoints() {
            return List.of();
        }
    }
}
