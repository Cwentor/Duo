package io.duo.sim.kernel.contract;

import io.duo.sim.kernel.api.EndpointShape;

/**
 * scheduler 契约（§5：调度器——依赖编排、重试与失败转移决策）。
 *
 * <p>必发事件：任务派发（{@code sut.task-dispatched}）、状态流转（{@code sut.task-status}）、
 * 转移/重试（{@code sut.task-retry}）。M0 事件子集即此三类 + 终态。
 * 可观测点：在途任务数、各任务尝试次数。
 * 配置项：{@code config}（如 clusterName）、重试上限（M0 固定 3 次）。
 *
 * <p>M0 real 档＝demo-scheduler（SUT 示范，端点 DUO_PORT，经 interface-direct 注入
 * RegistryContract 做注册/发现）；virtual 档＝虚拟调度桩（后续里程碑）。
 * 服务端协议语义复用 worker 契约报文集（T2）。
 */
public interface SchedulerContract {

    /** 当前在途任务数（已派发未终态）。 */
    int inFlightTasks();

    /** 指定任务的已尝试次数（1 起；用于验收重试断言）。 */
    int attemptCount(String taskId);

    /** 声明的端点形态（供能力元数据一致性校验核对）。 */
    EndpointShape declaredShape();
}
