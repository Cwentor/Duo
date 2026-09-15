package io.duo.sim.embedded.provider;

import io.duo.sim.embedded.registry.ZookeeperContainerRegistry;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

import java.util.Set;

/**
 * ZookeeperContainerRegistry 提供者：(REGISTRY, CONTAINER) 缺省实现（M4 T38）。
 *
 * <p>元数据：{@code endpointShape=THIRD_PARTY}（容器映射端口供 wire 连接）+
 * {@code interfaceDirect=true}（同进程门面，与 embedded 档 D1b 同构）+
 * {@code supportedFaults=∅}——容器档不支持 registry-flap（D6：重启换宿主端口，
 * wire 客户端无法按旧端口重连），时间线/热注入在校验期即被拒绝（§7.2 无降级）。
 */
public final class ZookeeperContainerProvider implements ComponentProvider {

    @Override
    public Contract contract() {
        return Contract.REGISTRY;
    }

    @Override
    public Tier tier() {
        return Tier.CONTAINER;
    }

    @Override
    public String implName() {
        return "zk-container-registry";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public CapabilityMetadata metadata() {
        return new CapabilityMetadata(EndpointShape.THIRD_PARTY, true, false,
                Set.of(), true);
    }

    @Override
    public VirtualComponent newComponent() {
        return new ZookeeperContainerRegistry();
    }
}
