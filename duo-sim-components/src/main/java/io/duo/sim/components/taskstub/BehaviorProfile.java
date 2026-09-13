package io.duo.sim.components.taskstub;

import java.util.List;
import java.util.Random;

/**
 * TaskStub 行为模型（设计文档 §9）。M0 字段子集：duration/jitter/successRate/
 * exception/logLines（failAt/neverReport/progress 属 M1 全集）。
 *
 * <p>它是任务执行的**行为模型**，不是独立组件（v0.5 §5 定位）：被 VirtualWorker
 * （及未来虚拟 engine 组件）复用——换档后同一 bindings 仍生效的关键。
 */
public record BehaviorProfile(long durationMillis, double jitterRatio, double successRate,
                              String exceptionType, List<String> logLines) {

    public BehaviorProfile {
        if (durationMillis < 0) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        if (jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("jitter must be within [0,1]");
        }
        if (successRate < 0 || successRate > 1) {
            throw new IllegalArgumentException("successRate must be within [0,1]");
        }
        logLines = List.copyOf(logLines == null ? List.of() : logLines);
    }

    /** 必败 profile（M0 验收的失败路径构造：exception 每次尝试必抛）。 */
    public static BehaviorProfile alwaysFail(long durationMillis, String exceptionType) {
        return new BehaviorProfile(durationMillis, 0.0, 0.0, exceptionType, List.of());
    }

    /** 正常 profile：按 successRate 概率成败。 */
    public static BehaviorProfile normal(long durationMillis, double jitter, double successRate) {
        return new BehaviorProfile(durationMillis, jitter, successRate, null, List.of());
    }

    /** 执行结果（终态 + 假日志）。 */
    public record Outcome(boolean success, String errorMessage, List<String> logs, long duration) {
    }

    /** 按给定随机源执行任务（阻塞 duration）。rng 外置以便单测用确定性种子。 */
    public Outcome execute(String taskName, Random rng) throws InterruptedException {
        long actual = durationMillis;
        if (jitterRatio > 0) {
            double delta = durationMillis * jitterRatio * (2 * rng.nextDouble() - 1);
            actual = Math.max(0, Math.round(durationMillis + delta));
        }
        Thread.sleep(actual);

        if (exceptionType != null && !exceptionType.isBlank()) {
            return new Outcome(false,
                    exceptionType + ": simulated failure for " + taskName, logLines, actual);
        }
        boolean success = rng.nextDouble() < successRate;
        return success
                ? new Outcome(true, null, logLines, actual)
                : new Outcome(false, "simulated failure for " + taskName, logLines, actual);
    }
}
