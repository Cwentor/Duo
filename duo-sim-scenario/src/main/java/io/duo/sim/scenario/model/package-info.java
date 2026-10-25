/**
 * 场景 DSL 数据模型（设计文档 §8）：{@link Scenario} 由 YAML 解析（{@code ScenarioLoader}）产出，
 * 经 {@code ScenarioValidator} 规则 1–8 校验后交给 ScenarioEngine / ComponentManager。
 * 字段全集、校验规则与断言语义见 docs/SCENARIO-DSL.md。
 */
package io.duo.sim.scenario.model;
