# duo-sim-components

**virtual 档组件库**：进程内状态机与 Duo 协议组件。

- 依赖：`duo-sim-kernel`、`duo-sim-protocol`
- 测试：111 条（`./mvnw -o -pl duo-sim-components -am test`），含由 `examples` 迁入的调度状态机 18 条
- SPI 注册（`META-INF/services/io.duo.sim.kernel.spi.ComponentProvider`）：
  `VirtualRegistryProvider`、`VirtualWorkerProvider`、`VirtualSchedulerProvider`、`VirtualEngineProvider`、
  `VirtualFilestoreProvider`、`VirtualMessageBrokerProvider`、`VirtualResourceProvider`

## 组件

| 组件 | 契约/档位 | 能力元数据 | 说明 |
| --- | --- | --- | --- |
| `VirtualRegistry` | `registry` / `virtual` | `NONE` + `interfaceDirect` + `registry-flap` | 会话/临时节点/watch 的内存状态机；`registry-flap` 是**持续窗口**（`duration` 有效），端点快照即事实源，flap 期间写入不丢 |
| `VirtualWorker` | `worker` / `virtual` | `DUO_PORT` + `instanceControl` + `task-kill`/`freeze`/`slow`/`resource-exhaust` | 每实例一个虚拟线程；心跳/槽位/任务收发；内嵌 `TaskStub` 行为模型；`task-kill` 终止在途任务并立即回报 `CANCELLED`；**满载派发显式拒绝**（`TaskStatus.REJECTED` + `sim.worker-task-rejected`，不得静默丢弃——G9）；槽位计数原子化，受理/拒绝后立即上报槽位 |
| `VirtualScheduler` | `scheduler` / `virtual` | `DUO_PORT` + `freeze` | 与 real 档 `DemoScheduler` **同源**（共用 `SchedulerStateMachine`/`DispatchSelector`）：DAG 依赖、重试、失败转移；发布 `sut.scheduler-started`/`sut.task-*`/`sut.failover` 等调度事实（**无选主语义**）；把自身端点注册为 registry 的 `"scheduler"` 端点，优雅停止时删除 `/duo/endpoints/scheduler`。**必须有一条 DIRECT registry 接线**（缺绑定＝启动期显式失败） |
| `VirtualEngine` | `engine` / `virtual` | `DUO_PORT` + `freeze`/`slow`/`resource-exhaust` | DUO_PORT **服务端**：首帧必须 `TaskDispatch`，其后 `TaskCancel`；另有进程内 `EngineContract.submit(taskName, cpu, memGB)` 直连路径；拒绝原因按序显式给出 `resource exhausted` → `engine frozen` → `no free slot`；`capacity.slots` 缺省＝`capacity.cpu`；复用共享 `BehaviorResolver`/`BehaviorProfile` |
| `VirtualFilestore` | `filestore` / `virtual` | `FS_PATH` + `interfaceDirect` | 自持临时根目录（或 `filestore.root`）；`resolve()` 拒绝 `../` 逃逸；读/删不存在的文件**显式失败**（`UncheckedIOException`，cause 为 `NoSuchFileException`）；`supportedFaults` 为空 |
| `VirtualMessageBroker` | `message` / `virtual` | `NONE` + `interfaceDirect` | 每主题 FIFO：`publish`/`drain`/`depth`/`subscribe`（**只读观察**）/`topics()`；超过 `message.maxDepthPerTopic`（缺省 10000）显式抛 `ComponentException`；`supportedFaults` 为空 |
| `VirtualResourceManager` | `resource` / `virtual` | `NONE` + `interfaceDirect` + `resource-exhaust` | 配额分配器；不足时报 `insufficient resource: requested ... remaining ...`；注入耗尽后对外可见配额为 0 并拒绝分配，注入/清除幂等 |

> M5 第 4 轮故障动作（`VirtualWorker`/`VirtualEngine` 上实现，`freeze` 另在 `VirtualScheduler`、
> `resource-exhaust` 另在 `VirtualResourceManager`）：
> `freeze`＝心跳/槽位停止上报、新派发**显式拒绝**（worker 详情 `"frozen"`、engine 详情 `"engine frozen"`）、
> 在途进度/日志/终态回报**挂起**至 `clear`（engine 的派发泵同时停摆）；`slow`＝执行时长 × 因子，
> 因子优先级 action `params.factor` > 节点 `config slow.factor` > 缺省 3.0，重复注入只更新不叠加，
> 因子 ≤ 1.0 显式拒绝；`resource-exhaust`＝显式拒绝 + 对外可见 0 空闲槽位/0 剩余配额。
> 三者均幂等；`task-kill` 仍是**实例级**动作（组件级 `inject` 显式抛 `UnsupportedOperationException`）。

## 调度状态机（两档共用）

| 类 | 职责 |
| --- | --- |
| `SchedulerStateMachine` | DAG 依赖推进、重试与失败转移的状态机（real 档 `DemoScheduler` 与 `VirtualScheduler` 同源） |
| `DispatchSelector` | 派发目标选择（负载/槽位口径） |

> M5 第 4 轮由 `duo-sim-examples` 迁入 `io.duo.sim.components.scheduler`，`SchedulerStateMachineTest`（11）
> 与 `DispatchSelectorTest`（7）随实现一并迁移——**换档不改变调度语义**。

## 行为模型（TaskStub）

| 类 | 职责 |
| --- | --- |
| `BehaviorProfile` | 8 字段行为模型：`duration`/`jitter`/`successRate`/`exception`/`logLines`/`failAt`/`neverReport`/`progress`；`failAt` 确定性截断；`neverReport` 丢弃回报；`progress=periodic` 每 25% 回调 |
| `BehaviorResolver` | 从 config 解析剧本（`behaviors.default.` / `behaviors.named.<X>.` / `behaviors.by-label.<L>.`）；匹配优先级 **精确名 > 标签 > 通配 > default**（**整条命中，不逐字段合并**）；`jitter` 接受 `0.1` 或 `20%`、`failAt` 接受 `60` 或 `60%`，越界报错点出配置键 |

> `logLines` **已接入 DSL（M5，G7 闭合）**：逗号串或 YAML 列表均可，执行期逐行发
> `sim.worker-log`（`{task}`/`{taskId}` 占位符可展开），virtual 与 real 两档 worker 同构。

→ [架构说明 · 契约与档位](../docs/ARCHITECTURE.md#5-契约与档位) · [DSL · 行为剧本](../docs/SCENARIO-DSL.md#2-behaviors-行为剧本)
