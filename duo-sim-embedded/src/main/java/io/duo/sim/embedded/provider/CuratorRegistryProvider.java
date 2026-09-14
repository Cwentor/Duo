package io.duo.sim.embedded.provider;

import io.duo.sim.embedded.registry.CuratorRegistry;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

import java.util.Set;

/**
 * CuratorRegistry 提供者：(REGISTRY, EMBEDDED) 缺省实现（M2 T24）。
 *
 * <p>元数据：{@code endpointShape=THIRD_PARTY}（真实 ZK 端口供 SUT wire 连接）且
 * {@code interfaceDirect=true}（同进程门面供框架组件 direct 消费）——两字段独立，
 * 注册期一致性校验不冲突（§7.5；计划 D1b）。
 * M2 T25 起声明 {@code registry-flap}（TestingServer.restart 整服闪断）。
 */
public final class CuratorRegistryProvider implements ComponentProvider {

    @Override
    public Contract contract() {
        return Contract.REGISTRY;
    }

    @Override
    public Tier tier() {
        return Tier.EMBEDDED;
    }

    @Override
    public String implName() {
        return "curator-registry";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public CapabilityMetadata metadata() {
        // THIRD_PARTY（wire 面）+ interfaceDirect（门面面）+ registry-flap（T25 整服闪断）
        return new CapabilityMetadata(EndpointShape.THIRD_PARTY, true, false,
                Set.of(FaultAction.REGISTRY_FLAP), true);
    }

    @Override
    public VirtualComponent newComponent() {
        return new CuratorRegistry();
    }
}
