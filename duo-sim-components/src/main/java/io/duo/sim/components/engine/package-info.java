/**
 * engine 契约的 virtual 档（M5 交付物 1）：{@link VirtualEngine}——计算引擎替身
 * （提交 → 受理 → 状态流转 → 终态 + 日志），与 worker 相反是**服务端**形态（对外暴露
 * DUO_PORT，对应 spark-submit 形态）；行为模型复用 {@code taskstub} 包（同一份 behaviors
 * 配置在 worker 与 engine 上表现一致），故障态复用 {@code support.FaultState}。
 */
package io.duo.sim.components.engine;
