package io.duo.sim.embedded.registry;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.core.SimpleEventBus;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T25 单测：embedded registry-flap = TestingServer 整服闪断（真实会话/临时节点丢失，无快照重放）。 */
class CuratorRegistryFlapTest {

    private CuratorRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.stop(StopMode.GRACEFUL);
        }
    }

    private CuratorRegistry start(SimpleEventBus bus) {
        registry = new CuratorRegistry();
        registry.init(new ComponentContext(new ComponentId("zk"), Map.of(),
                SimClock.real(), bus, Map.of(), Map.of()));
        registry.start();
        return registry;
    }

    private static FaultAction flap() {
        return new FaultAction(FaultAction.REGISTRY_FLAP,
                FaultAction.ComponentAddress.of(new ComponentId("zk")), Map.of(), null);
    }

    @Test
    void flapInvalidatesEphemeralNodesAndEmitsEvents() {
        var bus = new SimpleEventBus();
        List<String> types = new ArrayList<>();
        bus.subscribe(e -> types.add(e.type()));
        var r = start(bus);
        r.registerEndpoint("scheduler", "127.0.0.1:9000");
        assertEquals(List.of("127.0.0.1:9000"), r.discoverEndpoints("scheduler"));

        r.inject(flap());

        assertFalse(r.isFlapping(), "embedded flap is instantaneous (restart completes in inject)");
        // 真实语义：端点（临时节点）随闪断消失，**无快照重放**
        assertEquals(List.of(), r.discoverEndpoints("scheduler"),
                "embedded flap must NOT replay endpoints (unlike virtual tier)");
        assertTrue(types.contains("sim.registry-flap-started"));
        assertTrue(types.contains("sim.registry-flap-cleared"));
    }

    @Test
    void nodeDisappearanceVisibleFromIndependentWireClient() throws Exception {
        // 独立 wire 客户端（模拟 SUT）视角：闪断后其临时节点也消失
        var r = start(new SimpleEventBus());
        String connect = r.connectString();
        try (CuratorFramework client = CuratorFrameworkFactory.builder()
                .connectString(connect)
                .retryPolicy(new RetryOneTime(200))
                .build()) {
            client.start();
            assertTrue(client.blockUntilConnected(10, java.util.concurrent.TimeUnit.SECONDS));
            client.create().creatingParentsIfNeeded()
                    .forPath("/sut-flag", "alive".getBytes(StandardCharsets.UTF_8));

            r.inject(flap());

            // 闪断后（新 server 实例）该节点不存在——真实会话丢失
            try (CuratorFramework probe = CuratorFrameworkFactory.builder()
                    .connectString(r.connectString())
                    .retryPolicy(new RetryOneTime(200))
                    .build()) {
                probe.start();
                assertTrue(probe.blockUntilConnected(10, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(probe.checkExists().forPath("/sut-flag") == null,
                        "ephemeral node must be gone after full-server flap");
            }
        }
    }

    @Test
    void reRegistrationAfterFlapIsPossible() {
        var r = start(new SimpleEventBus());
        r.registerEndpoint("scheduler", "127.0.0.1:9000");
        r.inject(flap());
        assertEquals(List.of(), r.discoverEndpoints("scheduler"));

        // 恢复后重新注册（消费方自愈，框架不代劳）→ 可见
        r.registerEndpoint("scheduler", "127.0.0.1:9000");
        assertEquals(List.of("127.0.0.1:9000"), r.discoverEndpoints("scheduler"),
                "re-registration after flap must be visible");
    }

    @Test
    void flapIsIdempotent() {
        var r = start(new SimpleEventBus());
        r.registerEndpoint("scheduler", "127.0.0.1:9000");
        r.inject(flap());
        r.inject(flap()); // 第二次（已不 flapping）应可再次执行且不抛
        assertEquals(List.of(), r.discoverEndpoints("scheduler"));
    }

    @Test
    void unsupportedFaultRejected() {
        var r = start(new SimpleEventBus());
        assertThrows(UnsupportedOperationException.class,
                () -> r.inject(new FaultAction("freeze",
                        FaultAction.ComponentAddress.of(new ComponentId("zk")), Map.of(), null)));
    }

    @Test
    void facadeUsableAfterFlap() {
        // 门面在闪断后自动以新连接可用（框架组件的自愈由门面承担，SUT 的由 SUT 承担）
        var r = start(new SimpleEventBus());
        var session = r.openSession("w-1");
        session.createEphemeral("/duo/workers/w-1", "workers-1");
        r.inject(flap());
        assertTrue(r.health().healthy(), "facade must be reconnected and healthy after flap");
        // 闪断后旧会话的临时节点已消失（真实语义）
        var fresh = r.openSession("w-1b");
        fresh.createEphemeral("/duo/workers/w-1b", "workers-1b");
        assertEquals(List.of(), r.discoverEndpoints("scheduler")); // 端点未重注册 → 仍空
    }
}
