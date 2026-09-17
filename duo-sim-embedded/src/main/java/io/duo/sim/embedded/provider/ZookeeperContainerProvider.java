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
 *
 * <p><b>重启能力（M4 独立验收 HIGH 整改后明确化）</b>：容器档**不支持任何形式的整服重启**。
 * {@code supportedFaults=∅} 只覆盖 FaultInjectable 类动作（flap 等）；生命周期动作
 * {@code crash}/{@code restart} 在校验期被 §7.2 设计豁免（{@code ScenarioValidator} 的
 * {@code lifecycle} 分支），因此**不能**依赖元数据表达「容器档也不支持 restart」——
 * 该守卫已下沉到 {@code ZookeeperContainerRegistry.restart()} 显式抛
 * {@code UnsupportedOperationException}（父类 stop→start 会换宿主端口，导致 wire 永久挂起）。
 * 若将来需要支持重启，需先引入固定宿主端口（{@code PortBinding}）并覆写为「保端口重建」。
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
