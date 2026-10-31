package io.duo.sim.components.scheduler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 两档调度（virtual/real）共享的 DAG 解析（评审 2026-10-30 R5）：{@code dag.tasks} /
 * {@code dag.deps.<task>} 的解析骨架与缺省演示拓扑此前在 {@link VirtualScheduler} 与
 * examples 侧 {@code DemoScheduler} 各持一份（缺省串字面量 ×2）——「档位互换、配置零改动」
 * 的对照物两处维护必漂移，收敛单点。
 *
 * <p><b>语义收敛点（如实声明）</b>：空段（如尾逗号产生的 {@code ""}）一律跳过——取 virtual 档
 * 原口径；real 档旧版不跳过，属垃圾输入的容错差异，等价域（规范逗号串）内行为不变。
 *
 * <p><b>异构保留</b>：空图裁决（异常类型与文案）留调用方——virtual 档解析期
 * {@code IllegalArgumentException}，real 档启动期 {@code IllegalStateException}。
 */
public final class DagTopology {

    /** 缺省 DAG：两档同一套演示拓扑（档位互换时配置零改动，M0 验收的对照物）。 */
    public static final String DEFAULT_DAG =
            "load-orders,clean-orders,build-features,unstable-task,aggregate-report";

    private DagTopology() {
    }

    /** 解析 DAG：{@code dag.tasks}（逗号分隔，缺省 {@link #DEFAULT_DAG}）+ 每任务 {@code dag.deps.<task>}。 */
    public static Map<String, List<String>> parse(Map<String, String> config) {
        String tasksCfg = config.getOrDefault("dag.tasks", DEFAULT_DAG);
        Map<String, List<String>> dag = new HashMap<>();
        for (String t : tasksCfg.split(",")) {
            String name = t.trim();
            if (name.isEmpty()) {
                continue;
            }
            String deps = config.get("dag.deps." + name);
            dag.put(name, deps == null || deps.isBlank()
                    ? List.of() : List.of(deps.split(",")));
        }
        return dag;
    }
}
