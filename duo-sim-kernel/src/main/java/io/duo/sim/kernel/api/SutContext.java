package io.duo.sim.kernel.api;

import java.util.Map;
import java.util.Optional;

/**
 * in-process SUT 上下文（§7.3）：内核注入端点清单、直连对象、事实发布门面、
 * 配置变量（节点 config 字段）、协作式停止句柄。
 */
public interface SutContext {

    /** 端点清单（与 ExposedEndpoint/配置文件同口径）。 */
    Map<String, String> endpointByContract();

    /** interface-direct 绑定的契约对象（direct 槽才有；键＝槽名）。 */
    Map<String, Object> directBindings();

    /** 类型化访问：按契约取 direct 绑定（SUT 消费 registry 等契约的推荐方式）。 */
    default <T> Optional<T> direct(Contract contract, Class<T> type) {
        return directBindings().values().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst();
    }

    /** 内部事实发布门面（事件统一 sut. 前缀，§7.3/§7.4）。 */
    SutEventPublisher events();

    /** 节点 config: 自由键值，原样注入。 */
    Map<String, String> config();

    /**
     * 注册协作式停止处理器（§7.3）：内核停止 SUT＝触发 handler + 带超时等待 run() 返回。
     * 未注册时内核停止＝interrupt run() 线程并记停止失败。
     */
    void onStop(Runnable handler);

    /** 就绪回调：须在 ready 超时（默认 60s）内到达，否则按启动失败处理。 */
    void ready();
}
