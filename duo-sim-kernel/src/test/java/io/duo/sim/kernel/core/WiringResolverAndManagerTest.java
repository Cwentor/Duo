package io.duo.sim.kernel.core;

import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WiringResolverAndManagerTest {

    // ---- 拓扑素材 ----

    private static WiringResolver.NodeView node(String id, String contract,
                                                WiringResolver.TierView tier,
                                                Map<String, WiringResolver.SlotView> wiring,
                                                boolean external) {
        return new WiringResolver.NodeView(id, contract, tier, wiring, external);
    }

    private static final CapabilityMetadata VIRTUAL_REGISTRY =
            CapabilityMetadata.inProcessDirect(Set.of());
    private static final CapabilityMetadata VIRTUAL_WORKER =
            CapabilityMetadata.duoPort(true, Set.of());
    private static final CapabilityMetadata REAL_WORKER =
            new CapabilityMetadata(EndpointShape.DUO_PORT, false, false, Set.of(), false);

    private java.util.function.BiFunction<String, String, CapabilityMetadata> metadata() {
        return (contract, tier) -> switch (contract + "/" + tier) {
            case "registry/VIRTUAL" -> VIRTUAL_REGISTRY;
            case "worker/VIRTUAL" -> VIRTUAL_WORKER;
            case "worker/REAL" -> REAL_WORKER;
            default -> null;
        };
    }

    // ---- topoOrder ----

    @Test
    void topoOrdersDependenciesFirst() {
        var nodes = List.of(
                node("workers", "worker", WiringResolver.TierView.VIRTUAL,
                        Map.of("scheduler", new WiringResolver.SlotView("master",
                                "scheduler", null)), false),
                node("master", "scheduler", WiringResolver.TierView.REAL,
                        Map.of("registry", new WiringResolver.SlotView("zk", "registry",
                                null)), false),
                node("zk", "registry", WiringResolver.TierView.VIRTUAL, Map.of(), false));
        // workers 依赖 master（master 又依赖 zk）→ zk 最先，workers 最后
        List<String> order = WiringResolver.topoOrder(nodes);
        assertEquals(List.of("zk", "master", "workers"), order);
    }

    @Test
    void topoRejectsCycle() {
        var a = node("a", "registry", WiringResolver.TierView.VIRTUAL,
                Map.of("b", new WiringResolver.SlotView("b", "registry", null)), false);
        var b = node("b", "registry", WiringResolver.TierView.VIRTUAL,
                Map.of("a", new WiringResolver.SlotView("a", "registry", null)), false);
        var e = assertThrows(WiringResolver.WiringException.class,
                () -> WiringResolver.topoOrder(List.of(a, b)));
        assertTrue(e.getMessage().contains("cycle"));
    }

    // ---- 路径推断与规则 2/3 ----

    @Test
    void infersDirectForEndpointlessTarget() {
        var zk = node("zk", "registry", WiringResolver.TierView.VIRTUAL, Map.of(), false);
        var consumer = node("master", "scheduler", WiringResolver.TierView.REAL,
                Map.of("registry", new WiringResolver.SlotView("zk", "registry", null)), false);
        var bindings = WiringResolver.resolveNode(consumer,
                Map.of("zk", zk, "master", consumer), metadata(),
                (slot, target) -> new Object() {
                }, // direct 对象
                (slot, target) -> null);
        assertEquals(ComponentContext.ConnectionPath.DIRECT,
                bindings.get("registry").path());
    }

    @Test
    void infersWireForEndpointFullTarget() {
        var w = node("workers", "worker", WiringResolver.TierView.VIRTUAL, Map.of(), false);
        var master = node("master", "scheduler", WiringResolver.TierView.REAL,
                Map.of("workers", new WiringResolver.SlotView("workers", "worker", null)),
                false);
        var bindings = WiringResolver.resolveNode(master,
                Map.of("workers", w, "master", master),
                (c, t) -> "worker".equals(c) ? VIRTUAL_WORKER : VIRTUAL_REGISTRY,
                (slot, target) -> null,
                (slot, target) -> target + ":8123");
        assertEquals(ComponentContext.ConnectionPath.WIRE, bindings.get("workers").path());
        assertEquals("workers:8123", bindings.get("workers").endpoint());
    }

    @Test
    void explicitDirectFromExternalConsumerFails() {
        var zk = node("zk", "registry", WiringResolver.TierView.VIRTUAL, Map.of(), false);
        var ext = node("master", "scheduler", WiringResolver.TierView.REAL,
                Map.of("registry", new WiringResolver.SlotView("zk", "registry", "direct")),
                true); // external
        var e = assertThrows(WiringResolver.WiringException.class,
                () -> WiringResolver.resolveNode(ext, Map.of("zk", zk, "master", ext),
                        metadata(), (s, t) -> new Object(), (s, t) -> null));
        assertTrue(e.getMessage().contains("external"));
    }

    @Test
    void directTargetWithoutSameProcessAdapterFails() {
        var w = node("workers", "worker", WiringResolver.TierView.VIRTUAL, Map.of(), false);
        var consumer = node("master", "scheduler", WiringResolver.TierView.REAL,
                Map.of("workers", new WiringResolver.SlotView("workers", "worker", "direct")),
                false);
        var e = assertThrows(WiringResolver.WiringException.class,
                () -> WiringResolver.resolveNode(consumer,
                        Map.of("workers", w, "master", consumer),
                        (c, t) -> VIRTUAL_WORKER, (s, tt) -> null, (s, tt) -> null));
        assertTrue(e.getMessage().contains("interface-direct"));
    }

    @Test
    void contractMismatchFails() {
        var zk = node("zk", "registry", WiringResolver.TierView.VIRTUAL, Map.of(), false);
        var consumer = node("master", "scheduler", WiringResolver.TierView.REAL,
                Map.of("registry", new WiringResolver.SlotView("zk", "worker", null)), false);
        var e = assertThrows(WiringResolver.WiringException.class,
                () -> WiringResolver.resolveNode(consumer, Map.of("zk", zk, "master", consumer),
                        metadata(), (s, t) -> null, (s, t) -> null));
        assertTrue(e.getMessage().contains("expects contract"));
    }

    @Test
    void wireToTargetWithoutEndpointFailsWithUpgradeHint() {
        var zk = node("zk", "registry", WiringResolver.TierView.VIRTUAL, Map.of(), false);
        var ext = node("master", "scheduler", WiringResolver.TierView.REAL,
                Map.of("registry", new WiringResolver.SlotView("zk", "registry", "wire")),
                true);
        var e = assertThrows(WiringResolver.WiringException.class,
                () -> WiringResolver.resolveNode(ext, Map.of("zk", zk, "master", ext),
                        metadata(), (s, t) -> null, (s, t) -> null));
        assertTrue(e.getMessage().contains("no reachable endpoint"));
    }

    // ---- ComponentManager ----

    static final class Recorder implements VirtualComponent {
        final String name;
        final List<String> log;
        final boolean failOnStart;

        Recorder(String name, List<String> log, boolean failOnStart) {
            this.name = name;
            this.log = log;
            this.failOnStart = failOnStart;
        }

        @Override public ComponentId id() {
            return new ComponentId(name);
        }
        @Override public void init(ComponentContext ctx) { }
        @Override public void start() {
            if (failOnStart) {
                throw new IllegalStateException("boom " + name);
            }
            log.add("start:" + name);
        }
        @Override public void stop(StopMode mode) {
            log.add("stop:" + name);
        }
        @Override public void restart() {
            log.add("restart:" + name);
        }
        @Override public HealthReport health() {
            return HealthReport.ok();
        }
        @Override public List<io.duo.sim.kernel.api.ExposedEndpoint> endpoints() {
            return List.of();
        }
    }

    @Test
    void managerStartsInOrderAndStopsInReverse() {
        List<String> log = new ArrayList<>();
        var mgr = new ComponentManager();
        mgr.startAll(List.of("zk", "master", "workers"),
                id -> new Recorder(id, log, false));
        mgr.registerExtraStop("sut", () -> log.add("stop:sut-extra"));
        assertEquals(List.of("start:zk", "start:master", "start:workers"), log);
        mgr.stopAll();
        assertEquals(List.of("start:zk", "start:master", "start:workers",
                "stop:workers", "stop:master", "stop:zk", "stop:sut-extra"), log);
    }

    @Test
    void managerTearsDownReverseOnStartFailure() {
        List<String> log = new ArrayList<>();
        var mgr = new ComponentManager();
        var e = assertThrows(ComponentManager.StartupFailure.class,
                () -> mgr.startAll(List.of("a", "b", "c"),
                        id -> new Recorder(id, log, "b".equals(id))));
        assertTrue(e.getMessage().contains("b"));
        // b 失败 → 已启动的 a 逆序拆除
        assertEquals(List.of("start:a", "stop:a"), log);
    }
}
