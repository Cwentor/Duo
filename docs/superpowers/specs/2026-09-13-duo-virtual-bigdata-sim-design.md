# Duo —— 通用可组合虚拟大数据仿真系统 · 设计文档

- 日期：2026-09-13
- 状态：待用户评审（v0.1）
- 技术栈：Java 17+（LTS），Maven 多模块
- 首期重点：可组装内核闭环 + 调度状态机与容错（已与用户确认）

---

## 1. 背景与问题

测试大数据/分布式系统的调度与协同逻辑（DAG 依赖、重试、失败转移、选主、资源调度），传统做法需要搭建整套集群，环境启动以十分钟计、硬件开销大、故障演练不可重复、反馈回路以小时计。

业界已有三种成熟解法，但各自孤立：

| 先例 | 验证了什么 |
| --- | --- |
| KWOK（Kubernetes Without Kubelet） | 伪造节点即可欺骗真实调度器，单机万级节点可行 |
| Curator TestingServer | JVM 内可运行真实协议的轻量 ZooKeeper |
| Fabric8 Mock Server / H2 / embedded-* | 各类中间件均可内存替身化 |
| Testcontainers | 真协议校验可按需拉起真容器 |

本项目补齐缺失的一层：**行为可配置、可注入故障、可组装成任意链路拓扑的统一仿真框架**。

## 2. 目标与非目标

### 目标（均可验收）

1. **可组装**：一份 YAML 描述组件拓扑与连线，一键拉起/重置。
2. **任意项可测**：拓扑中任一节点可标记为被测对象（SUT），其余节点用替身。
3. **可替换**：同一契约位可在四档实现间切换（见 §6），测试代码零改动。
4. **行为可控**：任务桩按剧本产生状态流转（延迟/成败/异常/假日志/永不回报）。
5. **故障可注入**：时间线剧本（如 T+10s 杀 Worker）+ 运行时热注入 API。
6. **真实反馈**：SUT 连接的是真实协议端口（ZK/JDBC/RPC/HTTP），对端是替身，SUT 无感知。
7. **秒级反馈回路**：单 JVM 运行，容器档之外零 Docker 依赖。
8. **CI 友好**：JUnit5 扩展 + 断言库，场景文件可进版本库。

### 非目标

- 比特级网络模拟（半开连接、乱序）——状态层仿真，不做流量仿真。
- 真实计算引擎内部行为（Spark shuffle、Flink 反压）——留给容器档/预发。
- 分布式仿真内核本身——首期单 JVM；多进程仿真按需演进。
- 性能压测数字的真实性承诺——虚拟心跳吞吐仅作参考。
- 精美 Web 控制台——仅薄层 CLI/REST，视图为可选项。

## 3. 术语

| 术语 | 含义 |
| --- | --- |
| SUT | System Under Test，被测对象，拓扑中标 `sut: true` 的节点 |
| 契约 Contract | 一类组件在链条中的角色语义（如"注册中心"），内核唯一认识的抽象 |
| 档位 Tier | 契约的实现方式：`virtual / embedded / container / real` |
| 场景 Scenario | 拓扑 + 行为剧本 + 时间线的 YAML 描述，一次仿真运行 |
| 故障注入 | 按剧本或运行时向虚拟组件注入异常状态 |

## 4. 总体架构

```
场景 YAML ──► 场景引擎（校验/编排/时间线） ──► 仿真内核（SPI + 组件管理器 + 事件总线）
                                                  │
                     ┌────────────────────────────┼────────────────────────────┐
                     ▼                            ▼                            ▼
               虚拟组件库                    嵌入式替身库                     real 档节点
        VirtualWorker / TaskStub        ZK(Curator) / DB(H2) / K8s      SUT（被测对象）
                     │                            │                            ▲
                     └──────── 真实协议 / 状态事件 ─┴────────── 接线 ────────────┘
                                                  │
                                                  ▼
                                    观测面（事件流/指标/录制/断言）◄── 控制面（REST/CLI 热注入）
```

模块职责（Maven 多模块）：

| 模块 | 职责 | 阶段 |
| --- | --- | --- |
| `duo-sim-kernel` | SPI、契约注册表、组件管理器、事件总线、端点接线 | M0 |
| `duo-sim-scenario` | YAML 解析校验、场景编排、时间线故障注入 | M0 起，M1 深化 |
| `duo-sim-components` | VirtualWorker、TaskStub 等虚拟组件 | M0 |
| `duo-sim-embedded` | Curator TestingServer / H2 / Fabric8 mock 适配器 | M2 |
| `duo-sim-junit` | JUnit5 扩展 `@VirtualCluster` + 断言库 | M1/M2 |
| `duo-sim-control` | REST/CLI 控制面 | M3 |
| `duo-sim-examples` | 场景示例 + demo-scheduler 参考被测实现 | M0 起 |

数据流：YAML → 启动前快速校验 → 按依赖拓扑排序拉起组件 → 端点接线注入 SUT → 运行（事件流持续产生）→ 断言/观测 → 一键重置。

## 5. 契约体系（可组装的核心）

**内核不认识任何具体产品，只认识契约。** ZooKeeper、Spark、Kafka 只是某个契约的某个档位实现。

首批契约清单（以 2~3 个具体组件反推接口，后续按需增补）：

| 契约 | 角色语义 | 必发事件 | 典型实现 |
| --- | --- | --- | --- |
| `registry` | 协调中心：会话、临时节点、watch | 会话建立/断开、节点变更 | Curator TestingServer / 虚拟桩 |
| `store` | 关系库：JDBC、事务、方言差异 | 连接/慢查询/主从切换 | H2 / zonky PG / 容器 |
| `worker` | 执行节点：心跳、资源上报、任务收发 | 心跳、槽位变化、任务状态 | VirtualWorker / 真实 Worker |
| `engine` | 计算引擎：提交→状态流转→终态+日志 | 提交受理、状态流转、完成 | TaskStub / spark-submit |
| `resource` | 资源管理：队列、配额、容器分配 | 配额变化、分配/回收 | YARN/K8s 替身 |
| `message` | 消息：主题、生产/消费语义 | 积压、分区变化 | embedded Kafka / 桩 |
| `filestore` | 文件/对象存储：路径、读写、容量 | 容量告警、读写错误 | 本地 FS 桩 / MiniDFS |

每个契约定义四件事：**必须实现的行为语义、必须发出的事件、配置项、可观测点**。契约接口在 M0 由 `registry`/`worker`/`store` 三个先反推，其余契约只留扩展点，防止过早抽象。

## 6. 实现档位

| 档位 | 含义 | 示例（registry 契约位） |
| --- | --- | --- |
| `virtual` | 纯内存状态桩，无真实协议 | 会话/临时节点的内存状态机 |
| `embedded` | JVM 内运行真实协议实现 | Curator TestingServer |
| `container` | 本地 Docker 按需拉起真容器 | Testcontainers ZK |
| `real` | 被测对象本体（SUT 所在档） | 用户真实组件 |

关键约定：

- **同一契约位换档位，拓扑其他部分与测试代码零改动。** 这是 M0 的验收标准。
- `real` 档由内核负责"接线"而非实现：内核解析其 `wiring` 声明，把替身端点地址注入其配置。接线方式二选一：
  - `in-process`：SUT 是进程内组件，内核直接传入端点上下文启动它；
  - `external`：SUT 由用户自行启动，内核把端点清单写入配置文件/stdout，并轮询 ready 探针等待其上线。

## 7. 内核 SPI（接口草图）

```java
public interface VirtualComponent {
    ComponentId id();
    void init(ComponentContext ctx);        // ctx 提供：配置、SimClock、EventBus、wiring 端点
    void start() throws ComponentException;
    void stop(StopMode mode);               // GRACEFUL | CRASH（CRASH 即故障注入的"宕机"）
    HealthReport health();
    List<ExposedEndpoint> endpoints();      // SUT 需要连接的协议端点
}
```

- **组件管理器**：按 wiring 依赖拓扑排序启动；启动失败逆序拆除已启动组件并报告根因；场景结束统一定期清理。
- **事件总线**：进程内轻量发布/订阅（自研，不引消息中间件）。事件模型：`{type, sourceId, timestamp, payload}`。
- **SimClock**：首期仅真实时钟；可加速虚拟时钟留作 M4 评估项（依赖 SUT 可注入 `Clock`），接口上预留。

## 8. 场景 DSL（YAML）

```yaml
name: worker-crash-failover
topology:
  - id: zk
    contract: registry
    tier: embedded                      # Curator TestingServer
  - id: db
    contract: store
    tier: embedded                      # H2 (MySQL 方言)
  - id: master
    contract: scheduler-master
    tier: real
    sut: true                           # 被测对象
    launch: { mode: in-process, main: com.example.DemoScheduler }
    wiring: { registry: zk, store: db, workers: workers }
  - id: workers
    contract: worker
    tier: virtual
    count: 10
    capacity: { cpu: 4, memGB: 8 }
behaviors:                              # TaskStub 行为剧本
  task-stub:
    default:  { duration: 3s, successRate: 0.9 }
    spark-etl: { duration: 5s, failAt: 60% }
timeline:                               # 故障注入时间线
  - at: 10s
    action: crash
    target: workers[3]
  - at: 20s
    action: registry-flap
    target: zk
    duration: 5s
assertions:
  - failoverWithin: { task: any, seconds: 30 }
  - noTaskLost
```

校验规则（启动前快速失败）：契约名必须已注册；契约位必须有所选档位的实现；`wiring` 引用的节点 id 必须存在且契约匹配；`sut: true` 必须恰好一个；时间线动作的 target 必须可解析。

## 9. 虚拟组件库（首批）

- **VirtualWorker**：单 JVM 内以线程组模拟 N 个逻辑节点；职责为心跳上报、资源槽位上报、领取/执行任务、回报状态。`count × capacity` 决定拓扑规模。
- **TaskStub**：行为剧本驱动的任务响应器，字段首集：

| 字段 | 含义 |
| --- | --- |
| `duration` | 执行时长（支持抖动） |
| `successRate` | 成功率 |
| `failAt` | 进度到达百分比时失败 |
| `exception` | 抛出的异常类型（模拟报错） |
| `logLines` | 向日志流输出的假日志模板 |
| `neverReport` | 领取后永不回报（僵尸任务） |
| `progress` | 进度上报模式 |

- **demo-scheduler**（`examples` 模块）：一个最小参考被测实现，带真实的"调度状态机"（DAG + 重试 + 失败转移），仅用于 M0 验收档位切换，不是产品代码。**第三方调度器适配（如 DolphinScheduler 的 Netty 协议）不在 M0 范围，避免版本耦合阻塞内核。**

## 10. 场景引擎与故障注入

时间线动作首集：`crash`（宕机）、`freeze`（假死不响应）、`slow`（延迟劣化）、`registry-flap`（会话闪断）、`task-kill`、`resource-exhaust`（资源耗尽）、`custom-hook`（用户钩子）。

热注入：内核从 M0 起就内置 `ScenarioRuntime` API（`inject(FaultAction)`），M3 的 REST/CLI 只是其外层包装——保证"先有内核能力，后有控制面"。

## 11. 观测面与断言

- 事件模型统一为 `{type, sourceId, timestamp, payload}`；输出三通道：结构化日志、录制文件（JSON Lines，可回放复现场景）、Prometheus 格式指标。
- 断言首集（JUnit 断言库）：`failoverWithin`、`noTaskLost`、`masterReelectedWithin`、`alertFired`、`eventSequence`。

## 12. 错误处理

- 场景校验失败 → 启动前报错，指明行号与原因，不拉起任何组件。
- 组件启动失败 → 逆序拆除已启动组件，报告根因链。
- 故障注入本身失败（如目标组件已停止）→ 作为一级事件记录并计入场景结果，**不允许静默吞掉**。
- SUT 崩溃视为测试结果而非框架错误：自动保存现场（最近 N 条事件 + 全组件状态快照）。

## 13. 测试策略（框架自身）

- 内核：纯单测（生命周期、拓扑排序、事件总线）。
- 金标准场景集：每个契约至少一个正例一个故障例，CI 全跑。
- M0 验收场景：同一份拓扑，`workers` 节点在 `virtual ↔ real`（demo real worker）间切换档位，测试代码零改动跑通。
- 嵌入式/容器档集成测试：容器档在无 Docker 环境自动 skip。

## 14. 分阶段计划

| 阶段 | 周期（粗估） | 产出 | 验收标准 |
| --- | --- | --- | --- |
| **M0 内核骨架** | 1~2 周 | kernel + scenario(YAML 拓扑) + VirtualWorker/TaskStub v0 + demo-scheduler | 档位切换验收（§13）跑通 |
| **M1 场景与注入** | ~2 周 | 行为剧本全集、时间线注入、事件录制、断言库 v0 | "worker-3 于 T+10s 宕机 → 任务 30s 内转移成功"断言通过 |
| **M2 嵌入中间件** | 1~2 周 | Curator/H2/Fabric8 适配器、registry-flap 注入、`@VirtualCluster` 扩展 | 注册中心闪断 5s → 重新选主且无任务丢失 |
| **M3 控制面** | ~2 周 | REST/CLI 热注入（可选极简拓扑视图） | 运行中手动注入故障并观察自愈 |
| **M4 规模与桥接** | 按需 | 万级心跳压测、Testcontainers 桥、加速时钟评估、第三方 SUT 适配器 | 千~万 Worker 心跳压测报告 |

每个阶段以可运行场景文件 + 通过的验收断言收尾。

## 15. 技术选型

| 选择 | 理由 |
| --- | --- |
| Java 17+ LTS | 与目标生态（Curator/H2/Fabric8/DolphinScheduler）一致 |
| Maven 多模块 | 大数据生态惯例，模块边界即发布单元 |
| SnakeYAML | 场景 DSL 解析，事实标准 |
| 自研轻量事件总线 | 进程内场景，引入 MQ 得不偿失 |
| SLF4J + Logback | 日志门面惯例 |
| JUnit5 | 扩展模型适合 `@VirtualCluster` |
| License：Apache-2.0 | 大数据生态惯例（可再议） |

## 16. 风险与对策

1. **第三方协议耦合**：DolphinScheduler 等 SUT 的 RPC 报文版本耦合。对策：M0 用 demo-scheduler 验证内核，第三方适配放 M4 按需。
2. **桩太乖悖论**：行为太理想化测不出问题。对策：行为剧本内置异常分布（M1 必做，且作为 M1 验收一部分）。
3. **抽象过早**：契约接口膨胀。对策：YAGNI，每接一个新契约才泛化一次接口；契约清单明确"先 3 个反推"。
4. **Windows 环境兼容**：Curator 纯 Java 无碍；zonky PG 等托管二进制组件在 Windows 需逐个验证（M2 时确认，容器档可兜底）。

## 17. 开放问题

1. 第三方 SUT 适配器（如 DolphinScheduler Worker 协议）的优先级与版本基线——M4 前再定。
2. 虚拟时钟加速是否进路线图——依赖 SUT 是否可注入 `Clock`，M4 评估。
