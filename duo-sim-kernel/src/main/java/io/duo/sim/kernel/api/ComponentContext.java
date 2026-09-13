package io.duo.sim.kernel.api;

import io.duo.sim.kernel.contract.RegistryContract;
import io.duo.sim.kernel.contract.WorkerContract;
import io.duo.sim.kernel.contract.SchedulerContract;

import java.util.Map;
import java.util.Optional;

/**
 * 组件上下文（§7.1 init 注入）：配置、SimClock、EventBus、wiring 解析结果。
 *
 * <p>wiring 注入（计划 §2 连接路径）：
 * direct 槽 → 注入目标契约的 Java 接口实例（{@link #directRegistry()} 等类型化访问器）；
 * wire 槽 → 注入目标节点对外端点地址（{@link #wireEndpoint(String)}）。
 */
public final class ComponentContext {

    private final ComponentId id;
    private final Map<String, String> config;
    private final SimClock clock;
    private final EventBus eventBus;
    /** slotName -> 解析结果。 */
    private final Map<String, WiringBinding> wiring;
    /** 节点 exposes 声明（port 0 由管理器替换为实际绑定）。 */
    private final Map<Contract, ExposedEndpoint> exposes;

    public ComponentContext(ComponentId id, Map<String, String> config,
                            SimClock clock, EventBus eventBus,
                            Map<String, WiringBinding> wiring,
                            Map<Contract, ExposedEndpoint> exposes) {
        this.id = id;
        this.config = Map.copyOf(config);
        this.clock = clock;
        this.eventBus = eventBus;
        this.wiring = Map.copyOf(wiring);
        this.exposes = Map.copyOf(exposes);
    }

    public ComponentId id() {
        return id;
    }

    public Map<String, String> config() {
        return config;
    }

    public SimClock clock() {
        return clock;
    }

    public EventBus eventBus() {
        return eventBus;
    }

    public Map<Contract, ExposedEndpoint> exposes() {
        return exposes;
    }

    /** 类型化访问：registry 契约的 direct 绑定（首个 direct 槽）。 */
    public Optional<RegistryContract> directRegistry() {
        return firstDirect("registry", RegistryContract.class);
    }

    public Optional<WorkerContract> directWorker() {
        return firstDirect("worker", WorkerContract.class);
    }

    public Optional<SchedulerContract> directScheduler() {
        return firstDirect("scheduler", SchedulerContract.class);
    }

    /** wire 槽：目标节点对外端点地址（host:port）。 */
    public String wireEndpoint(String slotName) {
        WiringBinding b = wiring.get(slotName);
        if (b == null || b.path() != ConnectionPath.WIRE) {
            throw new ComponentException("slot not wired via wire-protocol: " + slotName);
        }
        return b.endpoint();
    }

    private <T> Optional<T> firstDirect(String byContract, Class<T> type) {
        return wiring.values().stream()
                .filter(b -> b.path() == ConnectionPath.DIRECT)
                .map(WiringBinding::directObject)
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst();
    }

    public enum ConnectionPath { WIRE, DIRECT }

    /** wiring 槽解析结果。 */
    public record WiringBinding(String slot, String targetNodeId, Contract contract,
                                ConnectionPath path,
                                Object directObject, String endpoint) {
    }
}
