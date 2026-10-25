/**
 * 内核运行时（设计文档 §7.5）：{@link ContractRegistry}（ServiceLoader 加载 + 注册期一致性校验 +
 * 缺省唯一性）、{@link ComponentManager}（拓扑排序启动、失败逆序拆除、统一清理）、
 * {@link ScenarioRuntime}（故障注入通路：target 解析 → 能力校验 → 事件 → 分发）、
 * {@link WiringResolver}（§6.1 判定表唯一权威）与 {@link SimpleEventBus}。
 *
 * <p>场景编排（加载→校验→时间线）不在这里——scenario 模块消费本包完成；控制面也只经
 * 既有公开 API 操作（§10 零内核改动约束的受益者）。
 */
package io.duo.sim.kernel.core;
