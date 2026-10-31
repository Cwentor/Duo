package io.duo.sim.examples.provider;

import io.duo.sim.examples.worker.DemoRealWorker;
import io.duo.sim.kernel.api.CapabilityMetadata;
import io.duo.sim.kernel.api.Contract;
import io.duo.sim.kernel.api.Tier;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.spi.ComponentProvider;

import java.util.Set;

/** demo real worker 提供者：(WORKER, REAL) 缺省实现（M0 档位切换验收的 real 档）。 */
public final class DemoRealWorkerProvider implements ComponentProvider {

    @Override
    public Contract contract() {
        return Contract.WORKER;
    }

    @Override
    public Tier tier() {
        return Tier.REAL;
    }

    @Override
    public String implName() {
        return "demo-real-worker";
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public CapabilityMetadata metadata() {
        // withDefault(true)：与 isDefault() 一致——注册期交叉校验（R10）会拒绝漂移
        return CapabilityMetadata.duoPort(true, Set.of()).withDefault(true);
    }

    @Override
    public VirtualComponent newComponent() {
        return new DemoRealWorker();
    }
}
