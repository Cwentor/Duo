/**
 * 角色契约接口（设计文档 §5）：registry / worker / scheduler / engine / store 在链路中的语义——
 * 必发事件、可观测点、配置项与档位语义差异（如 registry 两档 flap 后「快照重放 vs 会话真实丢失」）
 * 逐条写在各接口 Javadoc，是各档位实现与消费方（SUT）共同的语义权威；内核经
 * {@code kernel.api.Contract} 引用角色，不依赖任何具体实现。
 */
package io.duo.sim.kernel.contract;
