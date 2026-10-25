/**
 * message 契约的 virtual 档（M5 交付物 1）：{@link VirtualMessageBroker}——内存消息队列桩
 * （主题 + 发布/订阅 + 按序拉取），供 SUT 演练「消息驱动」路径而无需拉起 Kafka。
 * 端点形态 NONE + 同进程门面消费（§7.5 强制一致性：NONE ⇒ interface-direct）；
 * 真实 Kafka 档在有真实用例前不引入（YAGNI）。
 */
package io.duo.sim.components.message;
