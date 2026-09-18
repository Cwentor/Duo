# duo-sim-junit

**JUnit5 集成**：场景化测试注解 `@VirtualCluster` 与编程式断言 `DuoAssertions`。

- 依赖：`duo-sim-kernel`、`duo-sim-scenario`、`junit-jupiter-api`（`provided`）
- 测试：**本模块不带测试**（0 条）——扩展的集成测试必须放 `duo-sim-examples`，
  否则形成 `junit ↔ examples` 循环依赖

## API

| 类型 | 说明 |
| --- | --- |
| `@VirtualCluster(value)` | 声明 classpath 上的场景 YAML；属性：`autoStart`（缺省 true）、`assertResult`（缺省 true，断言失败即测试失败）、`sutExitTimeoutMs`（缺省 90s）。可标在类或方法上 |
| `VirtualClusterExtension` | 生命周期：加载 → 校验 → 启动 SUT 与组件 → 等 SUT 退出 → 测试体 → 断言评估 → 清理；失败时输出录制文件路径 |
| `DuoAssertions` | `assertThat(engine.events())` 链式包装：`failoverWithin`、`noTaskLostAllSuccess`、`noTaskLostTerminalOnly`、`eventSequence`、`affectedTasksAtLeast`、`check(自定义断言)` |

```java
@VirtualCluster("/scenarios/junit-extension-smoke.yaml")
class MyScenarioTest {
    @Test
    void scenarioRuns(ScenarioEngine engine) {   // 引擎按注解自动注入
        DuoAssertions.assertThat(engine.events()).noTaskLostAllSuccess();
    }
}
```

与 YAML `assertions:` 节**共用同一事件事实源**（§11 断言双轨分工）。

→ [架构说明 · 观测面与断言](../docs/ARCHITECTURE.md#11-观测面与断言11) · [DSL · 断言](../docs/SCENARIO-DSL.md#6-断言-assertions)
