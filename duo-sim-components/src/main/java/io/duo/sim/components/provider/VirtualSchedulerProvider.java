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
        // endpointShape=DUO_PORT（worker 拨号进来）+ interfaceDirect=false：两个字段互不影响。
        // 后者说明的是「同进程能不能直接拿到 SchedulerContract 接口对象」——worker SUT 在另一个进程/
        // 另一条线上，只能拨号，拿不到接口；这条"只能拨号"的形态正是「真实 worker 侧」验收要验证的，
        // 所以这里如实声明 false，而不是为了让 direct 槽好写就改口（改口会让验收失去意义）。
        // 同进程消费者要走门面时，在槽上写 path: direct 由 wiring 逐槽解析（§6），与本字段无关。
        return CapabilityMetadata.duoPort(false, Set.of(FaultAction.FREEZE)).withDefault(true);
    }

    @Override
    public VirtualComponent newComponent() {
        return new VirtualScheduler();
    }
}
