package io.duo.sim.components.provider;

import io.duo.sim.components.scheduler.VirtualScheduler;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

import java.util.Set;

/**
 * VirtualScheduler 提供者（(SCHEDULER, VIRTUAL) 缺省实现，M5 交付物 1）。
 *
 * <p>元数据：{@code endpointShape=DUO_PORT}（worker 拨号进来，计划 §2）+
 * {@code instanceControl=false}（调度桩无实例级操作语义）+ {@code supportedFaults={freeze}}
 * （派发停摆；实现 {@code FaultInjectable}——§7.5 一致性校验要求二者对应）。
 *
 * <p>与 real 档（examples 的 {@code DemoScheduler}）的关系：**调度语义共用同一份
 * {@code SchedulerStateMachine}/{@code DispatchSelector}**，差别只在「谁是 SUT」——
 * real 档的 SUT 是调度器本身，virtual 档让 SUT 可以落在 worker 侧。
 */
public final class VirtualSchedulerProvider implements ComponentProvider {

    @Override
    public Contract contract() {
        return Contract.SCHEDULER;
    }

    @Override
    public Tier tier() {
        return Tier.VIRTUAL;
    }

    @Override
    public String implName() {
        return "virtual-scheduler";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public CapabilityMetadata metadata() {
        return CapabilityMetadata.duoPort(false, Set.of(FaultAction.FREEZE)).withDefault(true);
    }

    @Override
    public VirtualComponent newComponent() {
        return new VirtualScheduler();
    }
}
