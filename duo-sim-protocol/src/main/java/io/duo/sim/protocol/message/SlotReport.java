package io.duo.sim.protocol.message;

import io.duo.sim.protocol.DuoMessage;

/** 上行：槽位上报（freeSlots/totalSlots，供调度器负载均衡）。 */
public record SlotReport(String instanceName, int freeSlots, int totalSlots) implements DuoMessage {
}
