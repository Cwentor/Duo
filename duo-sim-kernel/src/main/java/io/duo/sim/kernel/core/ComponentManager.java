package io.duo.sim.kernel.core;

import io.duo.sim.kernel.api.ComponentContext;
import io.duo.sim.kernel.api.ComponentId;
import io.duo.sim.kernel.api.SimClock;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;

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
                stopAll(); // 逆序拆除已启动
                throw new StartupFailure("component start failed: " + id, e);
            }
        }
    }

    /** 统一清理：组件逆序 stop(GRACEFUL) + 额外停止动作（含 SUT 协作停止）。 */
    public void stopAll() {
        // live 是 LinkedHashMap：keySet 保持插入（启动）序 → 逆序即反依赖序（§7.1）
        List<String> reverse = live.keySet().stream()
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        java.util.Collections.reverse(reverse);
        for (String id : reverse) {
            try {
                live.remove(id).stop(StopMode.GRACEFUL);
            } catch (RuntimeException ignored) {
                // 拆除阶段尽力而为，根因由启动侧保留
            }
        }
        for (String key : new java.util.ArrayList<>(extraStops.keySet())) {
            try {
                extraStops.remove(key).run();
            } catch (RuntimeException ignored) {
                // 尽力而为
            }
        }
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
