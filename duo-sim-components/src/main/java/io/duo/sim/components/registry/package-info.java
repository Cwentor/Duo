/**
 * registry 契约的 virtual 档（§5 / 计划 T6、T18）：{@link VirtualRegistry}——会话/临时节点/watch
 * 的进程内状态机，端点形态 NONE（interface-direct 消费）。支持 {@code registry-flap}：
 * 端点快照重放、对 SUT 透明——与 embedded 档「会话与临时节点真实丢失」的语义差异
 * 见 kernel 的 {@code RegistryContract} Javadoc（消费方必须知道档位差异）。
 */
package io.duo.sim.components.registry;
