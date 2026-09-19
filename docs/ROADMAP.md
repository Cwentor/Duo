# Duo 发展规划 —— 如何达成最初的目标

- 日期：2026-09-18
- 基线：`0.1.0-SNAPSHOT`，M0–M4 已完成并验收；**本轮（2026-09-18 第 4 轮）完成 M5 交付物 1/2/3
  （契约补全 + 档位补全 + 三个故障动作）**，此前第 3 轮已完成 M7 最小子集 + M6 主线 + M5 前两项（G7/custom-hook）；
  全量回归 **342 测 0 失败 / 11 skip**（无 Docker 档确定性可复现）
- 依据：设计文档 v1.0 §2（目标与非目标）、§13（测试策略）、§14（分阶段计划）、§16（风险）、§17（开放问题）
- 决策台账：[`DECISIONS.md`](DECISIONS.md)（D1–D9 已全部拍板，无悬空决策）

---

## 1. 最初的目标（设计文档 §2 原文口径）

> 补齐业界缺失的一层：**行为可配置、可注入故障、可组装成任意链路拓扑的统一仿真框架**。

八条可验收目标：

| # | 目标 | 判据 |
| --- | --- | --- |
| T1 | **可组装** | 一份 YAML 描述组件拓扑与连线，一键拉起/重置 |
| T2 | **任意项可测** | 拓扑中任一节点可标记为 SUT，其余节点用替身 |
| T3 | **可替换** | 同一契约位可在档位间切换，测试代码零改动 |
| T4 | **行为可控** | 任务桩按剧本产生状态流转（延迟/成败/异常/假日志/永不回报） |
| T5 | **故障可注入** | 时间线剧本 + 运行时热注入 API；支持实例级寻址与崩溃后重启 |
| T6 | **真实反馈** | `embedded` 档以上暴露真实第三方协议端口，SUT 无感知直连；交互型契约暴露 Duo 线协议真实端口 |
| T7 | **秒级反馈回路** | 单 JVM 运行，容器档之外零 Docker 依赖 |
| T8 | **CI 友好** | JUnit5 扩展 + 断言库，场景文件可进版本库 |

**非目标**（不做，避免范围蔓延）：比特级网络模拟；真实计算引擎内部行为（Spark shuffle/Flink 反压）；
分布式仿真内核；性能数字的真实性承诺；external SUT 内部事实的全量可观测；SUT 作为故障注入目标
（`custom-hook` 协作式操作除外）；精美 Web 控制台。

---

## 2. 目标达成度盘点

| # | 目标 | 达成度 | 证据 | 缺口 |
| --- | --- | --- | --- | --- |
| T1 | 可组装 | ✅ **达成** | `ScenarioLoader` + `ScenarioValidator` 规则 1–8 + `WiringResolver` 拓扑排序启动 | 契约种类少（G1/G3） |
| T2 | 任意项可测 | 🟡 **部分** | in-process SUT 全链路闭环（`SutMain`/`SutContext`/`SutLauncher`）；**external SUT 已闭环（M6：零依赖第三方进程端到端验收）**；替身覆盖 8/8 契约（M5 第 4 轮补齐 scheduler/engine/message/filestore/resource） | 替身档位仍是各契约 1 档（除 registry/worker/store/scheduler 有 2–3 档）；无 real worker 的 `SutMain` 示例（G3/G4） |
| T3 | 可替换 | 🟡 **部分** | M0 `TierSwapAcceptanceTest`：同一拓扑 `workers` 在 `virtual ↔ real` 间切换、测试代码零改动；**两档调度器共用同一份 `SchedulerStateMachine`（M5 第 4 轮移入 components）** | 仅 registry（3 档）/worker（2 档）/store（2 档）/scheduler（2 档）有多档；engine/message/filestore/resource 单档（G3） |
| T4 | 行为可控 | 🟢 **基本达成** | `BehaviorProfile` 8 字段全集（duration/jitter/successRate/failAt/exception/logLines/neverReport/progress）+ 四级匹配；**行为模型已由 worker 与 engine 两侧消费（M5 第 4 轮 `VirtualEngine` 复用 `BehaviorResolver`）** | 余项：行为模型未进入 message/filestore/resource（这三者无任务语义，属设计边界） |
| T5 | 故障可注入 | 🟢 **基本达成** | 时间线（`TimelineScheduler`，duration 到期自动 clear）+ 热注入（`ScenarioRuntime`，M3 REST/CLI 包装）；实例级寻址无降级；`crash`/`restart`/`registry-flap`/`task-kill` 已落地；`custom-hook` 已闭环；**`freeze`/`slow`/`resource-exhaust` 已落地（M5 第 4 轮）**：`freeze`＝worker/engine/scheduler、`slow`＝worker/engine、`resource-exhaust`＝worker/engine/resource，三者幂等且显式拒绝 | 无「动作 × 档位」的成对故障场景集（G4） |
| T6 | 真实反馈 | 🟡 **部分** | embedded 档暴露真实 ZK 端口（SUT 用真实 Curator 客户端）/JDBC URL/K8s REST；Duo 线协议帧+8 报文；container 档 Testcontainers 桥；**store container 档已补真 PostgreSQL（M5 第 4 轮，CI container job 已取证：6/6 绿、skip=0）** | 第三方 SUT 接入需 external（G2）；适配器未做（§17 决策：按需立专项）；容器档真机路径已由 CI 取证（ZK 4 + PG 6 全绿） |
| T7 | 秒级反馈回路 | ✅ **达成** | 全量回归 342 测（无 Docker 档，components 111 为最大头）；常规档零 Docker 依赖；万级规模单 JVM 实测 9,928 HB/s | — |
| T8 | CI 友好 | 🟡 **部分** | `@VirtualCluster` 扩展 + `DuoAssertions` + YAML 断言双轨；场景文件入版本库；**标准 Wrapper + CI 三 job 远端全绿（M7 最小子集，本轮）** | **无 LICENSE**；CI 门禁存在低概率假红（G9） |

**一句话结论**：框架的**内核与机制层已经完整**（T1/T7 达成，T3/T4/T5/T6 的机制已具备），
**external SUT 宿主已于 M6 打通**（T2 的两种宿主形态齐备），
缺口集中在**广度**（契约/档位/故障动作种类）与**工程化交付收尾**（LICENSE/发布配置）。

---

## 3. 差距清单

| # | 差距 | 证据（可核对） | 影响 | 优先级 |
| --- | --- | --- | --- | --- |
| **G1** | ~~**契约覆盖不全**~~ **✅ 已闭合（M5 第 4 轮）**：`engine`/`message`/`filestore` 原有枚举占位或仅接口骨架，现已各有可运行实现（`VirtualEngine`/`VirtualMessageBroker`/`VirtualFilestore`）+ `scheduler` virtual 档 + `resource` virtual 档 + `store` container 档 | `Contract` 枚举 8/8 × SPI 注册清单（components 7 行 + embedded 5 行） | — | ~~P1~~ 已闭合 |
| **G2** | ~~**external SUT 引擎未实现**~~ **✅ 已闭合（M6，本轮）**：`launch.mode=external` 代起/attach 两形态、端点告知双途径（配置文件 + stdout）、`tcp`/`http` ready 探针、`ready.timeout` 全链路消费、`sut.exited`/`sut.crashed` 事实与「场景结束不杀进程」全部落地 | `ExternalSutLauncher` / `ReadyProbe`；`ExternalSutAcceptanceTest`（3 例）+ `ExternalSutLauncherTest`（10 例） | — | ~~P0~~ 已闭合 |
| **G3** | **档位覆盖窄**（M5 第 4 轮大幅收窄）：~~`store`/`resource` 仅 embedded~~（store 已补 container、resource 已补 virtual）；~~`scheduler` 仅 real~~（已补 virtual 调度桩）；~~`engine` 无实现~~（已补 virtual） | 契约 × 档位矩阵 | 剩余：engine/message/filestore/resource 仍各 1 档；`scheduler` 两档共用状态机但缺「同拓扑换档」用例；`engine` 无第二档可换（**如实记录**） | P2（剩余） |
| **G4** | **金标准场景集不完整**：§13 要求「每个契约至少一个正例一个故障例」。第 4 轮补了 `m5-new-contracts-acceptance.yaml`（新契约 + 三故障动作同场景，但**未成对**）；第 5 轮逐契约补齐：message（冻结，新场景 + 3 例夹具）、filestore（挂载丢失，本轮新实现 `FaultInjectable`）、engine（冻结/资源耗尽，`VirtualEngineTest` 故障例）、resource（配额耗尽，**第 4 轮其实已成对**——本轮更正此前的悲观记录）。余下：scheduler 的「同拓扑换档」用例 | `duo-sim-examples/src/*/resources/scenarios/` + `duo-sim-components` 故障例 | 契约语义回归无门禁，新契约容易「实现了但没验证」 | P1（M5 交付物 6，收尾中） |
| **G5** | ~~**故障动作未闭环**~~ **✅ 已闭合（M5 第 4 轮）**：~~`HookRegistry` 无 `ScenarioEngine` 注入入口~~（第 3 轮闭合）；~~`freeze`/`slow`/`resource-exhaust` 仅常量声明~~（第 4 轮实现：`FaultInjectable` + `supportedFaults` 声明 + 幂等/显式拒绝用例 + YAML 端到端） | `FaultAction` 常量 vs Provider `Set.of(...)`（`freeze`＝worker/engine/scheduler、`slow`＝worker/engine、`resource-exhaust`＝worker/engine/resource） | — | ~~P1~~ 已闭合 |
| **G6** | **观测面缺两条**：Prometheus 指标未实现；结构化日志无 logback 配置（SLF4J 版本已管理但未成通道） | 全仓无 `logback*.xml`、无 metrics 端点 | §11 承诺的三通道只落地「事件流录制」一条 | P2 |
| **G7** | ~~**DSL 断链与设计偏差**~~ **已闭合（M5）**：`jitter`/`failAt` 接受 `%` 形态且越界报错点出配置键；`logLines` 接入 config 并在两档 worker 逐行落 `sim.worker-log`；`ready` 声明位置统一（节点级 `ready` 为 `launch.ready` 的等价别名，冲突显式报错）；~~`ready` 校验文案~~/~~`ready.timeout` 未消费~~（M6 已修） | 见 [DSL §8 偏差表](SCENARIO-DSL.md#8-现状与设计偏差务必先读)（1/2/3/6/7 全部闭合） | 照抄设计文档示例会直接抛异常；「写了不生效」类缺陷无门禁 | ✅ 已闭合 |
| **G8** | **工程化交付**：~~无 CI 配置~~（M7 三 job 已落地并远端全绿）；~~`mvnw.sh` 硬编码本机路径~~（M7 换标准 Wrapper）；~~无 `LICENSE`~~（本轮补 Apache-2.0 全文）；~~压测产物不留存~~（CI scale job 上传 artifact） | 仓库根目录清单 | 剩余：发布配置（source/javadoc/版本策略/CHANGELOG）与质量门禁 | P1（剩余：发布配置） |
| **G9** | ~~**派发通路可静默丢弃 → 间歇性挂起**~~（M7 CI 首跑暴露，**第 4 轮已修复**）：调度侧槽位视图滞后于实例真实状态时把重派任务发给已满实例，`VirtualWorker.handleDispatch` 在 `freeSlots<=0` 时**静默 return**，调度侧仍视任务为 RUNNING → DAG 永不终态。修复＝显式拒绝（`TaskStatus.REJECTED`）+ 调度侧回滚重排 + 拒绝上限兜底 + 槽位计数原子化 + 槽位变更即时上报 | CI run 35326005487 失败现场（`job-c` 派发 2 次、无第二次回报）+ 代码定位 | ~~CI 门禁低概率假红~~；违反 §12「不静默」 | ✅ **已闭合** |
| **G10** | **拒绝后的重派在线上不落地（第 5 轮新发现，待产品拍板）**：worker 回报 `REJECTED` 后，`DispatchSelector` **收不到任何槽位上报**（worker 既不 `SlotReport` 也不回滚本地计数），调度侧仍记着上一轮 `onDispatched` 的本地递减 ⇒ 最后一格容量被该任务永久占用；若该实例无其它候选，任务停在 PENDING、DAG 永不收敛（只产生 1 条拒绝事实）。状态机侧的 PENDING 与回滚**是对的**，缺的是「派发对象」 | `VirtualSchedulerTest.rejectedTaskIsNotRedispatchedAfterWorkerRefuses`（如实记录现状的守卫用例：`sut.task-dispatched` 恒为 1、`attempts` 恒为 1、无 `sut.dag-terminal`）+ `DispatchSelector.onDispatched/onSlotReport` 源码核对 | 「G9 已闭合」只覆盖了**同进程**通路；线协议通路仍会把任务卡死（违反 §12 不静默的完整语义） | P1（独立修复项，非 M5 交付物 6 的一部分） |
| **G11** | **DSL 缺「声明但不启动」开关（第 5 轮新发现，待拍板）**：`Scenario.NodeSpec` 没有 `autoStart` 之类的字段，写进 YAML 也**不解析**（静默忽略）。于是「只验某契约、不要 worker 一起把 DAG 跑完」这种合理诉求无法表达——`m5-new-contracts-acceptance` 的三个故障因此曾经落在任务已经跑完之后（断言形同对着空气通过，本轮改注入时点绕过） | `ScenarioLoader.parseNode`（逐字段解析，无严格未知键校验）+ `ScenarioEngine.startComponents` 的启动判据（`sut() ‖ isExternal ‖ !startable ‖ 已存在` ⇒ 其余全起） | 场景作者无法裁剪拓扑；写错的键不报错（与 §12「不静默」冲突） | P2（拍板后 1 小时内可落） |

---

## 4. 后续阶段计划

依赖关系（→ 表示「先于」）：

```
        ┌─────────────────────── M7 工程化与 CI（可与 M5/M6 并行，越早越好）
        │
M6 external SUT ──► M5 契约与档位补全 ──► M8 观测面与可诊断性
   （P0 关键路径）      （P1 广度）            （P2 运营增强）
```

### M6 — external SUT 与第三方接入 ✅ **已实施完成（2026-09-18）**

**为什么现在做**：这是唯一同时卡住 T2（任意项可测）与 T6（真实反馈）的缺口，也是「Duo 能不能用来测
真实系统」的分水岭。设计（§7.3）已经把语义定死，实现面清晰、无设计不确定性。

**交付物**（全部落地）

1. `ScenarioEngine` 支持 `launch.mode=external` ✅：
   - 生成端点配置文件（`launch.configOut`），写入 `duo.endpoint.<contract>=<endpoint>` 与节点 `config`
     （`ready.*` 不写入），路径另经环境变量 `duo.config` 告知子进程；
   - stdout 兜底解析（行首格式同上 → `sim.external-endpoint`）；
   - ready 探针 `tcp` / `http`（`launch.ready`），超时归启动失败路径，且**ready 前退出优先报退出根因**；
   - 生命周期归用户：场景结束只拆接线、不杀 external 进程，发 `sim.external-process-left-running`
     + 终态警告，句柄经 `ScenarioEngine.externalSut()` 暴露；**启动失败则销毁子进程**（决策 D9）。
2. `ready.timeout` 全链路消费（in-process 与 external 一致）✅。
3. 旁路观测：替身侧事件为主途径 ✅（验收用 embedded registry 的 `sim.registry-flap-*` 做旁路断言）。
4. 第一个第三方适配器**专项**（触发条件：出现真实接入需求）——按决策 D1 维持不选型，本轮不做。

**验收标准**（全部满足，证据见 §10 进展记录）

- ✅ 一个**不可改码**的 external 进程作为 SUT 接入（`FakeThirdPartySut.java`：零 Duo 依赖、JDK 单文件
  源码模式启动），跑通「启动 → 端点告知 → ready 探针 → 故障注入 → 旁路断言通过 → 场景结束不杀进程」；
- ✅ external SUT 中途崩溃/退出分别产生 `sut.crashed`/`sut.exited` 并终止场景；
- ✅ 端点配置文件与 stdout 兜底两条途径都有测试覆盖。

**实施中暴露并修正的缺陷**：`sim.fault-injected` 原在派发**之后**落流，导致组件反应事件排在「因」
之前——以它为窗口起点的断言与 `eventSequence: [sim.fault-injected, <反应>]` 失效。已改为先因后果
（`ScenarioRuntime.inject/clear`），并加回归用例 `faultInjectedEventPrecedesComponentReactionEvents`。

**风险与对策**：第三方协议版本耦合（→ 适配器专项化、钉版本基线，§16 风险 1）；
external 就绪判定不稳（→ 探针可配 + 明确超时归启动失败，不静默）。

---

### M5 — 契约与档位补全（广度）🟢 **交付物 1/2/3 已完成（2026-09-18 第 4 轮）；余交付物 6**

**为什么现在做**：T1「可组装任意链路拓扑」与 T3「换档零改动」的**可信度取决于覆盖了多少契约位**。
当前 8 个契约里只有 5 个有实现，且只有 2 个有多档。

**交付物**

1. **契约补全**：`engine` 虚拟组件（复用 `TaskStub`/`BehaviorProfile` 行为模型，§9 已预留定位）；
   `scheduler` 虚拟调度桩（让 SUT 可以是 worker 侧而不是调度侧）；`filestore` virtual 本地 FS 桩
   （端点形态 `FS_PATH`，可达）；`message` virtual 桩（按需 embedded Kafka）。✅ **已落地（第 4 轮）**
   ——`VirtualEngine`/`VirtualScheduler`/`VirtualFilestore`/`VirtualMessageBroker` + 4 个 provider
   （均 `virtual` 档、均缺省实现）；**8/8 契约有可运行实现**
2. **档位补全**：`store` 补 container 档（或 zonky PG embedded）；`resource` 补 virtual/container 档。
   ✅ **已落地（第 4 轮）**——`PostgresContainerStore`（Testcontainers 真 PostgreSQL，
   `store` container 档）+ `VirtualResourceManager`（`resource` virtual 档）
3. **故障动作落地**：`freeze`、`slow`、`resource-exhaust`（各契约按需声明 `supportedFaults` +
   实现 `FaultInjectable` + 单测幂等/拒绝语义）。✅ **已落地（第 4 轮）**——支持矩阵
   `freeze`＝worker/engine/scheduler、`slow`＝worker/engine、`resource-exhaust`＝worker/engine/resource；
   三者均幂等、均有显式拒绝语义（§12 不静默）
4. **`custom-hook` 闭环**：`ScenarioEngine` 暴露 `HookRegistry` 注入入口（构造参数或 setter），
   YAML 时间线可用自定义钩子；钩子事件参与断言。✅ **已落地**（`withHooks`/`hooks()`/`ScenarioHost.hooks()`；
   `CustomHookAcceptanceTest` 2 例 + `ScenarioHostTest` 1 例，含未注册名的显式失败）
5. **DSL 断链修复（G7）**：`jitter`/`failAt` 兼容百分号；`logLines` 接入 config；`ready` 声明位置统一
   （保留 `launch.ready`，节点级 `ready` 作为兼容别名）+ 校验文案修正。✅ **已落地**（G7 闭合）
6. **金标准场景集（G4）**：每个契约至少一个正例 + 一个故障例，全部进常规回归。🟡 **8 个契约里 7 个已成对**
   （第 5 轮：message 新场景成对、filestore 补 `FaultInjectable` 后成对、engine 取消/冻结语义可断言、
   resource 经核对**第 4 轮就已存在配对**），**唯一余项＝scheduler 的「同拓扑换档」用例**
   ——正例/故障例的落点分两类：**有对外端点的契约**（registry/worker/scheduler/engine）走 YAML 场景；
   **NONE + interface-direct 的契约**（message/filestore/resource，§7.5 根本没有地址）只能由
   JUnit 夹具从同进程门面发起，YAML 只提供真实拓扑（如实记录，不是"漏了 YAML"）

**第 5 轮实测证据**（`.\mvnw.cmd -o -B test`，无 Docker 档）

- 全量回归 **372 测 0 失败 / 11 skip**（components 120、embedded 55 含 10 skip、examples 62 含 1 skip、
  kernel 76、protocol 12、scenario 47）——较第 4 轮 342 净增 30 测；本轮**连跑 2 次全绿**，
  远端 CI（run 35420349133）同 HEAD 一致全绿
- **G4 本轮补齐的部分**：
  - **message 契约成对**（新增 `m5-message-contract-acceptance.yaml` +
    `MessageContractAcceptanceTest` 3 例）：正例＝发布→订阅观察→拉取消费的顺序/深度/事实全对；
    故障例＝`freeze` 期间发布**显式抛错**（§12 不静默）、**存量消息不丢**、解冻后恢复。
    为此给 `VirtualMessageBroker` **新增 `FaultInjectable` 实现**（`freeze`/`clear` + 幂等 +
    未声明动作显式拒绝），provider 元数据同步声明 `supportedFaults={freeze}`
    （§7.5 老校验：声明与实现必须对应，否则注册期就报错）
  - **filestore 契约成对**（本轮新增故障例）：给 `VirtualFilestore` 补 `FaultInjectable`，
    故障动作取 `crash`＝**挂载丢失**——注入后读/写/列/删一律显式抛 `ComponentException`
    （含 `mount lost`）、健康面转 DOWN、**已落盘数据不丢**（模拟"暂时不可达"而非"数据没了"），
    `clear` 后自动恢复且数据仍在；未声明的动作（如 `slow`）显式拒绝。
    provider 元数据同步声明 `supportedFaults={crash}`。新增 2 例故障用例与既有正例成对
  - **resource 契约其实第 4 轮就已成对**（`insufficientQuotaIsRejectedWithNumbers` 正例／
    `injectedExhaustionRejectsAllocationsAndIsIdempotent` 故障例）——本轮**更正**了此前
    ROADMAP 里"resource 尚未成对"的悲观记录（§12 不静默同样适用于对自己的记录）
  - **engine 的故障路径**从"注入了就算数"升级为可断言语义（`VirtualEngineTest` 12 例）
- **一处 YAML 侧的口径修正（否则是假绿）**：`m5-new-contracts-acceptance` 的三个故障原本注入在
  100/200/300ms，而 virtual worker 300ms 就把 DAG 跑完了——故障窗口里**没有任何在途任务**，
  断言等于对着空气通过。本轮把注入压到 200/400/600ms 并把 engine 任务时长拉到 3s，
  使故障真正发生在任务**在途期间**；同时补上 `freeze` 的 `duration: 300ms`，
  让「带 duration 的动作到期被 `TimelineScheduler` 自动 clear」这条元路径**真的被执行到**
  （断言 `eventSequence: [sim.engine-frozen, sim.engine-resumed]` 因此有效而非空断言）
- **一处踩坑记录（假红而非假绿）**：`freeze` 的 duration 若**大于**场景收敛时间，pending 的定时
  clear 会被 `engine.stop()` 取消，断言就变成"解冻事实永不出现"的**假红**。已把 duration 调到
  收敛窗口之内，并在用例里显式等待 `sim.engine-resumed`（附注释说明成因）
- **一处 CI 抓到的真实竞态（本机连跑 2 次全绿未复现）**：`stop()` 的顺序是「中断任务线程 →
  代际 +1 → 清空在途表」，被中断的线程先读到**旧代际**于是进入上报分支、之后才 `fire`，
  旧代际的 `CANCELLED` 终态事实因此偶发漂移到重启之后（CI run 35419372086 判红）。
  修复：`stop()` 改为**先递增代际、再在 stop 线程同步发射取消终态**，最后才中断；
  被中断线程不再重复发事实；正常完成分支补 `exec.cancelled` 守卫（否则会把"已决定取消"
  报成 succeeded/failed）。**用例口径同时更正**：原断言「不得存在任何 CANCELLED 事实」过严
  ——取消事实本该存在，但必须**早于** `sim.engine-restarted`；改为以重启标记为对账锚点。
  教训：断言写得比语义更严，和假绿一样不可信
- **一处 DSL 缺口（G11，新增）**：DSL **没有**「声明节点但不启动」的开关
  （`Scenario.NodeSpec` 无 `autoStart` 字段，写了也不解析）。于是"只验新契约、不要 worker 一起跑"
  这种合理诉求**无法表达**，只能靠场景注释说明——这正是上面那条假绿的成因之一。
  要么补 DSL（`autoStart: false`），要么明确"节点声明即启动"为设计约束，**待拍板**
- **G9 语义落地到「唯一裁判」**：新增 `SchedulerStateMachineRejectionTest`（3 例，单线程、无 pump、
  无 socket），把此前只能在 wire 层"试试看"的拒绝语义钉成确定性断言——拒绝 N 次 ⇒ 任务回到
  PENDING、**重试额度净消耗为 0**（`dispatch` +1 与回滚 −1 相抵，在途时 `attemptsOf == 0`）、
  超 `MAX_REJECTIONS` ⇒ 显式 FAILED + 下游 SKIPPED + `onAllTerminal` 恰好一次 + 拒绝不回调 `onRetry`
- **本轮自测发现的一处产品缺口（G10，新增）**：worker 回报 `REJECTED` 后，调度侧**不会把任务重派到
  其它实例**——被拒任务停在 PENDING，DAG 永不收敛（只产生 1 条拒绝事实）。
  证据链：`FakeWorker` 按"本 worker 收到的派发次数"计数（第 2 次以后仍拒），但
  `sut.task-dispatched` 事实始终只有 1 条 ⇒ 第 2 次派发**根本没发生**；
  根因在 `DispatchSelector`：worker 拒绝时**没有任何槽位上报**（既不 `SlotReport` 也不回滚本地计数），
  调度侧仍把上一轮 `onDispatched` 的本地递减记在账上，最后一格容量被该任务永久占用且无其它候选。
  已写成**如实记录现状**的用例 `rejectedTaskIsNotRedispatchedAfterWorkerRefuses`（不是期望行为），
  修复需产品拍板（worker 拒绝时补偿计数 / 让 `SlotReport` 成为唯一权威）——**独立工作项**
- **一处夹具级发现（非产品缺陷）**：`VirtualScheduler` 的 readFeed 只处理 `TaskStatus`，
  pump 对同一 taskId **不**区分"未派发"与"派发中"，故"帧级拒绝 → 重派"的**顺序**断言会随线程
  时序抖动（本轮实测 6 次假红）。结论：顺序性判据必须下沉到状态机（已做）；
  wire 层只断言与 pump 时序无关的事实（拒绝三字段、`sut.task-retry` 不出现、attempt 不推进）

**第 4 轮实测证据**（`.\mvnw.cmd -o -B test`，无 Docker 档）

- 全量回归 **342 测 0 失败 / 11 skip**（components 111、embedded 55 含 10 skip、examples 41 含 1 skip）
- 新增用例：components 43（`VirtualEngineTest` 11、`VirtualSchedulerTest` 8、`VirtualFilestoreTest` 8、
  `VirtualMessageBrokerTest` 7、`VirtualResourceManagerTest` 6）+ `VirtualWorkerTest` 3 条故障用例
  + embedded 16（PG 容器 6 门控 + 守卫 10）+ examples 2（`NewContractsAcceptanceTest`）
- **档位互换的共享实现**：`SchedulerStateMachine`/`DispatchSelector` 及其 18 条用例从 `duo-sim-examples`
  移入 `duo-sim-components`（`io.duo.sim.components.scheduler`）——两档调度器**共用一份** DAG/重试/失败转移
  实现，杜绝「同构逻辑写两遍后走偏」（与 G9 的 worker 同构修复同一纪律）
- **本轮自测发现并修复的一处并发缺陷（G9 同类）**：`VirtualEngine` 槽位占用为「先判定再 `decrementAndGet`」，
  wire 与同进程两条通路各写一份；16 路并发提交 / 2 槽位实测**修复前 5 个被受理**（超发 3），
  改为 CAS 原子占槽（`tryReserveSlot()`）后恒为 2 受理 / 14 显式拒绝
- 远端 CI 取证：首次 run [35341365256](https://github.com/Cwentor/Duo/actions/runs/35341365256)
  `regression` ✅ / `container` ✅ **真 PostgreSQL 6/6 且 skip=0**；此后两次 `regression` 红各暴露一个问题、
  均已修复——① `VirtualSchedulerTest` 夹具竞态（消除时序依赖，非产品缺陷）
  ② **engine 旧代际线程污染新代际计数**（产品缺陷：重启后 `freeSlots` 2→3 + 假 CANCELLED 终态，
  以代际 `AtomicLong` 隔离 + 可证伪守卫用例）；
  最终取证 run [35343279887](https://github.com/Cwentor/Duo/actions/runs/35343279887)
  `regression` ✅ **342/0/0/11**、`container` ✅ 15/15 skip=0（本机同 HEAD 342/0/11 一致）
- 剩余一处未闭合：「SUT 落在 worker 侧」需要 examples 提供 real worker 的 `SutMain`（当前不存在），
  故 virtual scheduler 的覆盖是**线协议级**（真实 DUO_PORT + 真实 registry + 假 worker）

**验收标准**

- 8 个契约全部至少有 1 个可运行实现（`message`/`filestore` 至少 virtual 桩）；✅ **8/8**
- `registry`/`worker`/`scheduler`/`engine` 至少各有一个「同拓扑换档」验收（测试代码零改动）；
  🟡 `registry`（3 档）/`worker`（2 档）已有 `TierSwapAcceptanceTest`；`scheduler` 两档**共用状态机**
  但尚无同拓扑换档用例（virtual 档线协议用例已补）；`engine` 仅 virtual 档，无档可换（**如实记录**）
- 每个新契约的故障例在 CI 常规回归中执行；✅ 三个故障动作 + 5 个新契约的用例全部在
  `duo-sim-components` 常规回归内（`m5-new-contracts-acceptance.yaml` 另在 examples 回归内）
- `custom-hook` 有 YAML 端到端用例。✅ **已达成**

**风险与对策**：抽象过早（§16 风险 3）→ 坚持「每接一个新契约才泛化一次接口」的 YAGNI 节奏；
无真实用例的契约（message/filestore）→ 只做 virtual 桩，不做 embedded/container 真协议实现。

---

### M7 — 工程化与 CI（最小子集 ✅ **已实施完成（2026-09-18）**；LICENSE/发布待后续轮次）

**为什么现在做**：这是**交付门槛**而非功能。它不提升能力，但决定别人能否用、改动能否被守住。
成本低、收益立即兑现，建议与 M5/M6 并行启动。

**交付物**

1. **标准 Maven Wrapper** ✅：`mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`
   （script-only 形态，`distributionUrl` 钉 Maven 3.9.11）；硬编码本机路径的 `mvnw.sh` 已删除（决策 D6）。
2. **CI（GitHub Actions）** ✅：
   - `regression` job：无 Docker（`-Dduo.docker.enabled=false` 强制确定性），`./mvnw -B test`，
     **skip 必须可见**（`.github/scripts/skip-summary.sh` 打印逐条 skip）；
   - `container` job：有 Docker，跑容器档（`ZookeeperContainerRegistryTest` 4 条）并**断言 skip=0**；
   - `scale` job（nightly / 手动触发）：`-Dduo.scale=true`，把 `build/scale/*.json` 与录制上传为 artifact
     ——直接解决 G8「规模数据不可追溯」。
3. **许可与发布**：✅ `LICENSE`（Apache-2.0 全文，本轮补齐）；**未做**（下一轮）：`maven-source-plugin` /
   `javadoc`、版本策略与 `CHANGELOG.md`。
4. **依赖与质量门禁**（**未做**，下一轮）：`dependency:analyze`、可选覆盖率报告（JaCoCo）。

**验收标准**

- ✅ 新机器上 `git clone && ./mvnw test` 一条命令成功（无需改任何文件）——**已取证**：远端干净 runner 上
  `regression` job 直接跑 `./mvnw` 全绿；本地 `mvnw.cmd -v` → Maven 3.9.11 / JDK 21.0.12.1（只需 `JAVA_HOME`）；
- ✅ CI 三个 job 全绿且 skip 数可解释——**已取证**：run
  [35325284561](https://github.com/Cwentor/Duo/actions/runs/35325284561) `regression` ✓ 2m57s、
  `container` ✓ 32s（`--fail-on-skip` 门禁通过＝容器档无 skip）、`scale` 按设计仅 nightly/手动触发；
- ✅ 压测产物作为 artifact 可从 CI 下载并与报告逐项比对——`scale` job 的 upload-artifact
  （`build/scale/*.json` + `**/build/scenarios/**/events.jsonl`）；regression job 亦归档事件录制便于失败回放。

> ✅ 遗留风险已闭合（**G9**）：`ControlPlaneAcceptanceTest` 的间歇性挂起在 CI run 35326005487 上**复现并定位**
> （调度侧把重派任务发给已满实例 → worker 静默丢弃 → 永久挂起），本轮完成修复 + 5 条回归用例 + 失败自诊断。

---

### M8 — 观测面与可诊断性

**为什么放最后**：它提升「用起来爽不爽」，但不阻塞目标达成；且指标口径最好在契约面稳定后再定。

**交付物**

1. **Prometheus 指标导出**（§11 三通道之一）：心跳吞吐、注入次数/失败数、断言结果、
   组件健康、连接数；端点形态与 `RestControlServer` 一致（薄层，不引 Web 框架）。
2. **logback 配置 + 结构化日志**：默认控制台可读；可选 JSON encoder；与事件流录制形成互补。
3. **可诊断性**：一次故障注入的完整因果链可被单命令导出（注入 → 组件反应 → SUT 事实 → 断言）。
4. **加速时钟评估**（§17 开放问题 2，触发条件：出现「小时级长稳场景」且目标档位为 virtual；
   `SimClock` 接口已预留，无返工成本）。

**验收标准**：`/metrics` 可被 Prometheus 抓取且指标口径有文档；日志能定位一次故障注入的完整因果链。

---

## 5. 建议节奏与优先级

| 顺序 | 内容 | 状态 | 并行性 |
| --- | --- | --- | --- |
| 1 | **M7 的 CI + Wrapper（最小子集）** | ✅ 已完成（远端 CI 连续 4 次全绿已取证） | 与 M6 并行 |
| 2 | **M6 external SUT** | ✅ 已完成（G2 闭合） | 主线 |
| 3 | **M5 的 DSL 断链修复（G7）+ custom-hook 闭环** | ✅ **第 3 轮完成**（G7 闭合、G5 钩子部分闭合；17 条新用例） | 与 M5 主线并行 |
| 4 | **M5 契约与档位补全**（`engine`/`scheduler`/`filestore`/`message` + `store`/`resource` 档位 + 三个故障动作） | ✅ **第 4 轮完成**（G1/G5 闭合、G3 大幅收窄；58 条新用例 + 18 条移入 components） | 可与 M7 收尾并行 |
| 5 | **M5 金标准场景集（G4）** | 🟡 **7/8 契约已成对**（本轮：message 成对、filestore 补故障例；余：scheduler 同拓扑换档） | 依赖顺序 4 ✅ |
| 5b | **修复 G10（拒绝后重派不落地）** | ⏭ 与顺序 5 同一轮或紧随（守卫用例已就位，修复后翻转断言即可） | 可并行 |
| 5c | **补 DSL 的「声明但不启动」开关（G11）** | ⏭ 待拍板：要么补 `autoStart: false`，要么把「节点声明即启动」写成设计约束 | 与顺序 5 耦合 |
| 6 | **M7 的发布配置**（source/javadoc/版本策略/CHANGELOG） | ⏭ 下一轮（交付合规；LICENSE 已补） | 随时 |
| 7 | **M8 观测面** | ⏭ 最后（P2） | 最后 |

**里程碑判定**：M6 + M5 完成 ⇒ T1–T6 全部达成；M7 完成 ⇒ T8 达成；T7 已达成。
即 **M6 + M5 + M7 完成时，「最初的目标」八条全部可验收**。
当前进度：**M6 ✅ + M7 最小子集 ✅ + M5 交付物 1–5 ✅（余项：交付物 6 金标准场景集——7/8 契约已成对，仅余 scheduler 同拓扑换档）**
⇒ T1/T4/T5/T7 达成或基本达成、T2/T3/T6 机制齐备待广度与容器档取证；剩余 M5-6 收尾 + M7 收尾 + M8**。
第 5 轮新增：**G9 语义确定性覆盖 + message/filestore 契约正例·故障例成对 + G4 假绿修正 + G11 缺口
+ 一处 CI 抓到的引擎取消跨代际竞态修复**；全量回归 **372/0/0/11**（连跑 2 次全绿，远端 CI 一致）。

---

## 6. 需要拍板的决策点

> **已全部拍板（2026-09-18）**：决策结果、理由、触发条件与落点见 [`DECISIONS.md`](DECISIONS.md)。
> 本节保留原始决策清单，仅加注拍板结论（①/② 与兑现状态）。

| # | 决策 | 选项 | 拍板结论 | 影响 |
| --- | --- | --- | --- | --- |
| D1 | 首个第三方 SUT 选型 | ① 暂不选，M6 只做 external 机制 + 自造 external 样例；② 现在就钉一个产品（如 DolphinScheduler）立专项 | **①** ✅ 本轮兑现（自造零依赖假第三方） | 决定 M6 是否含真实适配器；选②则需同时钉版本基线 |
| D2 | `message` / `filestore` 的深度 | ① 只做 virtual 桩（快）；② 直接上 embedded 真协议（如 embedded Kafka / MiniDFS） | **①**（M5 执行） | 决定 M5 工作量与依赖面 |
| D3 | 容器档是否支持 flap/restart | ① 维持「显式不支持」（当前）；② 引入固定宿主端口（`PortBinding`）后支持 | **①**（维持，含守卫用例） | 选②需先做端口绑定改造，且要重写 `restart()` 语义 |
| D4 | 是否引入 Prometheus | ① 做（M8）；② 用事件流 + 录制替代 | **①**（M8，零依赖手写 `/metrics`） | 决定 M8 范围与是否新增依赖 |
| D5 | 加速时钟是否立项 | ① 维持推迟（当前）；② 出现小时级长稳场景时立项 | **①**（维持推迟，`SimClock` 接口已预留） | 立项需同时引入 `SutContext` 时间源注入 + 行为剧本时钟统一 |
| D6 | 是否保留 `mvnw.sh` | ① 改造为标准 Wrapper 并删除；② 保留为兼容壳 | **①** ✅ 本轮兑现 | 影响外部使用者与文档 |

**本轮新增决策**（实施 M6/M7 时暴露，已记入 `DECISIONS.md`）：D7 external 支持 `launch.command`
代起形态（否则验收要求的退出/崩溃事实无法产出）；D8 CI 联网解析依赖 + 缓存 `~/.m2`（本地维持 `-o`）；
D9 启动失败即销毁子进程（与「场景结束不杀进程」不冲突）。

---

## 7. 明确不做的事（守住非目标）

| 不做 | 理由 |
| --- | --- |
| 比特级网络模拟（半开连接、乱序） | 状态层仿真，不做流量仿真 |
| 真实计算引擎内部行为（Spark shuffle、Flink 反压） | 留给容器档/预发 |
| 分布式仿真内核（多进程/多机） | 首期单 JVM；多进程按需演进 |
| 性能压测数字的真实性承诺 | 虚拟心跳吞吐仅作参考（当前报告已标注「绝对值随机器浮动」） |
| external SUT 内部事实的全量可观测 | 只承诺替身侧旁路事件；日志/指标侧车与自定义探针是扩展点 |
| SUT 作为故障注入目标 | in-process 无法安全强杀；唯一豁免是 `custom-hook` 协作式操作 |
| 精美 Web 控制台 | 仅薄层 CLI/REST；拓扑视图保持极简 |

---

## 8. 「最初目标达成」的判定清单

当下列全部为真时，可以宣告最初的目标已达成（每一条都可执行、可复现）：

- [ ] **T1** 一份 YAML 拉起任意拓扑：8 个契约均有实现，金标准场景集（每契约一正例一故障例）全绿
- [x] **T2 的部分**：任一节点可作 SUT——in-process **与 external 两种宿主都有端到端验收**（M6）；
      ⏳ 剩余：任一契约位都有可用替身（含 `scheduler`/`engine` 的 virtual 桩，M5）
- [ ] **T3** 换档零改动：`registry`/`worker`/`scheduler`/`engine` 各有「同拓扑换档、测试代码零改动」验收
- [ ] **T4** 行为可控：8 个行为字段全部可从 DSL 生效（含 `logLines`、百分号形态）
- [ ] **T5** 故障可注入：7 类动作全部有实现与场景级验收；实例级寻址无降级；`custom-hook` 可从 YAML 使用
- [x] **T6** 真实反馈：embedded/container 档暴露真实第三方端口；**external SUT 无感知直连（M6）**
- [x] **T7** 秒级反馈回路：常规回归 < 5 分钟（实测 3.4 分钟）、零 Docker 依赖（`-Dduo.docker.enabled=false` 确定性）
- [x] **T8 的主体**：标准 Wrapper（新机器一条命令构建）+ CI 三 job 远端全绿 + skip 可解释（`skip-summary.sh`）；
      ⏳ 剩余：LICENSE/发布产物（source/javadoc）；⏳ 已知风险：CI 门禁低概率假红（G9）
- [ ] **工程化**：标准 Wrapper、LICENSE、发布产物（source/javadoc）、压测产物可追溯

---

## 9. 文档基线

| 文件 | 内容 |
| --- | --- |
| `README.md` | 项目总览：问题、概念、架构图、模块地图、快速开始、契约矩阵、最小场景、现状速览 |
| `docs/README.md` | 文档索引与阅读路径、过程文档（设计/计划/验收）清单、维护约定 |
| `docs/ARCHITECTURE.md` | 架构详解：模块依赖、SPI、契约与档位、接线规则、生命周期时序、注入通路、事件命名空间、SUT 适配面、控制面、线协议、**不变式清单** |
| `docs/SCENARIO-DSL.md` | DSL 参考：字段全集、校验规则 1–8、行为剧本、时间线动作与实现状态、断言语义、组件 config 键、**现状与设计偏差**、报错速查 |
| `docs/DEVELOPMENT.md` | 开发指南：环境、命令、测试分层与门控、**五类扩展点操作步骤**、依赖纪律、编码约定、工程债、排查手册 |
| `docs/ROADMAP.md`（本文） | 目标达成度盘点、差距清单 G1–G8、M5–M8 阶段计划、优先级、决策点、达成判定清单 |
| `docs/DECISIONS.md` | 决策台账 D1–D9：决定/理由/触发条件/落点 + 决策→交付物映射表 + 修订记录 |
| 8 份模块 `README.md` | `duo-sim-{protocol,kernel,scenario,components,embedded,junit,control,examples}/README.md`：各模块职责、依赖、关键类、测试、踩坑 |

---

## 10. 进展记录

### 2026-09-18：M7 最小子集 + M6 主线

| 项 | 结果 |
| --- | --- |
| 拍板 | D1–D6 全部拍板（`DECISIONS.md`），实施中新增 D7–D9 |
| M7-1 Wrapper | `mvnw` / `mvnw.cmd` / `.mvn/wrapper/maven-wrapper.properties`（Maven 3.9.11，script-only）；删除 `mvnw.sh`；`.gitattributes` 钉 LF/CRLF 与可执行位；`LICENSE`（Apache-2.0 全文） |
| M7-2 CI | `.github/workflows/ci.yml` 三 job（regression/container/scale）+ `.github/scripts/skip-summary.sh`（skip 逐条可见，`--fail-on-skip` 门禁）；`-Dduo.docker.enabled=false` 确定性无 Docker 门控；**远端取证：run [35325284561](https://github.com/Cwentor/Duo/actions/runs/35325284561) `regression` ✓ 2m57s / `container` ✓ 32s（skip=0 门禁通过）** |
| M6 | `ReadyProbe`（探针规格与单次探测）、`ExternalSutLauncher`（配置文件/stdout 兜底/退出观测/失败销毁）、`ScenarioEngine` external 通路（代起与 attach、`${java}` 展开、结束不杀进程 + 警告）、校验规则 4 加固（`configOut` 必填、探针启动前校验） |
| 缺陷修正 | ① `sim.fault-injected`/`sim.fault-cleared` 改为**先因后果**落流；② `mvnw` 可执行位（Windows `core.fileMode=false` 覆盖 `--chmod=+x` 导致 CI 全红）；③ 新增失败自诊断 + CI 归档事件录制 |
| **G9 修复（本轮收口）** | 证据：CI run [35326005487](https://github.com/Cwentor/Duo/actions/runs/35326005487) 复现（`dispatch-per-task={job-c=2}`、`last-status={job-c=RETRYING@workers-2}`、`dispatch-per-instance={workers-3=2}`）→ 调度侧把重派任务发给**实际已满**的 `workers-3`，worker 静默丢弃 → 永久挂起。修复：`TaskStatus.REJECTED` 显式拒绝 + 调度侧回滚尝试重排 + 拒绝上限兜底 + 槽位计数原子化 + 槽位变更即时上报；`VirtualWorker`（virtual 档）与 `DemoRealWorker`（real 档）**同构修复**（详见验收记录 §4.1） |
| 远端复验 | 修复后 CI：[35329022833](https://github.com/Cwentor/Duo/actions/runs/35329022833) `regression` ✓ / `container` ✓；[35329733881](https://github.com/Cwentor/Duo/actions/runs/35329733881)（含 real 档镜像修复）`regression` ✓ / `container` ✓，并对后者 `gh run rerun` 两次亦全绿——**修复后连续 4 次全绿**（修复前 3 次中 2 次挂起） |
| 测试 | 全量回归 **263 测 / 0 失败 / 5 skip**（3.0 分钟）：kernel 76、scenario 41、examples 54（含 M6 端到端 3 例、G9 3 例）、embedded 39、components 44（含 G9 2 例）、protocol 9 |
| 未做（下一轮） | M7 的发布配置（source/javadoc/版本策略/CHANGELOG）与质量门禁；M5 全部；M8 全部 |

> **下一轮的入口建议**：按 §5 顺序启动 **M5（契约与档位补全 + DSL 断链修复 + 金标准场景集）**，
> 并行补 **M7 的发布配置**；M8 待契约面稳定后再定指标口径。

### 2026-09-18（第 3 轮）：M5 启动——DSL 断链 G7 闭合 + custom-hook 闭环

| 项 | 结果 |
| --- | --- |
| 拍板 | 本轮按 §5 顺序取 M5 的**低风险高收益子集**先落地（交付物 4/5），契约补全与档位补全（交付物 1/2/3）留下一轮——理由：前者是「写了不生效/照抄即报错」的**静默缺陷**，后者是新增契约位（工作量大且需要新决策口径） |
| M5-5 DSL 断链（G7 ✅ 闭合） | `jitter` 接受 `20%`（≡0.2）、`failAt` 接受 `60%`（≡60），越界**报错并点出配置键**；`logLines` 接入 config（YAML 列表由 loader 归一为逗号串），执行期逐行落 `sim.worker-log`（`{task}`/`{taskId}` 占位符），**virtual 与 real 两档 worker 同构**；节点级 `ready:` 成为 `launch.ready` 的等价别名，两处冲突**解析期报错**（不静默择一） |
| M5-4 custom-hook 闭环（G5 部分 ✅） | `ScenarioEngine.withHooks(...)`/`hooks()` + `ScenarioHost.hooks()`；YAML 端到端用例（`m5-custom-hook-acceptance.yaml`：时间线在 300ms 调 `scale-out`，hook 发 `sut.hook-scale-out`，YAML `eventSequence` 断言顺序）+ 未注册名的**显式 injectionFailure** 用例 |
| 缺陷发现（本轮） | **`VirtualWorkerTest` 全量回归下确定性失败**（模块内 4/4 复现，单跑 3/3 通过）→ 逐层取证（`pumpLog=[sent-register-response, pump-exit:EOFException]`、`offline=[{error=no register response (got TaskDispatch)}]`）→ 根因是**测试夹具竞态**：假 master 先 `received.add(注册请求)` 再写注册响应，测试据此提前在同一连接上派发，worker 握手读先拿到 `TaskDispatch` 而判「无注册响应」→ 实例离线。修复：假 master **先应答再发布** + 按**实例名寻址活动连接**（不再按 accept 顺序取，重连后亦正确） |
| 协议层加固（非缺陷，诚实标注） | 夹具竞态暴露出 `FrameConnection` 的**单线程写**契约被生产代码违反（同一连接上有心跳主循环/下行读/任务三个写者）。已把「写入 + flush」串行化（读侧保持单线程），并加 3 条并发写守卫用例；**加固前该用例亦通过**（3 次运行未复现帧损坏），故属**防御性加固**而非「已复现缺陷」——已在用例 javadoc 与本节如实标注 |
| 诊断改进（§12） | 注册失败原因由 `no register response` 改为 `no register response (got <实际类型>)`——否则无法区分「对端回了别的报文」与「对端回了 null」（本轮正是靠它定位） |
| 测试 | 全量回归 **280 测 / 0 失败 / 5 skip**（3.4 分钟）：protocol 12（+3 并发写守卫）、kernel 76、scenario 47（+6 loader）、components 49（+5：百分号/越界/logLines）、embedded 39、examples 57（+3 custom-hook 端到端）；`VirtualWorkerTest` 模块内连跑 **3/3 全绿**（修复前 4/4 红） |
| 远端取证 | 本轮提交 CI run [35335848180](https://github.com/Cwentor/Duo/actions/runs/35335848180)：`regression` ✓ / `container` ✓ / `scale` 按设计 skip——**累计连续 6 次 CI 全绿** |
| 未做（下一轮） | M5 交付物 1/2/3（`engine`/`scheduler`/`filestore`/`message` 契约补全、`store`/`resource` 档位、`freeze`/`slow`/`resource-exhaust` 故障动作）+ 交付物 6（金标准场景集 G4）；M7 发布配置；M8 全部 |
