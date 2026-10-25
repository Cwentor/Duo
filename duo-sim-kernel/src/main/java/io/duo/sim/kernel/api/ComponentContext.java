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

    /** 全参构造：config/wiring/exposes 拷贝为不可变 Map——上下文交给组件后对双方都只读。 */
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

    /** 组件唯一 id（拓扑节点 id 解析后内核分配）。 */
    public ComponentId id() {
        return id;
    }

    /** 节点 config 键值（装配期已合并 capacity/count/behaviors 展平结果）。 */
    public Map<String, String> config() {
        return config;
    }

    /** 仿真时钟（M0 真实时钟；接口为加速时钟预留）。 */
    public SimClock clock() {
        return clock;
    }

    /** 进程内事件总线——组件发布事实的唯一通道。 */
    public EventBus eventBus() {
        return eventBus;
    }

    /** 节点 exposes 声明（port 0 已由管理器替换为实际绑定）。 */
    public Map<Contract, ExposedEndpoint> exposes() {
        return exposes;
    }

    /** 类型化访问：registry 契约的 direct 绑定（首个 direct 槽）。 */
    public Optional<RegistryContract> directRegistry() {
        return firstDirect("registry", RegistryContract.class);
    }

    /** 类型化访问：worker 契约的 direct 绑定（首个 direct 槽）。 */
    public Optional<WorkerContract> directWorker() {
        return firstDirect("worker", WorkerContract.class);
    }

    /** 类型化访问：scheduler 契约的 direct 绑定（首个 direct 槽）。 */
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

    /** 接线路径：WIRE＝真实协议端点（host:port），DIRECT＝同 JVM 注入契约接口。 */
    public enum ConnectionPath { WIRE, DIRECT }

    /**
     * wiring 槽解析结果。
     *
     * @param slot 槽名（消费方声明的依赖位，如 "registry"/"scheduler"）
     * @param targetNodeId 目标拓扑节点 id
     * @param contract 目标节点承担的契约
     * @param path 接线路径（WIRE/DIRECT）
     * @param directObject path=DIRECT 时的契约接口实例（WIRE 时为 null）
     * @param endpoint path=WIRE 时的目标端点 host:port（DIRECT 时为 null）
     */
    public record WiringBinding(String slot, String targetNodeId, Contract contract,
                                ConnectionPath path,
                                Object directObject, String endpoint) {
    }
}
