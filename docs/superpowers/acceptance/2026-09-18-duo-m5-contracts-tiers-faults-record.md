# M5 交付物 1/2/3 验收记录（契约补全 / 档位补全 / 三个故障动作）

- 日期：2026-09-18（M5 第 4 轮）
- 范围：`docs/ROADMAP.md` M5 交付物 1、2、3（交付物 6「金标准场景集」不在本轮）
- 判定：**验收通过**（证据见下，含两处如实记录的未闭合项）

---

## 1. 交付物 1 — 契约补全（8/8 契约有可运行实现）

| 契约 | 本轮新增实现 | 档位 | 端点形态 | 缺省 | 故障声明 |
| --- | --- | --- | --- | --- | --- |
| ENGINE | `VirtualEngine` / `virtual-engine` | virtual | DUO_PORT | ✅ | freeze, slow, resource-exhaust |
| SCHEDULER | `VirtualScheduler` / `virtual-scheduler` | virtual | DUO_PORT | ✅ | freeze |
| FILESTORE | `VirtualFilestore` / `virtual-filestore` | virtual | FS_PATH | ✅ | ∅ |
| MESSAGE | `VirtualMessageBroker` / `virtual-message-broker` | virtual | NONE（interface-direct） | ✅ | ∅ |
| RESOURCE | `VirtualResourceManager` / `virtual-resource` | virtual | NONE（interface-direct） | ✅ | resource-exhaust |
| STORE | `PostgresContainerStore` / `pg-container-store`（交付物 2） | container | THIRD_PARTY | ✅ | ∅ |

既有：REGISTRY（virtual/embedded/container）、WORKER（virtual/real）、STORE（embedded）、SCHEDULER（real）。

**验收判据与证据**

- SPI 注册：`duo-sim-components` 的 `META-INF/services/...ComponentProvider` 7 行、`duo-sim-embedded` 5 行；
  `ContractRegistry.loadFromServiceLoader()` 能在校验期解析全部 8 个契约。
- 端到端（线协议级）：
  - `VirtualSchedulerTest`（8 例）：真实 `DUO_PORT` + 真实 `VirtualRegistry` + 假 worker（真实帧）——
    端点发布可发现、DAG 派发到终态、G9 拒绝回滚重派、实例失联重排、freeze 停摆、停止撤端点/重启重发布、
    缺 registry 接线**显式启动失败**。
  - `VirtualEngineTest`（10 例）：线协议提交（`TaskDispatch` → RUNNING → 终态）+ 同进程 `submit`、
    槽位耗尽显式拒绝、三个故障动作、restart 语义、日志事实、非法故障显式拒绝。
  - `VirtualFilestoreTest`（8）/`VirtualMessageBrokerTest`（7）/`VirtualResourceManagerTest`（6）：
    正常路径 + 边界（`../` 逃逸、读缺失、队列深度上限、配额不足）+ 停机/重启语义 + provider 元数据。

**如实记录（未闭合）**：ROADMAP 中「SUT 落在 worker 侧」这一动机，需要 examples 侧提供一个
real worker 的 `SutMain`；仓库当前没有，故 virtual scheduler 的覆盖是线协议级（假 worker 扮演 SUT 侧），
而 YAML 级验收（`m5-new-contracts-acceptance.yaml`）的 SUT 仍是 real 档调度器。
`engine` 只有 virtual 一档，因此**无法**做「同拓扑换档」验收（不是没做，是无可换之档）。

---

## 2. 交付物 2 — 档位补全

| 契约 | 补的档位 | 实现 | 证据 |
| --- | --- | --- | --- |
| STORE | container | `PostgresContainerStore`（Testcontainers `postgres:16-alpine` + `org.postgresql:postgresql:42.7.4`） | `PostgresContainerStoreTest` 6 例（Docker 门控）+ `PostgresContainerStoreGuardTest` 10 例（无 Docker 可跑：守卫/元数据/构造期链接） |
| RESOURCE | virtual | `VirtualResourceManager` | `VirtualResourceManagerTest` 6 例 |

**依赖版本（本轮修正）**：`store` container 档的 Testcontainers 模块用
`org.testcontainers:testcontainers-postgresql:2.0.5`，与模块既有核心 `org.testcontainers:testcontainers:2.0.5`
**版本一致**；1.x 坐标 `org.testcontainers:postgresql` 只发布到 1.21.4，与 2.x 核心混用会踩到
2.x 已删除的 shaded commons-io/lang3，**明令禁止**（pom 注释已写明）。

**如实记录（已闭合）**：本机无 Docker，6 条真机用例在本机全部 skip（surefire 计数可见）；
真实 PG 往返/事务回滚/停机 health 已由 CI `container` job（Docker + `--fail-on-skip`）取证为绿——
见 §5.1。

---

## 3. 交付物 3 — 故障动作（freeze / slow / resource-exhaust）

| 动作 | 支持矩阵 | 语义（全部幂等） |
| --- | --- | --- |
| `freeze` | worker, engine, scheduler | 心跳/槽位上报停发；新派发**显式拒绝**（`frozen` / `engine frozen`）；在途进展/日志/终态**挂起**，`clear` 后补报；engine 派发泵停摆 |
| `slow` | worker, engine | 时长 × 倍数；`params.factor` > `config slow.factor` > 3.0；重复注入只更新倍数不叠加；`≤1.0` **显式拒绝** |
| `resource-exhaust` | worker, engine, resource | 对外可观测容量归零 + 新请求**显式拒绝**（`resource exhausted`）；inject/clear 幂等 |

**证据**：`VirtualWorkerTest` 新增 3 例（freeze 停心跳+挂终态、slow 倍数与幂等、resource-exhaust 槽位归零+显式拒绝）；
`VirtualEngineTest` 各 1 例；`VirtualResourceManagerTest` 1 例；`m5-new-contracts-acceptance.yaml`
（三个动作从 YAML 时间线注入，`eventSequence` 断言三个事实按序落流 + `noTaskLost` 断言不丢任务）。

**不变式**：`task-kill` 仍是实例级（`injectOnInstance`）；整组 `inject(task-kill)` 与
不支持的动作一律 `UnsupportedOperationException` / 校验期失败——**无静默降级**。

---

## 3.1 本轮自测发现并修复的一处并发缺陷（G9 同类）

`VirtualEngine` 的槽位占用原为「先判定 `freeSlots > 0`、再 `decrementAndGet()`」，且
**两条提交通路各写一遍**（wire 的 `onDispatch` 与同进程的 `EngineContract.submit`）。
16 路并发提交、2 个槽位的守卫用例实测：

| | 受理数 | 结果 |
| --- | --- | --- |
| 修复前 | **5**（超发 3） | `concurrentSubmissionsNeverOversubscribeSlots` **红** |
| 修复后 | **2**（14 条显式拒绝） | 绿 |

修复＝CAS 原子占槽（`tryReserveSlot()`），两条通路共用；判定失败与「判定通过但占槽失败」
都走**显式拒绝**（`no free slot`）。这正是 G9「槽位计数必须原子化」在 engine 侧的镜像。

---

## 3.2 远端 CI 首跑暴露的**测试夹具**竞态（已修）

run 35341908188（纯文档提交）的 `regression` job 红：`VirtualSchedulerTest.instanceLostRequeuesInFlightTaskToNewConnection`
在 3s 内未等到 `sut.task-retry`。**不是产品缺陷**，是用例自身的竞态：

- 假 worker 的默认策略是「收到派发立刻回 SUCCESS」，于是「失联时任务是否仍在途」取决于
  「调度侧处理完 SUCCESS」与「测试关连接」谁先——本机（快）恒为后者，CI（慢/负载高）可为前者，
  一旦任务已终态，`onInstanceLost` 自然无在途任务可重排（无 `sut.task-retry`/`sut.failover`）。
- 修复＝该用例把策略设为「**不回报**」（`policy = d -> null`），任务必然停在 RUNNING，
  并加一条 `此时不应有终态事实` 的断言把该前提钉住。
- 复验：`-Dtest=VirtualSchedulerTest` 连跑 **6/6 全绿**（修复前本机亦绿，故这是**消除时序依赖**而非「跑过了就算」）。
- 同时把 `VirtualEngineTest.slowMultipliesExecutionDuration` 的判据从 `slow >= normal*2`
  改为「绝对下限 300ms + `slow > normal`」：比值判据在 CI 调度噪声下会两侧同时抬高而假红。

---

## 3.3 远端 CI 第二次红暴露的**产品缺陷**：engine 旧代际线程污染新代际（已修）

run 35342550716 的 `regression` 红：`VirtualEngineTest.restartResetsStateAndRebindsEndpoint`
断言 `freeSlots() == 2` 实测 **3**。定位为**产品缺陷**（非夹具）：

- `stop()` 中断在途任务线程后**立刻** `freeSlots.set(slots)`，但被中断的线程还没走完 `finally`；
  它的 `incrementAndGet()` 于是落在**新一代**的计数上 → 2 → 3 漂移（本机时序恰好掩盖）。
- 同一根因还会让旧代际任务在新代际里报出 `sim.engine-task-status(CANCELLED)`——**假终态事实**，
  违反 §12「不静默/不伪造成功」。

修复：引入生命周期**代际** `AtomicLong generation`（`stop`/`restart` 递增），`launch` 时快照 `gen`；
任务线程只有在 `gen == generation.get()` 时才归还槽位、报进展/日志/终态。旧代际线程彻底沉默，
其终结由 `sim.engine-stopped`/`-crashed` 记账。

守卫用例（可证伪）：`VirtualEngineTest.restartDoesNotLetStaleTaskThreadsDriftSlotCount`
——重启后等过旧任务原定 duration，断言计数不漂移且无 CANCELLED 假终态；
把代际判定临时改回恒真后该用例**必红**（已实测），故不是「跑过就算」；连跑 4/4 全绿。

> 两次 CI 红的价值正在于此：① 夹具竞态（§3.2）② 真实并发缺陷（本节）。
> 前者只需消除时序依赖，后者是 `duo-sim-components` 里一处必须修的计数不变量。

---

## 4. 一处刻意的架构改动（值得单列）

`SchedulerStateMachine`（263 行 DAG/有界重试/失败转移）与 `DispatchSelector` 及其 18 条用例，
从 `duo-sim-examples` **移入** `duo-sim-components`（`io.duo.sim.components.scheduler`）。

- 理由：`duo-sim-components` 不能依赖 `duo-sim-examples`，而两档调度器共用同一份语义；
  复制一份实现必然走偏（与 G9「worker 两档同构修复」同一纪律）。
- 副作用（可核对）：examples 测试数 57 → 41，components 49 → 111。

---

## 5. 全量回归（本机实测）

```
$env:JAVA_HOME="C:\Users\cwt15\devtools\jdk-21.0.12.1+1"
.\mvnw.cmd -o -B test
```

| 模块 | 测试数 | skip |
| --- | --- | --- |
| duo-sim-protocol | 12 | 0 |
| duo-sim-kernel | 76 | 0 |
| duo-sim-scenario | 47 | 0 |
| duo-sim-components | 111 | 0 |
| duo-sim-embedded | 55 | 10（4 ZookeeperContainer + 6 PostgresContainer，无 Docker） |
| duo-sim-junit | 0 | 0 |
| duo-sim-control | 0 | 0 |
| duo-sim-examples | 41 | 1（ScaleAcceptanceTest，压测开关未开） |
| **合计** | **342** | **11** |

- 上一轮基线：280 测 / 5 skip。本轮净增 62 测（components +62 中含移入的 18）。
- 每个 skip 都可解释（无 Docker / 压测开关），无静默跳过。

---

## 5.1 首次远端 CI 取证（commit `4cb9d8a`，run 35341365256；数字为该 run 当时实测）

| job | 结果 | 关键输出 |
| --- | --- | --- |
| `regression (no Docker)` | ✅ 3m24s | skip 汇总 **TOTAL 341 / fail 0 / error 0 / skip 11**，逐条列出 11 个被跳过的用例名（4 ZK 容器 + 6 PG 容器 + 1 压测） |
| `container tier (Docker)` | ✅ 1m11s | **`PostgresContainerStoreTest` Tests run: 6 / fail 0 / error 0 / skip 0**（真 PostgreSQL 往返 15.8s）、`ZookeeperContainerRegistryTest` 4/4、`ZookeeperContainerRegistryGuardTest` 5/5；`--fail-on-skip` 门禁：`skipped cases: none` |
| `scale` | ⏸ 按设计（nightly/手动） | — |

- 该 run 是**新容器档首次在真 Docker 上执行**：`org.testcontainers:testcontainers-postgresql:2.0.5` 与
  `org.postgresql:postgresql:42.7.4` 由 CI 在线解析并跑通，验证了「与核心同版」的依赖选择正确
  （此前 1.20.4 模块 + 2.0.5 核心的混用方案已废弃）。
- 结论：交付物 2 中「容器档真机路径」的取证缺口**闭合**。
### 5.2 修复后最终取证（commit `9d41402`，run 35343279887）

| job | 结果 | 关键输出 |
| --- | --- | --- |
| `regression (no Docker)` | ✅ 2m56s | skip 汇总 **TOTAL 342 / fail 0 / error 0 / skip 11**（11 条逐条列出且可解释） |
| `container tier (Docker)` | ✅ | `PostgresContainerStoreTest` **6/6 skip 0**、`ZookeeperContainerRegistryTest` 4/4、守卫 5/5，合计 15/15；`skipped cases: none` |
| `scale` | ⏸ 按设计 | — |

- CI 上**两次红均已收敛为绿**：① 夹具竞态（§3.2）② engine 代际漂移这一真实并发缺陷（§3.3）。
- 本机同 HEAD 全量回归：**342 测 / 0 失败 / 11 skip**（`.\mvnw.cmd -o -B test`），数字与远端一致。

---

## 6. 第 5 轮增补：契约语义确定化 + G4 金标准场景补对（commit 待定，本机取证）

本轮没有新增契约或档位，只做两件与「可验收性」直接相关的事：把**曾经只能在 wire 层"试试看"的
拒绝语义钉成确定性用例**，以及**把 G4 的正例/故障例补成对**。过程中暴露 2 个新缺口（G10/G11），
如实记录在 `ROADMAP.md`。

### 6.1 全量回归（`.\mvnw.cmd -o -B test`，无 Docker 档）

| 模块 | 测试数 | skip |
| --- | --- | --- |
| duo-sim-protocol | 12 | 0 |
| duo-sim-kernel | 76 | 0 |
| duo-sim-scenario | 47 | 0 |
| duo-sim-components | 118 | 0 |
| duo-sim-embedded | 55 | 10（4 ZookeeperContainer + 6 PostgresContainer，无 Docker） |
| duo-sim-examples | 62 | 1（ScaleAcceptanceTest，压测开关未开） |
| **合计** | **370** | **11** |

- 本轮连跑 **2 次全绿**（0 失败 / 0 错误 / 11 skip）；相对第 4 轮的 342 净增 28 测。
- 每个 skip 仍可解释（无 Docker / 压测开关），无静默跳过。

### 6.2 G9 拒绝链路：语义下沉到「唯一裁判」

新增 `SchedulerStateMachineRejectionTest`（3 例，单线程、无 socket、无线程池），把此前只能在
`VirtualSchedulerTest` 里"跑跑看"的行为钉成确定性断言：

- 拒绝 N 次 ⇒ 任务回到 PENDING，**重试额度净消耗为 0**（`dispatch` 的 +1 与回滚 −1 相抵）；
- 超 `MAX_REJECTIONS`（12）⇒ 显式 FAILED + 下游 SKIPPED + `onAllTerminal` 恰好一次，
  且拒绝**不**回调 `onRetry`；
- 迟到的/重复的拒绝幂等忽略。

同时把 `VirtualSchedulerTest` 中依赖 pump 时序的断言**降级为与线程时序无关的事实断言**
（拒绝事实三字段、`sut.task-retry` 不出现、`attempt==1`），因为实测该层面的顺序断言会假红 6 次。

### 6.3 G4：message 契约正例/故障例成对

- 新增 `m5-message-contract-acceptance.yaml` + `MessageContractAcceptanceTest`（3 例）：
  正例＝发布 → 订阅观察 → 拉取消费（顺序、深度、`sim.message-published` 事实）；
  故障例＝`freeze` 期间发布**显式抛错**、**存量消息不丢**、解冻后恢复。
- 为落地故障例，`VirtualMessageBroker` **新增实现 `FaultInjectable`**（`freeze`/`clear`，
  幂等，未声明动作显式拒绝），provider 元数据同步声明 `supportedFaults={freeze}`——
  否则 §7.5 的注册期一致性校验会直接报错（这正是该校验存在的意义）。
- 端点形态 `NONE + interface-direct` 的契约（message/filestore/resource）**没有地址可给 SUT**，
  其正例/故障例只能由 JUnit 夹具从同进程门面发起；YAML 只提供真实拓扑。这是如实记录的口径，
  不是"漏了 YAML"。

### 6.4 一处**假绿**的发现与修正（重要）

`m5-new-contracts-acceptance` 的三个故障原本注入在 100/200/300ms，而 virtual worker 300ms
就把 DAG 跑完了 —— 故障窗口里**没有任何在途任务**，`eventSequence` 断言形同"对着空气通过"。
本轮把注入时点压到 200/400/600ms 并把 engine 任务时长拉到 3s，使故障真正落在任务**在途期间**。
根因是 DSL 没有「声明但不启动」的开关（→ G11）：场景作者无法裁剪拓扑。

### 6.5 新缺口（如实记录，未修）

| 缺口 | 现象 | 证据 | 优先级 |
| --- | --- | --- | --- |
| **G10** | worker 回报 REJECTED 后调度侧**不再重派**（`DispatchSelector` 收不到槽位上报，最后一格容量被永久占用），任务停在 PENDING、DAG 永不收敛 | `VirtualSchedulerTest.rejectedTaskIsNotRedispatchedAfterWorkerRefuses`（守卫用例，记录现状而非期望） | P1 |
| **G11** | DSL 无 `autoStart` 类开关，未知键**静默忽略** | `ScenarioLoader.parseNode` 源码核对 + §6.4 的假绿现场 | P2 |

### 6.6 远端 CI 取证（commit `1da4218`，run 35417427531）

| job | 结果 | 说明 |
| --- | --- | --- |
| `regression (no Docker)` | ✅ success | 全量回归含新增的 message 契约 3 例 |
| `container tier (Docker)` | ✅ success（10 steps） | 本机无 Docker，容器档只能在 CI 取证 |
| `scale (nightly / manual)` | ⏸ 按设计跳过 | — |

结论：本轮 G4 增补**远端全绿**，与第 4 轮的证据链连续。

---

## 7. 第 5 轮续：filestore 契约补齐故障例（G4 收尾）

第 6 节只把 message 补成对；本轮继续按 §13「每个契约至少一个正例一个故障例」逐契约点名，
**先核对再动手**，结果与上一轮的记录有两处出入，一并更正。

### 7.1 逐契约点名（以源码为准，不凭印象）

| 契约 | 正例 | 故障例 | 结论 |
| --- | --- | --- | --- |
| message | `publishedMessagesAreOrderedObservableAndConsumable` | `freezeMakesPublishFailLoudlyWithoutDroppingQueuedMessages` | 第 6 节已成对 |
| engine | `VirtualEngineTest` 提交/受理/状态流转 | 冻结、资源耗尽故障例 | 已有 |
| **resource** | `allocateAndReleaseKeepQuotaAccounting` | `injectedExhaustionRejectsAllocationsAndIsIdempotent` | **其实第 4 轮就已成对**——上一轮 ROADMAP 记为"未成对"是错的，本轮更正 |
| **filestore** | `writeReadListDeleteRoundTrip` | **本轮新增** | 此前**确实没有**故障例 |
| registry / worker / scheduler | 各自既有 | 各自既有 | 沿用 |

### 7.2 filestore 故障例的设计取舍

`VirtualFilestore` 此前 `implements VirtualComponent` 而无 `FaultInjectable`——**连挂故障动作的洞都没有**。
本轮补上，并且只声明一个动作：

- **`crash` ＝ 挂载丢失**：注入后 `resolve/write/read/list/delete` 一律显式抛
  `ComponentException("filestore mount lost (crash injected): …")`，`health()` 转 DOWN，
  并发事实 `sim.filestore-mount-lost`；**已落盘数据保留**——真实存储不可达时数据仍在盘上，
  场景必须能区分「暂时不可达」与「数据没了」。`clear` 后发 `sim.filestore-mount-restored`、
  读写照常、数据仍在。
- **不声明 `freeze`/`slow`**：前者与 `crash` 语义重复且同样"拒绝写"，后者对本地文件 IO
  不可观测。**不声明即显式拒绝**（`UnsupportedOperationException`），不做"接受了但没效果"
  的静默降级（§12）。
- 幂等：重复 inject/clear 不产生第二条事实；provider 元数据同步声明
  `supportedFaults={crash}`——否则 §7.5「声明与实现必须对应」的注册期校验会直接报错。

新增 2 例（合计 filestore 单测 10 例），与既有正例成对。

### 7.3 顺手修掉一个夹具竞态（不是产品缺陷）

`VirtualWorkerTest` 的 `events` 是 `ArrayList`，由 worker 虚拟线程 `add`、
测试线程 `stream()` 遍历 ⇒ 本轮实测偶发 `ConcurrentModificationException`
（`taskKillTerminatesInFlightTaskWithCancelledReport`）。改为 `CopyOnWriteArrayList`
并在字段上写明成因。**这是夹具缺陷，产品代码未改**——如实区分，避免把夹具问题记成产品缺陷。

### 7.4 场景侧：让「带 duration 的自动 clear」真的被执行到

`m5-new-contracts-acceptance` 的 `freeze` 加上 `duration: 300ms`，断言
`eventSequence: [sim.engine-frozen, sim.engine-resumed]`。踩到并修正一个**假红**：
duration 若大于场景收敛时间，`engine.stop()` 会取消 pending 的定时 clear，解冻事实永不出现。
把 duration 压到收敛窗口内、并在用例里显式等待该事实后转绿（用例内已注释成因）。

### 7.5 本轮回归

| 模块 | 测试数 | skip |
| --- | --- | --- |
| duo-sim-protocol | 12 | 0 |
| duo-sim-kernel | 76 | 0 |
| duo-sim-scenario | 47 | 0 |
| duo-sim-components | 120 | 0 |
| duo-sim-embedded | 55 | 10（无 Docker 档） |
| duo-sim-examples | 62 | 1（压测开关未开） |
| **合计** | **372** | **11** |

连跑 **2 次全绿**（0 失败 / 0 错误）。相对第 6 节的 370 净增 2 测（filestore 故障例）。

### 7.6 结论

§13 的「每契约一正例一故障例」现在 **8 个契约里 7 个成对**，唯一余项是
**scheduler 的「同拓扑换档」用例**（机制已存在，缺的是场景），已记入 G4 行。

### 7.7 远端 CI 抓到的一个真实竞态（本机两次全绿也没抓到）

推送 `87e748e` 后 CI 判红：`regression (no Docker)` 失败于
`VirtualEngineTest.restartDoesNotLetStaleTaskThreadsDriftSlotCount`
（`旧代际任务不得向新一代报终态事实`）。这是**真实竞态**，不是偶发噪声：

- `stop()` 的顺序是「中断任务线程 → `generation.incrementAndGet()` → 清空 `runningTasks`」；
  被中断的任务线程在 `catch (InterruptedException)` 里**先读到旧代际**、于是进入上报分支，
  之后才执行 `fire(...)`——这条窗口让旧代际的 `CANCELLED` 终态事实偶发漂移到重启之后。
- 本机连跑两次全绿、CI 一次即红，正是窗口型竞态的典型表现。

修复分三处，都在"事实与意图必须一致"这条线上：

1. `stop()` 改为 **先递增代际、再同步发射取消终态**（`reportCancelledNow`，事实在 stop 线程
   落流，先事件后回写连接），最后才中断任务线程。这样代际判定与事实发射之间不再有异步空隙。
2. 被中断任务线程的 `catch` 分支**不再重复发事实**（一条取消只记一条事实），只在代际未变时
   补一次连接回写。
3. 正常完成分支补齐与 `catch` 对称的守卫：`exec.cancelled` 为真时**不报终态**
   （此前会把"已决定取消"报成 succeeded/failed）。

**用例口径同时更正**：原断言「不得存在任何 CANCELLED 事实」本身是错的——取消事实**本该存在**，
它必须出现在 `sim.engine-restarted` **之前**（谁取消谁记账）。改为以 `sim.engine-restarted`
为对账锚点，断言「其后不存在 CANCELLED」。这是**假绿的反面**：断言写得比语义更严，同样不可信。

修复后本机：`VirtualEngineTest` 12 例全绿；全量回归 **372/0/0/11 连跑 2 次全绿**。
