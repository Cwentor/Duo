package io.duo.sim.protocol.message;

import io.duo.sim.protocol.DuoMessage;

/** 上行：任务状态回报。state ∈ {RUNNING, SUCCESS, FAILED, CANCELLED}。 */
public record TaskStatus(String taskId, String instanceName, String state, String detail)
        implements DuoMessage {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
}
