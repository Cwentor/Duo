# Duo M1 实施计划 —— 时间线注入与断言库（v2）

- 日期：2026-09-13（v2 修订）
- 依据：设计文档 v1.0（冻结）§14 M1 行；M0 已验收（commit 29b1a42）
- 范围：M0 遗留 8 项 + 设计文档 §14 M1 产出 + v2 新增的验收前提项
- 状态：待用户批准（批准前不动代码）
- 修订记录见文末附录

---

## 1. 交付定义（唯一验收口径）

**完整 §8 金标准 YAML（含 timeline 与 M1 行为剧本）运行——`crash workers[3]` 于 T+10s 宕机 → `failoverWithin 30s` 断言通过，且为非空真通过。** 具体化为一条验收测试：

- 拓扑：M0 金标准 virtual 变体（zk virtual + master real SUT + workers virtual），`count: 4`
- **M1 行为剧本（重定义，非沿用 M0）**：DAG 改为 4 条并行长任务（无相互依赖），`duration: 30s`——保证 T+10s 时 workers[3] **确定持有在途任务**；去掉 M0 的 `successRate: 0.0` unstable-task（crash 成为唯一失败源，不污染断言）
- **前置守护断言 `affectedTasksAtLeast: 1`**：crash 事件后，归属 workers-3 的在途任务数 ≥ 1——防止 `failoverWithin` 空真通过
- 时间线：`crash workers[3]` @10s → `registry-flap zk` @20s（flap 5s，结束于 25s）→ `restart workers[3]` @27s（**与 flap 结束错开 2s**）
- 断言（YAML 内置 + JUnit 双轨）：
  - `failoverWithin { seconds: 30 }`：起点＝`sim.fault-injected`（crash workers[3]）；成功＝受影响任务重新派发至 instance ≠ workers-3 且随后到达终态（从 `sut.task-dispatched` + `sut.task-terminal` 事件推导，见 T20 数据契约）；workers-3 于 27s 重启后属"全新实例"（§7.1），其回报**计入**转移成功（新实例语义）
  - `noTaskLost { requireAllSuccess: true }`：**M1 口径钉死为全部 SUCCESS**（FAILED/SKIPPED 视为失败结局，判 noTaskLost 不通过）
  - `eventSequence [sim.fault-injected, sut.task-retry]`
- 通过条件：验收测试全绿 + M0 TierSwapAcceptanceTest 回归全绿

## 2. 范围与不做

**做**（M0 遗留 8 项 + 验收前提）：

| # | 项 | 来源 |
| --- | --- | --- |
| 1 | 时间线剧本化执行（调度器 + duration 自动 clear + **最小 ScenarioResult 模型**） | M0 遗留 |
| 2 | 行为字段全集：`failAt / neverReport / progress` + 标签/通配匹配（四级优先级） | M0 遗留 + §9 |
| 3 | VirtualRegistry 的 `registry-flap`（含端点恢复机制，见 T18 二选一钉死） | §14 |
| 4 | `task-kill` 动作（终止进行中桩任务 → CANCELLED 回报） | §10 |
| 5 | **demo-scheduler 崩溃转移路径**（worker 失联检测 → 在途任务重派发 + `sut.task-retry` + 事件补 instance 字段） | **v2 新增（P1-1）** |
| 6 | 事件流录制（JSON Lines） | §11 |
| 7 | 断言库 v0（三断言双轨）+ `affectedTasksAtLeast` 守护断言 | §11 |
| 8 | custom-hook（注册接口 + DSL 形态 + validator 豁免）+ M1 金标准 YAML | §14 |

**不做**：embedded 档（M2）、`@VirtualCluster`（M2）、控制面（M3）、加速时钟（M4）、`alertFired`（无告警组件，继续延后）、`masterReelectedWithin`（M2 Curator 后）、**demo real worker 的 task-kill/转移演练**（金标准钉死 virtual 变体；real 变体的行为剧本仍生效但故障路径不在 M1 验收范围）。

## 3. 任务分解（8 任务，估 10~12 天）

| # | 任务 | 内容与落点 | 完成判据 |
| --- | --- | --- | --- |
| T16 | 时间线执行器 + ScenarioResult | `TimelineScheduler`（场景 start 为 t0，按 at 相对触发既有 ScenarioRuntime 通路；duration 到期自动 clear；串行节拍器模型）；**最小 `ScenarioResult`**：{注入失败数、断言评估结果}，引擎暴露 `result()`——T16/T20 共用，不各写一份 | 单测：at 顺序、自动 clear、失败计入 result |
| T17 | 行为字段全集 | `failAt`（进度百分比确定性失败）、`neverReport`（领取后不回报）、`progress`；resolver 四级匹配（精确 > 标签 > 通配 > default）。**注明**：标签匹配仅 resolver 单测覆盖——`TaskDispatch` 报文无 label 字段，端到端留后续（P3 采纳） | 单测：failAt 失败点、neverReport 无终态、优先级矩阵 |
| T18 | registry-flap（含端点恢复钉死） | **采用方案一**：flap 波及**全部会话含 `__system__`**——flap 期间所有临时节点失效（watch 收 DELETED）、发现查询为空；flap 结束时 VirtualRegistry 自动重建 `__system__` 并**重放此前注册的端点**（注册表在内存中保留端点快照）——对 SUT 透明，无需 demo-scheduler 参与恢复；25s restart 的 workers 发现不受影响。SUT 侧可观测面＝watch DELETED 事件与 flap 期间的查询空结果（M1 flap 定位为注入通路 + registry 语义验证；真实 SUT 反应验证属 M2 Curator 场景）。**SUT 可改的替代场景**（demo-scheduler 经 `openSession` 持自有会话）留 M2 设计 | 单测：flap 期间查询空 + watch DELETED、恢复后端点自动重现 |
| T19 | demo-scheduler 崩溃转移（**P1-1，先于 T20**） | worker 连接丢失（readFeed IOException → feeds.remove）→ 触发状态机**在途任务重置 PENDING 并重派发** + 发布 `sut.task-retry {instance: 旧}`；同时 **`sut.task-status` / `sut.task-terminal` 载荷补 `instance` 字段**（T20 数据契约，P2-4 采纳）；失败转移事实 `sut.failover {task, from}` | 单测：杀 feed 后在途任务重派发、事件含 instance/from |
| T20 | 断言库 v0 | kernel `assert` 包：接口 + `failoverWithin`（归属核对＝crash 后 dispatched 事件的 instance ≠ workers-3 且该 taskId 随后 terminal——**从 dispatched+terminal 推导，不要求 status 事件自带归属**，若 T19 已补 instance 字段则直接用）/ `noTaskLost {requireAllSuccess}` / `eventSequence` / `affectedTasksAtLeast`（守护）；YAML 内置评估写入 ScenarioResult；**JUnit 编程式包装落 `duo-sim-junit` 模块**（kernel 只放接口与内置评估，P3 采纳） | 单测：三断言正反例 + 守护断言空真防护 |
| T21 | 事件录制 | `EventRecorder`：总线订阅 → JSON Lines（`build/scenarios/<name>/events.jsonl`）→ 场景结束 flush → 回读 API | 单测：落盘/回读等价 |
| T22 | custom-hook | `HookRegistry.register(name, Consumer<HookContext>)`（HookContext 含 target+事件门面）；**DSL 形态钉死**：timeline 动作 `custom-hook` 的 `params: {hook: 名称, ...透传}`，target 允许 SUT（validator 对 `action=custom-hook` 豁免 SUT 检查，§7.2）；hook 执行发 `sim.hook-executed {hook, target}` 参与断言 | 单测：注册/触发/事件/validator 豁免 |
| T23 | M1 金标准 + 验收测试 | 金标准 YAML（§1 全部要素：4 并行长任务剧本、timeline 3 动作、assertions 4 条含守护、duration 带单位 `30s/10s/20s/27s`，loader 校验单位）；`FailoverAcceptanceTest`：校验→启动→时间线自动执行→**断言驱动等待（替代 sleep）**→ScenarioResult 全绿；M0 TierSwap 回归 | **全绿＝M1 验收通过** |

## 4. 风险与对策

1. **时序漂移**：10s/20s/27s 相对时序在慢 CI 上挤压。对策：断言窗口 30s 宽裕；任务时长 30s 保证 crash 时刻必有在途任务；at 值 YAML 可调。
2. **flap 期间 restart 的窗口竞争**：已错开（flap 结束 25s，restart 27s）。
3. **转移判定的数据完整性**：T19 补 instance 字段 + T20 推导逻辑双保险；`affectedTasksAtLeast` 守护断言防空真。
4. **SUT 改造范围**：T19 只动 DemoScheduler（feed 移除回调 + 事件字段），状态机复用既有 onStatus 重试路径，~100 行内。

## 5. 执行节奏

T16 → T17 → T19 → T18 → T20 → T21 → T22 → T23（T19 提前于 T18/T20，因 T20 依赖其数据契约）；每任务全量回归；T23 全绿即 M1 关闭。**工期 10~12 天（≈2 周上限，P3 采纳）。**

---

## 附录：修订记录

- **v1（2026-09-13）**：初稿 6 任务。
- **v2（2026-09-13）**：依评审修订——
  1. **[P1] 新增 T19 demo-scheduler 崩溃转移**：feed 丢失 → 在途任务重派发 + `sut.task-retry` + 事件补 instance 字段；执行顺序提前至 T20 前；
  2. **[P1] M1 金标准行为剧本重定义**：4 条并行 30s 长任务、去掉 unstable-task 概率失败、新增 `affectedTasksAtLeast` 守护断言防空真；
  3. **[P1] flap 端点恢复钉死方案一**：波及全部会话含 `__system__`，恢复时端点快照自动重放（对 SUT 透明）；restart 错开至 27s；SUT 反应验证定位 M2；
  4. **[P2] failoverWithin 归属判定**：dispatched+terminal 推导（T19 补字段后直接用），写进 T20；
  5. **[P2] noTaskLost 钉死 requireAllSuccess**；金标准去掉 unstable-task；
  6. **[P2] custom-hook DSL 形态**（params.hook 引用 + validator 豁免）写进 T22；
  7. **[P2] 最小 ScenarioResult 归 T16** 交付；
  8. **[P3] 全落**：标签匹配覆盖范围注明、金标准钉 virtual 变体、workers-3 重启计新实例、duration 单位统一 `s` 并校验、断言库 kernel/junit 分工、工期上调 10~12 天。
