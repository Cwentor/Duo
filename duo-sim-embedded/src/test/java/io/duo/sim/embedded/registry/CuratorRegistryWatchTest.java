package io.duo.sim.embedded.registry;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.core.SimpleEventBus;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T26 单测：临时节点变化观察（节点变更 → 框架事件 sim.registry-node-changed）。 */
class CuratorRegistryWatchTest {

    private CuratorRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.stop(StopMode.GRACEFUL);
        }
    }

    @Test
    void nodeChangesEmitFrameworkEvents() throws Exception {
        var bus = new SimpleEventBus();
        List<Event> events = new CopyOnWriteArrayList<>();
        bus.subscribe(events::add);
        registry = new CuratorRegistry();
        registry.init(new ComponentContext(new ComponentId("zk"), Map.of(),
                SimClock.real(), bus, Map.of(), Map.of()));
        registry.start();

        // 门面注册端点 → CREATED 事件
        registry.registerEndpoint("scheduler", "127.0.0.1:9000");
        assertTrue(waitForEvent(events, "sim.registry-node-changed",
                        c -> "CREATED".equals(c.payload().get("kind"))
                                && "/duo/endpoints/scheduler".equals(c.payload().get("path"))),
                "node CREATED must surface as framework event");

        // 外部 wire 客户端删除节点 → 门面视角？门面只对自身操作发事件；
        // 这里验证门面删除 → DELETED 事件
        var session = registry.openSession("s-1");
        session.createEphemeral("/duo/probe", "v");
        session.delete("/duo/probe");
        assertTrue(waitForEvent(events, "sim.registry-node-changed",
                        c -> "DELETED".equals(c.payload().get("kind"))
                                && "/duo/probe".equals(c.payload().get("path"))),
                "node DELETED must surface as framework event");
    }

    @Test
    void externalWireClientNodeVisibleToFacadeDiscovery() throws Exception {
        // 观测途径①：外部客户端的节点变化经真实 ZK 对门面可见（发现查询）
        var bus = new SimpleEventBus();
        registry = new CuratorRegistry();
        registry.init(new ComponentContext(new ComponentId("zk"), Map.of(),
                SimClock.real(), bus, Map.of(), Map.of()));
        registry.start();

        try (CuratorFramework ext = CuratorFrameworkFactory.builder()
                .connectString(registry.connectString())
                .retryPolicy(new RetryOneTime(200))
                .build()) {
            ext.start();
            assertTrue(ext.blockUntilConnected(10, TimeUnit.SECONDS));
            ext.create().creatingParentsIfNeeded()
                    .forPath("/duo/endpoints/scheduler",
                            "127.0.0.1:7777".getBytes(StandardCharsets.UTF_8));
            assertNotNull(registry.discoverEndpoints("scheduler"));
            assertTrue(registry.discoverEndpoints("scheduler").contains("127.0.0.1:7777"),
                    "external wire client's node must be discoverable via facade (real ZK)");
        }
    }

    private static boolean waitForEvent(List<Event> events, String type,
                                        java.util.function.Predicate<Event> filter)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (events.stream().anyMatch(e -> e.type().equals(type) && filter.test(e))) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
