package io.duo.sim.components.provider;

import io.duo.sim.components.registry.VirtualRegistry;
import io.duo.sim.components.worker.VirtualWorker;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

import java.util.Set;

/** VirtualWorker 提供者（(WORKER, VIRTUAL) 缺省实现）。 */
public final class VirtualWorkerProvider implements ComponentProvider {

    @Override
    public Contract contract() {
        return Contract.WORKER;
    }

    @Override
    public Tier tier() {
        return Tier.VIRTUAL;
    }

    @Override
    public String implName() {
        return "virtual-worker";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public CapabilityMetadata metadata() {
        // DUO_PORT + instanceControl=true（实现 InstanceControl）；
        // 不实现 FaultInjectable → supportedFaults 必须为空（§7.5 一致性）
        return CapabilityMetadata.duoPort(true, Set.of());
    }

    @Override
    public VirtualComponent newComponent() {
        return new VirtualWorker();
    }
}
