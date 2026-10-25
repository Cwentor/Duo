/**
 * 组件提供者 SPI（§7.5）：{@link ComponentProvider} 以 (contract, tier) 为键经 ServiceLoader 注册，
 * 是档位实现进入内核的唯一入口；注册期一致性校验（元数据声明与实现接口对应）由
 * {@code kernel.core.ContractRegistry} 执行。
 */
package io.duo.sim.kernel.spi;
