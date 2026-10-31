package io.duo.sim.kernel.core;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 组件管理器（§7.1）：依赖拓扑排序启动、失败逆序拆除、统一清理。
 *
 * <p>SUT 协作停止的接入点：{@link #registerExtraStop(Runnable)}（T12 把 SUT 停止注册进来，
 * 纳入逆依赖拆除序列；M0-b 阶段仅组件自身）。
 */
public final class ComponentManager {

    private final Map<String, VirtualComponent> live = new LinkedHashMap<>();
    private final Map<String, Runnable> extraStops = new ConcurrentHashMap<>();

    public Map<String, VirtualComponent> live() {
        return live;
    }

    /** 纳管已在外部启动的组件（纳入逆序拆除序列，如 SUT 启动前的 registry）。 */
    public void adopt(String id, VirtualComponent c) {
        live.put(id, c);
    }

    /**
     * 从拆除序列摘除并停止一个已纳管组件（SUT 启动失败回滚，评审 A1 2026-10-30）。
     * 与 {@link #stopAll()} 的"尽力而为"不同：停止失败**向上抛**——由回滚侧记 warnings，
     * 不静默；返回是否确有此组件。
     */
    public boolean abandon(String id) {
        VirtualComponent c = live.remove(id);
        if (c == null) {
            return false;
        }
        c.stop(StopMode.GRACEFUL);
        return true;
    }

    /** 注册额外停止动作（SUT 协作停止），按注册顺序逆序执行。 */
    public void registerExtraStop(String key, Runnable stop) {
        extraStops.put(key, stop);
    }

    /**
     * 按给定顺序启动；任一失败 → 逆序拆除已启动组件后抛 {@link StartupFailure}
     * （携带根因，§12）。
     */
    public void startAll(List<String> order,
                         java.util.function.Function<String, VirtualComponent> instantiate) {
        for (String id : order) {
            VirtualComponent c = instantiate.apply(id);
            try {
                c.start();
                live.put(id, c);
            } catch (RuntimeException e) {
                List<String> rollbackFailures = stopAll(); // 逆序拆除已启动
                var message = "component start failed: " + id;
                if (!rollbackFailures.isEmpty()) {
                    message += " (rollback: " + String.join("; ", rollbackFailures) + ")";
                }
                throw new StartupFailure(message, e);
            }
        }
    }

    /**
     * 统一清理：组件逆序 stop(GRACEFUL) + 额外停止动作（含 SUT 协作停止）。
     * 返回逐条失败描述（评审 A4，2026-10-30）：此前 catch 后直接吞掉——组件停止失败
     * （没关 ServerSocket/没停线程池）是泄漏的最典型信号，必须可见（§12）；调用方
     * （ScenarioEngine.stop）据此写 warnings + 结果告警，启动回滚路径据此并入
     * StartupFailure 消息。空表＝全部干净。"尽力而为"的语义不变：单个失败不中断拆除。
     */
    public List<String> stopAll() {
        List<String> failures = new ArrayList<>();
        // live 是 LinkedHashMap：keySet 保持插入（启动）序 → 逆序即反依赖序（§7.1）
        List<String> reverse = live.keySet().stream()
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        java.util.Collections.reverse(reverse);
        for (String id : reverse) {
            try {
                live.remove(id).stop(StopMode.GRACEFUL);
            } catch (RuntimeException e) {
                failures.add("component '" + id + "' stop failed: " + e.getMessage());
            }
        }
        for (String key : new java.util.ArrayList<>(extraStops.keySet())) {
            try {
                extraStops.remove(key).run();
            } catch (RuntimeException e) {
                failures.add("extra stop '" + key + "' failed: " + e.getMessage());
            }
        }
        return failures;
    }

    /** 实例（含 restart 重拉）。 */
    public void restart(String id) {
        VirtualComponent c = live.get(id);
        if (c != null) {
            c.restart();
        }
    }

    public static class StartupFailure extends RuntimeException {
        public StartupFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
