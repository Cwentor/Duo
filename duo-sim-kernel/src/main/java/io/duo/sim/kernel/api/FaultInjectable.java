package io.duo.sim.kernel.api;

import java.util.Set;

/**
 * 契约级故障注入 SPI（§7.2 可选能力）。带 duration 的动作由场景引擎计时到期自动 clear。
 * 实现须在 CapabilityMetadata.supportedFaults 声明所支持的类型（§7.5 一致性校验）。
 */
public interface FaultInjectable {

    void inject(FaultAction action);

    void clear(FaultAction action);
}
