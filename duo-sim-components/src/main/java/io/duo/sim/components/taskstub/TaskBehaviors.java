package io.duo.sim.components.taskstub;

import io.duo.sim.components.provider.BehaviorResolver;

/**
 * 行为执行块的装配工厂（架构体检卡 8）：{@code BehaviorResolver.BehaviorEntry} →
 * {@link BehaviorProfile} 的 8 参逐字段拷贝此前在 VirtualWorker / VirtualEngine /
 * DemoRealWorker 三处重复（体检实证 ×3）。执行时长（含 slow 倍数）是调用方唯一会
 * 覆写的字段，其余 7 个字段原样透传——工厂化后「加一个剧本字段」只改一处。
 */
public final class TaskBehaviors {

    private TaskBehaviors() {
    }

    /** 从剧本条目构造执行档案；{@code durationMillis} 为调用方算好的生效时长（slow 已乘）。 */
    public static BehaviorProfile profile(BehaviorResolver.BehaviorEntry entry,
                                          long durationMillis) {
        return new BehaviorProfile(durationMillis,
                entry.jitterRatio(), entry.successRate(),
                entry.exceptionType(), entry.logLines(),
                entry.failAtPercent(), entry.neverReport(), entry.progressMode());
    }

    /**
     * 假日志占位符展开（评审 2026-10-30 R8）：{@code logLines} 的占位符语法是剧本契约，
     * 此前 {@code {task}}/{@code {taskId}} 的内联 replace 在 VirtualWorker / VirtualEngine /
     * DemoRealWorker / RealWorkerSut 四处逐一重复（R8 实证 ×4）——扩一个占位符只改这里。
     * 落流事件名与载荷（sim.worker-log / sim.engine-task-log / sut.worker-log）是各调用方
     * 的异构面，不在此收敛。
     */
    public static String expandLogLine(String line, String taskName, String taskId) {
        return line.replace("{task}", taskName).replace("{taskId}", taskId);
    }
}
