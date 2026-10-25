/**
 * worker 连接面共享件（架构体检卡 4）：{@link WorkerFeedServer}——Duo 线协议 master 侧的
 * accept 环 / 注册握手 / 读帧分发 / 失联收尾，以及槽位记账的全部生命周期（注册/移除/权威刷新/
 * 派发递减/拒绝回滚 G10）。virtual（VirtualScheduler）与 real（DemoScheduler）两档 scheduler
 * 共用本类，只提供业务回调——连接面与调度语义两档同源从口号变结构。
 */
package io.duo.sim.components.server;
