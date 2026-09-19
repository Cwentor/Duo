package io.duo.sim.components.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.duo.sim.components.scheduler.SchedulerStateMachine.Listener;

/**
 * G9 的核心语义在状态机入口的**确定性**验证（不依赖线程/时序）：
 * 「准入失败（worker 未受理、从未执行）不得消耗 {@link SchedulerStateMachine#MAX_ATTEMPTS}
 * 重试额度，且 DAG 必须必然收敛」。
 *
 * <p>为什么单独建文件：这条语义此前只在「同进程回调」与「真实帧」两条**并发**用例里被间接覆盖，
 * 一旦涉及的线程时序抖动就会出现与语义无关的假红（本轮在 {@code VirtualSchedulerTest} 上实测
 * 6 次）。状态机是这套语义的**唯一裁决者**，故把不变量钉在它自己的确定性用例上：
 * <ul>
 *   <li>拒绝 N 次 → 仍为 PENDING、重试额度净消耗为 0、可重派；</li>
 *   <li>拒绝超过 {@code MAX_REJECTIONS} → 显式 FAILED（**不允许挂起**，§12 不静默）；</li>
 *   <li>迟到/重复的拒绝回报幂等忽略（仅对 RUNNING 生效）；</li>
 *   <li>拒绝**不产生** {@code onRetry} 回调（拒绝回滚 ≠ 重试，否则事实面会污染
 *       {@code sut.task-retry}）。</li>
 * </ul>
 */
class SchedulerStateMachineRejectionTest {

    /** 记录所有回调，便于断言「发生了 / 没有发生」。 */
    private static final class Recorder implements Listener {
        final List<String> dispatches = new ArrayList<>();
        final List<String> rejections = new ArrayList<>();
        final List<String> retries = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        final List<String> failovers = new ArrayList<>();
        int allTerminal;

        @Override
        public void onDispatch(String taskId, String taskName, int attempt, String instanceName) {
            dispatches.add(taskId + "#" + attempt + "@" + instanceName);
        }

        @Override
        public void onStatus(String taskId, String state, String detail, int attempt,
                             String instanceName) {
            statuses.add(taskId + ":" + state);
        }

        @Override
        public void onRetry(String taskId, int nextAttempt) {
            retries.add(taskId + "#" + nextAttempt);
        }

        @Override
        public void onFailover(String taskId, String fromInstance) {
            failovers.add(taskId + "@" + fromInstance);
        }

        @Override
        public void onRejected(String taskId, String instanceName, String reason, int seen) {
            rejections.add(taskId + "#" + seen + "@" + instanceName + ":" + reason);
        }

        @Override
        public void onAllTerminal() {
            allTerminal++;
        }
    }

    private static SchedulerStateMachine machine(Recorder rec) {
        // 依赖方向：job-b **依赖** job-a（值＝job-b 的前置依赖），即 job-a → job-b。
        // 注意方向：put("job-b", List.of("job-a")) 表示 job-b 依赖 job-a，不可写反
        // （写反会让「拒绝后 job-a 回到可派发集合」的断言永远不成立）
        Map<String, List<String>> dag = new java.util.LinkedHashMap<>();
        dag.put("job-b", List.of("job-a"));
        dag.put("job-a", List.of());
        SchedulerStateMachine sm = new SchedulerStateMachine(dag, rec);
        return sm;
    }

    @Test
    void rejectionRollsBackAttemptAndKeepsTaskDispatchable() {
        Recorder rec = new Recorder();
        SchedulerStateMachine sm = machine(rec);
        assertEquals(1, sm.dispatch("job-a", "workers-1"), "首次派发 attempt 从 1 起");
        assertEquals(1, sm.attemptOf("job-a"));

        // 连续拒绝 3 次：每次都回滚到 PENDING、回到可派发集合，且**重试额度净消耗为 0**
        // （dispatch 后 attempts+1，拒绝后回滚 -1，故拒绝在途期间 attemptOf 为 0 —— 这也是
        //  「额度未被消耗」的准确口径：一次瞬时满载不会把任务推向 MAX_ATTEMPTS）
        for (int i = 1; i <= 3; i++) {
            assertTrue(sm.onStatus("job-a", "REJECTED", "no free slot", "workers-1"),
                    "第 " + i + " 次拒绝应触发重排（返回 true）");
            assertEquals(0, sm.attemptOf("job-a"),
                    "第 " + i + " 次拒绝后净消耗必须为 0（派发的 +1 已被回滚）");
            assertTrue(sm.dispatchable().contains("job-a"),
                    "第 " + i + " 次拒绝后任务必须回到可派发集合（不得挂起）；实际 phase="
                            + sm.phaseOf("job-a") + " dispatchable=" + sm.dispatchable());
            assertEquals(1, sm.dispatch("job-a", "workers-" + (i + 1)),
                    "重派后 attempt 必须仍是 1（额度未被消耗）");
        }

        assertEquals(List.of("job-a#1@workers-1", "job-a#1@workers-2", "job-a#1@workers-3",
                        "job-a#1@workers-4"), rec.dispatches,
                "每次重派的 attempt 都必须是 1（额度未被消耗），且带上目标实例");
        assertEquals(3, rec.rejections.size(), "每次拒绝都必须在事实面留痕（§12 不静默）");
        assertTrue(rec.rejections.get(0).startsWith("job-a#1@workers-1:no free slot"),
                "拒绝事实必须携带 序号/实例/原因，实际 " + rec.rejections.get(0));
        assertTrue(rec.retries.isEmpty(), "拒绝回滚不是重试：不得回调 onRetry");
        // 循环最后一次留下了「已重派但未回报」的在途状态：job-a=RUNNING、job-b 未放行
        assertEquals("RUNNING", sm.phaseOf("job-a"), "最后一次已重派，任务应在途");
        assertTrue(sm.dispatchable().isEmpty(),
                "无 PENDING 任务：job-b 的上游（job-a）尚未 SUCCESS，不得放行，实际 "
                        + sm.dispatchable());
    }

    @Test
    void rejectionBeyondLimitFailsExplicitlyAndSkipsDownstream() {
        Recorder rec = new Recorder();
        SchedulerStateMachine sm = machine(rec);

        // 每次「派发 → 拒绝」净消耗为 0，故必须一直拒绝到 MAX_REJECTIONS + 1 才判失败
        for (int i = 1; i <= SchedulerStateMachine.MAX_REJECTIONS + 1; i++) {
            sm.dispatch("job-a", "workers-1");
            boolean requeued = sm.onStatus("job-a", "REJECTED", "no free slot", "workers-1");
            if (i <= SchedulerStateMachine.MAX_REJECTIONS) {
                assertTrue(requeued, "第 " + i + " 次拒绝仍在额度内，应重排");
                assertTrue(sm.dispatchable().contains("job-a"), "仍应可重派（不得提前判死）");
            } else {
                assertFalse(requeued, "超出 MAX_REJECTIONS 后不再重排");
            }
        }
        assertTrue(rec.statuses.contains("job-a:FAILED"),
                "超限必须显式判失败（不允许挂起），实际 " + rec.statuses);
        assertTrue(rec.statuses.contains("job-b:SKIPPED"),
                "失败任务的下游必须被显式跳过，实际 " + rec.statuses);
        assertEquals(1, rec.allTerminal, "DAG 必须到达终态（onAllTerminal 恰好一次）");
        assertTrue(sm.dispatchable().isEmpty(), "终态后无可派发任务");
        assertEquals(SchedulerStateMachine.MAX_REJECTIONS, rec.rejections.size(),
                "留痕次数＝额度内被接受的拒绝次数（第 MAX+1 次走 FAILED 路径）");
    }

    @Test
    void lateOrDuplicateRejectionIsIgnored() {
        Recorder rec = new Recorder();
        SchedulerStateMachine sm = machine(rec);

        assertFalse(sm.onStatus("job-a", "REJECTED", "no free slot", "workers-1"),
                "从未派发的任务的拒绝回报必须被忽略（不得凭空重排）");
        assertTrue(rec.rejections.isEmpty(), "被忽略的回报不得产生拒绝事实");

        sm.dispatch("job-a", "workers-1");
        assertTrue(sm.onStatus("job-a", "REJECTED", "no free slot", "workers-1"));
        assertFalse(sm.onStatus("job-a", "REJECTED", "no free slot", "workers-1"),
                "回滚后任务已是 PENDING，重复/迟到的拒绝必须被幂等忽略");
        assertEquals(1, rec.rejections.size(), "重复拒绝不得重复留痕");

        sm.dispatch("job-a", "workers-1");
        sm.onStatus("job-a", "SUCCESS", null, "workers-1");
        assertFalse(sm.onStatus("job-a", "REJECTED", "no free slot", "workers-1"),
                "已 SUCCESS 的任务不得被迟到拒绝拉回");
        assertEquals(1, rec.rejections.size());
        assertTrue(sm.dispatchable().contains("job-b"), "job-a 成功后后继被放行");
    }
}
