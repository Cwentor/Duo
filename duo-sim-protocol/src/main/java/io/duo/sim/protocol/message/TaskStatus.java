package io.duo.sim.protocol.message;

import io.duo.sim.protocol.DuoMessage;

/**
 * 上行：任务状态回报。state ∈ {RUNNING, SUCCESS, FAILED, CANCELLED, REJECTED}。
 *
 * <p>{@link #REJECTED}（G9 修复）：worker **未受理**该派发（满载 / 连接已断），任务**从未执行**——
 * 调度侧据此把任务重新排队，而不得把它当作「在途」。此前 worker 对不可受理的派发静默丢弃，
 * 调度侧仍视任务为 RUNNING，任务永久丢失、DAG 永不终态（§12「不静默」被违反）。
 */
public record TaskStatus(String taskId, String instanceName, String state, String detail)
        implements DuoMessage {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    /** 未受理（从未执行）：满载或连接不可写——必须显式回报，不得静默丢弃。 */
    public static final String REJECTED = "REJECTED";
}
