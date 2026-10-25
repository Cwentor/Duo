/**
 * worker 契约的 virtual 档（设计文档 §9 / 计划 T8）：{@link VirtualWorker}——每个逻辑实例一个
 * 虚拟线程：心跳 + 槽位上报 + 领取/执行任务（TaskStub 行为模型）+ 状态回报。拨号 master
 * （wire 槽 "scheduler"），未发现端点时重试有界；支持实例级 stop/restart（InstanceControl）与
 * task-kill/freeze/slow/resource-exhaust 故障动作。
 */
package io.duo.sim.components.worker;
