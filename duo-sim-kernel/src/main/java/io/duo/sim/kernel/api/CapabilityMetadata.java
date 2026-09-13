package io.duo.sim.kernel.api;

import java.util.Set;

/**
 * 能力元数据（§7.5）：实现注册时声明的静态能力，**启动前校验的唯一事实源**。
 *
 * <p>注册期一致性校验（§7.5）：{@code endpointShape=NONE ⇒ interfaceDirect=true}；
 * {@code instanceControl=true} 必须实现 InstanceControl；{@code supportedFaults} 非空
 * 必须实现 FaultInjectable。由 ContractRegistry 强制。
 */
public record CapabilityMetadata(EndpointShape endpointShape,
                                 boolean interfaceDirect,
                                 boolean instanceControl,
                                 Set<String> supportedFaults,
                                 boolean defaultImpl) {

    public static CapabilityMetadata inProcessDirect(Set<String> supportedFaults) {
        return new CapabilityMetadata(EndpointShape.NONE, true, false, supportedFaults, false);
    }

    public static CapabilityMetadata duoPort(boolean instanceControl, Set<String> supportedFaults) {
        return new CapabilityMetadata(EndpointShape.DUO_PORT, false, instanceControl,
                supportedFaults, false);
    }

    public CapabilityMetadata withDefault(boolean isDefault) {
        return new CapabilityMetadata(endpointShape, interfaceDirect, instanceControl,
                supportedFaults, isDefault);
    }
}
