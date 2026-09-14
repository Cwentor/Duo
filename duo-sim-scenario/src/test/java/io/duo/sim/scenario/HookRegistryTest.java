package io.duo.sim.scenario;

import io.duo.sim.kernel.api.Event;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T22 custom-hook 单测：注册/触发/事件/未注册失败/validator 豁免。 */
class HookRegistryTest {

    @Test
    void registeredHookExecutesAndEmitsEvents() {
        var hooks = new HookRegistry();
        var observedTargets = new ArrayList<String>();
        var observedParams = new ArrayList<Map<String, Object>>();
        hooks.register("drain-queue", ctx -> {
            observedTargets.add(ctx.target());
            observedParams.add(ctx.params());
            ctx.emit("sut.queue-drained", Map.of("remaining", 0));
        });
        List<Event> events = new ArrayList<>();
        var r = hooks.execute("master", Map.of("hook", "drain-queue", "queue", "q1"),
                events::add);

        assertTrue(r.success(), r.reason());
        assertEquals(List.of("master"), observedTargets);
        assertEquals("q1", observedParams.get(0).get("queue"));
        // hook 自发的业务事件 + sim.hook-executed 执行事实
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sut.queue-drained")));
        assertTrue(events.stream().anyMatch(e -> e.type().equals("sim.hook-executed")
                && "master".equals(e.sourceId())
                && "drain-queue".equals(e.payload().get("hook"))));
    }

    @Test
    void unregisteredHookFailsNotSilent() {
        var hooks = new HookRegistry();
        List<Event> events = new ArrayList<>();
        var r = hooks.execute("master", Map.of("hook", "ghost-hook"), events::add);
        assertFalse(r.success());
        assertTrue(r.reason().contains("no hook registered"));
        assertTrue(events.isEmpty(), "no events on failure");
    }

    @Test
    void missingHookNameFails() {
        var hooks = new HookRegistry();
        var r = hooks.execute("master", Map.of(), ev -> {
        });
        assertFalse(r.success());
        assertTrue(r.reason().contains("params.hook"));
    }

    @Test
    void hookThrowingIsReportedNotSwallowed() {
        var hooks = new HookRegistry();
        hooks.register("boom", ctx -> {
            throw new IllegalStateException("hook bug");
        });
        var r = hooks.execute("master", Map.of("hook", "boom"), ev -> {
        });
        assertFalse(r.success());
        assertTrue(r.reason().contains("threw"));
    }

    @Test
    void targetMayBeSutPerExemption() {
        // §7.2 豁免：custom-hook 的 target 可为 SUT（协作式操作）
        var hooks = new HookRegistry();
        hooks.register("flush-state", ctx -> ctx.emit("sut.state-flushed", Map.of()));
        var r = hooks.execute("master", Map.of("hook", "flush-state"), ev -> {
        });
        assertTrue(r.success(), r.reason());
    }

    @Test
    void validatorExemptsCustomHookOnSutTarget() {
        // validator 层：crash 打 SUT 拒绝；custom-hook 打 SUT 放行
        var registry = new io.duo.sim.kernel.core.ContractRegistry();
        registry.register(new io.duo.sim.kernel.spi.ComponentProvider() {
            @Override public io.duo.sim.kernel.api.Contract contract() {
                return io.duo.sim.kernel.api.Contract.SCHEDULER;
            }
            @Override public io.duo.sim.kernel.api.Tier tier() {
                return io.duo.sim.kernel.api.Tier.REAL;
            }
            @Override public String implName() {
                return "sut-stub";
            }
            @Override public boolean isDefault() {
                return true;
            }
            @Override public io.duo.sim.kernel.api.CapabilityMetadata metadata() {
                return new io.duo.sim.kernel.api.CapabilityMetadata(
                        io.duo.sim.kernel.api.EndpointShape.DUO_PORT, false, false,
                        java.util.Set.of(), true);
            }
            @Override public io.duo.sim.kernel.api.VirtualComponent newComponent() {
                // 注册期一致性校验会实例化（§7.5）：返回最小可编译实现
                return new io.duo.sim.kernel.api.VirtualComponent() {
                    @Override public io.duo.sim.kernel.api.ComponentId id() {
                        return new io.duo.sim.kernel.api.ComponentId("sut-stub");
                    }
                    @Override public void init(
                            io.duo.sim.kernel.api.ComponentContext ctx) { }
                    @Override public void start() { }
                    @Override public void stop(io.duo.sim.kernel.api.StopMode mode) { }
                    @Override public void restart() { }
                    @Override public io.duo.sim.kernel.api.HealthReport health() {
                        return io.duo.sim.kernel.api.HealthReport.ok();
                    }
                    @Override public List<io.duo.sim.kernel.api.ExposedEndpoint> endpoints() {
                        return List.of();
                    }
                };
            }
        });
        var sutNode = new io.duo.sim.scenario.model.Scenario.NodeSpec("master", "scheduler",
                "real", true, null, Map.of(), List.of(), Map.of(), null, Map.of(), null);
        var scenario = new io.duo.sim.scenario.model.Scenario("t", List.of(sutNode),
                new io.duo.sim.scenario.model.Scenario.Behaviors(Map.of(), List.of()),
                List.of(new io.duo.sim.scenario.model.Scenario.TimelineEntry(
                                "5s", "custom-hook", "master", null,
                                Map.of("hook", "drain-queue")),
                        new io.duo.sim.scenario.model.Scenario.TimelineEntry(
                                "6s", "crash", "master", null, Map.of())),
                List.of());
        var report = new ScenarioValidator(registry).validate(scenario);
        // custom-hook 放行；crash 打 SUT 报错——两条 timeline 恰好一错（且错误不含 custom-hook）
        assertEquals(1, report.errors().size(),
                "exactly one error expected (crash on SUT): " + report.errors());
        assertTrue(report.errors().get(0).contains("is SUT"), report.errors().toString());
        assertTrue(report.errors().stream().noneMatch(e -> e.contains("'custom-hook'")),
                "custom-hook must be exempt: " + report.errors());
    }
}
