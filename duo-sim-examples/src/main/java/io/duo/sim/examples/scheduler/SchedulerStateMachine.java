package io.duo.sim.examples.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * demo-scheduler 的调度状态机（计划 T13，钉死三件事）：DAG 依赖 + 有界重试 + 失败转移。
 * 纯内存、不依赖 store 契约（§14 M0 产出）。
 *
 * <p>任务终态集合：SUCCESS / FAILED / SKIPPED（依赖失败未执行）。
 * 失败任务重试至多 {@code maxAttempts}（M0 固定 3），超限记 FAILED；其下游记 SKIPPED。
 * 所有事实经 {@link Listener} 回调（DemoScheduler 转成 sut.* 事件）。
 */
public final class SchedulerStateMachine {

    /** M0 固定重试上限（计划 §2）。 */
    public static final int MAX_ATTEMPTS = 3;

    public interface Listener {
        void onDispatch(String taskId, String taskName, int attempt, String instanceName);

        void onStatus(String taskId, String state, String detail, int attempt);

        void onRetry(String taskId, int nextAttempt);

        void onAllTerminal();
    }

    /** DAG 定义：taskName → 依赖列表（无环，启动前由构造保证）。 */
    private final Map<String, List<String>> dag;
    private final List<String> topoOrder;
    private final Listener listener;

    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> instanceByTask = new ConcurrentHashMap<>();

    enum Phase { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

    static final class TaskState {
        final String name;
        volatile Phase phase = Phase.PENDING;
        volatile int attempts = 0;

        TaskState(String name) {
            this.name = name;
        }
    }

    public SchedulerStateMachine(Map<String, List<String>> dag, Listener listener) {
        this.dag = Map.copyOf(dag);
        this.listener = listener;
        this.topoOrder = topoSort(this.dag);
    }

    /** 可派发判定：全部依赖 SUCCESS。 */
    public List<String> dispatchable() {
        List<String> out = new ArrayList<>();
        for (String name : topoOrder) {
            var t = tasks.computeIfAbsent(name, TaskState::new);
            if (t.phase != Phase.PENDING) {
                continue;
            }
            boolean depsOk = dag.getOrDefault(name, List.of()).stream()
                    .allMatch(d -> tasks.computeIfAbsent(d, TaskState::new)
                            .phase == Phase.SUCCESS);
            if (depsOk) {
                out.add(name);
            }
        }
        return out;
    }

    /** 派发（attempt 从 1 起）。 */
    public int dispatch(String taskName, String instanceName) {
        var t = tasks.computeIfAbsent(taskName, TaskState::new);
        t.attempts++;
        t.phase = Phase.RUNNING;
        instanceByTask.put(taskName, instanceName);
        listener.onDispatch(taskName, taskName, t.attempts, instanceName);
        return t.attempts;
    }

    /** 派发失败重试次数（0=无重试直接派发）。 */
    public int attemptOf(String taskName) {
        return tasks.computeIfAbsent(taskName, TaskState::new).attempts;
    }

    /** 状态回报（worker → scheduler）。返回是否触发重试。 */
    public boolean onStatus(String taskId, String state, String detail) {
        var t = tasks.computeIfAbsent(taskId, TaskState::new);
        switch (state) {
            case "SUCCESS" -> {
                t.phase = Phase.SUCCESS;
                listener.onStatus(taskId, "SUCCESS", detail, t.attempts);
            }
            case "FAILED", "CANCELLED" -> {
                if (t.attempts < MAX_ATTEMPTS) {
                    t.phase = Phase.PENDING; // 回到待派发 → 有界重试
                    listener.onStatus(taskId, "RETRYING", detail, t.attempts);
                    listener.onRetry(taskId, t.attempts + 1);
                    return true;
                }
                t.phase = Phase.FAILED;
                listener.onStatus(taskId, "FAILED", detail, t.attempts);
                markSkippedDownstream(taskId);
            }
            default -> {
                return false; // RUNNING 等中间态
            }
        }
        if (allTerminal()) {
            listener.onAllTerminal();
        }
        return false;
    }

    private void markSkippedDownstream(String failedTask) {
        for (String name : topoOrder) {
            var t = tasks.computeIfAbsent(name, TaskState::new);
            if (t.phase != Phase.PENDING && t.phase != Phase.SKIPPED) {
                continue;
            }
            boolean depFailedOrSkipped = dag.getOrDefault(name, List.of()).stream()
                    .anyMatch(d -> {
                        var dt = tasks.computeIfAbsent(d, TaskState::new);
                        return dt.phase == Phase.FAILED || dt.phase == Phase.SKIPPED;
                    });
            if (depFailedOrSkipped && t.phase == Phase.PENDING) {
                t.phase = Phase.SKIPPED;
                listener.onStatus(name, "SKIPPED", "upstream " + failedTask + " failed", 0);
            }
        }
    }

    public boolean allTerminal() {
        // 空任务表不算终态（空流 allMatch 恒真会让 SUT 启动即退出）
        return !tasks.isEmpty() && tasks.values().stream().allMatch(t ->
                t.phase == Phase.SUCCESS || t.phase == Phase.FAILED || t.phase == Phase.SKIPPED);
    }

    public String phaseOf(String taskName) {
        return tasks.computeIfAbsent(taskName, TaskState::new).phase.name();
    }

    public int attemptsOf(String taskName) {
        return tasks.computeIfAbsent(taskName, TaskState::new).attempts;
    }

    /** DAG 任务名（拓扑序，供诊断事件）。 */
    public List<String> topoTasks() {
        return List.copyOf(topoOrder);
    }

    private static List<String> topoSort(Map<String, List<String>> dag) {
        List<String> out = new ArrayList<>();
        var done = new java.util.LinkedHashSet<String>();
        var visiting = new java.util.LinkedHashSet<String>();
        for (var name : dag.keySet()) {
            visit(name, dag, done, visiting, out);
        }
        return out;
    }

    private static void visit(String name, Map<String, List<String>> dag,
                              java.util.Set<String> done, java.util.Set<String> visiting,
                              List<String> out) {
        if (done.contains(name)) {
            return;
        }
        if (!visiting.add(name)) {
            throw new IllegalArgumentException("DAG cycle at " + name);
        }
        for (String d : dag.getOrDefault(name, List.of())) {
            visit(d, dag, done, visiting, out);
        }
        visiting.remove(name);
        done.add(name);
        out.add(name);
    }
}
