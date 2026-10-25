/**
 * 内核 SPI 与值类型（设计文档 §7）：组件生命周期（{@link VirtualComponent}）、角色与档位
 * （{@link Contract} × {@link Tier}）、事件面（{@link Event} / {@link EventBus} / {@link EventProjections}）、
 * 时钟（{@link SimClock}）、故障注入（{@link FaultAction} / {@link FaultInjectable} / {@link InstanceControl}）
 * 与 SUT 适配面（{@link SutMain} / {@link SutContext}）。
 *
 * <p>内核只认识这里的抽象，不认识任何具体产品——「可组合、可注入故障、可换档」都建立在这层
 * 契约之上；各契约的角色语义（必发事件/档位差异）权威在 {@code kernel.contract} 子包。
 */
package io.duo.sim.kernel.api;
