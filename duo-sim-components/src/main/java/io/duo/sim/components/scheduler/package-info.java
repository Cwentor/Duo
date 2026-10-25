/**
 * scheduler 契约的 virtual 档与两档共用的调度语义（M5）：{@link VirtualScheduler}（虚拟调度桩——
 * 不写用户代码即可扮演调度侧，让 SUT 落在 worker 侧）、{@link SchedulerStateMachine}
 * （DAG 依赖 + 有界重试 + 失败转移；real 档 DemoScheduler 共用同一份，两档语义不可能分叉）、
 * {@link DispatchSelector}（freeSlots 负载均衡视图，平局轮转）。连接面在 {@code server} 包的
 * {@code WorkerFeedServer}。
 */
package io.duo.sim.components.scheduler;
