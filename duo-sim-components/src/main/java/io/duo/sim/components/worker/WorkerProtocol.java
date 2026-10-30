package io.duo.sim.components.worker;

import io.duo.sim.components.taskstub.TaskBehaviors;
import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentException;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.contract.RegistryContract;
import io.duo.sim.protocol.FrameConnection;
import io.duo.sim.protocol.message.SlotReport;
import io.duo.sim.protocol.message.TaskDispatch;
import io.duo.sim.protocol.message.TaskStatus;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * worker 两档（virtual/real）共享的**线缆原语**（评审 2026-10-30 R6）：发现重试环、槽位上报、
 * 拒绝回报、假日志落流四件在 {@link VirtualWorker} 与 examples 侧 {@code DemoRealWorker}
 * 逐字同形（逐段 diff 对照坐实），收敛单点防漂移。
 *
 * <p><b>边界（异构如实声明）</b>：本类只收「线上字节与重试纪律」；两档的连接生命周期
 * **不**共享——virtual 档的代次守卫/握手重试/拨号错峰/readiness（M4 压测硬化）与 real 档的
 * 最小示范形态是刻意分叉（failover 语义、FaultState 感知槽位口径均留在各自类内）。
 * 两处异常文案作为调用方参数显式传入，不静默取齐。
 */
public final class WorkerProtocol {

    /** 发现重试间隔（计划 §2/T8：500ms）。 */
    public static final long DISCOVER_INTERVAL_MS = 500;
    /** 发现重试上限：超限启动失败（20 次 ≈ 10s 等待窗）。 */
    public static final int DISCOVER_MAX_TRIES = 20;

    private WorkerProtocol() {
    }

    /**
     * 经 direct registry 发现 scheduler 端点：轮询 {@link #DISCOVER_INTERVAL_MS} ×
     * {@link #DISCOVER_MAX_TRIES}，超限启动失败（发现等待语义，计划 §2/T8）。
     *
     * @param directRegistryRequiredMessage 未绑定 direct registry 的启动失败文案（以 ": " 结尾，
     *                                      id 由本方法追加——两档文案不同，显式传入）
     * @param timeoutMessage                重试耗尽的启动失败文案（同上，拼接 id）
     */
    public static String discoverScheduler(ComponentContext ctx, ComponentId id,
                                           String directRegistryRequiredMessage,
                                           String timeoutMessage) {
        RegistryContract registry = ctx.directRegistry()
                .orElseThrow(() -> new ComponentException(directRegistryRequiredMessage + id));
        for (int tries = 0; tries < DISCOVER_MAX_TRIES; tries++) {
            List<String> addrs = registry.discoverEndpoints("scheduler");
            if (!addrs.isEmpty()) {
                return addrs.get(0);
            }
            try {
                Thread.sleep(DISCOVER_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ComponentException(timeoutMessage + id);
    }

    /**
     * 立即上报当前槽位（G9）：槽位变化后不等心跳发 {@link SlotReport}，缩小调度侧视图滞后窗口。
     * 槽位**取值口径**由调用方传入（virtual 档故障感知 effectiveSlots / real 档裸计数），
     * 写失败忽略：心跳周期上报兜底。
     */
    public static void reportSlots(FrameConnection conn, String instanceName,
                                   int freeSlots, int totalSlots) {
        try {
            conn.write(new SlotReport(instanceName, freeSlots, totalSlots));
        } catch (IOException ignored) {
            // 连接不可写：心跳周期上报兜底
        }
    }

    /**
     * 拒绝回报的线上字节（G9）：{@link TaskStatus#REJECTED}，写失败忽略——调度侧失联检测兜底。
     * 槽位视图刷新与 {@code sim.worker-task-rejected} 事实事件由调用方承接（两档口径不同）。
     */
    public static void writeRejected(FrameConnection conn, String instanceName,
                                     TaskDispatch d, String reason) {
        try {
            conn.write(new TaskStatus(d.taskId(), instanceName, TaskStatus.REJECTED, reason));
        } catch (IOException ignored) {
            // 连接已断：拒绝回报丢失，调度侧失联检测兜底
        }
    }

    /**
     * 假日志落流（G7）：两档同事件同载荷（{@code sim.worker-log} + taskId/line），
     * 占位符展开走 {@link TaskBehaviors#expandLogLine} 单点（R8）。事件发布途径由
     * {@code sink} 注入（fire / eventBus.publish 等价）。
     */
    public static void emitLogs(Consumer<Event> sink, String instanceSourceId,
                                TaskDispatch d, List<String> lines) {
        for (String line : lines) {
            sink.accept(Event.sim("sim.worker-log", instanceSourceId,
                    Map.of("taskId", d.taskId(),
                            "line", TaskBehaviors.expandLogLine(line, d.taskName(), d.taskId()))));
        }
    }
}
