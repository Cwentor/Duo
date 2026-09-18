# duo-sim-scenario

**场景层**：YAML 解析与校验（规则 1–8）、场景编排与生命周期、时间线故障注入、事件录制、自定义钩子。

- 依赖：`duo-sim-kernel`、`snakeyaml`、`jackson-databind`
- 测试：31 条（`mvn -o -pl duo-sim-scenario test`）

## 关键类

| 类 | 职责 |
| --- | --- |
| `ScenarioLoader` | YAML → `model.Scenario`（只做结构解析；`launch.ready.*` → `config["ready.*"]`、`capacity.*` → `config["capacity.*"]` 展平） |
| `ScenarioValidator` | 规则 1–8 全量校验 + 警告（如 embedded registry 上 `registry-flap` 的 `duration` 被忽略）；只读静态能力元数据 |
| `ScenarioEngine` | 校验 → `startSut()` → `startComponents()` → 时间线 → `stop()`；事件流与录制**同一订阅点**；结束时评估 YAML 断言 |
| `TimelineScheduler` | 以「全部组件启动完成」为 t0 调度时间线；带 `duration` 的动作到期自动 `clear()`；`custom-hook` 走 `HookRegistry` |
| `EventRecorder` | 事件 → JSON Lines（`build/scenarios/<name>/events.jsonl`）+ `readBack()` 回读 |
| `HookRegistry` | `custom-hook` 注册与执行（未注册＝失败，不静默）；发布 `sim.hook-executed` |
| `InteractiveTierGuard` | 拒绝交互型契约声明 embedded/container 档（该形态不存在） |
| `ScenarioResult` | 断言结果、注入失败、警告的收集与 `snapshot()` |

## 注意

- 引擎内部目前 `new HookRegistry()`，**未暴露注册入口**——YAML 时间线里的 `custom-hook` 会「no hook registered」
  （见 [DSL 偏差表](../docs/SCENARIO-DSL.md#8-现状与设计偏差务必先读) 第 7 条，已列入路线图 M5）。
- `startComponents()` 必须跳过已 `adopt` 的节点，否则二次 `start()` 会重建 TestingServer 导致已注册节点全丢。

→ [场景 DSL 参考](../docs/SCENARIO-DSL.md) · [架构说明 · 生命周期时序](../docs/ARCHITECTURE.md#7-生命周期时序)
