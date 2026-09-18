package io.duo.sim.components.resource;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.FaultInjectable;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * resource 契约 virtual 档（M5 交付物 2）：**同进程资源配额替身**——CPU/内存配额的
 * 分配与释放（pod/容器申请资源的抽象），使「资源不足 → 任务被拒」这条路径可以在
 * 无 Docker 的常规回归里演练（embedded 档 {@code Fabric8K8sMock} 走真实 K8s REST，
 * 但**不表达配额耗尽语义**，两者互补而非替代）。
 *
 * <p>端点形态 {@code NONE} + {@code interfaceDirect=true}（§7.5 强制一致性）；
 * embedded 档保持 {@code THIRD_PARTY}（真实 K8s API）不变——本档是**新增档位**，
 * 不改既有档位语义（§7.2 无降级）。
 *
 * <p>故障注入（M5-3）：{@code resource-exhaust}——注入后**全部**新分配被显式拒绝
 * （{@link ComponentException}，带剩余配额），已有分配不受影响；{@code clear} 恢复。
 * 幂等：重复注入/重复清除都不产生额外副作用（写成测试）。
 *
 * <p>配置项：{@code capacity.cpu}（缺省 16）、{@code capacity.memGB}（缺省 64）。
 * 可观测：{@code sim.resource-started/-stopped/-crashed/-restarted}、
 * {@code sim.resource-allocated}/{@code sim.resource-released}（含剩余配额）、
 * {@code sim.resource-exhausted}/{@code sim.resource-restored}（故障事实）。
 */
public final class VirtualResourceManager implements VirtualComponent, FaultInjectable {

    private volatile ComponentId id;
    private volatile ComponentContext ctx;
    private volatile int totalCpu = 16;
    private volatile int totalMemGb = 64;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean exhausted = new AtomicBoolean(false);
    private final AtomicInteger usedCpu = new AtomicInteger();
    private final AtomicInteger usedMemGb = new AtomicInteger();
    private final Map<String, Allocation> allocations = new ConcurrentHashMap<>();
    private final AtomicInteger handleSeq = new AtomicInteger();

    /** 一次资源分配。 */
    public record Allocation(String handle, int cpu, int memGB) {
    }

    // ---- VirtualComponent ----

    @Override
    public ComponentId id() {
        return id;
    }

    @Override
    public void init(ComponentContext ctx) {
        this.id = ctx.id();
        this.ctx = ctx;
        this.totalCpu = Integer.parseInt(ctx.config().getOrDefault("capacity.cpu", "16"));
        this.totalMemGb = Integer.parseInt(ctx.config().getOrDefault("capacity.memGB", "64"));
    }

    @Override
    public void start() throws ComponentException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        fire(Event.sim("sim.resource-started", id.value(),
                Map.of("cpu", totalCpu, "memGB", totalMemGb)));
    }

    @Override
    public void stop(StopMode mode) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        int released = allocations.size();
        allocations.clear();
        usedCpu.set(0);
        usedMemGb.set(0);
        exhausted.set(false);
        fire(Event.sim(mode == StopMode.CRASH ? "sim.resource-crashed"
                : "sim.resource-stopped", id.value(),
                Map.of("releasedAllocations", released, "mode", mode.name())));
    }

    @Override
    public void restart() {
        // §7.1：身份保留、内部状态清空（全部分配视为失效——真实资源管理器重启后的语义）
        stop(StopMode.GRACEFUL);
        start();
        fire(Event.sim("sim.resource-restarted", id.value(), Map.of()));
    }

    @Override
    public HealthReport health() {
        return running.get() ? HealthReport.ok() : HealthReport.down("resource manager not running");
    }

    @Override
    public List<ExposedEndpoint> endpoints() {
        return List.of(); // NONE：同进程门面
    }

    // ---- 同进程门面（interface-direct）----

    /**
     * 申请配额。不可满足（资源耗尽故障 / 配额不足）时**显式拒绝**并说明原因——
     * 「悄悄给一个超额分配」会让资源不足类场景完全失真（§12 不静默）。
     */
    public Allocation allocate(int cpu, int memGB) {
        requireRunning();
        if (cpu <= 0 || memGB <= 0) {
            throw new IllegalArgumentException(
                    "allocation must be positive: cpu=" + cpu + " memGB=" + memGB);
        }
        if (exhausted.get()) {
            throw new ComponentException("resource exhausted (injected): cannot allocate cpu="
                    + cpu + " memGB=" + memGB);
        }
        int remainingCpu = totalCpu - usedCpu.get();
        int remainingMem = totalMemGb - usedMemGb.get();
        if (cpu > remainingCpu || memGB > remainingMem) {
            throw new ComponentException("insufficient resource: requested cpu=" + cpu
                    + " memGB=" + memGB + ", remaining cpu=" + remainingCpu
                    + " memGB=" + remainingMem);
        }
        // 双计数同源（单线程快照）：先扣 CPU 再扣内存，任一失败即回滚，避免半个分配
        usedCpu.addAndGet(cpu);
        usedMemGb.addAndGet(memGB);
        String handle = id.value() + "-alloc-" + handleSeq.incrementAndGet();
        Allocation allocation = new Allocation(handle, cpu, memGB);
        allocations.put(handle, allocation);
        fire(Event.sim("sim.resource-allocated", id.value(),
                Map.of("handle", handle, "cpu", cpu, "memGB", memGB,
                        "remainingCpu", totalCpu - usedCpu.get(),
                        "remainingMemGB", totalMemGb - usedMemGb.get())));
        return allocation;
    }

    /** 释放配额（未知句柄即显式失败，避免「释放了但其实不存在」的假成功）。 */
    public void release(String handle) {
        requireRunning();
        Allocation a = allocations.remove(handle);
        if (a == null) {
            throw new ComponentException("unknown allocation handle: " + handle);
        }
        usedCpu.addAndGet(-a.cpu());
        usedMemGb.addAndGet(-a.memGB());
        fire(Event.sim("sim.resource-released", id.value(),
                Map.of("handle", handle, "remainingCpu", totalCpu - usedCpu.get(),
                        "remainingMemGB", totalMemGb - usedMemGb.get())));
    }

    /** 剩余配额（可观测点）。 */
    public int remainingCpu() {
        return exhausted.get() ? 0 : totalCpu - usedCpu.get();
    }

    public int remainingMemGB() {
        return exhausted.get() ? 0 : totalMemGb - usedMemGb.get();
    }

    /** 在册分配（诊断/测试用）。 */
    public List<Allocation> allocations() {
        return new ArrayList<>(allocations.values());
    }

    // ---- FaultInjectable（M5-3：resource-exhaust）----

    @Override
    public void inject(FaultAction action) {
        if (!FaultAction.RESOURCE_EXHAUST.equals(action.type())) {
            throw new UnsupportedOperationException("unsupported fault: " + action.type());
        }
        if (!exhausted.compareAndSet(false, true)) {
            return; // 已耗尽（幂等）
        }
        fire(Event.sim("sim.resource-exhausted", id.value(),
                Map.of("cpu", totalCpu, "memGB", totalMemGb)));
    }

    @Override
    public void clear(FaultAction action) {
        if (!FaultAction.RESOURCE_EXHAUST.equals(action.type())) {
            throw new UnsupportedOperationException("unsupported fault: " + action.type());
        }
        if (!exhausted.compareAndSet(true, false)) {
            return; // 未耗尽（幂等）
        }
        fire(Event.sim("sim.resource-restored", id.value(), Map.of()));
    }

    /** 是否处于耗尽态（测试/诊断用）。 */
    public boolean isExhausted() {
        return exhausted.get();
    }

    private void requireRunning() {
        if (!running.get()) {
            throw new ComponentException("resource manager not running: " + id);
        }
    }

    private void fire(Event e) {
        if (ctx != null) {
            ctx.eventBus().publish(e);
        }
    }
}
