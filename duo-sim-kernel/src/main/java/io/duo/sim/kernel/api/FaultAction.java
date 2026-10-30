package io.duo.sim.kernel.api;

import java.util.Map;
import java.util.Set;

/**
 * 故障动作（§7.2）。target 携带可选实例下标（1 起，缺省＝整组）；
 * {@code duration} 非空时由场景引擎计时到期自动 clear。
 * 校验期 target 解析依赖启动前可知的组件 id＝拓扑节点 id（§8 规则 6）。
 *
 * @param type 动作类型（生命周期类 crash/restart，或 FaultInjectable 类——见下方常量）
 * @param target 目标组件地址（实例下标可选）
 * @param params 动作参数（如 slow 的 factor、custom-hook 的 hook 名）
 * @param durationMillis 非空时由场景引擎计时到期自动 clear
 */
public record FaultAction(String type, ComponentAddress target,
                          Map<String, Object> params, Long durationMillis) {

    /** M0 支持的动作类型（生命周期类）。 */
    public static final String CRASH = "crash";
    public static final String RESTART = "restart";
    /** FaultInjectable 类动作：M1 T18 交付 registry-flap（registry）与 task-kill（worker 实例级）。 */
    public static final String FREEZE = "freeze";
    public static final String SLOW = "slow";
    public static final String REGISTRY_FLAP = "registry-flap";
    public static final String RESOURCE_EXHAUST = "resource-exhaust";
    public static final String TASK_KILL = "task-kill";

    /** 目标组件地址：组件 id + 可选实例下标（1 起；缺省＝整组）。 */
    /**
     * lifecycle 动作判定单点（评审 R15，2026-10-30）：crash/restart 不受 supportedFaults
     * 约束（§7.2 分发规则）的语义此前在三处各写一份（ScenarioRuntime 用常量、
     * ScenarioValidator 用字面量、TimelineScheduler 用常量）——新增生命周期动作时
     * 字面量版最易漏改，自此 FaultAction.isLifecycle(type) 一处定义。
     */
    public static boolean isLifecycle(String type) {
        return CRASH.equals(type) || RESTART.equals(type);
    }

    public record ComponentAddress(ComponentId componentId, Integer instanceIndex) {

        /** 整组寻址（不指定实例）。 */
        public static ComponentAddress of(ComponentId id) {
            return new ComponentAddress(id, null);
        }

        /** 实例级寻址（index 从 1 起）。 */
        public static ComponentAddress ofInstance(ComponentId id, int index) {
            return new ComponentAddress(id, index);
        }

        /**
         * 解析注入目标：{@code workers}（整组）或 {@code workers[2]}（实例，索引 1 起）。
         * 评审 R14（2026-10-30）：同一语法此前有三处解析（ScenarioValidator /
         * TimelineScheduler / DuoCli）且宽容度递减——CLI 版连下标合法性都不查，
         * {@code workers[2x]} 以裸 NumberFormatException 冒出。单点后三处共享同一套
         * 判定与错误消息（§8 快速失败：非法目标在解析期显式失败，不留给运行期）。
         *
         * @throws IllegalArgumentException 空白、缺 '[' 或 ']'、下标非数字、下标 &lt; 1
         */
        public static ComponentAddress parse(String spec) {
            if (spec == null || spec.isBlank()) {
                throw new IllegalArgumentException("target must not be blank");
            }
            int bracket = spec.indexOf('[');
            if (bracket < 0) {
                return of(new ComponentId(spec.trim()));
            }
            if (!spec.endsWith("]")) {
                throw new IllegalArgumentException("target '" + spec + "' must end with ']'");
            }
            String indexPart = spec.substring(bracket + 1, spec.length() - 1).trim();
            final int index;
            try {
                index = Integer.parseInt(indexPart);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("target '" + spec
                        + "': instance index '" + indexPart + "' is not a number");
            }
            if (index < 1) {
                throw new IllegalArgumentException("target '" + spec
                        + "': instance index must be >= 1 (1-based, §7.2)");
            }
            return ofInstance(new ComponentId(spec.substring(0, bracket).trim()), index);
        }
    }
}
