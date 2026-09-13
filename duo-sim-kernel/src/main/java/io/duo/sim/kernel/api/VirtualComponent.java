package io.duo.sim.kernel.api;

/**
 * 组件生命周期 SPI（设计文档 §7.1）。
 *
 * <p>约束：{@code stop(CRASH)} 后组件必须经 {@code restart()} 才能恢复上线——
 * restart 保留端点与身份、内部状态视为全新实例（§7.1 重启语义）。
 */
public interface VirtualComponent {

    /** 组件 id（内核 init 前分配；实现内保存）。 */
    ComponentId id();

    /** 注入上下文（配置、时钟、事件总线、wiring 绑定）。 */
    void init(ComponentContext ctx);

    void start() throws ComponentException;

    /**
     * 停止。GRACEFUL＝正常下线；CRASH＝宕机（不通知、不留清理），
     * 之后必须经 {@link #restart()} 恢复。
     */
    void stop(StopMode mode);

    /** 默认实现语义：端点与身份保留，内部状态清空，重新 init/start。 */
    void restart();

    HealthReport health();

    /** 运行时实际绑定地址（校验期不读本方法——§6：启动前校验读能力元数据）。 */
    java.util.List<ExposedEndpoint> endpoints();
}
