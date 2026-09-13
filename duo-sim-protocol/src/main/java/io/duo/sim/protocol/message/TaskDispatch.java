package io.duo.sim.protocol.message;

import io.duo.sim.protocol.DuoMessage;

/** 下行：任务派发（taskName 供行为剧本匹配，attempt 从 1 开始）。 */
public record TaskDispatch(String taskId, String taskName, int attempt,
                           int cpu, int memGB) implements DuoMessage {
}
