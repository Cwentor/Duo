package io.duo.sim.scenario;

import io.duo.sim.scenario.model.Scenario;

/**
 * NodeSpec 的启动形态判定——装配（{@code ComponentBootstrapper}）与校验
 * （{@code ScenarioValidator}）共享的唯一一份：「in-process / external」魔法串与
 * 兜底探针端口规则只在此处各存在一次。
 */
final class NodeSpecs {
    private NodeSpecs() {
    }

    /** launch.mode 缺省即 in-process（DSL 允许不写 launch）。 */
    static String launchMode(Scenario.NodeSpec n) {
        return n.launch() == null ? "in-process" : n.launch().mode();
    }

    static boolean isExternal(Scenario.NodeSpec n) {
        return "external".equals(launchMode(n));
    }

    /** external 节点的兜底探针端口（首个非 0 expose 端口；无则 0＝由 ReadyProbe 报错）。 */
    static int firstExposedPort(Scenario.NodeSpec n) {
        return n.exposes().stream()
                .filter(e -> e.port() != null && e.port() > 0)
                .mapToInt(Scenario.ExposeSpec::port)
                .findFirst()
                .orElse(0);
    }
}
