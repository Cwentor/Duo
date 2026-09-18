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

    /**
     * 单任务「派发被拒」上限（G9 安全网）：拒绝属**准入失败**（任务从未执行），故回滚尝试计数、
     * 不占 {@link #MAX_ATTEMPTS} 额度；但若持续被拒（调度侧视图长期滞后/实例持续满载），
     * 超过此上限即判 FAILED **显式失败**——DAG 必须能终态，任何情况下都不允许永久挂起。
     */
    public static final int MAX_REJECTIONS = 12;

    public interface Listener {
        void onDispatch(String taskId, String taskName, int attempt, String instanceName);

        void onStatus(String taskId, String state, String detail, int attempt,
                      String instance);

        void onRetry(String taskId, int nextAttempt);

        /** 失败转移事实（T19）：任务从失联实例转移重派。 */
        void onFailover(String taskId, String fromInstance);

        /** 派发被 worker 显式拒绝（G9）：任务未受理、已回到待派发，等待重派。 */
        void onRejected(String taskId, String instanceName, String reason, int rejections);

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
        /** 被拒绝次数（G9：准入失败计数，独立于 attempts）。 */
        final java.util.concurrent.atomic.AtomicInteger rejections =
                new java.util.concurrent.atomic.AtomicInteger();

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

    /** 归属于指定实例的在途（RUNNING）任务名列表（T19 崩溃转移用）。 */
    public List<String> inFlightOn(String instanceName) {
        List<String> out = new ArrayList<>();
        for (var e : instanceByTask.entrySet()) {
            var t = tasks.get(e.getKey());
            if (t != null && t.phase == Phase.RUNNING
                    && instanceName.equals(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** 该任务当前归属的实例名（无则 null）。 */
    public String ownerOf(String taskName) {
        return instanceByTask.get(taskName);
    }

    /**
     * 实例失联：其在途任务重置为 PENDING 触发转移（T19）。
     * 仍受 {@link #MAX_ATTEMPTS} 约束——已在最大尝试次数的任务直接判 FAILED 并跳过下游
     * （§7.2 无降级：转移不是无限重试）。返回实际重置为 PENDING 的任务名列表。
     */
    public List<String> onInstanceLost(String instanceName, String reason) {
        List<String> requeued = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String task : inFlightOn(instanceName)) {
            var t = tasks.get(task);
            if (t.attempts < MAX_ATTEMPTS) {
                t.phase = Phase.PENDING;
                instanceByTask.remove(task);
                requeued.add(task);
                listener.onStatus(task, "RETRYING", "instance " + instanceName
                        + " lost: " + reason, t.attempts, instanceName);
                listener.onRetry(task, t.attempts + 1);
                listener.onFailover(task, instanceName);
            } else {
                t.phase = Phase.FAILED;
                instanceByTask.remove(task);
                failed.add(task);
                listener.onStatus(task, "FAILED", "instance " + instanceName
                        + " lost at max attempts", t.attempts, instanceName);
            }
        }
        for (String f : failed) {
            markSkippedDownstream(f);
        }
        if (allTerminal()) {
            listener.onAllTerminal();
        }
        return requeued;
    }

    /** 状态回报（worker → scheduler）。返回是否触发重试。 */
    public boolean onStatus(String taskId, String state, String detail) {
        return onStatus(taskId, state, detail, instanceByTask.get(taskId));
    }

    /** 状态回报（带归属实例，T19(c)）。 */
    public boolean onStatus(String taskId, String state, String detail, String instance) {
        var t = tasks.computeIfAbsent(taskId, TaskState::new);
        switch (state) {
            case "REJECTED" -> {
                // G9：worker 显式拒绝（未受理、从未执行）→ 回滚尝试、回待派发；超出上限则显式失败
                return onRejected(taskId, instance, detail);
            }
            case "SUCCESS" -> {
                t.phase = Phase.SUCCESS;
                listener.onStatus(taskId, "SUCCESS", detail, t.attempts, instance);
            }
            case "FAILED", "CANCELLED" -> {
                if (t.attempts < MAX_ATTEMPTS) {
                    t.phase = Phase.PENDING; // 回到待派发 → 有界重试
                    listener.onStatus(taskId, "RETRYING", detail, t.attempts, instance);
                    listener.onRetry(taskId, t.attempts + 1);
                    return true;
                }
                t.phase = Phase.FAILED;
                listener.onStatus(taskId, "FAILED", detail, t.attempts, instance);
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

    /**
     * 派发被拒（G9）：任务**从未执行**，故回滚本次尝试计数（不占 {@link #MAX_ATTEMPTS} 额度——
     * 否则一次瞬时满载就把任务判死），回到 PENDING 等待重派；拒绝次数超过
     * {@link #MAX_REJECTIONS} 时判 FAILED 并跳过下游，**保证 DAG 必然终态**（不允许挂起）。
     *
     * <p>幂等：仅对 RUNNING（本次派发确实在途）的任务生效，迟到/重复的拒绝回报被忽略。
     */
    public boolean onRejected(String taskId, String instanceName, String reason) {
        var t = tasks.computeIfAbsent(taskId, TaskState::new);
        if (t.phase != Phase.RUNNING) {
            return false; // 已终态或已被其他路径重排：忽略迟到回报
        }
        int seen = t.rejections.incrementAndGet();
        if (seen > MAX_REJECTIONS) {
            t.phase = Phase.FAILED;
            instanceByTask.remove(taskId);
            listener.onStatus(taskId, "FAILED",
                    "dispatch rejected " + seen + " times (last: " + reason + ")", t.attempts,
                    instanceName);
            markSkippedDownstream(taskId);
            if (allTerminal()) {
                listener.onAllTerminal();
            }
            return false;
        }
        if (t.attempts > 0) {
            t.attempts--; // 回滚：本次派发未执行
        }
        t.phase = Phase.PENDING;
        instanceByTask.remove(taskId);
        listener.onRejected(taskId, instanceName, reason, seen);
        return true;
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
                listener.onStatus(name, "SKIPPED", "upstream " + failedTask + " failed", 0, null);
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
