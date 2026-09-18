package io.duo.sim.components.provider;

import io.duo.sim.components.message.VirtualMessageBroker;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

import java.util.Set;

/**
 * VirtualMessageBroker 提供者（(MESSAGE, VIRTUAL) 缺省实现，M5 交付物 1）。
 *
 * <p>元数据：{@code endpointShape=NONE} + {@code interfaceDirect=true}
 * （§7.5 的强制一致性：NONE ⇒ interface-direct；内存队列桩没有对外端口）。
 * 真实 Kafka（embedded/container 档）在有真实用例前不引入。
 */
public final class VirtualMessageBrokerProvider implements ComponentProvider {

    @Override
    public Contract contract() {
        return Contract.MESSAGE;
    }

    @Override
    public Tier tier() {
        return Tier.VIRTUAL;
    }

    @Override
    public String implName() {
        return "virtual-message-broker";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public CapabilityMetadata metadata() {
        return CapabilityMetadata.inProcessDirect(Set.of()).withDefault(true);
    }

    @Override
    public VirtualComponent newComponent() {
        return new VirtualMessageBroker();
    }
}
