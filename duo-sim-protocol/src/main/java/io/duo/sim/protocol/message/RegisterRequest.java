package io.duo.sim.protocol.message;

import io.duo.sim.protocol.DuoMessage;

/** 上行：worker 实例注册（连接建立后首帧）。 */
public record RegisterRequest(String instanceName, int cpu, int memGB) implements DuoMessage {
}
