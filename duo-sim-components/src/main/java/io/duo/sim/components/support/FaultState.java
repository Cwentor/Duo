package io.duo.sim.components.support;

import io.duo.sim.kernel.api.FaultAction;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 组件级故障态（freeze / slow / resource-exhaust，§7.2 M5-3）——virtual 档组件共享的深实现
 * （架构体检卡 8）：此前 {@code VirtualWorker} 与 {@code VirtualEngine} 各持三个
 * AtomicBoolean + inject/clear switch + 事件发布 + {@code resolveSlowFactor}（10 行逐字
 * 相同 ×2），加一个新故障动作至少改 2 类 4 处。事件名前缀由构造注入
 * （{@code sim.worker-*} / {@code sim.engine-*}），事件序与载荷形态与收敛前逐字一致——
 * 既有组件测试（事件名即契约）零改动即裁判。
 *
 * <p>语义（与既有实现一致）：
 * <ul>
 *   <li>幂等：已在目标态只跳过事件（slow 在已慢速时**仍更新倍数**，不叠加）；</li>
 *   <li>{@code resolveSlowFactor}：动作 {@code params.factor} 优先于构造给的缺省
 *       （config {@code slow.factor}），倍数 ≤1.0 显式拒绝（静默无效＝§12 违例）；</li>
 *   <li>resource-exhaust 联动的「立即刷新对外槽位视图」是**消费方**的副作用
 *       （worker 按实例刷、engine 单端口刷），经返回值判断状态迁移后自行执行。</li>
 * </ul>
 *
 * <p>{@code VirtualScheduler} 的 freeze 是单态桩（无 slots/slow），不入本类（真实差异）。
 */
public final class FaultState {

    /** 事实发布（消费方决定汇点与 sourceId）。 */
    @FunctionalInterface
    public interface Emitter {
        void emit(String event, Map<String, Object> payload);
    }

    private final String eventPrefix;
    private final double defaultSlowFactor;
    private final int totalSlots;
    private final Emitter emitter;

    private final AtomicBoolean frozen = new AtomicBoolean(false);
    private final AtomicBoolean slowActive = new AtomicBoolean(false);
    private final AtomicBoolean resourceExhausted = new AtomicBoolean(false);
    private volatile double activeSlowFactor = 3.0;

    public FaultState(String eventPrefix, int totalSlots, double defaultSlowFactor,
                      Emitter emitter) {
        this.eventPrefix = eventPrefix;
        this.totalSlots = totalSlots;
        this.defaultSlowFactor = defaultSlowFactor;
        this.emitter = emitter;
    }

    public boolean frozen() {
        return frozen.get();
    }

    public boolean slow() {
        return slowActive.get();
    }

    public boolean exhausted() {
        return resourceExhausted.get();
    }

    /** 当前生效的 slow 倍数（执行时长 = 剧本时长 × 本值，仅注入期间）。 */
    public double activeSlowFactor() {
        return activeSlowFactor;
    }

    /**
     * 注入。返回本调用是否发生**状态迁移**（false＝已在目标态，幂等跳过事件）——
     * 消费方据此触发 resource-exhaust 的槽位视图刷新等联动副作用。
     *
     * @throws UnsupportedOperationException 未知动作（§7.2 无降级）
     */
    public boolean inject(FaultAction action) {
        switch (action.type()) {
            case FaultAction.FREEZE -> {
                if (frozen.compareAndSet(false, true)) {
                    emitter.emit(eventPrefix + "-frozen", Map.of());
                    return true;
                }
            }
            case FaultAction.SLOW -> {
                // 倍数优先级：动作 params.factor > 节点 config slow.factor（缺省 3.0）
                // 幂等：已在慢速中只更新倍数，不叠加（叠加会让恢复语义不可逆）
                double factor = resolveSlowFactor(action);
                activeSlowFactor = factor;
                if (slowActive.compareAndSet(false, true)) {
                    emitter.emit(eventPrefix + "-slowed", Map.of("factor", factor));
                    return true;
                }
            }
            case FaultAction.RESOURCE_EXHAUST -> {
                if (resourceExhausted.compareAndSet(false, true)) {
                    emitter.emit(eventPrefix + "-resource-exhausted",
                            Map.of("slots", totalSlots));
                    return true;
                }
            }
            default -> throw new UnsupportedOperationException(
                    "unsupported fault: " + action.type());
        }
        return false;
    }

    /** 清除注入（与 {@link #inject} 对称；返回是否发生状态迁移）。 */
    public boolean clear(FaultAction action) {
        switch (action.type()) {
            case FaultAction.FREEZE -> {
                if (frozen.compareAndSet(true, false)) {
                    emitter.emit(eventPrefix + "-resumed", Map.of());
                    return true;
                }
            }
            case FaultAction.SLOW -> {
                if (slowActive.compareAndSet(true, false)) {
                    emitter.emit(eventPrefix + "-speed-restored", Map.of());
                    return true;
                }
            }
            case FaultAction.RESOURCE_EXHAUST -> {
                if (resourceExhausted.compareAndSet(true, false)) {
                    emitter.emit(eventPrefix + "-resource-restored", Map.of());
                    return true;
                }
            }
            default -> throw new UnsupportedOperationException(
                    "unsupported fault: " + action.type());
        }
        return false;
    }

    /** 三态清零（restart()：身份保留、内部状态视为全新实例，§7.1）。 */
    public void reset() {
        frozen.set(false);
        slowActive.set(false);
        resourceExhausted.set(false);
    }

    /**
     * slow 的生效倍数：动作 {@code params.factor} 优先，其次构造缺省（config slow.factor）。
     * 非法倍数（≤1.0）显式拒绝——否则「注入了但没变慢」会被当成静默无效。
     */
    private double resolveSlowFactor(FaultAction action) {
        Object raw = action.params() == null ? null : action.params().get("factor");
        double factor = raw == null ? defaultSlowFactor : Double.parseDouble(String.valueOf(raw));
        if (factor <= 1.0) {
            throw new IllegalArgumentException(
                    "slow factor must be > 1.0 (params.factor or config slow.factor), got "
                            + factor);
        }
        return factor;
    }
}
