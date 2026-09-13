# Duo M1 实施计划 —— 时间线注入与断言库（v3）

- 日期：2026-09-13（v3 修订）
- 依据：设计文档 v1.0（冻结）§14 M1 行；M0 已验收（commit 29b1a42）
- 范围：M0 遗留 8 项 + 设计文档 §14 M1 产出 + v2/v3 新增的验收前提项
- 状态：待用户批准（批准前不动代码）
- 修订记录见文末附录

---

## 1. 交付定义（唯一验收口径）

**完整 §8 金标准 YAML（含 timeline 与 M1 行为剧本）运行——`crash workers[3]` 于 T+10s 宕机 → `failoverWithin 30s` 断言通过，且为非空真通过。** 具体化为一条验收测试：

- 拓扑：M0 金标准 virtual 变体（zk virtual + master real SUT + workers virtual），`count: 4`，`capacity.slots: 4`
- **M1 行为剧本**：DAG 改为 4 条并行长任务（无相互依赖），**`duration: 15s`**——配合 T19 的派发选择修复（每 worker 恰好 1 条），保证 T+10s 时 workers[3] 确定持有在途任务；去掉 M0 的 `successRate: 0.0` unstable-task（crash 成为唯一失败源）
- **时序算术（结构性验证，P1-B 修订）**：T+0 4 任务各占 1 worker → 全忙至 ~T+15s；T+10s crash → workers[3] 任务回 PENDING，其余 3 feed 全忙 → **重派发等首批终态（~T+15s）后发生** → 重派发任务跑 15s → 终态 ~T+30s；断言死线 = fault-injected（T+10s）+ 30s = **T+40s，结构性余量 10s**（Thread.sleep 时长不随机器漂移，余量稳定）
- 时间线：`crash workers[3]` @10s → `registry-flap zk` @20s（flap 5s，结束于 25s）→ `restart workers[3]` @27s（与 flap 结束错开 2s，且早于 DAG 终态 ~T+30s，acceptor 存活可接重启实例）
- 断言（YAML 内置 + JUnit 双轨）：
  - `failoverWithin { seconds: 30 }`：起点＝`sim.fault-injected` 且 **payload.action=crash 过滤**（restart/flap 同样发该事件）；成功＝**crash 后发生重派发（attempt 递增的 dispatched）且该 taskId 随后到达 SUCCESS，重派发目标不得为 crash 时刻的 workers-3——`sim.worker-instance-restarted` 之后的 workers-3 视为不同实例（§7.1 全新实例语义），其回报计入转移成功**（判定式唯一，见 T20）
  - `noTaskLost { requireAllSuccess: true }`：全部任务 SUCCESS（FAILED/SKIPPED 判不通过）
  - `eventSequence [sim.fault-injected, sut.task-retry]`
  - `affectedTasksAtLeast: 1`（守护：crash 后归属 workers-3 的在途任务数 ≥ 1，防空真）
- 通过条件：验收测试全绿 + M0 TierSwapAcceptanceTest 回归全绿

## 2. 范围与不做

**做**（M0 遗留 8 项 + 验收前提）：

| # | 项 | 来源 |
| --- | --- | --- |
| 1 | 时间线剧本化执行（调度器 + duration 自动 clear + 最小 ScenarioResult） | M0 遗留 |
| 2 | 行为字段全集：`failAt / neverReport / progress` + 标签/通配匹配（四级优先级） | M0 遗留 + §9 |
| 3 | VirtualRegistry 的 `registry-flap`（端点快照重放 + flap 期间注册边界语义） | §14 |
| 4 | `task-kill` 动作（终止进行中桩任务 → CANCELLED 回报）。**覆盖范围注明：仅 T18 单测覆盖，M1 金标准 timeline 不含 task-kill，验收口径（§14 只要求 failover）不覆盖它** | §10 |
| 5 | **demo-scheduler 崩溃转移 + 派发选择修复**（worker 失联检测 → 在途任务重派发；**SlotReport 解析 + max-freeSlots/轮转派发选择**；事件补 instance、去重双发） | v2 新增、v3 扩scope |
| 6 | 事件流录制（JSON Lines） | §11 |
| 7 | 断言库 v0（failoverWithin/noTaskLost/eventSequence/affectedTasksAtLeast，双轨） | §11 |
| 8 | custom-hook（注册接口 + DSL 形态 + validator 豁免）+ M1 金标准 YAML | §14 |

**不做**：embedded 档（M2）、`@VirtualCluster`（M2）、控制面（M3）、加速时钟（M4）、`alertFired`（延后）、`masterReelectedWithin`（M2）、demo real worker 的 task-kill/转移演练（金标准钉 virtual 变体）。

## 3. 任务分解（8 任务，估 10~12 天）

| # | 任务 | 内容与落点 | 完成判据 |
| --- | --- | --- | --- |
| T16 | 时间线执行器 + ScenarioResult | `TimelineScheduler`（场景 start 为 t0，按 at 相对触发既有 ScenarioRuntime 通路；duration 到期自动 clear；串行节拍器模型）；最小 `ScenarioResult`：{注入失败数、断言评估结果}，引擎暴露 `result()`——T16/T20 共用 | 单测：at 顺序、自动 clear、失败计入 result |
| T17 | 行为字段全集 | `failAt`（进度百分比确定性失败）、`neverReport`（领取后不回报）、`progress`；resolver 四级匹配（精确 > 标签 > 通配 > default）。注明：标签匹配仅 resolver 单测覆盖（`TaskDispatch` 报文无 label 字段），端到端留后续 | 单测：failAt 失败点、neverReport 无终态、优先级矩阵 |
| T19 | **demo-scheduler：派发选择修复 + 崩溃转移**（v3 扩scope，先于 T18/T20） | **(a) 派发选择修复**：readFeed 解析 `SlotReport`（现被静默丢弃）维护调度侧每 feed freeSlots 视图（派发时本地递减 + SlotReport 权威刷新）；`pumpDispatches` 选 **freeSlots>0 且最大者，平局用轮转 cursor**（复用闲置的 dispatchCursor 字段）；**(b) 崩溃转移**：feed 丢失（readFeed IOException → feeds.remove）→ 在途任务重置 PENDING 重派发 + 发布 `sut.task-retry {instance: 旧}` + `sut.failover {task, from}`；**(c) 事件治理**：`sut.task-status/terminal` 补 instance 字段（线协议 TaskStatus 本就带 instanceName，readFeed 直接取用）；**去重 `sut.task-dispatched` 双发**（pumpDispatches 直发删掉，保留状态机 onDispatch 回调单一来源） | 单测：**4 任务 × 4 实例 → 各持恰好 1 条**（派发分布）；杀 feed 后在途任务重派发、事件含 instance/from；dispatched 事件无双发 |
| T18 | registry-flap（端点快照重放 + 边界语义） | flap 波及全部会话含 `__system__`：flap 期间所有临时节点失效（watch DELETED）、发现查询为空；**端点快照**（注册表内存保留）在 flap 结束时自动重建 `__system__` 并重放——对 SUT 透明。**flap 期间 registerEndpoint 的边界语义钉死：注册写入端点快照（快照即事实源），flap 结束统一按快照重建节点表——注册不丢、flap 期间发现返回空**（P3 采纳）。SUT 侧真实反应验证定位 M2 Curator 场景 | 单测：flap 期间查询空 + watch DELETED、恢复后端点自动重现、**flap 期间注册在恢复后可见** |
| T20 | 断言库 v0 | kernel `assert` 包：接口 + `failoverWithin`（**锚定：payload.action=crash 过滤；判定式唯一：crash 后 attempt 递增的重派发 + 该 taskId 随后 SUCCESS + 目标 ≠ crash 时刻 workers-3，重派发时间戳晚于 `sim.worker-instance-restarted` 的 workers-3 视为新实例**）/ `noTaskLost {requireAllSuccess}` / `eventSequence` / `affectedTasksAtLeast`；YAML 内置评估写入 ScenarioResult；JUnit 编程式包装落 `duo-sim-junit` 模块 | 单测：三断言正反例 + 守护断言 + 重启后 workers-3 作为目标通过的反向用例 |
| T21 | 事件录制 | `EventRecorder`：总线订阅 → JSON Lines（`build/scenarios/<name>/events.jsonl`）→ 场景结束 flush → 回读 API | 单测：落盘/回读等价 |
| T22 | custom-hook | `HookRegistry.register(name, Consumer<HookContext>)`；DSL 形态：timeline 动作 `custom-hook` 的 `params: {hook: 名称, ...透传}`，target 允许 SUT（validator 对 `action=custom-hook` 豁免 SUT 检查）；hook 执行发 `sim.hook-executed {hook, target}` 参与断言 | 单测：注册/触发/事件/validator 豁免 |
| T23 | M1 金标准 + 验收测试 | 金标准 YAML（§1 全部要素：4 并行 `duration: 15s` 任务、timeline 3 动作、assertions 4 条、duration 带单位）；`FailoverAcceptanceTest`：校验→启动→时间线自动执行→断言驱动等待→ScenarioResult 全绿；M0 TierSwap 回归 | **全绿＝M1 验收通过** |

## 4. 风险与对策

1. **时序漂移**：相对时序在慢 CI 上挤压。对策：断言窗口 30s，时序算术留 **10s 结构性余量**（§1，Thread.sleep 不漂移）；任务时长与 at 值 YAML 可调。
2. **flap/restart 窗口竞争**：已错开（flap 结束 25s，restart 27s）。
3. **转移判定数据完整性**：T19 (c) 补 instance（线协议现成字段）+ T20 判定式唯一化 + `affectedTasksAtLeast` 守护，三层防护。
4. **SUT 改造范围**：T19 = 派发选择修复 + 崩溃转移 + 事件治理三项，估计 ~200 行（v2 的 ~100 行上调，P1-A 采纳）。

## 5. 执行节奏

T16 → T17 → **T19** → T18 → T20 → T21 → T22 → T23（T19 提前：T20 的断言依赖其数据契约与派发分布）；每任务全量回归；T23 全绿即 M1 关闭。**工期 10~12 天。**

---

## 附录：修订记录

- **v1（2026-09-13）**：初稿 6 任务。
- **v2（2026-09-13）**：依评审修订——新增 T19 崩溃转移、金标准 4 并行长任务 + 守护断言、flap 端点快照重放（方案一）、noTaskLost=requireAllSuccess、custom-hook DSL 形态、ScenarioResult 归 T16、P3 全落。
- **v3（2026-09-13）**：依 v2 代码级复审修订——
  1. **[P1-A] T19 扩scope 派发选择修复**：v2 的"4 并行任务保证 workers[3] 持有在途任务"不成立——busy CAS 仅派发瞬间生效、SlotReport 被静默丢弃、无真实负载均衡，4 任务会全部落到同一 worker。修复：readFeed 解析 SlotReport 维护 freeSlots 视图 + max-freeSlots/轮转选择；完成判据加"4 任务 × 4 实例 → 各持恰好 1 条"；SUT 改造估计上调 ~200 行；
  2. **[P1-B] duration 30s→15s 修时序算术**：30s 任务下重派发需等首批终态（~T+30s）再跑 30s → 终态 ~T+60s，结构性超出 T+40s 死线。15s 后重派发 ~T+15s、终态 ~T+30s、余量 10s；§1 补完整时序算术，风险 1 对策同步；
  3. **[P2] failoverWithin 判定式唯一化**：采纳"crash 时刻实例排除 + 重启事件后的 workers-3 视为新实例"（时间戳比对 `sim.worker-instance-restarted`），消除 §1 内"instance ≠ workers-3"与"重启后计入"的自相矛盾；T20 锚定补 payload.action=crash 过滤（restart/flap 同发 fault-injected）+ 反向用例；
  4. **[P3] 四项**：T19 去重 `sut.task-dispatched` 双发（保留状态机回调单一来源）；T18 钉死 flap 期间 registerEndpoint 边界（写快照、恢复后可见、flap 期间发现为空）+ 边界单测；范围表注明 task-kill 仅单测覆盖；事件治理利用线协议 TaskStatus 现成 instanceName（比 v2 预想更便宜）。
