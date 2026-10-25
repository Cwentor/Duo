/**
 * 内核工具：{@link Durations}——带单位的时长解析（YAML 的 duration/at 统一口径，
 * 无单位值拒绝解析）。位于 kernel 是因为 components（行为剧本）与 scenario（时间线/断言）
 * 必须共用同一份解析，谁也不复制谁。
 */
package io.duo.sim.kernel.util;
