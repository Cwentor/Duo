/**
 * virtual 档组件的共享深实现（架构体检卡 8）：{@link FaultState}——freeze / slow /
 * resource-exhaust 三动作的统一组件故障态（幂等、事件发布、slow 倍数解析），worker 与 engine
 * 共用；此前两类各持一份逐字相同的实现，加一个新故障动作要改 2 类 4 处，收敛后只改一处。
 */
package io.duo.sim.components.support;
