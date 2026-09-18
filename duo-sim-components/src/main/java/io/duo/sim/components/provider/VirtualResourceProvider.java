package io.duo.sim.components.provider;

import io.duo.sim.components.resource.VirtualResourceManager;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

import java.util.Set;

/**
 * VirtualResourceManager 提供者（(RESOURCE, VIRTUAL) 缺省实现，M5 交付物 2）。
 *
 * <p>元数据：{@code endpointShape=NONE} + {@code interfaceDirect=true}（同进程配额门面）+
 * {@code supportedFaults={resource-exhaust}}（M5-3；实现 {@code FaultInjectable}——
 * §7.5 一致性校验要求二者对应）。
 *
 * <p>与 embedded 档（{@code Fabric8K8sMock}）的分工：embedded 走真实 K8s REST 协议、
 * 表达「API 交互」；virtual 表达「配额分配/耗尽」。二者是**不同档位**，不互相替代。
 */
public final class VirtualResourceProvider implements ComponentProvider {

    @Override
    public Contract contract() {
        return Contract.RESOURCE;
    }

    @Override
    public Tier tier() {
        return Tier.VIRTUAL;
    }

    @Override
    public String implName() {
        return "virtual-resource";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public CapabilityMetadata metadata() {
        return CapabilityMetadata.inProcessDirect(Set.of(FaultAction.RESOURCE_EXHAUST))
                .withDefault(true);
    }

    @Override
    public VirtualComponent newComponent() {
        return new VirtualResourceManager();
    }
}
