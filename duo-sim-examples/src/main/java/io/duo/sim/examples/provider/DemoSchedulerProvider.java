package io.duo.sim.examples.provider;

import io.duo.sim.examples.scheduler.DemoScheduler;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

import java.util.List;
import java.util.Set;

/**
 * demo-scheduler 提供者：(SCHEDULER, REAL) 缺省实现。
 *
 * <p>demo-scheduler 是 SUT——不走 VirtualComponent 生命周期（内核不启动它，
 * ScenarioEngine.startSut() 负责），但契约注册表需要其档位实现存在（§8 规则 1）。
 * 声明为"适配性 VirtualComponent"：内核若意外实例化也不会工作，仅用于元数据声明。
 */
public final class DemoSchedulerProvider implements ComponentProvider {

    @Override
    public Contract contract() {
        return Contract.SCHEDULER;
    }

    @Override
    public Tier tier() {
        return Tier.REAL;
    }

    @Override
    public String implName() {
        return "demo-scheduler";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public CapabilityMetadata metadata() {
        return new CapabilityMetadata(EndpointShape.DUO_PORT, false, false, Set.of(), true);
    }

    @Override
    public VirtualComponent newComponent() {
        return new VirtualComponent() {
            @Override public io.duo.sim.kernel.api.ComponentId id() {
                return new io.duo.sim.kernel.api.ComponentId("demo-scheduler-sut");
            }

            @Override public void init(io.duo.sim.kernel.api.ComponentContext ctx) {
                throw new UnsupportedOperationException(
                        "SUT is launched via ScenarioEngine.startSut(), not component manager");
            }

            @Override public void start() {
                throw new UnsupportedOperationException("SUT cannot be started as component");
            }

            @Override public void stop(StopMode mode) { }

            @Override public void restart() { }

            @Override public io.duo.sim.kernel.api.HealthReport health() {
                return io.duo.sim.kernel.api.HealthReport.down("SUT managed by SutLauncher");
            }

            @Override public List<ExposedEndpoint> endpoints() {
                return List.of();
            }
        };
    }
}
