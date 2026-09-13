package io.duo.sim.kernel.contract;

import io.duo.sim.kernel.api.EndpointShape;

/**
 * worker 契约（§5：执行节点——心跳、资源上报、任务收发）。
 *
 * <p>必发事件：心跳（{@code sim.worker-heartbeat}）、槽位变化（{@code sim.worker-slot}）、
 * 任务状态（{@code sim.worker-task-status}）。
 * 可观测点：每实例在线状态、freeSlots。
 * 配置项：{@code count × capacity（cpu/memGB）}。
 *
 * <p>交互型契约（§6）：virtual 档＝VirtualWorker（端点 DUO_PORT，暴露本接口供
 * 测试与实例操作）；real 档＝demo real worker（examples）。M0 连接模型：
 * worker 拨号 scheduler 的单条双向长连接（计划 §2）。
 */
public interface WorkerContract {

    /** 实例数（拓扑 count 解析结果）。 */
    int instanceCount();

    /** 实例名（1 起，如 workers-3）。 */
    String instanceName(int index);

    /** 实例是否在线（心跳线程存活）。 */
    boolean isInstanceAlive(int index);

    /** 实例当前空闲槽位。 */
    int instanceFreeSlots(int index);

    /** 声明的端点形态（供能力元数据一致性校验核对）。 */
    EndpointShape declaredShape();
}
