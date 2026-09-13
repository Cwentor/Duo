package io.duo.sim.protocol.message;

import io.duo.sim.protocol.DuoMessage;

/** 上行：派发受理确认（worker 已接收任务，随后异步回报 TaskStatus）。 */
public record TaskAck(String taskId, String instanceName) implements DuoMessage {
}
