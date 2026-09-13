package io.duo.sim.kernel.spi;

import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;

/**
 * 组件实现提供者（§7.5）：经 ServiceLoader 注册，键为 (contract, tier)。
 *
 * <p>注册期一致性校验由 ContractRegistry 执行（§7.5）：
 * {@code endpointShape=NONE ⇒ interfaceDirect=true}；{@code instanceControl=true}
 * 必须实现 InstanceControl；{@code supportedFaults} 非空必须实现 FaultInjectable。
 */
public interface ComponentProvider {

    Contract contract();

    Tier tier();

    /** 实现名（同一 (contract, tier) 下唯一；节点可用 {@code impl:} 指定）。 */
    String implName();

    /** 是否缺省实现（同一 (contract, tier) 必须恰好一个）。 */
    boolean isDefault();

    CapabilityMetadata metadata();

    VirtualComponent newComponent();
}
