# Duo 开发指南

- 适用版本：`0.1.0-SNAPSHOT`（HEAD `5b72753`，2026-09-18）
- 相关：[架构说明](ARCHITECTURE.md) · [场景 DSL](SCENARIO-DSL.md) · [发展规划](ROADMAP.md)

---

## 1. 环境与工具链

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **21（LTS）** | 项目使用虚拟线程、`record`、文本块、模式匹配 `switch` |
| Maven | **3.9+** | 多模块 reactor（9 个 module 含 parent） |
| Docker | 可选 | **仅** `container` 档需要；无 Docker 时相关测试按设计自动 skip |
| OS | Windows 11 已验证 | Curator/H2/Fabric8 均纯 Java；Testcontainers 需 Docker |

### 1.1 标准 Maven Wrapper（M7 起）

仓库自带标准 Maven Wrapper：`mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`。

```bash
./mvnw -o clean test        # Git Bash / Linux / macOS
.\mvnw.cmd -o clean test    # PowerShell / cmd
```

- 形态为 **script-only**（不提交 `maven-wrapper.jar`）：首次运行按 `maven-wrapper.properties`
  里的 `distributionUrl` 下载 **Maven 3.9.11** 到 `~/.m2/wrapper/dists/`，之后离线可用。
- **只依赖 `JAVA_HOME`**（JDK 21），不再硬编码任何本机路径——`git clone && ./mvnw test` 即可构建。
- 旧的手写壳 `mvnw.sh`（硬编码作者本机 JDK/Maven 路径）已按决策 D6 删除，见
  [`DECISIONS.md`](DECISIONS.md)。

### 1.2 PowerShell（无需 Git Bash）

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'   # 仅需 JAVA_HOME；Maven 由 wrapper 自备
cd D:\Program\Duo
.\mvnw.cmd -o -B clean test
```

> PowerShell 传 `-D` 属性时要**加引号**（否则 `.` 会被当作参数分隔）：
> `.\mvnw.cmd -o test "-Dduo.docker.enabled=false"`。

---

## 2. 常用命令

| 目的 | 命令 |
| --- | --- |
| 全量回归 | `./mvnw -o clean test` |
| 无 Docker 档（CI 回归 job 同款） | `./mvnw -o clean test "-Dduo.docker.enabled=false"` |
| 单模块 | `./mvnw -o -pl duo-sim-kernel -am test` |
| 单测试类 | `./mvnw -o -pl duo-sim-examples -am test -Dtest=FailoverAcceptanceTest` |
| 安装到本地仓库（跑 CLI 前必须） | `./mvnw -o install -DskipTests` |
| 压测（千/万档） | `./mvnw -o -pl duo-sim-examples -am test -Dtest=ScaleAcceptanceTest "-Dduo.scale=true"` |
| CLI 演练（同进程 + 跨进程热注入） | `bash scripts/duo-inject-demo.sh` |
| 依赖树 | `./mvnw -o -pl duo-sim-embedded dependency:tree` |
| 构建类路径（脚本用） | `./mvnw -o -q -pl duo-sim-examples dependency:build-classpath -Dmdep.outputFile=target/demo-classpath.txt -DincludeScope=runtime` |

`-o` ＝离线（本机依赖已就绪，避免网络抖动）；首次构建可去掉。

> **`-pl <module>` 记得带 `-am`**：不带时该模块会解析 `~/.m2` 里**已安装的旧内核 jar**，
> 改了内核源码却报「找不到符号」——这是本仓最容易踩的坑。

### 2.0 CI（`.github/workflows/ci.yml`，M7）

| job | 触发 | 内容 |
| --- | --- | --- |
| `regression` | push / PR | `./mvnw -B -Dduo.docker.enabled=false test`（确定性无 Docker 档）+ skip 汇总 |
| `container` | push / PR | `-pl duo-sim-embedded -am test -Dtest='ZookeeperContainer*'`，并**断言 skip=0**（有 Docker 时不许静默跳过） |
| `scale` | nightly / 手动 | `-Dduo.scale=true`，把 `build/scale/*.json` 与事件录制上传为 artifact（G8「规模数据可追溯」） |

skip 汇总由 `.github/scripts/skip-summary.sh` 输出（`--fail-on-skip` 用于容器档门禁）。

### 2.1 `surefire` 的 Windows 特殊配置

父 POM 给 surefire 设了：

```xml
<argLine>-Djdk.net.URLClassPath.disableClassPathURLCheck=true</argLine>
```

原因：**Windows 多盘符**（项目在 `D:`、`.m2` 在 `C:`）下，surefire 的 Manifest-JAR 类路径检查会拒绝发现测试。
这是官方提示的规避方式，不是可选项——去掉后 Windows 上会「零测试被发现」。

---

## 3. 测试分层与门控

### 3.1 分层

| 层 | 位置 | 内容 |
| --- | --- | --- |
| 单元测试 | 各模块 `src/test/java` | 内核生命周期/拓扑排序/事件总线/restart 语义/实例寻址/接线推断与校验/注册期一致性；组件行为模型；断言语义；DSL 校验 |
| 集成/验收测试 | `duo-sim-examples/src/test/java/.../acceptance/` | 端到端场景：档位切换（M0）、故障转移（M1）、重选主（M2）、控制面热注入（M3）、规模压测（M4）、**external 第三方 SUT（M6）** |
| JUnit 扩展测试 | `examples`（用 `@VirtualCluster`） | 注解生命周期、引擎参数注入、断言评估、录制路径 |

> `duo-sim-junit` / `duo-sim-control` 自身**不带测试**——它们的集成测试必须放 `examples`，
> 否则会形成 `junit ↔ examples` 循环依赖。

### 3.2 当前分布（2026-09-20 实测，worker 侧真实 SUT 落地后）

| 模块 | 测试数 | skip |
| --- | --- | --- |
| `duo-sim-protocol` | 12 | 0 |
| `duo-sim-kernel` | 79 | 0 |
| `duo-sim-scenario` | 55 | 0 |
| `duo-sim-components` | 120 | 0 |
| `duo-sim-embedded` | 57 | **10**（无 Docker：4 条 `ZookeeperContainer` + 6 条 `PostgresContainer`） |
| `duo-sim-junit` | 0 | 0 |
| `duo-sim-control` | 23 | 0 |
| `duo-sim-examples` | 50 | **1**（未开压测开关：`ScaleAcceptanceTest`） |
| **合计（reactor 内 8 模块）** | **396** | **11** |

> **口径说明（第 32 轮修正）**：上表是 `.\mvnw.cmd -o -B test` 的实测输出，逐模块与 Maven 的
> `Tests run:` 行一一对应，不需要再做任何换算。此前版本在这里写过一段"`duo-sim-control` 的测试
> 不在自己模块里，所以 366 + 25 = 391"的换算——那段**已经过时且是错的**：`ScenarioHostTest`（10）
> 与 `RestControlServerTest`（13）第 14 轮就搬进了 `duo-sim-control/src/test`，`DuoCliTest`（10）
> 留在 examples（它编排 `DemoScheduler` 场景，与示例强耦合）。三段合计仍然对得上，
> 但**归属已经清楚**，无需再叠加口径；上表直接就是全量数字。
>
> ⚠️ **注意 `duo-sim-examples` 的 1 条 skip**：`ScaleAcceptanceTest` 是 `@ParameterizedTest`
> 带两个档（`scale-1k.yaml` / `scale-10k.yaml`），`@EnabledIfSystemProperty(duo.scale=true)`
> 关闭时 JUnit 计 **1** 条 skip 而不是 2 条（参数化整体被禁用）。所以"skip=1"说的是
> "压测门控关着"，不是"只跳了一个档"。

```bash
.\mvnw.cmd -o -B test                          # 本机实测：396 测 / 0 失败 / 0 错误 / 11 skip（本机无 Docker）
./mvnw -o -B test "-Dduo.docker.enabled=false" # CI regression job 同款：确定性关闭容器档（skip 口径同上）
.\mvnw.cmd -o -B "-Dquality" "-DskipTests" verify  # 依赖门禁：9 模块零未声明/零未使用（CI regression job 已接入）
bash .github/scripts/skip-summary.sh           # skip 逐条可解释（--fail-on-skip 用于容器档门禁）
```

> **第 12–32 轮明细（366 → 396，净 +30）**：
>
> - **kernel 76 → 79（+3）**：`SutLauncherTest` 与 `ReadyProbeTest` 的 SUT 启动面补测
>   （端点文件口径 / 未解析端点必须留警告而不是静默）。
> - **scenario 51 → 55（+4）**：§8 校验补充（`sut: true` 必须声明 `launch`；
>   SUT 先于 `startComponents()` 启动 ⇒ 端点冻结这条事实的守卫）。
> - **embedded 55 → 57（+2）**、**components 120 → 120（净 0，内部重排）**。
> - **control 0 → 23（+23）**：`ScenarioHostTest`（13）+ `RestControlServerTest`（10）
>   第 14 轮迁入本模块 `src/test`（此前它们在 examples 反应堆步里执行、在本表里没有归属）。
> - **examples 52 → 50（−2，同时移出 13 条 control 测试）**：本次净增 3 条
>   （`WorkerSutAcceptanceTest`：真连 master / BOM 配置 / 断连自愈），另迁出 13 条 control 测试、
>   以及若干为"编排解耦"而合并的重复用例。
>
> **worker 侧真实 SUT（`RealWorkerSut`，本次交付）**：`virtual` 档调度器原来只有 wire 级假 worker
> 的覆盖，**仓库里没有任何 worker 侧 `SutMain` 示例**（§3.2 旧文已诚实记下这一点）。现在
> `duo-sim-examples` 提供完整档实现——真连 master（显式端点 / `master.list` / registry 自注册三条
> 发现路径）、心跳、领取/执行/回报、断连自愈——并由 `WorkerSutAcceptanceTest` 三条用例钉住。
> **同一份调度语义**：master 侧仍是内核 `VirtualScheduler`（`SchedulerStateMachine` +
> `DispatchSelector`），worker 侧是本示例，两档不各写一套状态机。
>
> **一条必须说清的事实（别把断言写成场景调度的镜像）**：`sut: true` 场景里
> `sut.dag-terminal` / `sut.task-terminal state=FAILED` / `sut.task-retry` **结构上不可达**。
> `ScenarioEngine.startSut()` 在 `startComponents()` 之前运行，SUT 会话一结束
> `awaitSutExit` 立刻触发拆卸（`ScenarioEngine.stop()` → `manager.stopAll()`），于是
> 调度器连接断开、在途任务被 `onInstanceLost` 重新入队、而实例已从 `DispatchSelector` 摘除
> ⇒ 无处再派。实测：给 10s 窗口也等不到半条 `sut.task-retry`，事件表里只有
> `sut.task-dispatched/status/terminal → sut.instance-lost → sut.worker-sut-finished → sut.exited`。
> 因此验收断言只取**链路真的产生的事实**（SUT 自报的 `dispatches`、worker 归属的终态、
> `sut.worker-sut-finished.heartbeats`）；DAG 终态那条路径由**不含 `sut` 节点**的场景覆盖。
>
> **本场景有一条预期告警，不是故障**：`node master exposes 'scheduler' but the SUT 'workers'
> was already started when the endpoint was bound …`。它正是上面那条"端点冻结"的如实陈述，
> 也是中继/注册表自注册这条正路存在的理由。验收断言写成"除这条以外没有别的告警"，
> 而不是"一条告警都不许有"——后者等于要求引擎对已知事实闭嘴（§12 要的是说出来）。
>
> **`-Dquality verify` 抓到的一处真实依赖违规**：`duo-sim-control` 的测试夹具
> `ControlFixtureSut` 讲了 19 处协议（`FrameConnection` / `RegisterRequest` / `HeartbeatReport`
> / `SlotReport` / `TaskAck` …），却没在 `pom.xml` 声明 `duo-sim-protocol`，靠传递依赖白用。
> 已补 `test` 范围声明。这条被抓住很有价值：夹具"真的讲协议"是它作为验收样本的全部意义，
> 少了声明，夹具只能假装注册成功。
>
> **同一轮澄清的一处元数据误改**：工作区里 `VirtualSchedulerProvider.metadata()` 曾被改成
> `duoPortWithDirect(...)`（即 `interfaceDirect=true`），想让 direct 槽好写。已回退成
> `duoPort(false, …)`：`interfaceDirect` 只说"同进程能不能直接拿到接口对象"，而 worker SUT
> 在另一条线上只能拨号——声明成 true 会让"真实 worker 侧"验收退化成进程内直调，
> 不再证明线协议可用。两个字段独立（设计 §7.5 v2），消费路径由 `WiringResolver` 逐槽解析，
> 同进程消费者在槽上显式写 `path: direct` 即可，与这个字段无关。

> 上表是**实测值**（Windows，JAVA_HOME 指向 JDK 21）。容器档那 10 条 skip 的原因是**本机无 Docker**，
> 不是设计上应当跳过——CI `container` job（Docker + `--fail-on-skip`）才是它们必须跑通的地方。

> M6 新增 21 条（内核 18：`ExternalSutLauncherTest` 10 + `ReadyProbeTest` 8；examples 3：
> `ExternalSutAcceptanceTest`），M6 修正的注入顺序缺陷另加 1 条（`ScenarioRuntimeTest`），
> scenario 新增 10 条（校验规则 4 的 5 条 + 命令行切分 5 条）。
>
> M5 第 3 轮新增 17 条：protocol 3（`FrameConnectionConcurrentWriteTest`：多线程写同一连接的帧完整性
> 守卫 + 畸形帧显式报错）、scenario 6（`ScenarioLoaderTest`：节点级 `ready` 别名/冲突报错、YAML 列表
> 字段归一）、components 5（`BehaviorResolverM1Test` 4：百分号形态/越界报错/`logLines` 解析；
> `VirtualWorkerTest` 1：`logLines` 逐行落流且顺序在终态之前）、examples 3
> （`CustomHookAcceptanceTest` 2：YAML 端到端 + 未注册名显式失败；`ScenarioHostTest` 1：宿主注入）。
>
> **M5 第 4 轮净增 62 条（280 → 342）**，明细：
>
> - **components 49 → 111（+62）**：新增 44 条——`VirtualFilestoreTest` 8、`VirtualMessageBrokerTest` 7、
>   `VirtualResourceManagerTest` 6、`VirtualEngineTest` 11、`VirtualSchedulerTest` 8（真实 DUO_PORT + 真实
>   registry + 线上假 worker 的 wire 级用例）、`VirtualWorkerTest` 新增 3 条故障用例（该文件现 16 条）；
>   另**接收从 `examples` 迁入的 18 条**（`SchedulerStateMachineTest` 11 + `DispatchSelectorTest` 7）——
>   调度状态机与派发选择器移入 `io.duo.sim.components.scheduler`，两档 scheduler 同源，测试随实现走。
> - **embedded 39 → 55（+16）**：`PostgresContainerStoreTest` 6（Docker 门控，本机 skip）+
>   `PostgresContainerStoreGuardTest` 10（**不标门控**，无 Docker 也可离线验证「不支持＝显式拒绝」）。
> - **examples 57 → 41（−16）**：迁出上述 18 条调度状态机测试，新增 `NewContractsAcceptanceTest` 2 条
>   （场景 `duo-sim-examples/src/test/resources/scenarios/m5-new-contracts-acceptance.yaml`）。
>
> **第 5–11 轮净增 24 条（342 → 366）**，明细：scenario +4（G11 `autoStart` 2 + 未知键/SUT 陷阱 2）、
> components +9（G10 槽位回滚守卫 + message/filestore/resource 故障例）、examples +11
> （M8 观测面 5：指标端点 2 + 因果链日志 2 + 诊断链 1；G4 scheduler 跨档位 3；其余为契约成对补全）。
>
> **本轮自测发现并修复的一处并发缺陷（G9 同类，值得记账）**：`VirtualEngine` 的槽位占用是
> 「先判定 `freeSlots > 0`、再 `decrementAndGet()`」，两条提交通路（wire 的 `onDispatch` 与同进程的
> `submit`）在多线程并发下都会**超发槽位**（计数可为负）。修复＝CAS 原子占槽（`tryReserveSlot()`），
> 判定失败与占槽失败都走**显式拒绝**（`no free slot`）。守卫用例
> `VirtualEngineTest.concurrentSubmissionsNeverOversubscribeSlots`：16 路并发提交、2 个槽位 →
> 实测修复前 **5 个被受理**（超发 3），修复后恒为 2 受理 / 14 显式拒绝。此即 G9「计数必须原子化」
> 纪律在 engine 侧的镜像落地。
>
> **CI 红之一：夹具竞态（已修，非产品缺陷）**：`VirtualSchedulerTest` 的失联用例依赖「失联时任务仍在途」，
> 而假 worker 默认立刻回 SUCCESS —— 快机器恒成立、慢机器不成立（CI run 35341908188 红）。
> 修复＝该用例改为**不回报**（`policy = d -> null`）+ 钉住「此时无终态事实」；
> 连跑 6/6 全绿。同批把 engine 的 slow 判据从比值改为「绝对下限 + 相对比较」，避免 CI 噪声假红。
>
> **CI 红之二：真实并发缺陷（已修）** —— `VirtualEngineTest.restartResetsStateAndRebindsEndpoint`
> 断言 `freeSlots()==2` 实测 3：`stop()` 中断在途线程后立刻 `freeSlots.set(slots)`，被中断线程尚未走完
> `finally`，其归还落到新一代计数上（并会报出假 CANCELLED 终态）。修复＝生命周期代际 `AtomicLong` +
> 任务线程仅在 `gen == generation.get()` 时归还/上报；守卫用例 `restartDoesNotLetStaleTaskThreadsDriftSlotCount`
> 在临时取消代际判定时**必红**（已实测），连跑 4/4 全绿。
> 最终远端取证：run [35343279887](https://github.com/Cwentor/Duo/actions/runs/35343279887)
> `regression` ✅ 342/0/0/11、`container` ✅ 15/15 skip=0。
>
> **尚未闭环（诚实记录）**：M5 交付物 6（金标准场景集，G4：每个契约一正例 + 一故障例）仍开放；
> 容器档 PostgreSQL 的 6 条用例**已在 CI 上取证为绿**（本机无 Docker，只能 skip）——run
> [35341365256](https://github.com/Cwentor/Duo/actions/runs/35341365256)：PG 6/6、ZK 4/4、skip=0 门禁通过。
>
> ~~virtual scheduler 的「worker 侧 SUT」用途尚无真实 `SutMain` 示例（仓库仍无 worker SUT），
> 其自身覆盖是 wire 级 `VirtualSchedulerTest`~~ ——**本条已于第 32 轮闭环**：
> `duo-sim-examples` 交付完整档 `RealWorkerSut`（注册/心跳/领取执行回报/断连自愈）与
> `WorkerSutAcceptanceTest` 三条验收用例（真连 master / BOM 配置 / 断连自愈），
> 场景 YAML 见 `duo-sim-examples/src/main/resources/scenarios/m0-acceptance-real-worker-sut.yaml`。
> 「尚无 worker SUT」这句话从今天起不再成立，保留删除线是为了让读到这里的人知道它被**证伪**过。

### 3.3 三条明文门控

| 门控 | 机制 | 设计依据 |
| --- | --- | --- |
| 容器档 | `@EnabledIf(dockerAvailable)` 自动 skip；**同时**有 15 条不标门控的守卫用例（`ZookeeperContainerRegistryGuardTest` 5 + `PostgresContainerStoreGuardTest` 10）离线验证「不支持＝显式拒绝」 | 设计 §13「容器档在无 Docker 环境自动 skip」；M4 独立验收 MEDIUM 整改 |
| 容器档（确定性关闭） | `-Dduo.docker.enabled=false` 强制 `dockerAvailable()==false`——CI 回归 job 用它让「零 Docker 依赖」成为**确定事实**，而非「恰好这台机器没 Docker」 | M7 / T8「skip 必须可见、可解释」 |
| 压测 | `-Dduo.scale=true` 显式触发（缺省 skip） | M4 计划 D2「压测不进常规回归」 |
| **依赖门禁** | `-Dquality` 激活 `dependency:analyze-only`（绑 `verify`），`failOnWarning=true`——**新增一条"未声明/未使用"告警即构建失败**；缺省不激活 ⇒ 常规回归零开销 | M7 交付物 4（第 11 轮）；基线取证见 [`superpowers/plans/m7-quality-gate-baseline.md`](superpowers/plans/m7-quality-gate-baseline.md) |

**新增测试的纪律**：skip 必须可解释、可复算，且**安全属性不能只被门控覆盖**（容器档守卫用例的教训）。

---

## 4. 扩展点操作步骤

### 4.1 新增一个档位实现

以「为 `registry` 契约新增 `XxxRegistry`（embedded 档的另一种实现）」为例：

1. **实现组件**：`implements VirtualComponent`（生命周期），如需实例级能力再加 `InstanceControl`，
   如需故障注入再加 `FaultInjectable`。
2. **实现契约接口**（如 `RegistryContract`）——**仅 direct 路径需要**；纯 wire 实现可跳过。
3. **写 Provider**：`implements ComponentProvider`，`contract()/tier()/implName()/isDefault()/metadata()/newComponent()`。
   - 同 `(contract, tier)` 已有一个 `default: true` 时，新实现必须 `isDefault() == false`，
     并在场景里用 `impl: <implName>` 显式指定。
4. **声明元数据**：`endpointShape` / `interfaceDirect` / `instanceControl` / `supportedFaults` / `defaultImpl`。
   ⚠️ 三条注册期一致性校验会**在注册时**拒绝元数据与实现不符的 Provider（§7.5）。
5. **登记 SPI**：把全限定类名加进该模块的 `src/main/resources/META-INF/services/io.duo.sim.kernel.spi.ComponentProvider`。
6. **测试 + 文档**：单测（契约语义、生命周期、故障、停止语义）+ 场景级验收；更新
   [README 契约矩阵](../README.md#6-契约--档位当前已实现的实现清单) 与 [DSL 组件配置键](SCENARIO-DSL.md#7-内置组件-config-键参考)。

### 4.2 新增一个契约

契约按「**每个新契约才泛化一次接口**」的 YAGNI 节奏引入（§5）：

1. `Contract` 枚举加值；判断是否**交互型**（`isInteractive()`）——交互型 virtual 档走 Duo 线协议。
2. 在 `duo-sim-kernel/.../contract/` 定义契约接口（行为语义、必发事件、配置项、可观测点四件事）。
3. 若为交互型：在 `duo-sim-protocol` 增报文（`DuoMessage` 的 `@JsonSubTypes` 注册）。
4. 实现至少一个档位（见 §4.1），并让 `ScenarioValidator` 的交互型档位守卫（`InteractiveTierGuard`）覆盖新契约。
5. 金标准场景：**每个契约至少一个正例 + 一个故障例**（§13），进 CI。
6. 更新 [架构文档 §5](ARCHITECTURE.md#5-契约与档位) 与 README 契约矩阵。

### 4.3 新增一个故障动作

1. `FaultAction` 加常量。
2. 目标组件 `implements FaultInjectable`，在 `inject()`/`clear()` 中实现（`clear` 幂等）。
3. Provider 的 `supportedFaults` 加入该动作（否则注册期一致性校验失败/校验期拒绝）。
4. `ScenarioValidator` 规则 6 会自动覆盖（读 `supportedFaults`）——**无需改校验器**。
5. 需要事件与断言参与时，发 `sim.*` 事件（框架动作）或让 SUT 发 `sut.*` 事实。
6. 单测（幂等、未声明动作被拒、停止后注入语义）+ 场景级验证。
7. 更新 [DSL 动作表](SCENARIO-DSL.md#31-动作类型与实现状态)。

### 4.4 新增一个断言

1. 在 `kernel/assertion/Assertions.java` 加静态工厂，返回 `Assertion`（`name()` + `evaluate(List<Event>)`）。
   **语义必须钉死**：起点事件、成功判据、窗口边界（参照 `failoverWithin` 的注释）。
2. `AssertionParser` 加 `case`（YAML 形态）。
3. `ScenarioValidator` 的断言解析校验自动覆盖（未知断言名启动前失败）。
4. 可选：`DuoAssertions` 加编程式包装。
5. 单测必须包含**反例**（无起点事件 / 超窗 / 无受影响对象）——断言最怕「永远为真」。
6. 更新 [DSL 断言表](SCENARIO-DSL.md#6-断言-assertions)。

### 4.5 新增一个 DSL 字段

链路是**四段**，缺一段就会「写了不生效」：

```
Scenario.model（record 字段）
  → ScenarioLoader（解析 + 展平到 config）
  → ScenarioValidator（校验规则；该报错就报错，不静默）
  → ScenarioEngine / 组件（消费）
  → docs/SCENARIO-DSL.md（文档）
```

> 历史上的「写了不生效」正来自这条链路断在最后一段（如 `ready.timeout`、`logLines`、`jitter: 20%`），
> 见 [DSL 现状与偏差](SCENARIO-DSL.md#8-现状与设计偏差务必先读)。

---

## 5. 模块依赖纪律

```
protocol  ← components, examples
kernel    ← scenario, components, embedded, junit, control, examples
scenario  ← junit, control, examples
components/embedded/control/junit ← examples（唯一聚合点）
```

- **禁止环**。`kernel` 不得依赖 `scenario`（拓扑信息由 `WiringResolver.NodeView` 适配传入）。
- 新增第三方依赖：版本进父 POM `dependencyManagement`；**注意传递依赖的 JUnit 版本冲突**
  （`curator-test`、`kubernetes-server-mock` 都要排除 `junit-jupiter-api`/`junit-platform-commons`，
  否则 surefire 测试发现失败——这是踩过的坑）。
- `jackson-annotations` 在 `duo-sim-embedded` 中被显式钉到 `2.18.2`：Testcontainers → docker-java 会传递 `2.10.3`，
  压过 Fabric8 mock 需要的 `2.17+`（`JsonKey` 缺失 → `NoClassDefFoundError`）。
- **依赖门禁（第 11 轮起）**：本地与 CI 都跑 `-Dquality ... verify`（`dependency:analyze-only`，
  `failOnWarning=true`）。它把「主代码用了但没声明」（靠传递依赖编译、上游改版即断）与
  「声明了但没人用」（多余的依赖面）都变成**构建失败**。
  - 直接依赖就**显式声明**：`junit-jupiter-api`/`params`、`jackson-core`/`annotations`、
    `curator-test`（examples）都是被这条门禁逼出来的真修复。
  - 确实需要但源码不 import 的（SLF4J 后端、JDBC 驱动、聚合件、端到端宿主模块的 compile 依赖），
    在父 POM 的 `ignoredUnusedDeclaredDependencies` / `ignoredUsedUndeclaredDependencies` /
    `ignoredNonTestScopedDependencies` 里**逐条豁免并写理由**——豁免清单是**账本**，不是橡皮擦；
    新增豁免必须能一句话说清"为什么这条不是缺陷"。
  - 基线（7 类告警逐条原文 + 真修复 vs 有意保留的取舍）见
    [`superpowers/plans/m7-quality-gate-baseline.md`](superpowers/plans/m7-quality-gate-baseline.md)。

---

## 6. 编码与文档约定

1. **注释写「为什么」而非「是什么」**，且引用设计文档章节（`§7.2`）与计划/提交号（`T18`、`016914f`）。
   现有代码大量使用「修复了哪个真实缺陷」的注释——**保留这种风格**，它是防回归的知识资产。
2. **不可变优先**：`record` + `Map.copyOf`/`List.copyOf`；跨线程集合显式选型（`CopyOnWriteArrayList` 用于只追加事件流）。
3. **无降级路径**：能力不足＝显式失败 + 一级事件，绝不静默改写语义（§7.2）。
4. **不静默**：错误要么抛（启动前/校验期），要么记事件 + 计入 `ScenarioResult`（运行期）。
5. **事件命名**：`sim.`＝框架事件、`sut.`＝SUT 事实；实例级 `sourceId` 用 `componentId-N`。
6. **测试命名**：`<被测行为><期望>`（如 `readyThenImmediateCrashIsNotStartupFailure`）；
   断言消息要能直接定位根因；**避免自旋等待**，用限时轮询（M4 教训：`onSpinWait` 预算在高负载下耗尽）。
7. **中文文档 + 中文提交信息**（仓库既有风格），提交信息包含：做了什么 + 实测结果 + 关键决策。

---

## 7. 提交与验收流程

1. **设计先行**：语义变更先改/新增计划文档（`docs/superpowers/plans/`），设计冻结稿不动（§[文档维护约定](README.md#4-文档维护约定)）。
2. **实现 + 单测**：按 §4 的扩展点步骤落地。
3. **全量回归**：`./mvnw -o -B test "-Dduo.docker.enabled=false"` 必须 BUILD SUCCESS，并记录**实测测试分布**（不要写估算值）。
4. **场景级验收**：金标准场景 + 断言通过；必要时跑 `scripts/duo-inject-demo.sh`。
5. **落验收记录**：`docs/superpowers/acceptance/YYYY-MM-DD-duo-mN-<主题>-record.md`，
   含验收标准对照表、实测数字、缺陷处置、限制说明。
6. **同步工程文档**：按 [文档索引的「何时需要改它」](README.md#2-工程文档) 一栏执行。
7. **提交**：提交信息里写明实测数字与对应提交号（如「实测全仓 396 测 / 11 skip（无 Docker）」）。

---

## 8. 已知工程债（勿踩坑清单）

| # | 债 | 影响 | 计划 |
| --- | --- | --- | --- |
| 1 | ~~`mvnw.sh` 硬编码本机工具链路径，非标准 Wrapper~~ **已闭合（M7：标准 Wrapper，D6）** | — | ✅ |
| 2 | ~~无 CI 配置~~ **已闭合（M7：三 job + skip 可见性脚本）；待远端首跑取证** | — | ✅/待取证 |
| 3 | 无 `LICENSE` 文件（设计 §15 已选 Apache-2.0） | 法务状态不明确 | M7 |
| 4 | 无 logback 配置 | 「结构化日志」通道名存实亡 | M8 |
| 5 | 无 Prometheus 指标导出 | 观测三通道只落地两条 | M8 |
| 6 | ~~`external` SUT 引擎未实现~~ **已闭合（M6，D7/D9）** | — | ✅ |
| 7 | `ready.timeout` 已消费（M6 ✅）；`logLines` / `jitter: 20%` / `failAt: 60%` 仍断链 | 写了不生效或直接抛异常 | M5 |
| 8 | `custom-hook` 无法从引擎注入 `HookRegistry` | YAML 时间线用不了自定义钩子 | M5 |
| 9 | 压测产物在 `build/`（gitignore 覆盖）；**CI scale job 已上传为 artifact** | 本地仍不随提交留存 | M7 部分 ✅ |
| 10 | `build/verify-016914f/` 等一次性复验脚手架留在工作区（被 `.gitignore` 覆盖） | 历史遗留，可按需清理 | 随时 |

---

## 9. 排查手册

| 现象 | 可能原因 | 处置 |
| --- | --- | --- |
| Windows 上「零测试被发现」 | 缺 `disableClassPathURLCheck` | 检查父 POM surefire `argLine` |
| 测试发现失败 / `NoClassDefFoundError: JsonKey` | 传递依赖版本冲突（JUnit / jackson-annotations） | 见 §5 的排除与钉版本 |
| 场景报 `no implementation registered for X/Y` | 该档位实现不在 classpath | 给 `examples`（或你的模块）加模块依赖 + SPI 文件 |
| 场景校验失败但看不懂 | 按 [DSL 常见报错速查](SCENARIO-DSL.md#9-常见报错速查) 对照 | — |
| 心跳/行为剧本「配置不生效」 | **构建产物陈旧**（改的是源码，跑的是旧 jar） | `./mvnw -o install -DskipTests`，或单模块用 `-pl X -am`（不带 `-am` 会解析旧 jar） |
| CLI 报 `ConnectException` / 端口占用 | `serve` 未就绪或遗留进程占端口 | `--port 0` 让内核分配；清理遗留 JVM |
| 录制文件被覆盖 | 两个运行共用场景名 | 并发运行同一场景需改名 |
