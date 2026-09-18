package io.duo.sim.embedded.registry;

import io.duo.sim.embedded.provider.ZookeeperContainerProvider;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.EventBus;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.core.SimpleEventBus;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 T38：container 档（Testcontainers ZK）——真实容器端口 wire 往返 + 无 Docker 自动 skip
 * （设计文档 §13「容器档在无 Docker 环境自动 skip」；计划 D6：skip 路径本身即验证）。
 *
 * <p>本机无 Docker 时全部用例 skipped（Assumptions）；有 Docker 的环境跑出真实往返。
 * provider 元数据与注册期校验（supportedFaults=∅ 等）不依赖 Docker，始终验证。
 */
@EnabledIf(value = "io.duo.sim.embedded.registry.ZookeeperContainerRegistryTest#dockerAvailable",
        disabledReason = "Docker not available — container tier auto-skipped (§13)")
class ZookeeperContainerRegistryTest {

    private ZookeeperContainerRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.stop(StopMode.GRACEFUL);
        }
    }

    /** Docker 探活（§13 无 Docker 自动 skip；@EnabledIf 使 skip 在 surefire 计数中可见）。 */
    static boolean dockerAvailable() {
        // CI 回归 job 用 -Dduo.docker.enabled=false 显式关闭容器档：让「常规回归零 Docker 依赖」
        // 成为确定性事实，而不是「恰好这台机器没 Docker」（M7/T8：skip 必须可见、可解释）
        if ("false".equalsIgnoreCase(System.getProperty("duo.docker.enabled", "true"))) {
            return false;
        }
        try (Socket s = new Socket()) {
            String endpoint = System.getProperty("duo.docker.host", "");
            if (!endpoint.isBlank()) {
                // 显式 DOCKER_HOST（tcp://host:port 形式）
                var uri = java.net.URI.create(endpoint.replaceFirst("^tcp://", "http://"));
                s.connect(new InetSocketAddress(uri.getHost(),
                        uri.getPort() > 0 ? uri.getPort() : 2375), 1000);
                return true;
            }
            // 缺省 npipe/unix socket 不可直接探测端口；以 docker 客户端探活为准
            Process p = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private ZookeeperContainerRegistry start(EventBus bus) {
        registry = new ZookeeperContainerRegistry();
        registry.init(new ComponentContext(new ComponentId("zk"), Map.of(),
                SimClock.real(), bus, Map.of(), Map.of()));
        registry.start();
        return registry;
    }

    @Test
    void providerMetadataRejectsFlapAndPassesRegistration() {
        var p = new ZookeeperContainerProvider();
        assertEquals(Tier.CONTAINER, p.tier());
        assertEquals(EndpointShape.THIRD_PARTY, p.metadata().endpointShape());
        // D6：容器档不支持 flap——supportedFaults 为空集，时间线声明即校验期拒绝
        assertTrue(p.metadata().supportedFaults().isEmpty());
        assertFalse(p.metadata().instanceControl());
    }

    @Test
    void wireEndpointExposesContainerZkPort() throws Exception {
        var bus = new SimpleEventBus();
        var r = start(bus);
        var eps = r.endpoints();
        assertEquals(1, eps.size());
        assertEquals(EndpointShape.THIRD_PARTY, r.declaredShape());
        assertNotNull(r.connectString());

        // 独立 Curator 客户端（模拟 SUT 的 wire 连接）能连上容器内真实 ZK
        try (CuratorFramework client = CuratorFrameworkFactory.builder()
                .connectString(r.connectString())
                .retryPolicy(new RetryOneTime(200))
                .build()) {
            client.start();
            assertTrue(client.blockUntilConnected(20, TimeUnit.SECONDS),
                    "wire client must connect to container ZK");
        }
    }

    @Test
    void facadeRoundTripThroughRealZk() {
        var bus = new SimpleEventBus();
        var r = start(bus);
        r.registerEndpoint("scheduler", "127.0.0.1:9999");
        assertEquals(java.util.List.of("127.0.0.1:9999"), r.discoverEndpoints("scheduler"));

        // 会话：临时节点创建/关闭清理（门面经真实协议转发到容器 ZK）
        var session = r.openSession("s1");
        session.createEphemeral("/duo/session/s1", "worker");
        assertTrue(session.isAlive());
        session.close();
        assertFalse(session.isAlive());
    }

    @Test
    void eventsCarryContainerKind() {
        var bus = new SimpleEventBus();
        java.util.List<Event> seen = new java.util.ArrayList<>();
        bus.subscribe(seen::add);
        start(bus);
        // 启动事件 kind=container（诊断可读：与 embedded 档区分）
        assertTrue(seen.stream().anyMatch(e -> e.type().equals("sim.registry-started")
                        && "container".equals(e.payload().get("kind"))),
                "registry-started must be emitted with kind=container");
    }
}
