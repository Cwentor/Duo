# Duo 架构深化（Architecture Deepening）设计方案

- 日期：2026-10-24（立项同日开工）
- 状态：**实施中**——10 张卡按序推进，卡 1 已完成（`2d1db7b`）、卡 2 代码完成待全量验证、卡 3 设计冻结；实施进度以配套计划的状态行与勾选为准
- 来源：2026-10-24 架构体检（dev-improve-architecture 流程：近 4 个月提交热点圈定 → 控制面/场景编排链/组件与协议三路走查 → 关键证据人工核对）。体检报告为会话外草稿（不入库），其 10 张候选卡的结论全部内嵌于本文 §2，本文即唯一权威来源
- 实施计划：[`docs/superpowers/plans/2026-10-24-duo-architecture-deepening-plan.md`](../plans/2026-10-24-duo-architecture-deepening-plan.md)

---

## 1. 背景与目标

体检发现 10 个候选项，其中 4 个**行为已分叉/潜在缺陷**（实证）、5 个**结构摩擦**（改动成本与漏修风险）、1 个**查过判定不动**（删除测试未通过）。本设计把它们按「深模块」原则逐卡修复：

- **深度**：接口小而行为多。守卫阶梯、判定表、投影、样板各只存在一处。
- **局部性**：安全口径、语义修复、并发教训一次修正处处生效（G10 漏接、L336 未掩码、generation 教训未回流，都是局部性缺失的实证）。
- **测试面**：编排、事件日志、判定表、装配核心第一次拥有不启整个场景的单元测试面；既有契约测试（HTTP 接缝、组件接缝）作为行为保持的裁判，**零改动全绿**是每卡的硬门槛。

**总验收**：整 reactor 测试全绿 + `-Dquality` 8 模块零告警 + 行为变化全部有测试钉住 + 文档同步（ARCHITECTURE/模块 README/DECISIONS）。

## 2. 候选卡总表（体检结论 + 状态）

| # | 名称（领域命名） | 强度 | 摩擦本质 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | 控制面 REST 端点管道 | Strong | 15 端点各自手写守卫阶梯，安全口径已分叉（L336 未掩码） | ✅ 已完成 `2d1db7b` |
| 2 | 场景编排引擎（ScenarioEngine） | Strong | 790 行 24 方法 9 职责，接口面≈实现面，无单元测试面 | 🔶 代码完成，全量验证中 |
| 3 | 接线判定表 | Strong | §6.1 判定表两处平行实现；运行期三查缺第②③查 | ✅ 已完成 `13be711`+`df1b06a`+`7187228` |
| 4 | 虚拟调度器服务端循环 | Strong | 两档 scheduler 连接循环近逐字重复，G10 修复已漏同步 | ✅ 已完成 `8e9cdb3` |
| 5 | 组件停止/重启收尾纪律 | Strong | 同形状竞态只在 scheduler 修过；worker 槽位归还无代次守卫 | ⏳ 未开始 |
| 6 | 输入信任档 | Worth exploring | 三个镜像枚举 + 一处绕过映射的第四入口 | ⏳ 未开始 |
| 7 | 事件流消费投影 | Worth exploring | Event→Map/JSON 投影手抄 5 处；增量读取整表拷贝 | ⏳ 未开始（依赖卡 2） |
| 8 | 故障动作样板与行为执行块 | Worth exploring | worker/engine 逐字级复制（resolveSlowFactor ×2、BehaviorProfile 拷贝 ×3） | ⏳ 未开始 |
| 9 | 控制面观测读面 + CLI 双模 | Worth exploring | Map 即接口，host 接口随端点线性变宽；CLI 7 命令同构分支 | ⏳ 未开始（依赖卡 1/7） |
| 10 | 注册中心门面（ZkBackedRegistry） | Speculative | 删除测试**未通过**：剩余为真实双面复杂度 | ⏳ 落档不拆（D17） |

**已核对实证**（2026-10-24 人工验证，行号以当日工作区为准）：`"method not allowed"` ×10、409 守卫 ×7（RestControlServer 977 行）；`onDispatchRolledBack` 全库仅 VirtualScheduler:340 调用（DemoScheduler 漏接）；三枚举 `Trust{CONFIG,EXTERNAL_INPUT}`/`InputPolicy{CONFIG,EXTERNAL_INPUT}`/`InputTrust{CONFIG,EXTERNAL}`（值名都不一致）；`/inject` 400 分支 `String.valueOf(e.getMessage())` 未过 `sanitizeReason`（L336）；`supportedFaults` 仅 ScenarioValidator:300 静态检查、ScenarioRuntime 只有注释声称三查；worker `restartInstance` 直接 `freeSlots.set(totalSlots)` 无代次守卫而 engine 已用 generation（L87/454-498）。

## 3. 全局纪律（所有卡默认隐含）

1. **决策台账不动摇**：D10/D11（信任模型与输入分层）、D12（句柄归属）、D13–D16 全部有效；无候选与 D1–D16 冲突。
2. **核心 REST 端点签名冻结**（D16）：`/scenario`、`/inject`、`/events` 等既有端点的路径/方法/响应形状/错误码不变；控制台专属面在 `/api/` 下演进。
3. **内核符号纪律**：控制面改动零内核改动；卡 3/7 允许 kernel 内改动，但新增符号必须服务 scenario/components 的真实需要（判定表、事件投影），不得是仅控制面需要的符号。
4. **测试即裁判**：每卡以既有契约测试零改动全绿为行为保持判据；行为变化（如新增校验、统一 405）必须补测试钉住并在提交信息说明。
5. **构建纪律**：每卡收尾跑整 reactor（本地 `-o`）；收尾跑 `-Dquality` 门禁；提交信息按实测数字。
6. **词汇纪律**：模块/接口/接缝/深度/局部性（dev-codebase-design）；领域名词（契约/档位/场景/SUT/接线/组件）沿用 CONTEXT 既有语言。

---

## 4. 分卡设计

### 卡 1 · 控制面 REST 端点管道 ✅ 已完成

**Problem**：15 个端点全部内联在 RestControlServer（977 行），每端点手写「guard→405→409→readBody→catch 阶梯→手拼 Map」；`/api/scenarios` 单 lambda 124 行，路径段解码+白名单在预检循环后又各分支重复执行；`/inject` 400 分支未过 sanitizeReason（终审 M-1 记账的历史遗留），安全口径修正需人工核对 15 处。

**设计（已落地）**：
- 新增 `rest/RestRoutes`（管道）：D10 三层防线的执行点 + 方法检查（405）+ 异常→HTTP 错误映射总表（EndpointError→自带码；BodyTooLarge→413 有界排空；NoSuchFile→404；Jackson/IAE→400 **一律掩码**；ISE→405；IOException→500；其余→500 摘要）+ `sanitizeReason` 唯一实现。守卫/校验/掩码自此结构性只此一份。
- 新增 `rest/HttpCall`（端点调用上下文）：`body()`（带上限读取）、`query/pathTail/method`、`ok/json/text/error/require`（require/error 抛 EndpointError 由管道统一响应）。
- RestControlServer 977→505 行：端点=「一条注册+业务分支」；`POST /scenario` 的 ISE→409 例外、`/events`/`/diagnose` 的 500 专用文案以 EndpointError 局部表达，管道总表注释里逐条登记例外。

**行为变化（有意，已提交说明）**：`/assertions`、`/topology`、`/api/capabilities`、`/api/meta` 原不校验方法（POST 也 200），统一为 405（对齐 T33「方法不匹配→405」口径）。

**验证**：duo-sim-control 46 测（RestConsoleApiTest 15、RestControlServerTest 14——HTTP 接缝零改动）+ duo-sim-examples 全绿（含 SecurityRemediation/ControlPlaneAcceptance）。

### 卡 2 · 场景编排引擎拆分 🔶 代码完成，全量验证中

**Problem**：ScenarioEngine 790 行、24 个 public 方法、9 类职责；实例化代码块在 startComponents 与 startDirectDependencies 重复两份；`metadataLookup` 对 specById 线性扫描 O(n²)；无 ScenarioEngineTest，编排本体只能端到端覆盖；stop() 4 处告警双写；NodeSpec 判定私有副本与校验器逐行相同。

**设计（已落地，公开接口面零变化——junit/control/examples 调用方零改动）**：引擎拆三个内部模块（包内可见，外部接缝不变）：
- `EventJournal`——内存事件流（有界+锁）+ JSONL 录制 + 世代闸门 + 收尾三告警；`events()/eventCount()/droppedEvents()/recordingPath()/epoch()/withEpochGuard()/finish()`。卡 7 的投影与增量读取在此接缝上续作。
- `ComponentBootstrapper`——SUT 双形态启动与拓扑序启动共用装配核心：`instantiate()` 单点（provider 解析→config/capacity/count 合并→[behaviors 展平]→[接线解析]→init）；`metadataIndex()` 按 (contract,tier) 小写一次解析 O(1) 查表；SUT 端点表/主类守卫/命令行切分归属于此。**语义保持点**：SUT 直接依赖不展平 behaviors、接线表为空（既有差异，非遗漏）。
- `NodeSpecs`——launchMode/isExternal/firstExposedPort 唯一一份（引擎与校验器共用，私有副本删除）。
- 引擎本体：薄编排（validated→startSut→startComponents→stop）+ `warn()` 助手收拢告警双写；`specById` 字段删除（bootstrapper 内聚）。

**新增测试面**：EventJournalTest（2：单订阅点落流、世代过期放弃写盘并告警）、ComponentBootstrapperBehaviorsTest（3：default/by-label/named 前缀、default 优先、未匹配零贡献）；ScenarioEngineCommandTest 随接缝迁至 ComponentBootstrapperCommandTest（5 例不变）。

**验证**：duo-sim-scenario 60 测全绿；整 reactor 验证进行中（结果见计划状态行）。

### 卡 3 · 接线判定表收敛 + 运行期三查 ⏳ 设计冻结

**Problem**：§6.1 接线判定表在 ScenarioValidator 规则 2/3（L159–206，收集错误）与 WiringResolver（L38–129，抛异常）两处平行实现，形状各异；**校验器漏掉「未知 path 值」行**（垃圾 path 过静态校验、启动期才炸）；§7.2 注入三查只实现第①查（precheck：SUT/未知 target/下标范围），第②查（supportedFaults）仅静态侧、运行期热注入不复查，第③查（instanceControl）靠 dispatch 抛异常兜底——ScenarioRuntime 类注释自称三查，实现不符。

**设计**：
- kernel `WiringResolver` 新增判定表唯一权威：

```java
/** §6.1 判定表的结构化失败（两侧各自格式化消息，判定逻辑唯一）。 */
public enum SlotFailure { UNKNOWN_PATH, WIRE_WITHOUT_ENDPOINT, DIRECT_EXTERNAL_CONSUMER, DIRECT_NOT_SUPPORTED }

public record SlotDecision(ComponentContext.ConnectionPath path, SlotFailure failure) {
    public static SlotDecision ok(ComponentContext.ConnectionPath path) { ... }
    public static SlotDecision fail(SlotFailure f) { ... }
}

/** 唯一权威：显式 path 校验/缺省推断（端点形态→wire 否则 direct）+ direct 双前置。 */
public static SlotDecision decide(boolean consumerExternal, String explicitPath,
                                  boolean targetHasEndpoint, boolean targetInterfaceDirect)
```

- `resolveNode` 消费 `decide()`（含 wire 端点地址/direct 绑定两项**运行期**查找，留在 resolver）；异常文案保持现状（既有测试断言不变）。
- 校验器规则 2/3 消费 `decide()`，错误文案保持现状；**补 UNKNOWN_PATH 行**（静态校验期拒绝垃圾 path，新测试钉住）。
- 运行期三查补齐：`ScenarioRuntime.Target` 扩为 `(component, componentId, instanceCount, Set<String> supportedFaults, boolean instanceControl)`（构造点 4 处：ComponentBootstrapper ×2 传 `provider.metadata()`、TimelineSchedulerTest、ScenarioRuntimeTest ×3 同步更新）；`precheck` 增第②查（非 crash/restart 生命周期动作须在 supportedFaults）与第③查（带下标须 instanceControl）；`clear()` 增第②查。失败文案与校验器规则 6 同风格。

**验证**：kernel 新增 `WiringDecisionTest`（判定表每行 + 缺省推断）；ScenarioValidatorTest 补未知 path 用例；ScenarioRuntimeTest 补热注入不支持动作/带下标无 instanceControl 的早失败用例；既有 WiringResolverAndManagerTest 10 例、ScenarioValidatorTest 20 例零改动全绿。

### 卡 4 · 服务端连接循环共享（两档 scheduler 同源） ⏳

**Problem**：「accept→注册→读帧分发→失联处理」在 VirtualScheduler（L276–361）与 DemoScheduler（L167–254, 484–533）近逐字重复，共享 SchedulerStateMachine 并未阻止调用点分叉——**G10 修复 `onDispatchRolledBack` 只在 VirtualScheduler 接线，DemoScheduler 漏接，两档槽位回滚语义已分叉**；VirtualEngine（L334–384）是第三变体（首帧不同）；测试侧 FakeWorker/FakeScheduler 重演协议对话约 5 份。

**设计**：components 新增共享服务端连接循环（内部接缝，不改 VirtualComponent 接口）：
- `io.duo.sim.components.server.DuoPortServer`：`accept 循环 + 连接生命周期（registered/lost）+ 读帧分发回调 + closeAll join barrier`；回调接口含 `onDispatchRolledBack`（G10 类钩子从循环侧统一接线，漏接在结构上不可能）。
- VirtualScheduler 与 DemoScheduler 都改为消费 DuoPortServer，各自只留分发语义与业务编排；**DemoScheduler 的参考 SUT 教学身份保留**——共享的是协议机制，业务编排仍手写（与 M5 第 4 轮「两档同源状态机」决策同向）。
- VirtualEngine 变体按首帧差异参数化接入或保持独立（实施第一步先做两处循环的逐行差异清单，差异清单决定接入深度——**真实差异保留为各自实现，伪差异（break vs return 类）收敛**）。
- closeAll 语义沿用 scheduler 已修的 join barrier（卡 5 同款纪律）。

**验证**：VirtualSchedulerTest 11 例零改动全绿；新增「派发失败回滚槽位」跨两档的一致性用例（各一条）；DemoScheduler 侧补同款守卫。

### 卡 5 · 组件收尾纪律（generation/join parity） ⏳

**Problem**：stop/restart 的线程收尾纪律各组件各写各的：VirtualScheduler closeAll 逐线程 join（近期 `1148cb8` 修复），engine/worker 不 join（同形状竞态裸露）；engine 已用 generation 守卫槽位归还（L87/166/454–498），worker `restartInstance`（L541–550）无守卫直接 `freeSlots.set(totalSlots)`+`running.clear()`，旧任务线程 finally 仍 `incrementAndGet` → 槽位可超 totalSlots。

**设计**：
- worker：InstanceState 增 per-instance `AtomicLong generation`；`restartInstance` 先 `incrementAndGet` 再重置槽位；任务线程启动时捕获 gen，finally 归还槽位前比对 gen，过期不归还（engine 同款教训回流）。
- worker/engine 的 `stop()`：实例线程按 scheduler closeAll 同款 join barrier（每线程有界等待）收尾，消除「同形状竞态只修过一处」。
- **先复现后修**：用 freeze 门构造确定性复现（冻结实例→派发任务→restartInstance→解冻→断言槽位 ≤ totalSlots），红→绿各留档。

**验证**：复现测试转绿；VirtualWorkerTest 16 例、VirtualEngineTest 12 例零改动全绿；已知 flaky（VirtualWorkerTest CME，夹具已修）不复发。

### 卡 6 · 输入信任档三枚举收敛 ⏳

**Problem**：同一概念三个镜像枚举（control `Trust`/engine `InputPolicy`/validator `InputTrust{CONFIG,EXTERNAL}`——值名都不一致），手工映射 2 处、运行期散查 2 处、`ScenarioLibrary` 第 4 入口绕过映射直连。加一档（D11 已预留 EXTERNAL_TRUSTED 触发条件）要改 3 枚举+2 映射+2 散查。

**设计**：收敛为 scenario 顶层 `io.duo.sim.scenario.InputTrust`（值 `CONFIG, EXTERNAL_INPUT`），三处枚举全部删除、调用方（ScenarioHost/DuoCli/RestControlServer/ScenarioLibrary/engine.validated/validator 内部）直接传递；`EXTERNAL`→`EXTERNAL_INPUT` 更名对齐。删除测试通过（纯转发层消失，复杂度不在调用点冒头）。D11 的分层语义不变——收敛的是类型，不是信任边界。

**验证**：SecurityRemediationAcceptanceTest 等既有信任档测试零改动全绿；全库 grep 无三旧枚举名残留；ARCHITECTURE §12.1 表述同步。

### 卡 7 · 事件流消费投影统一 + 增量零拷贝 ⏳（依赖卡 2）

**Problem**：`{type,sourceId,timestamp,payload}` 投影手抄 4 处（ScenarioHost.eventsSince、RestControlServer.reportToJson、EventRecorder.toJsonLine、MetricsCollector）+ 反向 1 处（FaultDiagnostics）；`events()` 每次全表 `List.copyOf`，控制台轮询高频路径 O(n) 分配。

**设计**：
- kernel 新增 `io.duo.sim.kernel.api.EventProjections.toMap(Event)`（纯 Map 投影，无 Jackson——kernel 零第三方依赖不破）；四个正向消费方全部改用（场景库/控制面/指标共用一份投影语义）。
- `EventJournal.eventsSince(int since)`：锁内 `List.copyOf(recorded.subList(since, size))`——只拷贝增量 O(new)；`ScenarioHost.eventsSince` 改走 journal 增量 + 投影，不再全表快照。M3 D3 的 1-based 下标语义不变（快照下标稳定性的前提是只追加，锁内切片与之等价）。
- `events()` 全量快照保留（断言评估/诊断用），不在高频路径。

**验证**：`/events` 契约（next=since+size、不重不漏）既有测试零改动全绿；新增 journal.eventsSince 增量语义单测。

### 卡 8 · 故障动作样板与行为执行块收敛 ⏳（与卡 5 同区）

**Problem**：frozen/slow/resourceExhausted 三 AtomicBoolean + inject/clear switch + 事件发布在 VirtualWorker（L577–668）与 VirtualEngine（L258–546）近似复制（`resolveSlowFactor` 10 行逐字相同 ×2）；`BehaviorProfile` 8 参逐字段拷贝、emitLogs、reject 各抄 3 份（worker/engine/DemoRealWorker）。加一个新故障动作至少改 2 类 4 处。

**设计**：components 新增共享实现（包内接缝）：
- `FaultState`——组件级故障态深实现：三态 + slow 因子 + `apply(FaultAction, boolean inject)`（switch 与事件发布唯一一份，事件名前缀由构造参数注入，`sim.worker-frozen`/`sim.engine-frozen` 等事件名不变）；worker/engine 委托。
- 行为执行块工厂——`BehaviorProfile` 拷贝/emitLogs/reject 收敛为 components 内共享静态工厂（DemoRealWorker 在 examples，经 components 既有依赖消费）。

**验证**：VirtualWorkerTest 16 / VirtualEngineTest 12 零改动全绿（事件名与语义即契约）；故障支持矩阵（§5.4 注）不变。

### 卡 9 · 观测读面收敛 + CLI 双模命令表 ⏳（依赖卡 1/7）

**Problem**：ScenarioHost 577 行 27 方法，观测面直接返回手拼 JSON Map，Event→Map 投影与 REST 层重复；host 接口随端点线性变宽（M10 加清除端点先加 2 方法）；DuoCli 7 个命令各自手写「有 --url 走 HTTP / 否则本地调用」分支 ×8。

**设计**：
- 观测读面：`eventsSince`/诊断链的事件投影改走卡 7 的 `EventProjections`（Map 形状=REST 契约，保持不变——收敛的是构造处，不是形状）；`status/assertions/topology` 的形状维持现状（它们本身就是深接口，报告已确认生命周期侧是深模块）。
- DuoCli：双模分派收敛为一个助手（`dualMode(remoteSupplier, localCall)`：有 --url 时发送+打印状态码与响应体，否则本地调用），7 个命令的分支删除；命令注册表化（名称→处理器）顺带完成。

**验证**：DuoCliTest（examples）与 control 契约测试零改动全绿；「加一个控制台功能」的成本从 4 处（Server+Host+测试+前端）缩到 2 处（声明+前端）作为完工判据在计划任务中核对。

### 卡 10 · 注册中心门面：查过，不拆（D17 落档） ⏳

**体检结论**：ZkBackedRegistry（375 行）三关切一类是**真实双面复杂度**——M4 T38 已抽取过一轮，删除测试未通过（硬拆在两个子类重新冒头）。唯一的「修复」是把「查过、判定不动、何时重开」写进决策台账，防止未来体检重复提出。

**落档**：DECISIONS.md 新增 D17（不拆；触发条件：出现第二个 embedded 契约实现时，门面/wire 分离才有第二个 adapter 证明接缝真实）+ §3 对照表行 + 修订记录。

---

## 5. 顺序与依赖

- 1 → 9（9 复用 1 的管道）；2 → 7（7 在 EventJournal 接缝上续作）；5 与 8 同区同文件（VirtualWorker/engine），按 5→8 顺序执行避免冲突；3、4、6 相对独立。
- 全部完成后收尾：整 reactor + `-Dquality` 门禁 + 文档同步（ARCHITECTURE §6.1「两处执行语义一致」段简化为判定表唯一权威、§8 运行期三查表述补实、§12.1 Trust 枚举名、§12.2 错误映射指向管道、模块 README 测试数）+ 决策台账 D17。

## 6. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 行为保持回归（REST 错误码全表/事件名/判定表消息） | 既有契约测试零改动全绿为硬门槛；有意的行为变化（卡 1 的 405 统一、卡 3 的未知 path 校验、运行期三查早失败）逐条在提交信息与测试中钉住 |
| 卡 4 共享循环误吞两档真实差异 | 实施第一步产出逐行差异清单，真实差异保留为各自实现 |
| 卡 5 竞态修复无复现即修 | 冻结门确定性复现先行，红→绿留档 |
| kernel 零依赖纪律（卡 7 投影落 kernel） | 投影为纯 Map 构造，不引 Jackson |
| 卡 6 公开枚举删除破坏调用方 | 全部调用方在本仓库内（junit 只用 validated()），全库替换 + grep 清点 |
