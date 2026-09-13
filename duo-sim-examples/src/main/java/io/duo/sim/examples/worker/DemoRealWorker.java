package io.duo.sim.examples.worker;

import io.duo.sim.components.provider.BehaviorResolver;
import io.duo.sim.components.taskstub.BehaviorProfile;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.EndpointShape;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.ExposedEndpoint;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.HealthReport;
import io.duo.sim.kernel.api.InstanceControl;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;
import io.duo.sim.kernel.contract.RegistryContract;
import io.duo.sim.kernel.contract.WorkerContract;
import io.duo.sim.protocol.FrameConnection;
import io.duo.sim.protocol.message.HeartbeatReport;
import io.duo.sim.protocol.message.RegisterRequest;
import io.duo.sim.protocol.message.RegisterResponse;
import io.duo.sim.protocol.message.SlotReport;
import io.duo.sim.protocol.message.TaskAck;
import io.duo.sim.protocol.message.TaskCancel;
import io.duo.sim.protocol.message.TaskDispatch;
import io.duo.sim.protocol.message.TaskStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * demo real worker（计划 T14）：worker 契约 real 档参考实现。kernel-hosted VirtualComponent
 * （由组件管理器启动），经 registry 发现 master 后拨号、跑任务、回报。
 *
 * <p>它是独立于 VirtualWorker 的真实实现（不是同一类的换皮），但**复用 TaskStub 行为模型**
 * （BehaviorProfile/BehaviorResolver）消费同一 behaviors bindings——换档后行为剧本仍生效，
 * 这是 M0 零改动验收成立的关键（计划 T12/T15）。
 */
public final class DemoRealWorker implements VirtualComponent, WorkerContract, InstanceControl {

    private static final long DISCOVER_INTERVAL_MS = 500;
    private static final int DISCOVER_MAX_TRIES = 20;
    private static final long HEARTBEAT_INTERVAL_MS = 100;

    private volatile ComponentId id;
    private volatile ComponentContext ctx;
    private volatile int count = 1;
    private volatile int totalSlots = 4;
    private volatile BehaviorResolver behaviors;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<Integer, Inst> instances = new ConcurrentHashMap<>();

    private static final class Inst {
        final int index;
        final String name;
        final AtomicBoolean alive = new AtomicBoolean(false);
        final AtomicInteger freeSlots = new AtomicInteger();
        volatile Thread thread;
        volatile FrameConnection conn;

        Inst(int index, String name, int slots) {
            this.index = index;
            this.name = name;
            this.freeSlots.set(slots);
        }
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
        this.count = Integer.parseInt(ctx.config().getOrDefault("count", "1"));
        this.totalSlots = Integer.parseInt(ctx.config().getOrDefault("capacity.slots", "4"));
        this.behaviors = BehaviorResolver.fromConfig(ctx.config());
        instances.clear();
        for (int i = 1; i <= count; i++) {
            instances.put(i, new Inst(i, id.instanceSourceId(i), totalSlots));
        }
    }

    @Override
    public void start() throws ComponentException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        String masterAddr = discoverMaster();
        instances.values().forEach(inst ->
                inst.thread = Thread.ofVirtual().name(inst.name)
                        .start(() -> dialAndServe(inst, masterAddr)));
        ctx.eventBus().publish(Event.sim("sim.worker-started", id.value(),
                Map.of("instances", count, "kind", "real")));
    }

    private String discoverMaster() {
        var registry = ctx.directRegistry()
                .orElseThrow(() -> new ComponentException(
                        "real worker requires direct registry: " + id));
        for (int tries = 0; tries < DISCOVER_MAX_TRIES; tries++) {
            List<String> addrs = registry.discoverEndpoints("scheduler");
            if (!addrs.isEmpty()) {
                return addrs.get(0);
            }
            try {
                Thread.sleep(DISCOVER_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ComponentException("scheduler not discovered within retries: " + id);
    }

    private void dialAndServe(Inst inst, String masterAddr) {
        inst.alive.set(true);
        int colon = masterAddr.lastIndexOf(':');
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(masterAddr.substring(0, colon),
                    Integer.parseInt(masterAddr.substring(colon + 1))));
            inst.conn = new FrameConnection(s);
            inst.conn.write(new RegisterRequest(inst.name, 4, 8));
            var resp = inst.conn.read();
            if (!(resp instanceof RegisterResponse r) || !r.accepted()) {
                inst.alive.set(false);
                return;
            }
            inst.conn.write(new SlotReport(inst.name, inst.freeSlots.get(), totalSlots));
            var reader = Thread.ofVirtual().name(inst.name + "-read").start(() -> {
                try {
                    while (inst.alive.get() && running.get()) {
                        var msg = inst.conn.read();
                        if (msg instanceof TaskDispatch d) {
                            dispatch(inst, d);
                        } else if (msg instanceof TaskCancel c) {
                            // M0：real 替身不主动取消（scheduler 主动取消属 M1）
                        }
                    }
                } catch (IOException e) {
                    // 连接断开
                }
            });
            while (inst.alive.get() && running.get()) {
                inst.conn.write(new HeartbeatReport(inst.name, System.currentTimeMillis()));
                inst.conn.write(new SlotReport(inst.name, inst.freeSlots.get(), totalSlots));
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
            }
            reader.join(500);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            inst.alive.set(false);
        }
    }

    private void dispatch(Inst inst, TaskDispatch d) {
        if (inst.freeSlots.get() <= 0) {
            return;
        }
        inst.freeSlots.decrementAndGet();
        try {
            inst.conn.write(new TaskAck(d.taskId(), inst.name));
        } catch (IOException e) {
            inst.freeSlots.incrementAndGet();
            return;
        }
        Thread.ofVirtual().name(inst.name + "-task-" + d.taskId()).start(() -> {
            try {
                var entry = behaviors.resolve(d.taskName());
                var profile = new BehaviorProfile(entry.durationMillis(), entry.jitterRatio(),
                        entry.successRate(), entry.exceptionType(), entry.logLines());
                var r = profile.execute(d.taskName(), new Random());
                inst.conn.write(new TaskStatus(d.taskId(), inst.name,
                        r.success() ? TaskStatus.SUCCESS : TaskStatus.FAILED,
                        r.errorMessage()));
            } catch (Exception e) {
                // 回报失败由失联检测兜底
            } finally {
                inst.freeSlots.incrementAndGet();
            }
        });
    }

    @Override
    public void stop(StopMode mode) {
        running.set(false);
        instances.values().forEach(inst -> {
            inst.alive.set(false);
            closeQuietly(inst);
            if (inst.thread != null) {
                inst.thread.interrupt();
            }
        });
        ctx.eventBus().publish(Event.sim(
                mode == StopMode.CRASH ? "sim.worker-crashed" : "sim.worker-stopped",
                id.value(), Map.of("mode", mode.name())));
    }

    @Override
    public void restart() {
        instances.clear();
        try {
            init(ctx);
            start();
        } catch (ComponentException e) {
            throw e;
        }
    }

    @Override
    public HealthReport health() {
        return running.get() ? HealthReport.ok() : HealthReport.down("demo real worker down");
    }

    @Override
    public List<ExposedEndpoint> endpoints() {
        return List.of(); // M0：real 替身不监听（master 接收拨入）
    }

    // ---- WorkerContract ----

    @Override
    public int instanceCount() {
        return count;
    }

    @Override
    public String instanceName(int index) {
        return id.instanceSourceId(index);
    }

    @Override
    public boolean isInstanceAlive(int index) {
        var s = instances.get(index);
        return s != null && s.alive.get();
    }

    @Override
    public int instanceFreeSlots(int index) {
        var s = instances.get(index);
        return s == null ? 0 : s.freeSlots.get();
    }

    @Override
    public EndpointShape declaredShape() {
        return EndpointShape.DUO_PORT;
    }

    // ---- InstanceControl ----

    @Override
    public void stopInstance(int index, StopMode mode) {
        var s = instances.get(index);
        if (s == null) {
            throw new ComponentException("no such instance: " + id.instanceSourceId(index));
        }
        s.alive.set(false);
        closeQuietly(s);
        if (s.thread != null) {
            s.thread.interrupt();
        }
        ctx.eventBus().publish(Event.sim(
                mode == StopMode.CRASH ? "sim.worker-instance-crashed"
                        : "sim.worker-instance-stopped",
                id.instanceSourceId(index), Map.of("mode", mode.name())));
    }

    @Override
    public void restartInstance(int index) {
        var s = instances.get(index);
        if (s == null) {
            throw new ComponentException("no such instance: " + id.instanceSourceId(index));
        }
        String masterAddr = discoverMaster();
        s.freeSlots.set(totalSlots);
        s.thread = Thread.ofVirtual().name(s.name).start(() -> dialAndServe(s, masterAddr));
        ctx.eventBus().publish(Event.sim("sim.worker-instance-restarted",
                id.instanceSourceId(index), Map.of()));
    }

    @Override
    public void injectOnInstance(FaultAction action) {
        throw new UnsupportedOperationException("demo real worker declares no faults");
    }

    private void closeQuietly(Inst s) {
        if (s.conn != null) {
            try {
                s.conn.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
