package io.duo.sim.examples.acceptance;

import io.duo.sim.kernel.api.Event;
import io.duo.sim.kernel.core.ContractRegistry;
import io.duo.sim.scenario.HookRegistry;
import io.duo.sim.scenario.ScenarioEngine;
import io.duo.sim.scenario.ScenarioLoader;
import io.duo.sim.scenario.model.Scenario;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5/G5 验收：**YAML 时间线驱动用户自定义 hook 的端到端闭环**。
 *
 * <p>此前 `ScenarioEngine` 内部固定 {@code new HookRegistry()}，用户无处注册，
 * 场景里写 `custom-hook` 必然以「no hook registered」失败（DSL §8 偏差 7）。
 * 现引擎暴露 {@link ScenarioEngine#withHooks} / {@link ScenarioEngine#hooks()}：
 * 注册的 hook 被时间线按名调用，其发布的事实进入统一事件流并参与断言。
 */
class CustomHookAcceptanceTest {

    private static Scenario load(String resource) {
        try (InputStream in = CustomHookAcceptanceTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must exist");
            return ScenarioLoader.load(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void yamlTimelineInvokesRegisteredHookAndItsFactJoinsAssertions() throws Exception {
        var scenario = load("/scenarios/m5-custom-hook-acceptance.yaml");
        var registry = ContractRegistry.loadFromServiceLoader();

        // 测试侧持有 hook 的调用记录（证明时间线确实按名调到了它，且 params 透传）
        var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var hookRegistry = new HookRegistry();
        hookRegistry.register("scale-out", ctx -> {
            calls.add(ctx.target() + ":" + ctx.params().get("instances")
                    + ":" + ctx.params().get("reason"));
            // hook 发布自定义事实 → 参与断言（YAML 里用 eventSequence 检查顺序）
            ctx.emit("sut.hook-scale-out", Map.of(
                    "target", ctx.target(),
                    "instances", ctx.params().get("instances")));
        });

        try (ScenarioEngine engine = ScenarioEngine.validated(scenario, registry)
                .withHooks(hookRegistry)) {
            engine.startSut();
            engine.startComponents();
            // 时间线在 300ms 触发；等 hook 事实出现（最多 10s）
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline
                    && calls.isEmpty()) {
                Thread.sleep(50);
            }
            engine.stop();

            List<Event> events = engine.events();
            // ---- hook 被调用（名、target、params 透传）----
            assertEquals(List.of("master:2:hot-spot"), calls,
                    "hook must be invoked once with params passed through");
            // ---- 框架事实 + hook 自定义事实都在流里 ----
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.hook-executed")
                            && "scale-out".equals(e.payload().get("hook"))),
                    "sim.hook-executed must record the hook name");
            assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.hook-scale-out")
                            && "2".equals(String.valueOf(e.payload().get("instances")))),
                    "hook-emitted fact must join the unified event stream");
            // ---- YAML 断言（双轨之一）评估通过：顺序为 hook 事实 → sim.hook-executed ----
            var snapshot = engine.result().snapshot();
            assertTrue(snapshot.injectionFailures().isEmpty(),
                    "no injection failure expected: " + snapshot.injectionFailures());
            assertEquals(1, snapshot.assertions().size());
            snapshot.assertions().forEach(a -> assertTrue(a.passed(),
                    "assertion '" + a.name() + "' failed: " + a.detail()));
        }
    }

    @Test
    void unregisteredHookFailsLoudlyAsInjectionFailure() throws Exception {
        // §12 不静默：写了 hook 名却没注册 → 记录 injectionFailure（不吞掉、不假装成功）
        var scenario = load("/scenarios/m5-custom-hook-acceptance.yaml");
        var registry = ContractRegistry.loadFromServiceLoader();
        var seen = new AtomicReference<String>();
        var empty = new HookRegistry(); // 故意不注册 scale-out
        assertFalse(empty.has("scale-out"));

        try (ScenarioEngine engine = ScenarioEngine.validated(scenario, registry)
                .withHooks(empty)) {
            engine.startSut();
            engine.startComponents();
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline
                    && engine.result().snapshot().injectionFailures().isEmpty()) {
                Thread.sleep(50);
            }
            engine.stop();
            seen.set(String.valueOf(engine.result().snapshot().injectionFailures()));
            assertTrue(engine.result().snapshot().injectionFailures().stream()
                            .anyMatch(f -> String.valueOf(f).contains("no hook registered")),
                    "unregistered hook must be reported: " + seen.get());
        }
    }
}
