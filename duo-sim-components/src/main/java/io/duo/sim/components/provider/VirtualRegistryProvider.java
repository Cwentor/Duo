package io.duo.sim.components.provider;

import io.duo.sim.components.registry.VirtualRegistry;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

import java.util.Set;

/** VirtualRegistry 提供者（(REGISTRY, VIRTUAL) 缺省实现）。 */
public final class VirtualRegistryProvider implements ComponentProvider {

    @Override
    public Contract contract() {
        return Contract.REGISTRY;
    }

    @Override
    public Tier tier() {
        return Tier.VIRTUAL;
    }

    @Override
    public String implName() {
        return "virtual-registry";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public CapabilityMetadata metadata() {
        // 端点形态 NONE ⇒ interfaceDirect=true（§7.5）；M1 T18 声明 registry-flap
        // （VirtualRegistry 实现 FaultInjectable——注册期一致性校验要求二者对应）
        return new CapabilityMetadata(EndpointShape.NONE, true, false,
                Set.of(FaultAction.REGISTRY_FLAP), true);
    }

    @Override
    public VirtualComponent newComponent() {
        return new VirtualRegistry();
    }
}
