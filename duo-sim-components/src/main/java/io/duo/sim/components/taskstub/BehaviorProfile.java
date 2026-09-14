package io.duo.sim.components.taskstub;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * TaskStub 行为模型（设计文档 §9）。M1 字段全集：duration/jitter/successRate/
 * exception/logLines + failAt/neverReport/progress。
 *
 * <p>它是任务执行的**行为模型**，不是独立组件（v0.5 §5 定位）：被 VirtualWorker
 * （及未来虚拟 engine 组件）复用——换档后同一 bindings 仍生效的关键。
 *
 * <p>M1 字段语义：
 * <ul>
 *   <li>{@code failAtPercent}（0–100）：进度到达该百分比时**确定性失败**（不依赖
 *       successRate 随机源），执行在失败点截断而非跑完全程；</li>
 *   <li>{@code neverReport}：领取后永不回报终态（模拟"回报通道丢失"——worker 侧
 *       在结果产出后丢弃状态回报；槽位照常释放，供 master 失联/超时回收机制演练）；</li>
 *   <li>{@code progressMode}：{@code periodic} 时执行期间每完成 25% 进度回调一次
 *       progressListener（worker 转成 {@code sim.worker-task-progress} 事件）。</li>
 * </ul>
 */
public record BehaviorProfile(long durationMillis, double jitterRatio, double successRate,
                              String exceptionType, List<String> logLines,
                              Integer failAtPercent, boolean neverReport,
                              String progressMode) {

    /** progressMode 白名单。 */
    public static final String PROGRESS_OFF = "off";
    public static final String PROGRESS_PERIODIC = "periodic";
    private static final Set<String> PROGRESS_MODES = Set.of(PROGRESS_OFF, PROGRESS_PERIODIC);

    /** 规范构造器（8 参）。 */
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
        if (failAtPercent != null && (failAtPercent < 0 || failAtPercent > 100)) {
            throw new IllegalArgumentException("failAt must be within [0,100]");
        }
        if (progressMode != null && !PROGRESS_MODES.contains(progressMode)) {
            throw new IllegalArgumentException(
                    "progress must be one of " + PROGRESS_MODES + ", got: " + progressMode);
        }
        logLines = List.copyOf(logLines == null ? List.of() : logLines);
    }

    /** M0 兼容构造器（无 M1 字段）。 */
    public BehaviorProfile(long durationMillis, double jitterRatio, double successRate,
                           String exceptionType, List<String> logLines) {
        this(durationMillis, jitterRatio, successRate, exceptionType, logLines,
                null, false, null);
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
        return execute(taskName, rng, null);
    }

    /**
     * 带 progress 回调的执行。listener 在 progressMode=periodic 时按 25% 步进回调
     * （25/50/75/100，百分比到达点回调而非精确时刻）。
     */
    public Outcome execute(String taskName, Random rng,
                           IntConsumer progressListener) throws InterruptedException {
        long actual = durationMillis;
        if (jitterRatio > 0) {
            double delta = durationMillis * jitterRatio * (2 * rng.nextDouble() - 1);
            actual = Math.max(0, Math.round(durationMillis + delta));
        }
        boolean periodic = progressListener != null
                && PROGRESS_PERIODIC.equals(progressMode);

        if (failAtPercent != null) {
            long failAtMs = Math.min(actual,
                    Math.round(actual * failAtPercent / 100.0));
            sleepWithProgress(failAtMs, actual, periodic, progressListener);
            return new Outcome(false, "failAt " + failAtPercent + "% reached for "
                    + taskName, logLines, failAtMs);
        }

        sleepWithProgress(actual, actual, periodic, progressListener);

        if (exceptionType != null && !exceptionType.isBlank()) {
            return new Outcome(false,
                    exceptionType + ": simulated failure for " + taskName, logLines, actual);
        }
        boolean success = rng.nextDouble() < successRate;
        return success
                ? new Outcome(true, null, logLines, actual)
                : new Outcome(false, "simulated failure for " + taskName, logLines, actual);
    }

    /** 分段睡眠，periodic 时按已睡进度每过 25% 回调一次（失败点截断后不再虚报）。 */
    private static void sleepWithProgress(long sleepMs, long totalMs, boolean periodic,
                                          IntConsumer listener)
            throws InterruptedException {
        if (!periodic || listener == null || totalMs <= 0) {
            Thread.sleep(sleepMs);
            return;
        }
        long slept = 0;
        int lastReported = 0;
        while (slept < sleepMs) {
            long chunk = Math.min(sleepMs - slept, Math.max(1, totalMs / 4));
            Thread.sleep(chunk);
            slept += chunk;
            int pct = (int) Math.min(100, slept * 100 / totalMs);
            if (pct > lastReported) {
                listener.accept(pct);
                lastReported = pct;
            }
        }
    }
}
