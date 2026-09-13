package io.duo.sim.protocol.message;

import io.duo.sim.protocol.DuoMessage;

/** 上行：心跳（携带存活序号，供 master 侧失联检测）。 */
public record HeartbeatReport(String instanceName, long seq) implements DuoMessage {
}
