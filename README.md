# Duo —— 通用可组合虚拟大数据仿真系统

> 用一份 YAML 在**单 JVM** 里搭出大数据/分布式系统的整条链路（注册中心、存储、资源管理、Worker、调度器），
> 把真正要测的那一个节点换成你的真实实现（SUT），然后在秒级反馈回路里对它注入故障、观察自愈、用断言判成败。

| 项目 | 说明 |
| --- | --- |
| 版本 | `0.1.0-SNAPSHOT`（`io.duo:duo-sim-parent`） |
| 技术栈 | Java 21（LTS）· Maven 多模块 · SnakeYAML · Jackson · Curator/H2/Fabric8/Testcontainers |
| 阶段状态 | **M0–M8 均已实施完成并验收；差距清单 G1–G11 全部闭合**（唯一保留项：M8 交付物 4「加速时钟评估」等触发条件）；**M9 Phase A 完成（2026-09-25）：首个真实第三方系统 DolphinScheduler 3.4.3 registry-flap 演练全绿** |
| 最近全量回归 | 2026-09-25 · `.\mvnw.cmd -o -B test` → **397 测 / 0 失败 / 0 错误 / 12 skip**（逐模块分布见 [开发指南 §3.2](docs/DEVELOPMENT.md)；skip 逐条可解释：容器档 10 + 压测 1 + M9 真实 SUT 演练门控 1） |
| 质量门禁 | `.\mvnw.cmd -o -B "-Dquality" -DskipTests verify` → 依赖"零未声明/零未使用"（已进 CI） |
| 设计依据 | [设计文档 v1.0（冻结）](docs/superpowers/specs/2026-09-13-duo-virtual-bigdata-sim-design.md) |

**里程碑进度**：`M0` 内核骨架 ✅ · `M1` 场景与注入 ✅ · `M2` 嵌入中间件 ✅ · `M3` 控制面 ✅ ·
`M4` 规模与桥接 ✅ · `M5` 契约与档位补全 ✅ · `M6` external SUT ✅ · `M7` 工程化与 CI ✅ · `M8` 观测面 ✅

---

## 1. 它解决什么问题

测试大数据系统的调度与协同逻辑（DAG 依赖、重试、失败转移、选主、资源调度），传统做法要搭整套集群：
启动以十分钟计、硬件开销大、故障演练不可重复、反馈回路以小时计。

业界已有四类成熟先例，但各自孤立：

| 先例 | 验证了什么 |
| --- | --- |
| KWOK（Kubernetes Without Kubelet） | 伪造节点即可欺骗真实调度器 |
| Curator TestingServer | JVM 内可运行**真实协议**的轻量 ZooKeeper |
| Fabric8 Mock / H2 | 各类中间件均可内存替身化 |
| Testcontainers | 真协议校验可按需拉起真容器 |

Duo 补齐的是缺失的那一层：**行为可配置、故障可注入、可组装成任意链路拓扑的统一仿真框架**——
内核只认识「契约」，不认识任何具体产品。

## 2. 四个核心概念

| 概念 | 含义 |
| --- | --- |
| **契约 Contract** | 组件在链路中的角色语义（`registry`/`store`/`worker`/`engine`/`scheduler`/`resource`/`message`/`filestore`）。内核唯一认识的抽象 |
| **档位 Tier** | 契约的实现方式：`virtual < embedded < container < real`。同一契约位可在档位间切换，**测试代码零改动** |
| **SUT** | System Under Test，拓扑中标 `sut: true` 的唯一节点，通常是 `real` 档的真实实现 |
| **wiring** | 节点消费依赖的方式：`wire`（走真实协议端口/线协议）或 `direct`（同 JVM 注入契约 Java 接口），由能力元数据推断或显式指定 |

## 3. 总体架构

```
                   场景 YAML
                       │
                       ▼
   ┌───────────────────────────────────┐        ┌──────────────────────────────┐
   │ 场景引擎                           │        │ 控制面                        │
   │ 校验 / 编排 / 时间线 / 录制 / 钩子  │        │ REST（`duo serve`）+ CLI      │
   └────────────────┬──────────────────┘        │ 热注入 · 状态 · 事件 · 拓扑    │
                    │                            └───────────────┬──────────────┘
                    ▼                                            │
   ┌───────────────────────────────────┐                        │
   │ 仿真内核                           │◄───────────────────────┘
   │ SPI · 契约注册表 · 组件管理器 · 事件总线 · wiring 解析        │
   └────────────────┬──────────────────┘
                    │
     ┌──────────────┼──────────────────────────┬─────────────────────┐
     ▼              ▼                          ▼                     ▼
 虚拟组件库     嵌入式替身库              容器档替身              real 档节点
 VirtualWorker  Curator ZK (真协议)      Testcontainers ZK      真实实现（其一是 SUT）：
 VirtualRegistry H2 (真 JDBC)            PostgreSQL 16          kernel-hosted 或 external
 VirtualScheduler Fabric8 K8s Mock                             例：DemoScheduler（master 侧）
 VirtualEngine  ...                                            RealWorkerSut（worker 侧）
     │              │                          │                     │
     └──────────────┴──── 真实协议 / Duo 线协议 / 状态事件 ──────────┘
                    │
                    ▼
        观测面：事件流（唯一事实来源）· 日志（因果链）· 指标（/metrics）· 断言
```

## 4. 模块地图

每个模块都有自己的 `README.md`（职责/依赖/关键类/测试/踩坑）：

| 模块 | 职责 | 关键类 |
| --- | --- | --- |
| [`duo-sim-protocol`](duo-sim-protocol/README.md) | Duo 线协议帧格式、编解码、契约报文；第三方适配器只依赖此工件 | `FrameCodec` `DuoCodec` `DuoMessage` |
| [`duo-sim-kernel`](duo-sim-kernel/README.md) | SPI、契约注册表与能力元数据、组件管理器、实例寻址、事件总线、wiring 解析、SUT 适配面、断言内核 | `VirtualComponent` `ComponentProvider` `ContractRegistry` `WiringResolver` `ScenarioRuntime` `SutLauncher` `Assertions` |
| [`duo-sim-scenario`](duo-sim-scenario/README.md) | YAML 解析与校验（规则 1–8）、场景编排、时间线注入、事件录制、自定义钩子 | `ScenarioLoader` `ScenarioValidator` `ScenarioEngine` `TimelineScheduler` `EventRecorder` `HookRegistry` |
| [`duo-sim-components`](duo-sim-components/README.md) | virtual 档组件：`VirtualWorker`（内嵌 TaskStub 行为模型）、`VirtualRegistry`、`VirtualScheduler`、`VirtualEngine`、`VirtualFilestore`、`VirtualMessageBroker`、`VirtualResourceManager` | `VirtualWorker` `VirtualRegistry` `VirtualScheduler` `VirtualEngine` `BehaviorProfile` `BehaviorResolver` |
| [`duo-sim-embedded`](duo-sim-embedded/README.md) | embedded/container 档：Curator TestingServer、H2、Fabric8 K8s Mock、Testcontainers ZK/PostgreSQL | `CuratorRegistry` `H2Store` `PostgresContainerStore` `Fabric8K8sMock` `ZookeeperContainerRegistry` |
| [`duo-sim-junit`](duo-sim-junit/README.md) | JUnit5 扩展 `@VirtualCluster` + 编程式断言 `DuoAssertions` | `VirtualCluster` `VirtualClusterExtension` `DuoAssertions` |
| [`duo-sim-control`](duo-sim-control/README.md) | REST + CLI 控制面（热注入、状态、事件、断言、拓扑） | `ScenarioHost` `RestControlServer` `DuoCli` |
| [`duo-sim-examples`](duo-sim-examples/README.md) | 参考 SUT `demo-scheduler`、`demo real worker`、**worker 侧真实 SUT `RealWorkerSut`**（注册/心跳/领取执行回报/断连自愈）、金标准场景与全部验收测试 | `DemoScheduler` `DemoRealWorker` `RealWorkerSut` |

模块依赖方向（**无环**）：

```
protocol ← components / examples
kernel   ← scenario / junit / control / embedded / components
examples ← 聚合全部（junit / control 的集成测试落在 examples，避免模块循环依赖）
```

> 测试归属口径：`duo-sim-control` 的契约测试（`ScenarioHostTest` / `RestControlServerTest`）在**本模块**
> `src/test` 内执行；与示例强耦合的编排用例 `DuoCliTest` 留在 `examples`。三段各自归属清楚，
> 不需要任何跨模块换算（详见 [开发指南 §3.2](docs/DEVELOPMENT.md)）。

## 5. 快速开始

### 5.1 环境

- **JDK 21**（本项目用虚拟线程、`record`、文本块）
- **Maven 3.9+**
- 可选：**Docker**（仅 `container` 档需要；无 Docker 时相关测试按设计自动 skip）

仓库自带**标准 Maven Wrapper**（`mvnw` / `mvnw.cmd` + `.mvn/wrapper/`，script-only 形态），
只需 `JAVA_HOME` 指向 JDK 21，无需预装 Maven、无需改任何文件：

```bash
./mvnw -o clean test           # 全量回归（Git Bash / Linux / macOS）
.\mvnw.cmd -o clean test       # PowerShell / cmd
```

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'   # 仅需 JAVA_HOME；Maven 3.9.11 由 wrapper 自备
.\mvnw.cmd -o -B clean test
```

> 首次运行会按 `.mvn/wrapper/maven-wrapper.properties` 下载 Maven 3.9.11 到 `~/.m2/wrapper/dists/`。
> 旧的手写壳 `mvnw.sh`（硬编码作者本机路径）已删除，见 [决策 D6](docs/DECISIONS.md)。

### 5.2 构建与测试

```bash
./mvnw -o -B test "-Dduo.docker.enabled=false"       # 397 测（12 条设计门控 skip：容器档 10 + 压测 1 + M9 真实 SUT 演练 1）
./mvnw -o -pl duo-sim-examples -am test -Dtest=ScaleAcceptanceTest "-Dduo.scale=true"
                                                     # 千/万 Worker 心跳压测（≥5 分钟，>1GB 堆）
./mvnw -o install -DskipTests                        # 安装到本地仓库（跑 CLI 演练前需要）
```

CI（[`.github/workflows/ci.yml`](.github/workflows/ci.yml)）分三个 job：

| job | 触发 | 内容 |
| --- | --- | --- |
| `regression` | push / PR | 无 Docker 全量回归 + 依赖门禁（`-Dquality`）+ skip 清单可见 |
| `container` | push / PR | 有 Docker，跑容器档并断言 **skip=0**（真 PostgreSQL 6/6 + 真 ZK 4/4） |
| `scale` | nightly / 手动 | 千/万 Worker 心跳压测，产物留档 |

容器档已在 CI 上**四次取证为绿**：runs
[35341365256](https://github.com/Cwentor/Duo/actions/runs/35341365256)、
[35343279887](https://github.com/Cwentor/Duo/actions/runs/35343279887)、
[35417427531](https://github.com/Cwentor/Duo/actions/runs/35417427531)、
[35420349133](https://github.com/Cwentor/Duo/actions/runs/35420349133)。

> **两件必须分清的事**：① 本机无 Docker，这 10 条只能 skip——所以「本机绿」与「容器档绿」不是同一件事，
> 别互相代替；② 本地 `master` 领先 `origin/master` 若干提交，这些提交**没有 CI 运行**，
> 引用取证时只能引用上面这些已跑过的版本。

### 5.3 跑一个场景（CLI 控制面）

```bash
# 单命令：启动 → 3s 后热注入 crash workers[2] → 等 SUT 退出 → 打印结果，退出码即结论
./mvnw -o install -DskipTests
export JAVA_HOME=/path/to/jdk-21     # 脚本要求 JAVA_HOME 必填（安全审计 L-5：不再回落本机路径）
bash scripts/duo-inject-demo.sh
```

演练脚本内的两条命令等价于（下文 `duo` 是 `java -cp <classpath> io.duo.sim.control.cli.DuoCli` 的简写，
脚本负责拼类路径；仓库**不提供** `duo` 可执行文件）：

```bash
duo run duo-sim-examples/src/main/resources/scenarios/m3-inject-demo.yaml \
    --inject-after 3s "crash workers[2]" --wait      # 同进程模式

# 跨进程模式：serve 必须给令牌（或用 DUO_TOKEN / --token-file）；
# 不给就拒绝启动——除非显式 --insecure-no-auth（仅供纯本机调试）
duo serve <scenario.yaml> --port 0 --token dev-secret &
duo status  --url http://127.0.0.1:<port> --token dev-secret
duo topology --url http://127.0.0.1:<port> --token dev-secret
duo inject crash workers[2] --url http://127.0.0.1:<port> --token dev-secret
duo events --since 0 --url http://127.0.0.1:<port> --token dev-secret
duo assert  --url http://127.0.0.1:<port> --token dev-secret    # 退出码反映断言通过与否
duo metrics --url http://127.0.0.1:<port> --token dev-secret    # Prometheus 文本格式（可直接被 scrape）
duo diagnose --url http://127.0.0.1:<port> --token dev-secret   # 一次注入的完整因果链；断链退出码 1
```

> **控制面安全口径（安全审计 2026-09-20）**：控制面只监听 `127.0.0.1`，但「能连上端口」**不是**信任边界
> ——同机进程与浏览器里的任意网页都连得上。因此：① 除 `/health` 外所有端点要求 Bearer 令牌；
> ② `Host`/`Origin` 必须回环（挡 DNS-rebinding / CSRF）；③ 请求体上限 1 MiB。
> 经 `POST /scenario` 提交的场景按**外部输入档**校验：禁 `launch.command`、禁框架外 `sut.main`、
> `config` 键白名单、值禁绝对路径与 URL scheme。本机 YAML（CLI/测试）不受此限。
> 细节见 `docs/ARCHITECTURE.md` §12.1 与 `docs/DECISIONS.md` D10/D11。

### 5.4 观测面：三条通道怎么用（M8）

§11 承诺的三条通道相互**互补**，不要互相替代：

| 通道 | 面向 | 入口 | 何时用 |
| --- | --- | --- | --- |
| 事件流 | 机器 | `events.jsonl` / `duo events` | 断言与录制（唯一的"事实来源"） |
| 日志 | 人 | 控制台 / `grep FAULT` | 读因果链："谁对谁做了什么、为什么被拒" |
| 指标 | 聚合系统 | `GET /metrics` | 趋势与告警："心跳吞吐掉了没有" |

日志默认控制台可读，第三方（ZK/Curator/Netty/Testcontainers/H2）已降噪到 WARN。
两个开关：

```bash
-Dduo.log.level=DEBUG     # 打开 Duo 自身的 DEBUG（默认 INFO）
-Dduo.log.json=INFO       # 额外输出结构化 JSON 行（默认关闭；零新增依赖）
```

单命令导出一次故障注入的因果链（注入 → 组件反应 → SUT 事实 → 断言）：

```bash
duo diagnose --url http://127.0.0.1:<port>
# 来源: http://127.0.0.1:51422  事件总数: 608  注入次数: 1
#
# [1] 注入 crash → workers-1  (事件 #6)
#     结果: 已下达  窗口至事件 #598
#     组件反应 (5): sim.worker-instance-crashed@workers-1  sim.worker-task-status@workers-2 …
#     SUT 事实 (586 条，8 类): sut.heartbeat×564 […]  sut.task-dispatched×4 […] …
#
# 断言: 1 条，失败 0 条
#     PASS noTaskLost: 4 task(s) terminal, all SUCCESS
```

**断链会被显式报出**：注入之后没有任何 `sut.*` 事实 ⇒ 报告打 `MISSING` 并列出原因、
退出码非 0（"注入下达了但被测对象没反应"是最需要被看见的情形）。
指标口径（19 个指标族逐个口径 + 已知边界）见 [`docs/METRICS.md`](docs/METRICS.md)。

### 5.5 写一个场景测试（JUnit5）

```java
@VirtualCluster("/scenarios/junit-extension-smoke.yaml")
class MyScenarioTest {

    @Test
    void dagShouldSurviveWorkerCrash(ScenarioEngine engine) {
        // engine 已按 YAML 启动；测试体里可热注入、读事件、读断言结果
        engine.inject(new FaultAction("crash",
                FaultAction.ComponentAddress.ofInstance(new ComponentId("workers"), 1),
                Map.of(), null));
        DuoAssertions.assertThat(engine.events())
                .affectedTasksAtLeast(1)
                .noTaskLostAllSuccess();
    }
}
```

## 6. 契约 × 档位（当前已实现的实现清单）

| 契约 | 类型 | virtual | embedded | container | real |
| --- | --- | --- | --- | --- | --- |
| `registry` | 标准协议 | `VirtualRegistry`（进程内状态机，端点 NONE，支持 `registry-flap`） | `CuratorRegistry`（真实 ZK 端口 + 同进程门面，支持 `registry-flap`） | `ZookeeperContainerRegistry`（Testcontainers ZK，**不支持 flap/restart**） | — |
| `worker` | 交互型 | `VirtualWorker`（Duo 协议端口，实例级控制，支持 `task-kill`/`freeze`/`slow`/`resource-exhaust`） | —（交互型无此形态） | — | `DemoRealWorker`（kernel-hosted 参考实现）、**`RealWorkerSut`（worker 侧 SUT：真拨号 + 注册/心跳/领取/回报/断连自愈）** |
| `scheduler` | 交互型 | `VirtualScheduler`（Duo 协议端口 + 注册 `/duo/endpoints/scheduler`，支持 `freeze`；需 **DIRECT** registry 接线） | — | — | `DemoScheduler`（**参考 SUT**） |
| `engine` | 交互型 | `VirtualEngine`（DUO_PORT 服务端：首帧 `task-dispatch` 后 `task-cancel`；另有进程内 `EngineContract.submit`，支持 `freeze`/`slow`/`resource-exhaust`） | — | — | — |
| `store` | 标准协议 | — | `H2Store`（真实 H2 内存库 + JDBC URL） | `PostgresContainerStore`（Testcontainers `postgres:16-alpine`，**不支持 `restart()`**，显式拒绝 `store.jdbcUrl`） | — |
| `resource` | 标准协议 | `VirtualResourceManager`（配额分配器，支持 `resource-exhaust`） | `Fabric8K8sMock`（真实 K8s REST 协议） | — | — |
| `message` | 标准协议 | `VirtualMessageBroker`（每主题 FIFO；`message.maxDepthPerTopic` 缺省 10000） | — | — | — |
| `filestore` | 标准协议 | `VirtualFilestore`（FS_PATH 临时根目录或 `filestore.root`；拒绝 `../` 逃逸） | — | — | — |

> `scheduler` 两档共用同一套 DAG/重试/失败转移实现：`SchedulerStateMachine` 与 `DispatchSelector` 位于
> `duo-sim-components`（M5 第 4 轮由 `duo-sim-examples` 迁入，18 条测试随实现迁移）。
> 故障动作支持矩阵：`freeze` = worker + engine + scheduler；`slow` = worker + engine；
> `resource-exhaust` = worker + engine + resource（见 [架构说明](docs/ARCHITECTURE.md) §5.4）。

**换档矩阵**（同一条链路，谁是 SUT 可以换）：

| 组合 | 场景 / 用例 | 覆盖了什么 |
| --- | --- | --- |
| virtual × virtual | `m0-acceptance-virtual-workers.yaml` | SUT = `DemoScheduler`（master 侧） |
| real × real | `m0-acceptance-real-workers.yaml` | SUT = `DemoScheduler`，worker 为 `DemoRealWorker` |
| **real 档 worker SUT × virtual 档 master** | `m0-acceptance-real-worker-sut.yaml` + `WorkerSutAcceptanceTest` | **SUT = `RealWorkerSut`（worker 侧）**：真发现 → 拨号 → 注册 → 心跳 → 领取/执行/回报 → 断连自愈 |

档位缺口与补全计划见 [路线图 M5](docs/ROADMAP.md#m5--契约与档位补全广度)。

## 7. 一个最小场景

```yaml
name: worker-crash-failover
topology:
  - id: zk
    contract: registry
    tier: virtual                    # 进程内状态机（无端点）→ wiring 缺省推断 direct
  - id: master
    contract: scheduler
    tier: real
    sut: true                        # 全场景恰好一个
    launch: { mode: in-process, main: io.duo.sim.examples.scheduler.DemoScheduler }
    config: { dag.tasks: "job-a,job-b,job-c,job-d" }
    exposes: [{ contract: scheduler, port: 0 }]      # port 0 = 内核分配
    wiring:
      registry: { node: zk, contract: registry }
  - id: workers
    contract: worker
    tier: virtual
    count: 4
    capacity: { slots: 1 }
    wiring:
      registry: { node: zk, contract: registry }
behaviors:
  profiles:
    default: { duration: 15s, jitter: 0.1, successRate: 1.0 }
  bindings:
    - node: workers
      profile: default
timeline:
  - at: 10s
    action: crash
    target: workers[3]               # 实例下标从 1 开始
  - at: 27s
    action: restart
    target: workers[3]
assertions:
  - affectedTasksAtLeast: { min: 1 } # 防空真守护
  - failoverWithin: { seconds: 30 }
  - noTaskLost: { requireAllSuccess: true }
  - eventSequence: [sim.fault-injected, sut.task-retry]
```

字段全集、校验规则与断言语义：[`docs/SCENARIO-DSL.md`](docs/SCENARIO-DSL.md)。

> **换到 worker 侧**：把 `sut: true` 从 `master` 移到 `workers` 节点（`tier: real`、
> `launch.main: io.duo.sim.examples.worker.RealWorkerSut`），master 改回 `tier: virtual` 即可——
> SUT 变成被调度方，其余 YAML 结构不变。完整例子见
> [`m0-acceptance-real-worker-sut.yaml`](duo-sim-examples/src/main/resources/scenarios/m0-acceptance-real-worker-sut.yaml)。

## 8. 现状与差距（一页速览）

| 原始目标（设计 §2） | 现状 | 证据 / 缺口 |
| --- | --- | --- |
| 1 可组装（YAML 描述拓扑） | ✅ 已达成 | `ScenarioLoader` + `WiringResolver` 拓扑排序启动 |
| 2 任意项可测（SUT + 替身） | 🟡 部分 | in-process 与 **external（M6：零依赖第三方进程端到端验收）** 均已闭环；8 个契约**都已有替身实现**；**worker 侧真实 SUT 已落地**（`RealWorkerSut` + `WorkerSutAcceptanceTest` 三条端到端用例，master 侧复用内核同一份状态机）；缺口＝金标准场景集（G4：每契约一正例 + 一故障例）仍开放 |
| 3 可替换（换档零改动） | 🟡 部分 | M0 `TierSwapAcceptanceTest` 通过；`registry`（三档）、`store`/`resource`/`worker`/`scheduler`（各两档）已有多档实现；`scheduler` 两档同源（`SchedulerStateMachine`） |
| 4 行为可控（任务桩剧本） | ✅ 已达成 | `BehaviorProfile` 8 字段全集（M1） |
| 5 故障可注入（时间线 + 热注入） | ✅ 已达成 | `crash`/`restart`/`registry-flap`/`task-kill`/`custom-hook` 已落地；**M5 第 4 轮**补齐 `freeze`（worker/engine/scheduler）、`slow`（worker/engine）、`resource-exhaust`（worker/engine/resource），均幂等且已声明 `supportedFaults` |
| 6 真实反馈（真协议端口） | ✅ 已达成 | embedded 档暴露真实 ZK/JDBC/K8s 端口；交互型走 Duo 线协议（**worker 侧也是真协议**：`RealWorkerSut` 经 `FrameConnection` 注册/心跳/领取/回报）；container 档另有真 PostgreSQL + 真 ZK，**已在 CI `container` job 四次取证为绿**（本机无 Docker 时 10 条容器用例按设计 skip） |
| 7 秒级反馈回路（单 JVM 零 Docker） | ✅ 已达成 | 全量回归 **397 测 / 0 失败 / 0 错误 / 12 skip**（10 条容器档因本机无 Docker、1 条未开压测开关、1 条未开 `-Dduo.ds=true` 真实 SUT 演练门控——其常驻守卫 `DsFailoverDrillGuardTest` 无门控进常规回归；`-Dduo.docker.enabled=false` 让「无 Docker」成为确定事实） |
| 8 CI 友好（JUnit5 + 断言 + 场景入版本库） | ✅ 已达成 | 扩展/断言库 + 标准 Wrapper + CI 三 job（M7；远端连续全绿）+ 发布产物（source/javadoc + CHANGELOG）+ **依赖门禁（`-Dquality`，第 11 轮，已进 CI）** |
| — 观测面（设计 §11，M8） | ✅ 已达成 | 三条通道全落地：事件流录制（既有）+ 日志（`logback.xml`/`logback-test.xml`，`io.duo.sim.fault` 因果链）+ 指标（19 个指标族的 `/metrics`）；单命令因果链导出 `duo diagnose` |

**一句话总结**：1/4/5/6/7/8 与观测面已达成；2/3 剩的是**广度**而非能力——
缺口清单（G1–G11）已全部闭合，唯一登记在案的保留项是 M8 交付物 4「加速时钟评估」
（触发条件「小时级长稳场景 + virtual 档」尚未出现，`SimClock` 接口已预留，无返工成本）。

完整差距分析与阶段计划见 **[docs/ROADMAP.md](docs/ROADMAP.md)**。

## 9. 文档索引

| 文档 | 内容 |
| --- | --- |
| [`docs/README.md`](docs/README.md) | 全仓文档索引与阅读路径 |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 架构详解：模块、SPI、契约与档位、接线规则、事件总线、SUT 适配面、控制面、线协议 |
| [`docs/SCENARIO-DSL.md`](docs/SCENARIO-DSL.md) | 场景 DSL 参考手册（字段全集 / 校验规则 1–8 / 断言语义 / 内置组件 config 键） |
| [`docs/METRICS.md`](docs/METRICS.md) | `/metrics` 指标口径（19 个指标族的语义、刷新时机与已知边界） |
| [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) | 开发指南：环境、构建、测试分层、扩展点、约定、已知工程债 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 发展规划：目标达成度盘点、差距清单 G1–G11、阶段计划与验收口径 |
| [docs/DECISIONS.md](docs/DECISIONS.md) | 决策台账：拍板记录（决定/理由/触发条件/落点）与修订记录 |
| [`docs/superpowers/specs/`](docs/superpowers/specs) | 设计文档 v1.0（冻结，唯一依据） |
| [`docs/superpowers/plans/`](docs/superpowers/plans) | 各阶段实施计划 |
| [`docs/superpowers/acceptance/`](docs/superpowers/acceptance) | 验收记录、万级压测报告、独立复验记录 |
| [`CHANGELOG.md`](CHANGELOG.md) | 版本策略与未发布/已发布变更 |

## 10. 许可

**Apache-2.0**（设计文档 §15 选定，大数据生态惯例）——完整许可文本见仓库根目录 [`LICENSE`](LICENSE)。

---

<div align="center">

**Duo** · 用一份 YAML，把大数据链路的故障演练压进秒级反馈回路

</div>
