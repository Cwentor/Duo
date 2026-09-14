package io.duo.sim.embedded.provider;

import io.duo.sim.embedded.resource.Fabric8K8sMock;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

import java.util.Set;

/**
 * Fabric8K8sMock 提供者：(RESOURCE, EMBEDDED) 缺省实现（M2 T28）。
 * 端点形态 THIRD_PARTY（HTTP，真实 K8s REST 协议）。
 */
public final class Fabric8K8sMockProvider implements ComponentProvider {

    @Override
    public Contract contract() {
        return Contract.RESOURCE;
    }

    @Override
    public Tier tier() {
        return Tier.EMBEDDED;
    }

    @Override
    public String implName() {
        return "fabric8-k8s-mock";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public CapabilityMetadata metadata() {
        return new CapabilityMetadata(EndpointShape.THIRD_PARTY, false, false, Set.of(), true);
    }

    @Override
    public VirtualComponent newComponent() {
        return new Fabric8K8sMock();
    }
}
