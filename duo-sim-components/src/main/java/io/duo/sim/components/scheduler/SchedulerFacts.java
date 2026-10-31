package io.duo.sim.components.scheduler;

import java.util.HashMap;
import java.util.Map;

/**
 * 两档 scheduler 的 SUT 事实监听器（R4，评审 2026-10-30）：onDispatch / onStatus /
 * onRetry / onFailover / onRejected 五个回调的事件类型与载荷此前在
 * {@code VirtualScheduler} 与 {@code DemoScheduler} 各写一份（~46 行 ×2，仅发布通道不同
 * ——组件事件总线的 {@code Event.sut} vs SUT 事实发布器的 {@code publish}），物理双份使
 * "改一个事件载荷要同步两处"。事实类型/载荷自此单点，发布通道经 {@link Sink} 注入；
 * onAllTerminal 归消费方（各自持有 dag-terminal 的 CAS 守卫与生命周期决策，本类不代做
 * ——F2 修复后两档均为单点发布）。
 *
 * <p>既有契约（改这里＝同时改两个档位的事实面）：RETRYING 不发 task-terminal
 * （重试中的中间态不是终态）；task-status/task-terminal 载荷键序
 * taskId/state/attempt/instance（instance 仅在归属明确时携带，T19(c)）。
 */
public final class SchedulerFacts implements SchedulerStateMachine.Listener {

    /** 事实发布通道：事件类型与载荷由本类拼定，实现决定落点。 */
    public interface Sink {
        void publish(String type, Map<String, Object> payload);
    }

    private final Sink sink;
    private final Runnable onAllTerminal;

    public SchedulerFacts(Sink sink, Runnable onAllTerminal) {
        this.sink = sink;
        this.onAllTerminal = onAllTerminal;
    }

    @Override
    public void onDispatch(String taskId, String taskName, int attempt, String instance) {
        sink.publish("sut.task-dispatched",
                Map.of("taskId", taskId, "attempt", attempt, "instance", instance));
    }

    @Override
    public void onStatus(String taskId, String state, String detail, int attempt,
                         String instance) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", taskId);
        payload.put("state", state);
        payload.put("attempt", attempt);
        if (instance != null) {
            payload.put("instance", instance); // T19(c)：断言归属核对的数据契约
        }
        sink.publish("sut.task-status", Map.copyOf(payload));
        if (!"RETRYING".equals(state)) {
            sink.publish("sut.task-terminal", Map.copyOf(payload));
        }
    }

    @Override
    public void onRetry(String taskId, int nextAttempt) {
        sink.publish("sut.task-retry", Map.of("taskId", taskId, "nextAttempt", nextAttempt));
    }

    @Override
    public void onFailover(String taskId, String fromInstance) {
        sink.publish("sut.failover", Map.of("taskId", taskId, "from", fromInstance));
    }

    @Override
    public void onRejected(String taskId, String instanceName, String reason, int rejections) {
        // G9 事实：worker 未受理该派发（任务从未执行）→ 已回到待派发，等待重派。
        // 与 sut.task-retry（执行失败后重试）区分：这是**准入失败**，不消耗重试额度。
        sink.publish("sut.task-rejected", Map.of("taskId", taskId, "instance", instanceName,
                "reason", reason, "rejections", rejections));
    }

    @Override
    public void onAllTerminal() {
        onAllTerminal.run();
    }
}
