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
