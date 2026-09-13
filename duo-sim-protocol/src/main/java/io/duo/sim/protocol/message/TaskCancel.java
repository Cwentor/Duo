package io.duo.sim.protocol.message;

import io.duo.sim.protocol.DuoMessage;

/** 下行：任务取消（M0 仅要求 worker 回报 CANCELLED；scheduler 侧主动取消语义留 M1）。 */
public record TaskCancel(String taskId, String reason) implements DuoMessage {
}
