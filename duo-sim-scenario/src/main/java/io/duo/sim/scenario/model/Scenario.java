package io.duo.sim.scenario.model;

import java.util.List;
import java.util.Map;

/**
 * 场景 DSL 数据模型（设计文档 §8）。由 YAML 解析（{@code ScenarioLoader}）产出，
 * 经 {@code ScenarioValidator} 规则 1–8 校验后交给 ScenarioEngine/ComponentManager。
 */
public record Scenario(String name,
                      List<NodeSpec> nodes,
                      Behaviors behaviors,
                      List<TimelineEntry> timeline,
                      List<Map<String, Object>> assertions) {

    /** 拓扑节点。count>1 展开为多实例；path 为可选显式连接路径（wire/direct）。 */
    public record NodeSpec(String id,
                           String contract,
                           String tier,
                           boolean sut,
                           Launch launch,
                           Map<String, String> config,
                           List<ExposeSpec> exposes,
                           Map<String, WiringSpec> wiring,
                           Integer count,
                           Map<String, String> capacity,
                           String impl) {
    }

    /** launch: mode ∈ {in-process, external}；in-process 需 main，external 需 configOut。 */
    public record Launch(String mode, String main, String configOut) {
    }

    /** exposes: [{contract, port, addr?}]；port 0＝内核分配；external 节点必须显式声明。 */
    public record ExposeSpec(String contract, Integer port, String addr) {
    }

    /** wiring 槽：显式 {node, contract, path?}。 */
    public record WiringSpec(String node, String contract, String path) {
    }

    /** behaviors: profiles（名称→剧本）+ bindings（绑定到节点 + 任务匹配）。 */
    public record Behaviors(Map<String, Map<String, String>> profiles,
                            List<Binding> bindings) {
    }

    /** 匹配规则 M0 两级（精确任务名 > default）；M1 扩标签/通配。 */
    public record Binding(String node, String profile, String taskName) {
    }

    /** 时间线动作（M0 仅校验不执行；duration 可空）。 */
    public record TimelineEntry(String at, String action, String target, String duration,
                                Map<String, Object> params) {
    }
}
