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

    /**
     * launch: mode ∈ {in-process, external}；in-process 需 main，external 需 configOut。
     *
     * <p>{@code command}（M6，决策 D7）仅 external 用：内核代起的外部进程命令行，
     * 空＝attach 形态（进程由用户自行启动，内核只写端点配置 + 探针就绪）。
     * 支持 {@code ${java}} / {@code ${java.home}} 占位符，避免场景文件写死本机路径。
     */
    public record Launch(String mode, String main, String configOut, String command) {

        /** 兼容构造（M6 前的三字段形态，{@code command} 缺省＝attach）。 */
        public Launch(String mode, String main, String configOut) {
            this(mode, main, configOut, null);
        }
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

    /** 匹配规则 M1 四级（精确任务名 > 标签 > 通配 > default）：taskName 可含 * 通配，label 为标签匹配。 */
    public record Binding(String node, String profile, String taskName, String label) {
    }

    /** 时间线动作（M1 T16 起由 TimelineScheduler 自动执行；at/duration 带单位，duration 可空）。 */
    public record TimelineEntry(String at, String action, String target, String duration,
                                Map<String, Object> params) {
    }
}
