/**
 * JUnit5 集成面（M2 T30）：{@link VirtualCluster} 注解声明场景 YAML 夹具，
 * {@link VirtualClusterExtension} 管理场景生命周期（加载→校验→启动→断言评估→停止清理）并把
 * {@code ScenarioEngine} 注入测试参数；{@link DuoAssertions} 提供编程式断言（与 YAML 内置评估
 * 共用同一事件事实源）。
 *
 * <p>本模块按设计不带测试——与示例强耦合的集成测试落在 duo-sim-examples，
 * 避免模块循环依赖（DEVELOPMENT.md §3.1）。
 */
package io.duo.sim.junit;
