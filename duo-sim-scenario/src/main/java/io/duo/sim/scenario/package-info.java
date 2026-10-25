/**
 * 场景引擎（计划 T11）：把一份 YAML 变成活的仿真——{@link ScenarioLoader} 结构解析、
 * {@link ScenarioValidator} 规则 1–8（外部输入档再加 9–11）、{@link ScenarioEngine} 统一编排
 * （内部拆 {@link EventJournal} / {@link ComponentBootstrapper} / {@code TimelineScheduler} 三模块，
 * 接缝各有单测）。辅件：{@link HookRegistry}（custom-hook 注册表）、{@link InputTrust}
 * （输入信任档全链路唯一枚举）、{@link NodeSpecs}（启动形态判定，装配与校验共享）、
 * {@link InteractiveTierGuard}（交互型契约档位静态检查）。
 *
 * <p>DSL 字段模型在 {@code scenario.model}；字段全集与校验规则见 docs/SCENARIO-DSL.md。
 */
package io.duo.sim.scenario;
