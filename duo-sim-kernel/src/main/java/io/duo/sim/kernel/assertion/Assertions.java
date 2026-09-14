package io.duo.sim.kernel.assertion;

import io.duo.sim.kernel.api.Event;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 断言实现集（M1 T20，语义按设计文档 §11 钉死）。
 *
 * <p><b>failoverWithin</b>：计时起点＝{@code sim.fault-injected} 且
 * {@code payload.action=crash}（restart/flap 同样发该事件，必须锚定）；成功＝crash 后
 * 该 taskId 发生 attempt 递增的 {@code sut.task-dispatched}，且随后到达 SUCCESS 终态。
 * 目标实例判定（§1 v4 唯一化）：不得为 **crash 时刻的** workers-3；
 * {@code sim.worker-instance-restarted} 事件**之后**的 workers-3 视为新实例（§7.1），
 * 其回报计入转移成功。
 *
 * <p><b>noTaskLost</b>：M1 口径 {@code requireAllSuccess=true}——全部派发任务
 * 到达 SUCCESS（FAILED/SKIPPED 判不通过）。
 *
 * <p><b>eventSequence</b>：给定事件类型子序列按序出现。
 *
 * <p><b>affectedTasksAtLeast</b>：crash 后归属该实例的在途任务数 ≥ N（防空真守护）。
 */
public final class Assertions {

    private Assertions() {
    }

    /** crash 事件（锚定 action=crash）。 */
    private static Event crashEvent(List<Event> events) {
        return events.stream()
                .filter(e -> e.type().equals("sim.fault-injected"))
                .filter(e -> "crash".equals(e.payload().get("action")))
                .findFirst().orElse(null);
    }

    /** 该实例最近一次 restart 事件时间（无则 null）。 */
    private static Instant lastRestartAt(List<Event> events, String instanceSourceId) {
        return events.stream()
                .filter(e -> e.type().equals("sim.worker-instance-restarted"))
                .filter(e -> instanceSourceId.equals(e.sourceId()))
                .map(Event::timestamp)
                .max(Instant::compareTo)
                .orElse(null);
    }

    /** crash 时刻失联的实例名（sourceId 约定 workers-N）。 */
    private static String crashedInstance(Event crash) {
        return crash.sourceId();
    }

    /**
     * failoverWithin：crash 后转移成功且发生在窗口内。
     *
     * @param windowSeconds 计时窗口（从 crash 事件起算）
     */
    public static Assertion failoverWithin(long windowSeconds) {
        return new Assertion() {
            @Override
            public String name() {
                return "failoverWithin";
            }

            @Override
            public Outcome evaluate(List<Event> events) {
                Event crash = crashEvent(events);
                if (crash == null) {
                    return new Outcome(name(), false, "no crash fault-injected event found");
                }
                String lost = crashedInstance(crash);
                Instant restartAt = lastRestartAt(events, lost);
                Instant deadline = crash.timestamp().plus(Duration.ofSeconds(windowSeconds));

                // 受影响任务集合：crash 后、该实例上已派发过的任务（用 crash 前的 dispatched 归属）
                Set<String> affected = tasksOwnedByBefore(events, lost, crash.timestamp());
                if (affected.isEmpty()) {
                    return new Outcome(name(), false,
                            "no in-flight task owned by " + lost + " at crash time");
                }

                for (String task : affected) {
                    Instant requeue = firstReDispatchAfter(events, task, crash.timestamp(), lost,
                            restartAt);
                    if (requeue == null) {
                        return new Outcome(name(), false,
                                "task " + task + " was not re-dispatched after crash");
                    }
                    if (requeue.isAfter(deadline)) {
                        return new Outcome(name(), false,
                                "task " + task + " re-dispatched after deadline: " + requeue);
                    }
                    Instant terminal = firstSuccessAfter(events, task, requeue);
                    if (terminal == null) {
                        return new Outcome(name(), false,
                                "task " + task + " did not reach SUCCESS after re-dispatch");
                    }
                    if (terminal.isAfter(deadline)) {
                        return new Outcome(name(), false,
                                "task " + task + " reached SUCCESS after deadline: " + terminal);
                    }
                }
                return new Outcome(name(), true,
                        affected.size() + " task(s) failed over within " + windowSeconds + "s");
            }
        };
    }

    /** crash 前该实例持有的任务（dispatched 事件的 instance 归属）。 */
    private static Set<String> tasksOwnedByBefore(List<Event> events, String instance,
                                                  Instant crashAt) {
        Map<String, Instant> lastDispatch = new HashMap<>();
        Map<String, String> owner = new HashMap<>();
        for (Event e : events) {
            if (!e.type().equals("sut.task-dispatched") || e.timestamp().isAfter(crashAt)) {
                continue;
            }
            Object task = e.payload().get("taskId");
            Object inst = e.payload().get("instance");
            if (task != null && inst != null) {
                lastDispatch.put(String.valueOf(task), e.timestamp());
                owner.put(String.valueOf(task), String.valueOf(inst));
            }
        }
        Set<String> out = new HashSet<>();
        for (var en : owner.entrySet()) {
            if (instance.equals(en.getValue())) {
                out.add(en.getKey());
            }
        }
        return out;
    }

    /**
     * crash 后该任务的首次重派发时刻。目标实例约束（§1 v4）：
     * 不得为 crash 时刻的失联实例，除非重派发发生在该实例 restart 事件之后（新实例）。
     */
    private static Instant firstReDispatchAfter(List<Event> events, String task,
                                                Instant crashAt, String lostInstance,
                                                Instant restartAt) {
        for (Event e : events) {
            if (!e.type().equals("sut.task-dispatched") || e.timestamp().isBefore(crashAt)) {
                continue;
            }
            if (!task.equals(String.valueOf(e.payload().get("taskId")))) {
                continue;
            }
            String target = String.valueOf(e.payload().get("instance"));
            boolean sameInstance = lostInstance.equals(target);
            boolean afterRestart = restartAt != null && e.timestamp().isAfter(restartAt);
            if (sameInstance && !afterRestart) {
                continue; // crash 时刻的实例（未重启）不算转移
            }
            return e.timestamp();
        }
        return null;
    }

    private static Instant firstSuccessAfter(List<Event> events, String task, Instant after) {
        for (Event e : events) {
            if (!e.type().equals("sut.task-terminal") || e.timestamp().isBefore(after)) {
                continue;
            }
            if (task.equals(String.valueOf(e.payload().get("taskId")))
                    && "SUCCESS".equals(String.valueOf(e.payload().get("state")))) {
                return e.timestamp();
            }
        }
        return null;
    }

    /** noTaskLost：M1 口径＝全部任务 SUCCESS。 */
    public static Assertion noTaskLost(boolean requireAllSuccess) {
        return new Assertion() {
            @Override
            public String name() {
                return "noTaskLost";
            }

            @Override
            public Outcome evaluate(List<Event> events) {
                Set<String> dispatched = new HashSet<>();
                Map<String, String> terminal = new HashMap<>();
                for (Event e : events) {
                    if (e.type().equals("sut.task-dispatched")) {
                        dispatched.add(String.valueOf(e.payload().get("taskId")));
                    } else if (e.type().equals("sut.task-terminal")) {
                        terminal.put(String.valueOf(e.payload().get("taskId")),
                                String.valueOf(e.payload().get("state")));
                    }
                }
                if (dispatched.isEmpty()) {
                    return new Outcome(name(), false, "no task was dispatched");
                }
                for (String t : dispatched) {
                    String state = terminal.get(t);
                    if (state == null) {
                        return new Outcome(name(), false, "task " + t + " has no terminal state");
                    }
                    if (requireAllSuccess && !"SUCCESS".equals(state)) {
                        return new Outcome(name(), false,
                                "task " + t + " terminal state is " + state
                                        + " (requireAllSuccess)");
                    }
                }
                return new Outcome(name(), true,
                        dispatched.size() + " task(s) terminal, all SUCCESS");
            }
        };
    }

    /** eventSequence：给定类型子序列按序出现。 */
    public static Assertion eventSequence(List<String> types) {
        return new Assertion() {
            @Override
            public String name() {
                return "eventSequence";
            }

            @Override
            public Outcome evaluate(List<Event> events) {
                int idx = 0;
                for (Event e : events) {
                    if (idx < types.size() && types.get(idx).equals(e.type())) {
                        idx++;
                    }
                }
                if (idx == types.size()) {
                    return new Outcome(name(), true, "sequence matched: " + types);
                }
                return new Outcome(name(), false, "sequence broken at index " + idx
                        + " (expected " + types.get(idx) + ") of " + types);
            }
        };
    }

    /**
     * masterReelectedWithin：注册中心闪断后"重新选主"（M2 T29）。
     *
     * <p>起点＝{@code sim.fault-injected} 且 {@code payload.action=registry-flap}；
     * 成功＝窗口内出现 {@code sut.leader-elected}（SUT 在重新注册成功后发布的事实，
     * §7.3）。首次注册的 leader-elected（闪断之前）不满足窗口起点约束。
     */
    public static Assertion masterReelectedWithin(long windowSeconds) {
        return new Assertion() {
            @Override
            public String name() {
                return "masterReelectedWithin";
            }

            @Override
            public Outcome evaluate(List<Event> events) {
                Event flap = events.stream()
                        .filter(e -> e.type().equals("sim.fault-injected"))
                        .filter(e -> "registry-flap".equals(e.payload().get("action")))
                        .findFirst().orElse(null);
                if (flap == null) {
                    return new Outcome(name(), false,
                            "no registry-flap fault-injected event found");
                }
                Instant deadline = flap.timestamp().plus(Duration.ofSeconds(windowSeconds));
                Event reelect = events.stream()
                        .filter(e -> e.type().equals("sut.leader-elected"))
                        .filter(e -> e.timestamp().isAfter(flap.timestamp()))
                        .findFirst().orElse(null);
                if (reelect == null) {
                    return new Outcome(name(), false,
                            "no sut.leader-elected after flap (master did not re-register)");
                }
                if (reelect.timestamp().isAfter(deadline)) {
                    return new Outcome(name(), false,
                            "leader re-elected after deadline: " + reelect.timestamp());
                }
                return new Outcome(name(), true,
                        "leader re-elected within " + windowSeconds + "s: "
                                + reelect.payload());
            }
        };
    }

    /** affectedTasksAtLeast：crash 时该实例持有的在途任务数 ≥ min（防空真守护）。 */
    public static Assertion affectedTasksAtLeast(int min) {
        return new Assertion() {
            @Override
            public String name() {
                return "affectedTasksAtLeast";
            }

            @Override
            public Outcome evaluate(List<Event> events) {
                Event crash = crashEvent(events);
                if (crash == null) {
                    return new Outcome(name(), false, "no crash event found");
                }
                Set<String> affected = tasksOwnedByBefore(events,
                        crashedInstance(crash), crash.timestamp());
                if (affected.size() >= min) {
                    return new Outcome(name(), true, affected.size() + " affected task(s)");
                }
                return new Outcome(name(), false, "only " + affected.size()
                        + " affected task(s), need >= " + min);
            }
        };
    }
}
