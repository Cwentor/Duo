package io.duo.sim.components.provider;

import io.duo.sim.components.engine.VirtualEngine;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

import java.util.Set;

/**
 * VirtualEngine 提供者（(ENGINE, VIRTUAL) 缺省实现，M5 交付物 1）。
 *
 * <p>元数据：{@code endpointShape=DUO_PORT}（提交方经线协议提交作业）+
 * {@code instanceControl=false}（引擎无实例级操作语义）+ {@code supportedFaults=
 * {freeze, slow, resource-exhaust}}（M5-3 三动作；实现 {@code FaultInjectable}——
 * §7.5 一致性校验要求二者对应）。
 */
public final class VirtualEngineProvider implements ComponentProvider {

    @Override
    public Contract contract() {
        return Contract.ENGINE;
    }

    @Override
    public Tier tier() {
        return Tier.VIRTUAL;
    }

    @Override
    public String implName() {
        return "virtual-engine";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public CapabilityMetadata metadata() {
        return CapabilityMetadata.duoPort(false, Set.of(
                FaultAction.FREEZE, FaultAction.SLOW, FaultAction.RESOURCE_EXHAUST))
                .withDefault(true);
    }

    @Override
    public VirtualComponent newComponent() {
        return new VirtualEngine();
    }
}
