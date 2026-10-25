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
    public record ComponentAddress(ComponentId componentId, Integer instanceIndex) {

        /** 整组寻址（不指定实例）。 */
        public static ComponentAddress of(ComponentId id) {
            return new ComponentAddress(id, null);
        }

        /** 实例级寻址（index 从 1 起）。 */
        public static ComponentAddress ofInstance(ComponentId id, int index) {
            return new ComponentAddress(id, index);
        }
    }
}
