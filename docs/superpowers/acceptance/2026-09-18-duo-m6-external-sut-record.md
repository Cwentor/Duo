# M6 验收记录：external SUT 与第三方接入

- 日期：2026-09-18
- 范围：ROADMAP §4 M6（G2 闭合）+ §5 第 1 项（M7 最小子集：标准 Wrapper + CI）
- 决策依据：[`DECISIONS.md`](../../DECISIONS.md) D1、D6–D9
- 设计依据：设计文档 v1.0 §7.3（SUT 适配面）、§11（观测面与断言）、§12（失败路径）、§13（测试策略）

---

## 1. 验收标准对照

| # | ROADMAP 验收标准 | 结论 | 证据 |
| --- | --- | --- | --- |
| 1 | 一个**不可改码**的 external 进程作为 SUT 接入，跑通「启动 → 端点告知 → ready 探针 → 故障注入 → 旁路断言通过 → 场景结束不杀进程」 | ✅ | `duo-sim-examples` `ExternalSutAcceptanceTest.externalThirdPartySutEndToEndWithoutKillingProcess` |
| 2 | external SUT 中途崩溃/退出分别产生 `sut.crashed`/`sut.exited` 并终止场景 | ✅ | 同测试类 `externalSutNormalExitPublishesExitedAndEndsScenario`、`externalSutCrashPublishesCrashedAndEndsScenario` |
| 3 | 端点配置文件与 stdout 兜底两条途径都有测试覆盖 | ✅ | 内核 `ExternalSutLauncherTest`（10 例，含两条途径各一例）+ examples 端到端（两条途径同时生效） |
| 4 | `ready.timeout` 全链路消费（in-process 与 external 一致） | ✅ | `ScenarioEngine.readyTimeoutMs`；内核 `ReadyProbeTest`（超时单位/缺省/非法值）+ `ExternalSutLauncherTest` 超时用例 |
| 5 | M7-1 新机器 `git clone && ./mvnw test` 一条命令成功 | ✅ 已取证 | 远端 CI `regression` job 用 `./mvnw` 在干净 runner 上全绿（2m57s）；本地 `mvnw.cmd -v` → Maven 3.9.11 / JDK 21.0.12.1 |
| 6 | M7-2 CI 三 job 全绿且 skip 可解释 | ✅ 已取证（scale 按设计仅 nightly/手动） | run [35325284561](https://github.com/Cwentor/Duo/actions/runs/35325284561)：`regression` ✓ 2m57s、`container` ✓ 32s（skip=0 门禁通过）、`scale` 未触发 |

---

## 2. 实测数字（本机，2026-09-18）

```bash
./mvnw -o -B test "-Dduo.docker.enabled=false"     # → BUILD SUCCESS，03:22 min
bash .github/scripts/skip-summary.sh               # → TOTAL 263 run / 0 fail / 0 error / 5 skip
```

| 模块 | 测试数 | skip |
| --- | --- | --- |
| `duo-sim-protocol` | 9 | 0 |
| `duo-sim-kernel` | 76 | 0 |
| `duo-sim-scenario` | 41 | 0 |
| `duo-sim-components` | 42 | 0 |
| `duo-sim-embedded` | 39 | 4（无 Docker，`-Dduo.docker.enabled=false` 强制） |
| `duo-sim-junit` | 0 | 0 |
| `duo-sim-control` | 0 | 0 |
| `duo-sim-examples` | 51 | 1（未开 `-Dduo.scale`） |
| **合计** | **263** | **5** |

skip 逐条可解释（脚本输出）：

```
- eventsCarryContainerKind                    [ZookeeperContainerRegistryTest]
- facadeRoundTripThroughRealZk                [ZookeeperContainerRegistryTest]
- providerMetadataRejectsFlapAndPassesRegistration [ZookeeperContainerRegistryTest]
- wireEndpointExposesContainerZkPort          [ZookeeperContainerRegistryTest]
- heartbeatScaleScenarioPasses(String)        [ScaleAcceptanceTest]
```

T7 预算（常规回归 < 5 分钟）保持：实测 3.4 分钟。

---

## 3. 交付物清单

### 3.1 M6 内核侧

| 文件 | 内容 |
| --- | --- |
| `duo-sim-kernel/.../sut/ReadyProbe.java` | 探针规格 `Spec(type,host,port,path,timeoutMs)` + `spec(config, fallbackPort)` 解析（缺 type/端口/非法单位一律**启动前**抛错）+ `once(Spec)` 单次探测（tcp 建连 / http < 500） |
| `duo-sim-kernel/.../sut/ExternalSutLauncher.java` | 写端点配置文件（`duo.endpoint.*` + 节点 config，剔除 `ready.*`，另设环境变量 `duo.config`）→ 代起子进程 → stdout 端点兜底（**行首**严格前缀）→ 轮询 ready（200ms，与进程死亡赛跑）→ 退出观测（`sut.exited`/`sut.crashed`，仅 ready 之后）→ 启动失败销毁子进程 |
| `duo-sim-kernel/.../api/Event.java` | 新增 4 个框架事件类型：`sim.external-process-started` / `sim.external-endpoint` / `sim.external-sut-ready` / `sim.external-process-left-running` |

### 3.2 M6 场景层

| 文件 | 内容 |
| --- | --- |
| `model/Scenario.java`、`ScenarioLoader.java` | `Launch` 增 `command` 字段（3 参兼容构造器保留） |
| `ScenarioValidator.java` | 规则 4 加固：external 必填 `launch.configOut`；探针经 `ReadyProbe.spec` 启动前校验；文案改 `launch.ready` |
| `ScenarioEngine.java` | `startSut()` 分派 in-process / external；`splitCommand`（引号感知 + `${java}`/`${java.home}` 展开）；`readyTimeoutMs` 消费 `ready.timeout`；`onSutEvent` 统一发布并映射退出/崩溃；`stop()` 对 external 发 `sim.external-process-left-running` + 终态警告（不杀进程）；`externalSut()` 暴露句柄 |

### 3.3 M7 最小子集

| 文件 | 内容 |
| --- | --- |
| `mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties` | 标准 Wrapper（script-only），`distributionUrl` 钉 Maven 3.9.11；删除 `mvnw.sh` |
| `.github/workflows/ci.yml` | `regression`（无 Docker，skip 可见）/ `container`（断言 skip=0）/ `scale`（nightly，产物 artifact） |
| `.github/scripts/skip-summary.sh` | surefire XML → 逐报告 + TOTAL 表 + 逐条 skip 名；`--fail-on-skip` 门禁 |
| `ZookeeperContainerRegistryTest` | `dockerAvailable()` 受 `-Dduo.docker.enabled=false` 强制关闭（让「零 Docker 依赖」成为确定事实） |

### 3.4 测试

| 测试 | 条数 | 覆盖 |
| --- | --- | --- |
| `ExternalSutLauncherTest` | 10 | ready + 配置文件 + stdout 兜底 + 事件；`exposes` 兜底端口；ready 超时**销毁子进程**；ready 前退出/崩溃快速失败；正常退出 → `sut.exited`；崩溃 → `sut.crashed`（含 exitCode）；attach 形态；`close()` 不杀进程；端点行严格解析 |
| `ReadyProbeTest` | 8 | 规格解析与非法值拒绝、tcp/http 单次探测 |
| `ScenarioValidatorTest`（+5） | 18 | `configOut` 必填、未知探针类型、缺端口、非法时长单位、合规用例通过 |
| `ScenarioEngineCommandTest` | 5 | 空白切分、引号、`${java}`/`${java.home}` 展开、未知 token 原样保留 |
| `ExternalSutAcceptanceTest` | 3 | 端到端（配置文件到达 SUT + stdout 发现 + 旁路断言 + 不杀进程 + 警告）、正常退出、崩溃 |
| `ScenarioRuntimeTest`（+1） | 8 | 注入事件先因后果 |

假第三方 SUT fixture：`duo-sim-kernel/src/test/resources/external/FakeTcpSut.java`（最小形态）与
`duo-sim-examples/src/test/resources/external/FakeThirdPartySut.java`（增 report 文件，证明端点告知到达 SUT）。
两者**零 Duo 依赖**，经 `java <file>.java` 单文件源码模式启动（无 classpath/manifest-jar 问题）。

---

## 4. 缺陷处置

| # | 缺陷 | 级别 | 处置 |
| --- | --- | --- | --- |
| 1 | `sim.fault-injected` 在 `dispatch` **之后**落流 → 组件在同一注入调用内发布的反应事件（embedded `sim.registry-flap-started/cleared`）排在「因」之前，`eventSequence: [sim.fault-injected, <反应>]` 恒不可满足；以它为窗口起点的断言（`failoverWithin`/`masterReelectedWithin`）变脆 | **HIGH**（静默削弱断言） | 改为**先因后果**（`ScenarioRuntime.inject/clear` 先落流再派发）；派发失败另记 `sim.fault-inject-failed`；回归用例 `faultInjectedEventPrecedesComponentReactionEvents` 守住；文档记入 DSL §8 注与 ARCHITECTURE §9 |
| 2 | stdout 端点行若用 `indexOf("duo.endpoint.")` 容错匹配，会把 SUT 回显的内核配置行误判为自身端点宣告 | MEDIUM | 改为**行首严格前缀**解析，并加用例（`ExternalSutLauncherTest`） |
| 3 | 内核 jar 陈旧导致 `-pl <module>` 报「找不到符号」（构建纪律） | MEDIUM | 文档固化：单模块一律带 `-am`（DEVELOPMENT §2、排查手册） |
| 4 | PowerShell 传 `-Dx.y=z` 会被按 `.` 拆参（`-Dmaven=3.9.11` 生成过错误的 `distributionUrl`） | MEDIUM | 文档固化：PowerShell 下 `-D` 属性加引号（DEVELOPMENT §1.2） |
| 5 | `mvnw` / `skip-summary.sh` 提交为 `100644`（Windows 侧 `core.fileMode=false` 把 `git add --chmod=+x` 覆盖回 644）→ Linux runner 上 `./mvnw` 报 `Permission denied`（exit 126），CI 三 job 全红 | **HIGH**（交付门槛失效） | 用 `git update-index --chmod=+x` 显式钉索引位；`.gitattributes` 钉 LF/CRLF；复跑 CI 三 job 全绿 |
| 6 | **CI 首跑暴露的间歇性挂起**：`ControlPlaneAcceptanceTest`（M3 热注入自愈）在 GitHub 2 vCPU runner 上出现「注入后 90s 场景仍 RUNNING」；本地 16 核（含 14 进程满载干扰、连跑 8 次）**未复现** | **HIGH**（CI 门禁间歇性红） | **本轮已修复**：先交付失败自诊断，第二次 CI 失败（run 35326005487）即当场定位——`dispatch-per-task={job-c=2}`、`last-status={job-c=RETRYING@workers-2}`、`dispatch-per-instance={workers-3=2}`：调度侧把重派任务发给**实际已满**的 `workers-3`，而 worker 在 `freeSlots<=0` 时**静默 return**，调度侧仍视其为 RUNNING → 永久挂起。修复与验证见 §4.1 |

无未处置的高级别缺陷。

### 4.1 G9：派发静默丢弃 → 间歇性挂起（本轮已修复）

**现象与证据**：CI run [35324375056](https://github.com/Cwentor/Duo/actions/runs/35324375056)（首跑）与
[35326005487](https://github.com/Cwentor/Duo/actions/runs/35326005487) 两次失败，断言同一处：
`SUT state after awaitFinish: {state=RUNNING, ... events=2741}`。本轮先交付**失败自诊断**（事件流聚合 +
事件录制归档），第二次失败即给出决定性事实：

```
dispatch-per-task={job-a=1, job-b=1, job-c=2, job-d=1}
retry-per-task={job-c=1}
last-status={job-a=SUCCESS@workers-1, job-b=SUCCESS@workers-4, job-c=RETRYING@workers-2, job-d=SUCCESS@workers-3}
terminal={job-a=SUCCESS@workers-1, job-b=SUCCESS@workers-4, job-d=SUCCESS@workers-3}
dispatch-per-instance={workers-1=1, workers-2=1, workers-3=2, workers-4=1}
instance-lost=[workers-2(requeued=job-c)]
```

即：`job-c` 在 `workers-2` 崩溃后被重派到 `workers-3`（该实例此刻已在跑 `job-d`），**worker 侧没有任何回报**
（既无 RUNNING 也无终态），调度侧却把它记为 RUNNING → 任务永久在途、DAG 永不终态。

**根因（代码级）**：

1. `VirtualWorker.onDispatch`（旧）在 `freeSlots <= 0` 时**静默 `return`**，注释写着「scheduler 侧排队由拓扑
   规模保证不发生」——该假设**被崩溃转移重派发打破**（4 个任务产生了第 5 次派发）；
2. 调度侧槽位视图来自 worker 的周期 `SlotReport`（`DispatchSelector.onSlotReport` 直接覆盖），与
   `pumpDispatches()`（每 50ms）之间无时序耦合，视图可滞后于实例真实状态；
3. 旧实现另有两处会**永久泄漏槽位**的隐患：`freeSlots--` 在 `TaskAck` 写成功**之前**执行（写失败即泄漏）；
   `volatile int freeSlots` 由读线程自减、任务线程自增（并发丢更新）。任一泄漏都会让实例被误判为长期
   满载，从而放大 (1)。

**修复（§12「不静默」原则落地）**：

| 层 | 改动 |
| --- | --- |
| 协议 | `TaskStatus.REJECTED`：worker **未受理**（满载/连接不可写）时显式回报，语义＝「从未执行」 |
| worker | `onDispatch` 不可受理即 `reject()`（回报 REJECTED + 发 `sim.worker-task-rejected` 事件 + 刷新槽位）；受理路径改为「确认可写后再占用槽位」；`freeSlots` 改 `AtomicInteger`；受理/拒绝后**立即上报槽位**（不等心跳，把视图滞后窗口压到一个 RTT）。`VirtualWorker`（virtual 档）与 `DemoRealWorker`（real 档）**同构修复**，两档行为一致 |
| 调度 | `SchedulerStateMachine.onRejected`：任务回 PENDING、**回滚尝试计数**（准入失败不占 `MAX_ATTEMPTS`，否则一次瞬时满载即判死）、发 `sut.task-rejected` 事实；`MAX_REJECTIONS=12` 兜底——超限即判 FAILED 并跳过下游，**DAG 必然终态**（宁可显式失败，绝不静默挂起） |

**验证**：新增 5 条回归用例——worker 侧 2 条（满载显式拒绝且不占槽位/不发执行状态；被拒任务在槽位释放后
**重派可跑到终态**）、状态机侧 3 条（拒绝后回 PENDING + 回滚 attempts + 不发重试事实；迟到拒绝幂等忽略；
连续拒绝超限 → FAILED + 下游 SKIPPED）。`DemoRealWorker`（real 档）的镜像修复由 M0 档位切换验收
`TierSwapAcceptanceTest`（3 例）覆盖正常通路，其拒绝分支与 `VirtualWorker` 逐行同构、由上述用例守护。
全量回归 **263 测 / 0 失败 / 5 skip**。

**远端复验（回归 vs 修复）**：修复前 3 次 CI 中 **2 次**在 `ControlPlaneAcceptanceTest` 上挂起；
修复后同一测试在 2 vCPU runner 上**连续 4 次全绿**（[35329022833](https://github.com/Cwentor/Duo/actions/runs/35329022833)、
[35329733881](https://github.com/Cwentor/Duo/actions/runs/35329733881) 及对后者的两次 `gh run rerun`），
`regression` / `container` 两 job 每次均通过。按修复前观测到的失败率（2/3）估算，连续 4 次全绿属于
低概率偶然（≈1–6%），故作为「缺陷已消除」的证据（非数学证明：该缺陷依赖时序竞争，见 §5 限制 7）。

---

## 5. 限制与未覆盖（诚实声明）

1. **第三方真实产品未接入**（决策 D1 ①）：M6 交付的是**机制** + 自造零依赖样例；真实适配器（报文 ↔ Duo 帧
   翻译层 + 契约映射 + 版本基线）仍是触发式专项。
2. **attach 形态的退出不可观测**：省略 `launch.command` 时内核没有进程句柄，故无 `sut.exited`/`sut.crashed`
   （已在 DSL §1.5 明确写出，不静默）。
3. **CI 远端取证与 G9 均已闭环**：run 35325284561 首次全绿；首跑的间歇性挂起（§4 第 6 项）在第二次 CI
   失败中**定位并修复**，修复后连续 4 次 CI 全绿（含 2 次重跑）。
4. **发布配置未做**（M7 余项）：`LICENSE` 已补 Apache-2.0 全文；source/javadoc、版本策略、`CHANGELOG.md`
   仍未做，留在 ROADMAP G8。
5. **`ready` 声明位置未做别名兼容**（M5 交付物 5）：本轮只修正文案与启动前校验，节点级 `ready` 兼容别名未做。
6. **stdout 端点兜底依赖 SUT 主动打印**：内核不解析日志行中的其他格式（不做模糊匹配，避免误判）。
7. **G9 的修复是「显式化 + 有界兜底」，不是消除视图滞后本身**：调度侧槽位视图仍可能与实例真实状态短暂
   不一致（双向异步的固有权衡），但现在**不一致不再导致静默丢失或挂起**——要么被拒绝后重排，要么在
   拒绝超限时显式失败。若后续要把「不必要的一次重派」也消掉，需要引入派发租约/确认超时（记为 M8 候选）。
   本地复现尝试：16 核 + 14 进程满载干扰下连跑 8 次全部通过（19.4–22.5s），该缺陷只在 2 vCPU runner 出现。

---

## 6. 复现步骤

```bash
# 1) 全量回归（零 Docker 依赖的确定性档）
./mvnw -o -B test "-Dduo.docker.enabled=false"
bash .github/scripts/skip-summary.sh

# 2) M6 端到端（单独跑，约 8 秒）
./mvnw -o -pl duo-sim-examples -am test -Dtest=ExternalSutAcceptanceTest

# 3) 内核侧 external 通路（约 30 秒）
./mvnw -o -pl duo-sim-kernel -am test -Dtest='ExternalSutLauncherTest,ReadyProbeTest'
```
