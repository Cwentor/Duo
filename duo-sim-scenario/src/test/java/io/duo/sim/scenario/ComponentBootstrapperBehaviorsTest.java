package io.duo.sim.scenario;

import io.duo.sim.scenario.model.Scenario;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配核心的 behaviors 展平单测（卡 2 拆分后第一次有不启场景的单元测试面）：
 * default / by-label / named 三种前缀的落点，与「default 档优先、未知 profile 忽略」语义。
 */
class ComponentBootstrapperBehaviorsTest {

    private static Scenario.NodeSpec node(String id) {
        return new Scenario.NodeSpec(id, "worker", "virtual", false, null,
                Map.of(), List.of(), Map.of(), 1, Map.of(), null);
    }

    @Test
    void flattensDefaultLabelAndNamedBindingsWithDistinctPrefixes() {
        Map<String, Map<String, String>> profiles = new LinkedHashMap<>();
        profiles.put("default", Map.of("duration", "3s"));
        profiles.put("fast", Map.of("duration", "1s"));
        profiles.put("slow", Map.of("duration", "9s"));
        var behaviors = new Scenario.Behaviors(profiles, List.of(
                new Scenario.Binding("workers", "default", null, null),
                new Scenario.Binding("workers", "fast", null, "etl"),
                new Scenario.Binding("workers", "slow", "report-*", null)));
        var scenario = new Scenario("behaviors-unit", List.of(), behaviors, List.of(), List.of());

        Map<String, String> cfg = new LinkedHashMap<>();
        ComponentBootstrapper.mergeBehaviors(cfg, node("workers"), scenario);

        assertEquals("3s", cfg.get("behaviors.default.duration"));
        assertEquals("1s", cfg.get("behaviors.by-label.etl.duration"));
        assertEquals("9s", cfg.get("behaviors.named.report-*.duration"));
        assertEquals(3, cfg.size(), "flattening must not leak raw keys without prefixes");
    }

    @Test
    void defaultProfileWinsEvenWithTaskMatcher() {
        // 实现语义：profile 名为 default（或未带任何匹配器）一律归 default 档
        var behaviors = new Scenario.Behaviors(Map.of("default", Map.of("duration", "2s")),
                List.of(new Scenario.Binding("workers", "default", "t-*", null)));
        var scenario = new Scenario("behaviors-unit-default", List.of(), behaviors,
                List.of(), List.of());

        Map<String, String> cfg = new LinkedHashMap<>();
        ComponentBootstrapper.mergeBehaviors(cfg, node("workers"), scenario);
        assertEquals("2s", cfg.get("behaviors.default.duration"));
    }

    @Test
    void bindingsForOtherNodesOrUnknownProfilesAreIgnored() {
        var behaviors = new Scenario.Behaviors(Map.of("p", Map.of("duration", "1s")),
                List.of(new Scenario.Binding("others", "p", null, null),
                        new Scenario.Binding("workers", "missing-profile", null, null)));
        var scenario = new Scenario("behaviors-unit-skip", List.of(), behaviors,
                List.of(), List.of());

        Map<String, String> cfg = new LinkedHashMap<>();
        ComponentBootstrapper.mergeBehaviors(cfg, node("workers"), scenario);
        assertTrue(cfg.isEmpty(), "unmatched bindings must contribute nothing");
    }
}
