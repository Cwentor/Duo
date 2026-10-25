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
}
