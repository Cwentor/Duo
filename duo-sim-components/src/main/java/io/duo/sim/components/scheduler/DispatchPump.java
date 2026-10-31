package io.duo.sim.components.scheduler;

import io.duo.sim.components.server.WorkerFeedServer;
import io.duo.sim.protocol.FrameConnection;
import io.duo.sim.protocol.message.TaskDispatch;

import java.io.IOException;

/**
 * 派发泵单轮（R3，评审 2026-10-30）：两档 scheduler 的 select→feed→dispatch→write 循环
 * 此前逐字复制（{@code VirtualScheduler.pumpLoop} 与 {@code DemoScheduler.pumpDispatches}
 * 的内层 for，diff 仅变量名与 break/return 措辞）——G10 式修复若只改一处即重演
 * 体检卡 4 的两档分叉，故沉为共享件。调度节拍仍归消费方：virtual 在独立泵线程里循环
 * 调用（freeze 时整轮跳过）、real 由 run() 每 50ms 调用一次。
 *
 * <p>单轮语义：遍历状态机 {@code dispatchable()}；选择器无可用 worker（槽位耗尽或未上报）
 * 即止本轮；目标连接缺失则从选择器摘除；派发成功先本地递减槽位（SlotReport 到达时权威
 * 刷新）再写 {@code TaskDispatch}；写失败 {@code drop} 该 worker。
 * {@code sut.task-dispatched} 由 {@link SchedulerFacts#onDispatch} 单一来源发布
 * （本类不发布事件，T19(c) 去重的既有口径）。
 */
public final class DispatchPump {

    private DispatchPump() {
    }

    /** 执行一轮派发（状态机与连接面均由调用方持有；本类无状态）。 */
    public static void dispatchRound(WorkerFeedServer feedServer, SchedulerStateMachine sm) {
        for (String task : sm.dispatchable()) {
            String target = feedServer.selector().select();
            if (target == null) {
                break; // 本轮无可用 worker（槽位耗尽或未上报槽位）
            }
            FrameConnection conn = feedServer.feed(target);
            if (conn == null) {
                feedServer.selector().onRemoved(target);
                continue;
            }
            try {
                int attempt = sm.dispatch(task, target);
                feedServer.selector().onDispatched(target); // 本地递减；SlotReport 到达时权威刷新
                conn.write(new TaskDispatch(task, task, attempt, 1, 1));
                // sut.task-dispatched 由 SchedulerFacts.onDispatch 单一来源发布（去重）
            } catch (IOException e) {
                feedServer.drop(target);
            }
        }
    }
}
