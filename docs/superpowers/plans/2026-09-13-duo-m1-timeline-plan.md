# Duo M1 实施计划 —— 时间线注入与断言库

- 日期：2026-09-13
- 依据：设计文档 v1.0（冻结）§14 M1 行；M0 已验收（commit 29b1a42，全仓 81 测全绿）
- 范围：M0 遗留清单全部 8 项 + 设计文档 §14 M1 产出；验收标准＝§14 M1 验收场景断言通过
- 状态：待用户批准（批准前不动代码）

---

## 1. 交付定义（唯一验收口径）

**设计文档 §14 M1 验收场景：完整 §8 金标准 YAML（含 timeline）运行——`crash workers[3]` 于 T+10s 宕机 → 任务 30s 内转移成功断言通过。** 具体化为一条验收测试：

- 拓扑：沿用 M0 金标准（zk virtual + master real SUT + workers，count 提升至 4 以含下标 3）
- 时间线执行（相对场景起始的调度器）：
  - `at: 10s` → `crash workers[3]`（实例级，InstanceControl 通路，M0 已交付分发）
  - `at: 20s` → `registry-flap zk`（M1 新实现：VirtualRegistry 内存会话闪断 5s）
  - `at: 25s` → `restart workers[3]`（实例恢复上线）
- 断言（YAML assertions 内置评估 + JUnit 双轨）：
  - `failoverWithin { seconds: 30 }`：计时起点＝`sim.fault-injected`（crash workers[3]），成功判定＝受影响任务在新实例首次状态回报（§11 基准）
  - `noTaskLost`：全部派发任务到达终态，无任务停留在 RUNNING 直至场景结束
  - `eventSequence [sim.fault-injected, sut.task-retry]`：时序断言 v0
- 通过条件：验收测试全绿 + M0 的 TierSwapAcceptanceTest 回归全绿（无破坏性变更）

## 2. 范围与不做

**做**（M0 遗留 + §14 M1 行）：

| # | 项 | 来源 |
| --- | --- | --- |
| 1 | 时间线剧本化执行（调度器：at 解析、串行触发、duration 到期自动 clear） | M0 遗留 |
| 2 | 行为字段全集：`failAt / neverReport / progress` + 标签/通配匹配（四级优先级：精确名 > 标签 > 通配 > default） | M0 遗留 + §9 |
| 3 | VirtualRegistry 的 `registry-flap`（内存会话闪断，声明进 supportedFaults） | §14 |
| 4 | `task-kill` 动作（终止进行中桩任务并触发 CANCELLED 回报，§10 M1 精确定义） | §10 |
| 5 | `custom-hook` 定义（注册接口 + 断言参与 + target 可指向 SUT 的边界，§7.2 豁免落地） | §14 |
| 6 | 事件流录制（JSON Lines 落盘，§11；真实时钟不承诺确定性重放——仅回放审查） | §11 |
| 7 | 断言库 v0：`failoverWithin / noTaskLost / eventSequence`（YAML 内置评估 + JUnit 编程式，双轨共用事实源） | §11 |
| 8 | 完整 §8 示例 YAML 升级为 M1 金标准 | 计划 v3 |

**不做**：embedded 档（Curator/H2/Fabric8 registry-flap 属 M2）、`@VirtualCluster` JUnit 扩展（M2）、控制面 REST/CLI（M3）、加速时钟（M4）、告警事件语义 `alertFired`（无告警组件，继续延后）、masterReelectedWithin（依赖 external SUT 旁路，M2 Curator 交付后才有真实场景）。

## 3. 任务分解（6 任务，估 8~10 天）

| # | 任务 | 内容与落点 | 完成判据 |
| --- | --- | --- | --- |
| T16 | 时间线执行器 | `duo-sim-scenario`：`TimelineScheduler`——场景 start 时刻为 t0，按 `at` 相对延时触发动作（经 ScenarioRuntime 既有通路）；`duration` 到期自动 clear（§7.2）；动作触发失败记 `sim.fault-inject-failed` 并计入场景结果（§12）；串行模型（M0 单 JVM 足够） | 单测：at 触发顺序、duration 自动 clear、失败事件；与 ScenarioRuntime 集成 |
| T17 | 行为字段全集 | `BehaviorProfile` 增 `failAt`（进度百分比处失败）、`neverReport`（领取后永不回报→僵尸任务）、`progress`（进度上报模式）；`BehaviorResolver` 支持标签与通配（`taskName: "spark-*"`、`label: etl`），四级优先级解析 | 单测：failAt 确定性失败点、neverReport 不产出终态、通配/标签匹配优先级矩阵 |
| T18 | registry-flap + task-kill | VirtualRegistry 实现 `FaultInjectable`（supportedFaults=[registry-flap]，声明进元数据——§7.5 一致性校验自动通过）：flap 期间会话全部临时失效（watch 收到 DELETED），恢复后需重新注册——SUT 侧"重选主/重新注册"可观测；`task-kill` 动作：VirtualWorker/instance 级终止执行中任务 → 回报 CANCELLED（复用 TaskCancel 通路） | 单测：flap 期间发现查询为空、恢复后可重注册；task-kill 后 CANCELLED 回报 |
| T19 | 事件录制 | `duo-sim-scenario`：`EventRecorder`——事件总线订阅 → JSON Lines 落盘（`build/scenarios/<name>/events.jsonl`）；场景结束 flush；JUnit 断言可读回 | 单测：落盘格式、回读等价于内存事件流 |
| T20 | 断言库 v0 | kernel 新 `assert` 包：`Assertion` 接口 + 三个实现（`failoverWithin`：起点=FaultInjected 事件/成功=新实例首报；`noTaskLost`：全部 dispatched 任务有终态；`eventSequence`：类型子序列匹配）；YAML assertions 内置评估（场景结果 pass/fail）+ JUnit 断言类双轨共用同一事件源 | 单测：正例/反例各一；YAML 评估与 JUnit 一致性 |
| T21 | custom-hook + M1 金标准 | `custom-hook` 注册接口（`HookRegistry.register(name, Consumer<HookContext>)`；HookContext 含 target 与事件门面——target 可为 SUT，§7.2 豁免）；hook 事实事件 `sim.hook-executed` 参与断言；完整 §8 示例 YAML（timeline 3 动作 + assertions 3 条）落 resources | 单测：hook 注册/触发/事件；T22 验收全绿 |

## 4. 验收测试（T22）

`FailoverAcceptanceTest`（examples 模块）：加载 M1 金标准 YAML → 校验（含 timeline 规则 6 全量）→ 启动 → 时间线自动执行 → `awaitSutExit` + 断言评估 → 全绿即 M1 验收通过。附加回归：M0 `TierSwapAcceptanceTest` 不变全绿。

## 5. 风险与对策

1. **时间窗与时序脆弱**：crash 10s/flap 20s/restart 25s 的相对时序在 CI 慢机上可能漂移。对策：验收断言窗口宽（30s）；时间线调度基于单调节拍器而非绝对睡眠；必要时把 at 值做成 YAML 可调。
2. **registry-flap 的 SUT 感知**：demo-scheduler 对会话失效的响应（重新注册）是新路径，M0 未演练。对策：T18 先以 Watch 事件单测验证，T21 场景集成验证。
3. **断言语义漂移**：`failoverWithin` 的"新实例"判定需关联 crash 前后 worker-3 的任务归属。对策：沿用 §11 已钉死的基准（起点=FaultInjected、成功=新实例首次回报），实现时以事件载荷 instance 字段核对。

## 6. 执行节奏

T16 → T17 → T18 → T19 → T20 → T21 → T22 顺序推进（T19/T20 可并行）；每任务收尾全量回归；T22 全绿即 M1 关闭。工期约 8~10 天（§14 估 ~2 周的上限内）。

**批准本计划后即开始 T16 编码。**
