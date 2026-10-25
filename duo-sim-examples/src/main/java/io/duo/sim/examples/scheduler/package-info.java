/**
 * master 侧参考 SUT（计划 T13）：{@code DemoScheduler}——in-process SUT（实现 SutMain），
 * 向 registry 注册自身 Duo 端点、接受 worker 拨号、驱动两档共用的 SchedulerStateMachine
 * （DAG + 有界重试 + 失败转移），DAG 全部终态后 run() 返回（场景结束信号）。
 * worker 侧参考实现族在 {@code examples.worker}——两边正好覆盖「SUT 在 master / 在 worker」
 * 两种拓扑。
 */
package io.duo.sim.examples.scheduler;
