package io.duo.sim.scenario;

import io.duo.sim.kernel.api.Event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * custom-hook 注册表（M1 T22，§7.2 豁免落地）。
 *
 * <p>DSL 形态：timeline 动作 {@code custom-hook} 的
 * {@code params: {hook: <名称>, ...透传}}；target 允许 SUT（协作式操作，validator 对
 * 该动作豁免 SUT 检查）。hook 执行发布 {@code sim.hook-executed {hook, target}} 参与断言。
 *
 * <p>HookContext 提供 target 名与事件门面（hook 可发自定义事实参与断言）。
 */
public final class HookRegistry {

    /** hook 上下文。 */
    public interface HookContext {
        /** timeline 动作的 target（可为 SUT，§7.2 豁免）。 */
        String target();

        /** 透传参数（params 除 hook 名外的键值）。 */
        Map<String, Object> params();

        /** 发事件（sim.* 或 sut.* 前缀；参与断言）。 */
        void emit(String type, Map<String, Object> payload);
    }

    private final Map<String, Consumer<HookContext>> hooks = new ConcurrentHashMap<>();

    /** 注册 hook（重名覆盖并告警由调用方处理；此处直接覆盖）。 */
    public void register(String name, Consumer<HookContext> hook) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("hook name must not be blank");
        }
        hooks.put(name, hook);
    }

    public boolean has(String name) {
        return hooks.containsKey(name);
    }

    /**
     * 执行 hook（timeline custom-hook 动作入口）。
     *
     * @return 执行结果（未注册＝失败，§12 不静默）
     */
    public Result execute(String target, Map<String, Object> params,
                          Consumer<Event> eventSink) {
        Object hookName = params == null ? null : params.get("hook");
        if (hookName == null || String.valueOf(hookName).isBlank()) {
            return new Result(false, "custom-hook requires params.hook (the hook name)");
        }
        String name = String.valueOf(hookName);
        Consumer<HookContext> hook = hooks.get(name);
        if (hook == null) {
            return new Result(false, "no hook registered: " + name);
        }
        HookContext ctx = new HookContext() {
            @Override
            public String target() {
                return target;
            }

            @Override
            public Map<String, Object> params() {
                return params == null ? Map.of() : Map.copyOf(params);
            }

            @Override
            public void emit(String type, Map<String, Object> payload) {
                eventSink.accept(new Event(type, target,
                        java.time.Instant.now(), payload == null ? Map.of() : payload));
            }
        };
        try {
            hook.accept(ctx);
        } catch (RuntimeException e) {
            return new Result(false, "hook '" + name + "' threw: " + e.getMessage());
        }
        eventSink.accept(Event.sim("sim.hook-executed", target, Map.of("hook", name)));
        return new Result(true, null);
    }

    /** 执行结果。 */
    public record Result(boolean success, String reason) {
    }

    /** 已注册 hook 名（测试/诊断用）。 */
    public List<String> registered() {
        return List.copyOf(hooks.keySet());
    }
}
