package io.duo.sim.kernel.api;

import java.util.Map;
import java.util.Set;

/**
 * 故障动作（§7.2）。target 携带可选实例下标（1 起，缺省＝整组）；
 * {@code duration} 非空时由场景引擎计时到期自动 clear。
 * 校验期 target 解析依赖启动前可知的组件 id＝拓扑节点 id（§8 规则 6）。
 */
public record FaultAction(String type, ComponentAddress target,
                          Map<String, Object> params, Long durationMillis) {

    /** M0 支持的动作类型（生命周期类）。 */
    public static final String CRASH = "crash";
    public static final String RESTART = "restart";
    /** FaultInjectable 类动作（M0 组件不声明，通路用 fixture 验证）。 */
    public static final String FREEZE = "freeze";
    public static final String SLOW = "slow";
    public static final String REGISTRY_FLAP = "registry-flap";
    public static final String RESOURCE_EXHAUST = "resource-exhaust";

    public record ComponentAddress(ComponentId componentId, Integer instanceIndex) {

        public static ComponentAddress of(ComponentId id) {
            return new ComponentAddress(id, null);
        }

        public static ComponentAddress ofInstance(ComponentId id, int index) {
            return new ComponentAddress(id, index);
        }
    }
}
