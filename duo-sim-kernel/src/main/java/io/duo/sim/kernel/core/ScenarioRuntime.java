package io.duo.sim.kernel.core;

import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.api.FaultAction;
import io.duo.sim.kernel.api.FaultInjectable;
import io.duo.sim.kernel.api.InstanceControl;
import io.duo.sim.kernel.api.StopMode;
import io.duo.sim.kernel.api.VirtualComponent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * ScenarioRuntime 注入通路（§10/§7.2，计划 T10）：
 * target 解析（含实例下标）→ 能力校验（元数据，无降级）→ {@code sim.fault-injected} 事件
 * → 分发（crash/restart 生命周期或 FaultInjectable）。
 *
 * <p>顺序为「先因后果」：框架的注入事件先落流，组件反应事件随后（M6 验收暴露的顺序缺陷修正，
 * 详见 {@link #inject}）。
 *
 * <p>校验三查（§7.2，卡 3c 起全部在 {@link #precheck} 实现——此前仅①落地、②只在静态
 * 校验器、③靠 dispatch 异常兜底）：① target 可解析（下标 ∈ [1,count]）；② 动作在
 * supportedFaults 或属 crash/restart 生命周期；③ 带下标时实现必须 instanceControl。
 * 任一不满足：热注入记注入失败一级事件并返回失败（§12，不静默）。
 */
public final class ScenarioRuntime {

    /** 注入结果。 */
    public record InjectionResult(boolean success, String reason) {

        static InjectionResult ok() {
            return new InjectionResult(true, null);
        }

        static InjectionResult fail(String reason) {
            return new InjectionResult(false, reason);
        }
    }

    /** 已解析的实例目标（ScenarioEngine 注册）。supportedFaults＝注册期能力元数据（§7.5），运行期第②查的依据。 */
    public record Target(VirtualComponent component, String componentId, int instanceCount,
                         Set<String> supportedFaults) {
    }

    /** 注入钩子：供 duration 自动 clear（M0 简单起见不启用 duration；M1 时间线用）。 */
    private final Map<String, Target> targets = new ConcurrentHashMap<>();
    /** SUT 节点 id 集合（§7.2：SUT 不得为 target）。 */
    private final Set<String> sutIds = ConcurrentHashMap.newKeySet();
    private final Consumer<Event> recorder;

    /** @param recorder 注入相关事件的统一汇（ScenarioEngine 里接事件总线——与内存流/录制共用单一订阅路径，T21）。 */
    public ScenarioRuntime(Consumer<Event> recorder) {
        this.recorder = recorder;
    }

    /** 外部事件汇入统一流（T22 custom-hook 等扩展点用；与注入事件同源录制）。 */
    public void emitExternal(Event event) {
        recorder.accept(event);
    }

    /** 注册已解析的注入目标（装配期由 bootstrapper 调；supportedFaults 取注册期能力元数据，运行期第②查的依据）。 */
    public void registerTarget(String id, Target t) {
        targets.put(id, t);
    }

    /** 标记节点为 SUT（§7.2：SUT 不得为注入目标——precheck 第①查的依据）。 */
    public void markSut(String id) {
        sutIds.add(id);
    }

    /**
     * 执行注入（M0：crash/restart 生命周期动作 + FaultInjectable 分发通路）。
     * 返回失败原因同时发布 {@code sim.fault-inject-failed} 一级事件（§12）。
     */
    public InjectionResult inject(FaultAction action) {
        String componentId = action.target().componentId().value();
        InjectionResult precheck = precheck(componentId, action);
        if (!precheck.success()) {
            recorder.accept(Event.sim("sim.fault-inject-failed", componentId,
                    Map.of("action", action.type(), "reason", precheck.reason())));
            return precheck;
        }
        Target t = targets.get(componentId);
        Integer idx = action.target().instanceIndex();
        // 因果顺序（M6 验收暴露的缺陷修正）：§11 断言以 sim.fault-injected 为观测窗口**起点**
        // （failoverWithin / masterReelectedWithin），故它是「因」，必须先于组件反应事件（果）落流。
        // 旧顺序（先 dispatch 后记录）会让组件在同一调用内发布的反应事件排在因之前——
        // 如 embedded registry-flap 的 sim.registry-flap-started/cleared 早于 sim.fault-injected，
        // 使 `eventSequence: [sim.fault-injected, <反应事件>]` 恒不可满足。
        recorder.accept(Event.sim("sim.fault-injected",
                idx == null ? componentId : t.component().id().instanceSourceId(idx),
                Map.of("action", action.type())));
        try {
            dispatch(t, idx, action);
        } catch (RuntimeException e) {
            // 派发失败：因已落流，另记一级失败事件（§12 不静默）——语义＝「已下达但目标未执行」
            InjectionResult r = InjectionResult.fail("dispatch threw: " + e.getMessage());
            recorder.accept(Event.sim("sim.fault-inject-failed", componentId,
                    Map.of("action", action.type(), "reason", r.reason())));
            return r;
        }
        return InjectionResult.ok();
    }

    /**
     * 清除注入（§7.2：带 duration 的动作由场景引擎计时到期自动 clear；M1 T16 通路）。
     * 仅对 FaultInjectable 类动作有意义（crash/restart 生命周期不可"清除"，
     * 由调用方——TimelineScheduler——不得对生命周期动作安排 clear）。
     * 校验失败同样记 {@code sim.fault-inject-failed}（clear 失败不允许静默）。
     */
    public InjectionResult clear(FaultAction action) {
        String componentId = action.target().componentId().value();
        if (sutIds.contains(componentId)) {
            return failAndRecord(componentId, action, "target must not be SUT");
        }
        Target t = targets.get(componentId);
        if (t == null) {
            return failAndRecord(componentId, action, "unknown target component");
        }
        if (!(t.component() instanceof FaultInjectable fi)) {
            return failAndRecord(componentId, action,
                    "component does not implement FaultInjectable");
        }
        // 第②查同款（卡 3c）：不能清除本就不可注入的动作
        if (!isLifecycle(action) && !t.supportedFaults().contains(action.type())) {
            return failAndRecord(componentId, action, "action '" + action.type()
                    + "' unsupported by '" + componentId + "' (§7.2 无降级, supported: "
                    + t.supportedFaults() + ")");
        }
        // 同上（因果顺序）：sim.fault-cleared 先落流再派发，保证「窗口收尾」先于组件反应事件
        recorder.accept(Event.sim("sim.fault-cleared", componentId,
                Map.of("action", action.type())));
        try {
            fi.clear(action);
        } catch (RuntimeException e) {
            return failAndRecord(componentId, action, "clear threw: " + e.getMessage());
        }
        return InjectionResult.ok();
    }

    private InjectionResult failAndRecord(String componentId, FaultAction action, String reason) {
        recorder.accept(Event.sim("sim.fault-inject-failed", componentId,
                Map.of("action", action.type(), "reason", reason)));
        return InjectionResult.fail(reason);
    }

    /** 校验（不依赖实例，只依赖注册表元数据 + 拓扑解析信息）——供 T11 的 timeline 校验期复用。 */
    public InjectionResult precheck(String componentId, FaultAction action) {
        if (sutIds.contains(componentId)) {
            return InjectionResult.fail("target must not be SUT: " + componentId);
        }
        Target t = targets.get(componentId);
        if (t == null) {
            return InjectionResult.fail("unknown target component: " + componentId);
        }
        Integer idx = action.target().instanceIndex();
        if (idx != null && (idx < 1 || idx > t.instanceCount())) {
            return InjectionResult.fail("instance index out of range [1," + t.instanceCount()
                    + "]: " + idx);
        }
        // ② 动作受支持（§7.2）：非生命周期动作必须在注册期 supportedFaults 中——
        //    此前仅静态校验器（规则 6）检查、热注入不复查（体检实证的语义分叉，卡 3c 补齐）
        if (!isLifecycle(action) && !t.supportedFaults().contains(action.type())) {
            return InjectionResult.fail("action '" + action.type() + "' unsupported by '"
                    + componentId + "' (§7.2 无降级, supported: " + t.supportedFaults() + ")");
        }
        // ③ 实例能力（§7.2）：带下标须 instanceControl——注册期一致性校验（§7.5）保证
        //    元数据与实现接口对应，故直接以接口形态判定，少一个可漂移的传递字段
        if (idx != null && !(t.component() instanceof InstanceControl)) {
            return InjectionResult.fail("instance index requires instanceControl capability: "
                    + componentId);
        }
        return InjectionResult.ok();
    }

    /** crash/restart 是生命周期动作（§8 分发规则），不受 supportedFaults 约束（判定单点见 FaultAction.isLifecycle）。 */
    private static boolean isLifecycle(FaultAction action) {
        return FaultAction.isLifecycle(action.type());
    }

    private void dispatch(Target t, Integer idx, FaultAction action) {
        switch (action.type()) {
            case FaultAction.CRASH -> {
                if (idx == null) {
                    t.component().stop(StopMode.CRASH);
                } else {
                    asInstanceControl(t.component()).stopInstance(idx, StopMode.CRASH);
                }
            }
            case FaultAction.RESTART -> {
                if (idx == null) {
                    t.component().restart();
                } else {
                    asInstanceControl(t.component()).restartInstance(idx);
                }
            }
            default -> {
                // FaultInjectable 类：能力校验（§7.2 ②）
                if (!(t.component() instanceof FaultInjectable fi)) {
                    throw new UnsupportedOperationException(
                            "component does not implement FaultInjectable: " + action.type());
                }
                if (idx == null) {
                    fi.inject(action);
                } else {
                    asInstanceControl(t.component()).injectOnInstance(action);
                }
            }
        }
    }

    private static InstanceControl asInstanceControl(VirtualComponent c) {
        if (!(c instanceof InstanceControl ic)) {
            throw new UnsupportedOperationException("no instance control: " + c.id());
        }
        return ic;
    }
}
