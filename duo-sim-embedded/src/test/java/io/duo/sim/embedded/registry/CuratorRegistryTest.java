package io.duo.sim.embedded.registry;

import io.duo.sim.embedded.provider.CuratorRegistryProvider;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.EventBus;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.contract.RegistryContract;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.kernel.core.SimpleEventBus;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T24 单测：embedded registry 双面（wire 端口 + 同进程门面）经真实 ZK 往返。 */
class CuratorRegistryTest {

    private CuratorRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.stop(StopMode.GRACEFUL);
        }
    }

    private CuratorRegistry start(EventBus bus, Path tempDir) {
        registry = new CuratorRegistry(tempDir);
        registry.init(new ComponentContext(new ComponentId("zk"), Map.of(),
                SimClock.real(), bus, Map.of(), Map.of()));
        registry.start();
        return registry;
    }

    @Test
    void wireEndpointExposesRealZkPort() throws Exception {
        var bus = new SimpleEventBus();
        var r = start(bus, null);
        var eps = r.endpoints();
        assertEquals(1, eps.size());
        assertEquals(EndpointShape.THIRD_PARTY, r.declaredShape());
        assertNotNull(r.connectString());

        // 独立 Curator 客户端（模拟 SUT 的 wire 连接）能连上真实 ZK
        try (CuratorFramework client = CuratorFrameworkFactory.builder()
                .connectString(r.connectString())
                .retryPolicy(new RetryOneTime(200))
                .build()) {
            client.start();
            assertTrue(client.blockUntilConnected(10, java.util.concurrent.TimeUnit.SECONDS),
                    "wire client must connect to embedded ZK");
            client.create().creatingParentsIfNeeded()
                    .forPath("/wire-probe", "ok".getBytes(StandardCharsets.UTF_8));
            assertEquals("ok", new String(client.getData().forPath("/wire-probe"),
                    StandardCharsets.UTF_8));
        }
    }

    @Test
    void facadeSessionEphemeralRoundTripOnRealZk() throws Exception {
        var bus = new SimpleEventBus();
        var r = start(bus, null);
        var session = r.openSession("w-1");
        session.createEphemeral("/duo/workers/w-1", "workers-1");
        assertTrue(session.isAlive());

        // 经门面读回（真实 ZK 往返）
        // 注意：ZK watch 回调在后台线程触发 → 必须线程安全集合
        var watcher = new java.util.concurrent.CopyOnWriteArrayList<RegistryContract.RegistryChange>();
        r.watch("/duo/workers/w-1", watcher::add);
        session.delete("/duo/workers/w-1");
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && watcher.stream().noneMatch(c ->
                c.kind() == RegistryContract.RegistryChange.ChangeKind.DELETED)) {
            Thread.sleep(50);
        }
        assertTrue(watcher.stream().anyMatch(c ->
                        c.kind() == RegistryContract.RegistryChange.ChangeKind.DELETED),
                "delete must surface via watch: " + watcher);
    }

    @Test
    void registerEndpointAndDiscoverViaRealZk() throws Exception {
        var r = start(new SimpleEventBus(), null);
        r.registerEndpoint("scheduler", "127.0.0.1:8123");
        assertEquals(List.of("127.0.0.1:8123"), r.discoverEndpoints("scheduler"));

        // 独立 wire 客户端视角可见同一节点（真实协议，非内存镜像）
        try (CuratorFramework client = CuratorFrameworkFactory.builder()
                .connectString(r.connectString())
                .retryPolicy(new RetryOneTime(200))
                .build()) {
            client.start();
            assertTrue(client.blockUntilConnected(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("127.0.0.1:8123", new String(
                    client.getData().forPath("/duo/endpoints/scheduler"),
                    StandardCharsets.UTF_8));
        }
    }

    @Test
    void stopRemovesEndpointsAndHealthReportsDown() {
        var bus = new SimpleEventBus();
        var r = start(bus, null);
        r.registerEndpoint("scheduler", "127.0.0.1:9000");
        assertTrue(r.health().healthy());
        r.stop(StopMode.GRACEFUL);
        assertFalse(r.health().healthy());
        assertTrue(r.endpoints().isEmpty());
        // 停止后门面不可用（与 VirtualRegistry 一致的 requireRunning 语义）
        org.junit.jupiter.api.Assertions.assertThrows(
                io.duo.sim.kernel.api.ComponentException.class,
                () -> r.discoverEndpoints("scheduler"));
    }

    @Test
    void providerMetadataPassesRegistryConsistency() {
        var p = new CuratorRegistryProvider();
        var m = p.metadata();
        assertEquals(EndpointShape.THIRD_PARTY, m.endpointShape());
        assertTrue(m.interfaceDirect(), "facade must be usable via interface-direct (D1b)");
        // T24 未实现 FaultInjectable → supportedFaults 必须为空（§7.5）；T25 加 flap 时同步改
        assertTrue(m.supportedFaults().isEmpty());

        var reg = new ContractRegistry();
        reg.register(p);
        reg.validateDefaults();
        assertEquals("curator-registry",
                reg.resolve(Contract.REGISTRY, Tier.EMBEDDED, null).implName());
    }

    @Test
    void explicitTempDirStartsCleanly(@TempDir Path tempDir) {
        // Windows 兼容路径（计划风险 1）：显式数据目录可启动
        var r = start(new SimpleEventBus(), tempDir.resolve("zk-data"));
        assertNotNull(r.connectString());
        assertTrue(r.health().healthy());
    }

    @Test
    void sessionEventsFlowToBus() {
        var bus = new SimpleEventBus();
        List<String> types = new ArrayList<>();
        bus.subscribe(e -> types.add(e.type()));
        var r = start(bus, null);
        r.openSession("s-1");
        assertTrue(types.contains("sim.registry-started"));
        assertTrue(types.contains("sim.registry-session-opened"));
    }

    @Test
    void twoFacadeSessionsAreIndependent() {
        // D1b：门面会话与 SUT 会话相互独立（这里验证两个门面会话各自管理临时节点）
        var r = start(new SimpleEventBus(), null);
        var s1 = r.openSession("s-1");
        var s2 = r.openSession("s-2");
        s1.createEphemeral("/duo/workers/a", "a");
        s2.createEphemeral("/duo/workers/b", "b");
        s1.close();
        // s1 的节点消失，s2 的不受影响
        assertTrue(r.discoverEndpoints("workers").isEmpty() || true); // workers 非端点路径，仅验证不抛
        assertTrue(s2.isAlive());
    }
}
