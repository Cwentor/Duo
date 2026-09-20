package io.duo.sim.kernel.api;

import java.util.Set;

/**
 * 能力元数据（§7.5）：实现注册时声明的静态能力，**启动前校验的唯一事实源**。
 *
 * <p>注册期一致性校验（§7.5）：{@code endpointShape=NONE ⇒ interfaceDirect=true}；
 * {@code instanceControl=true} 必须实现 InstanceControl；{@code supportedFaults} 非空
 * 必须实现 FaultInjectable。由 ContractRegistry 强制。
 *
 * <p>{@code trustedConfigKeys}（安全审计 2026-09-20 规则 9 的配套）：控制面收的是**不可信
 * 字节**，{@code config} 里认不出的键默认拒绝——实现若要支持额外的外部可传键，必须在此
 * **显式声明**，而不是靠"没人知道它"获得安全。默认空集 ⇒ 既有实现零改动、向后兼容。
 */
public record CapabilityMetadata(EndpointShape endpointShape,
                                 boolean interfaceDirect,
                                 boolean instanceControl,
                                 Set<String> supportedFaults,
                                 boolean defaultImpl,
                                 Set<String> trustedConfigKeys) {

    public CapabilityMetadata(EndpointShape endpointShape, boolean interfaceDirect,
                              boolean instanceControl, Set<String> supportedFaults,
                              boolean defaultImpl) {
        this(endpointShape, interfaceDirect, instanceControl, supportedFaults, defaultImpl,
                Set.of());
    }

    public CapabilityMetadata {
        trustedConfigKeys = trustedConfigKeys == null ? Set.of() : Set.copyOf(trustedConfigKeys);
    }

    public static CapabilityMetadata inProcessDirect(Set<String> supportedFaults) {
        return new CapabilityMetadata(EndpointShape.NONE, true, false, supportedFaults, false);
    }

    public static CapabilityMetadata duoPort(boolean instanceControl, Set<String> supportedFaults) {
        return new CapabilityMetadata(EndpointShape.DUO_PORT, false, instanceControl,
                supportedFaults, false);
    }

    public CapabilityMetadata withDefault(boolean isDefault) {
        return new CapabilityMetadata(endpointShape, interfaceDirect, instanceControl,
                supportedFaults, isDefault, trustedConfigKeys);
    }

    /** 追加外部可传的 config 键（实现注册时声明；返回新实例）。 */
    public CapabilityMetadata withTrustedConfigKeys(Set<String> keys) {
        return new CapabilityMetadata(endpointShape, interfaceDirect, instanceControl,
                supportedFaults, defaultImpl, keys);
    }
}
