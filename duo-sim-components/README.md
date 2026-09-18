# duo-sim-components

**virtual 档组件库**：进程内状态机与 Duo 协议组件。

- 依赖：`duo-sim-kernel`、`duo-sim-protocol`
- 测试：44 条（`./mvnw -o -pl duo-sim-components -am test`）
- SPI 注册（`META-INF/services/io.duo.sim.kernel.spi.ComponentProvider`）：
  `VirtualRegistryProvider`、`VirtualWorkerProvider`

## 组件

| 组件 | 契约/档位 | 能力元数据 | 说明 |
| --- | --- | --- | --- |
| `VirtualRegistry` | `registry` / `virtual` | `NONE` + `interfaceDirect` + `registry-flap` | 会话/临时节点/watch 的内存状态机；`registry-flap` 是**持续窗口**（`duration` 有效），端点快照即事实源，flap 期间写入不丢 |
| `VirtualWorker` | `worker` / `virtual` | `DUO_PORT` + `instanceControl` + `task-kill` | 每实例一个虚拟线程；心跳/槽位/任务收发；内嵌 `TaskStub` 行为模型；`task-kill` 终止在途任务并立即回报 `CANCELLED`；**满载派发显式拒绝**（`TaskStatus.REJECTED` + `sim.worker-task-rejected`，不得静默丢弃——G9）；槽位计数原子化，受理/拒绝后立即上报槽位 |

## 行为模型（TaskStub）

| 类 | 职责 |
| --- | --- |
| `BehaviorProfile` | 8 字段行为模型：`duration`/`jitter`/`successRate`/`exception`/`logLines`/`failAt`/`neverReport`/`progress`；`failAt` 确定性截断；`neverReport` 丢弃回报；`progress=periodic` 每 25% 回调 |
| `BehaviorResolver` | 从 config 解析剧本（`behaviors.default.` / `behaviors.named.<X>.` / `behaviors.by-label.<L>.`）；匹配优先级 **精确名 > 标签 > 通配 > default** |

> `logLines` 已在 `BehaviorProfile` 中，但 `BehaviorResolver` 目前固定传空列表——**DSL 写了不生效**，
> 见 [DSL 偏差表](../docs/SCENARIO-DSL.md#8-现状与设计偏差务必先读) 第 3 条。

→ [架构说明 · 契约与档位](../docs/ARCHITECTURE.md#5-契约与档位) · [DSL · 行为剧本](../docs/SCENARIO-DSL.md#2-behaviors-行为剧本)
