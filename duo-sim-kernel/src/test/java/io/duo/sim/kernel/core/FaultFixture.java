package io.duo.sim.kernel.core;

import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.FaultAction;

import java.util.List;
import java.util.Set;

/**
 * 测试 fixture 组件（T10）：实现 InstanceControl + FaultInjectable，
 * 供注入通路与"无降级"校验路径使用。
 */
public class FaultFixture implements io.duo.sim.kernel.api.VirtualComponent,
        io.duo.sim.kernel.api.InstanceControl, io.duo.sim.kernel.api.FaultInjectable {

    private final ComponentId id;
    private final int instanceCount;
    private final Set<String> faults;
    /** 观察用。 */
    public final List<String> actions = new java.util.concurrent.CopyOnWriteArrayList<>();
    public final java.util.concurrent.atomic.AtomicBoolean running =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    public FaultFixture(String id, int instanceCount, Set<String> faults) {
        this.id = new ComponentId(id);
        this.instanceCount = instanceCount;
        this.faults = faults;
    }

    @Override public ComponentId id() {
        return id;
    }
    @Override public void init(io.duo.sim.kernel.api.ComponentContext ctx) { }
    @Override public void start() { }
    @Override public void stop(io.duo.sim.kernel.api.StopMode mode) {
        running.set(false);
        actions.add("stop:" + mode);
    }
    @Override public void restart() {
        running.set(true);
        actions.add("restart");
    }
    @Override public io.duo.sim.kernel.api.HealthReport health() {
        return io.duo.sim.kernel.api.HealthReport.ok();
    }
    @Override public List<io.duo.sim.kernel.api.ExposedEndpoint> endpoints() {
        return List.of();
    }

    @Override public void stopInstance(int index, io.duo.sim.kernel.api.StopMode mode) {
        actions.add("stopInstance:" + index + ":" + mode);
    }
    @Override public void restartInstance(int index) {
        actions.add("restartInstance:" + index);
    }
    @Override public void injectOnInstance(FaultAction action) {
        actions.add("injectOnInstance:" + action.type() + ":" + action.target().instanceIndex());
    }

    @Override public void inject(FaultAction action) {
        actions.add("inject:" + action.type());
    }
    @Override public void clear(FaultAction action) {
        actions.add("clear:" + action.type());
    }

    public int instanceCount() {
        return instanceCount;
    }

    public Set<String> supportedFaults() {
        return faults;
    }
}
