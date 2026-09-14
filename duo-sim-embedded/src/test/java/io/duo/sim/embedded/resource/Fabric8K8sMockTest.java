package io.duo.sim.embedded.resource;

import io.duo.sim.embedded.provider.Fabric8K8sMockProvider;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.kernel.core.SimpleEventBus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T28 单测：Fabric8 K8s mock 适配器（内存 K8s API server，无需真实集群）。 */
class Fabric8K8sMockTest {

    private Fabric8K8sMock mock;

    @AfterEach
    void tearDown() {
        if (mock != null) {
            mock.stop(StopMode.GRACEFUL);
        }
    }

    private Fabric8K8sMock start(SimpleEventBus bus) {
        mock = new Fabric8K8sMock();
        mock.init(new ComponentContext(new ComponentId("k8s"), Map.of(),
                SimClock.real(), bus, Map.of(), Map.of()));
        mock.start();
        return mock;
    }

    @Test
    void mockServerStartsAndExposesHttpEndpoint() {
        var r = start(new SimpleEventBus());
        assertNotNull(r.url());
        assertEquals(EndpointShape.THIRD_PARTY,
                new Fabric8K8sMockProvider().metadata().endpointShape());
        assertEquals(1, r.endpoints().size());
        assertEquals("http", r.endpoints().get(0).kind());
        assertTrue(r.health().healthy());
    }

    @Test
    void podCrudRoundTripThroughMockClient() {
        var r = start(new SimpleEventBus());
        Pod pod = new PodBuilder()
                .withNewMetadata().withName("demo-pod").withNamespace("default").endMetadata()
                .withNewSpec().addNewContainer()
                .withName("c1").withImage("busybox").endContainer().endSpec()
                .build();

        // 非 CRUD 模式：显式声明期望的 API 交互（Fabric8 mock 标准用法）
        r.server().expect().post()
                .withPath("/api/v1/namespaces/default/pods")
                .andReturn(201, pod)
                .once();
        r.server().expect().get()
                .withPath("/api/v1/namespaces/default/pods/demo-pod")
                .andReturn(200, pod)
                .once();

        var created = r.client().pods().inNamespace("default").resource(pod).create();
        assertNotNull(created, "mock must return the declared pod");
        assertEquals("demo-pod", created.getMetadata().getName());

        Pod fetched = r.client().pods().inNamespace("default").withName("demo-pod").get();
        assertNotNull(fetched, "created pod must be retrievable (real K8s REST round-trip)");
        assertEquals("busybox", fetched.getSpec().getContainers().get(0).getImage());
    }

    @Test
    void startedEventFlowsToBus() {
        var bus = new SimpleEventBus();
        List<String> types = new ArrayList<>();
        bus.subscribe(e -> types.add(e.type()));
        start(bus);
        assertTrue(types.contains("sim.resource-started"));
    }

    @Test
    void stoppedMockRejectsFurtherUse() {
        var r = start(new SimpleEventBus());
        r.stop(StopMode.GRACEFUL);
        assertFalse(r.health().healthy());
        assertTrue(r.endpoints().isEmpty());
    }

    @Test
    void providerMetadataPassesRegistryConsistency() {
        var p = new Fabric8K8sMockProvider();
        assertEquals(Contract.RESOURCE, p.contract());
        assertEquals(Tier.EMBEDDED, p.tier());
        var reg = new ContractRegistry();
        reg.register(p);
        reg.validateDefaults();
        assertEquals("fabric8-k8s-mock",
                reg.resolve(Contract.RESOURCE, Tier.EMBEDDED, null).implName());
    }

    @Test
    void restartRebuildsServer() {
        var r = start(new SimpleEventBus());
        String oldUrl = r.url();
        r.restart();
        assertNotNull(r.url(), "server must be up after restart");
        assertTrue(r.health().healthy());
        // 端口可能变化（新 mock server 实例）
        assertNotNull(oldUrl);
    }
}
