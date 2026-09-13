package io.duo.sim.kernel.contract;

import io.duo.sim.kernel.api.EndpointShape;

/**
 * engine 契约骨架（§5：计算引擎——提交→状态流转→终态+日志）。
 *
 * <p>M0 仅接口与事件语义（无实现）：TaskStub 行为模型的事件语义即本契约语义
 * （提交受理 {@code sim.engine-submitted}、状态流转、终态+日志）；M0 闭环场景
 * 不含独立 engine 节点。虚拟 engine 组件（复用 TaskStub）与 spark-submit（real）后续里程碑交付。
 */
public interface EngineContract {

    /** 提交执行（行为由实现决定）。 */
    String submit(String taskName, int cpu, int memGB);

    /** 声明的端点形态（供能力元数据一致性校验核对）。 */
    EndpointShape declaredShape();
}
