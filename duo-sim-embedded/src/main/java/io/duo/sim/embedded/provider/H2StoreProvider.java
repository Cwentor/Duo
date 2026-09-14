package io.duo.sim.embedded.provider;

import io.duo.sim.embedded.store.H2Store;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

import java.util.Set;

/**
 * H2Store 提供者：(STORE, EMBEDDED) 缺省实现（M2 T27）。
 * 端点形态 THIRD_PARTY（JDBC URL）；不提供同进程适配（SUT 用真实 JDBC 驱动连接）。
 */
public final class H2StoreProvider implements ComponentProvider {

    @Override
    public Contract contract() {
        return Contract.STORE;
    }

    @Override
    public Tier tier() {
        return Tier.EMBEDDED;
    }

    @Override
    public String implName() {
        return "h2-store";
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
        return new H2Store();
    }
}
