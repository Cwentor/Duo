# Duo —— 通用可组合虚拟大数据仿真系统 · 设计文档

- 日期：2026-09-13
- 状态：v0.2 修订版，待用户复核
- 技术栈：Java 21（LTS），Maven 多模块
- 首期重点：可组装内核闭环 + 调度状态机与容错（已与用户确认）
- 修订记录见文末附录

---

## 1. 背景与问题

测试大数据/分布式系统的调度与协同逻辑（DAG 依赖、重试、失败转移、选主、资源调度），传统做法需要搭建整套集群，环境启动以十分钟计、硬件开销大、故障演练不可重复、反馈回路以小时计。

业界已有四类成熟先例，但各自孤立：

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
3. **可替换**：同一契约位可在四档实现间切换（见 §6，切换受契约类型约束），测试代码零改动。
4. **行为可控**：任务桩按剧本产生状态流转（延迟/成败/异常/假日志/永不回报）。
5. **故障可注入**：时间线剧本（如 T+10s 杀 Worker）+ 运行时热注入 API；支持崩溃后重启以验证 SUT 对节点恢复的感知。
6. **真实反馈**：分契约类型——标准协议契约（registry/store/message 等）在 `embedded` 档以上暴露真实第三方协议端口，SUT 无感知直连；交互型契约（worker/engine/scheduler 等）的 `virtual` 档暴露框架自定义的 **Duo 线协议**真实端口（定义见 §3）。即：对端是替身，但连接、端口与报文是真实的。第三方 SUT 接入交互型契约需协议适配器（→ §16 风险 1）。
7. **秒级反馈回路**：单 JVM 运行，容器档之外零 Docker 依赖。
8. **CI 友好**：JUnit5 扩展 + 断言库，场景文件可进版本库。

### 非目标

- 比特级网络模拟（半开连接、乱序）——状态层仿真，不做流量仿真。
- 真实计算引擎内部行为（Spark shuffle、Flink 反压）——留给容器档/预发。
- 分布式仿真内核本身——首期单 JVM；多进程仿真按需演进。
- 性能压测数字的真实性承诺——虚拟心跳吞吐仅作参考。
- external SUT 内部事实的全量可观测承诺——观测能力按 §7.3 三途径界定。
- 精美 Web 控制台——仅薄层 CLI/REST，视图为可选项。

## 3. 术语

| 术语 | 含义 |
| --- | --- |
| SUT | System Under Test，被测对象，拓扑中标 `sut: true` 的节点 |
| 契约 Contract | 一类组件在链条中的角色语义（如"注册中心"），内核唯一认识的抽象 |
| 档位 Tier | 契约的实现方式：`virtual / embedded / container / real` |
| Duo 线协议 | 框架自定义的交互型契约线协议（真实 TCP 端口 + 报文格式），供 real 档组件/SUT 直连 virtual 档交互型组件；不冒充任何第三方产品协议 |
| 场景 Scenario | 拓扑 + 行为剧本 + 时间线的 YAML 描述，一次仿真运行 |
| 故障注入 | 按剧本或运行时向虚拟组件注入异常状态 |
| 事件发布门面 | SUT 向框架事件总线发布内部事实（选主、任务终态等）的 API |

## 4. 总体架构

```
场景 YAML ──► 场景引擎（校验/编排/时间线） ──► 仿真内核（SPI + 组件管理器 + 事件总线）
                                                  │
                     ┌────────────────────────────┼────────────────────────────┐
                     ▼                            ▼                            ▼
               虚拟组件库                    嵌入式/容器替身库                 real 档节点
      VirtualWorker / TaskStub        ZK(Curator) / DB(H2) / K8s /     真实实现（其一是 SUT，
      （virtual 档，Duo 线协议）        Testcontainers 桥                如 demo real worker）
                     │                            │                            ▲
                     └──── 真实协议 / Duo 协议 / 状态事件 ──── 接线 ────────────┘
                                                  │
                                                  ▼
                                    观测面（事件流/指标/录制/断言）◄── 控制面（REST/CLI 热注入）
```

模块职责（Maven 多模块）：

| 模块 | 职责 | 阶段 |
| --- | --- | --- |
| `duo-sim-kernel` | SPI、契约注册表、组件管理器、事件总线、wiring 接线、SUT 适配面 | M0 |
| `duo-sim-scenario` | YAML 解析校验、场景编排、时间线故障注入 | M0 起，M1 深化 |
| `duo-sim-components` | VirtualWorker、TaskStub 等虚拟组件（virtual 档交互型实现） | M0 |
| `duo-sim-embedded` | embedded 档适配（Curator TestingServer / H2 / Fabric8 mock）**及 container 档适配（Testcontainers 桥，M4 交付）** | M2 起 |
| `duo-sim-junit` | JUnit5 扩展 `@VirtualCluster` + 断言库 | M1/M2 |
| `duo-sim-control` | REST/CLI 控制面 | M3 |
| `duo-sim-examples` | 场景示例 + demo-scheduler / demo real worker（参考被测实现） | M0 起 |

数据流：YAML → 启动前快速校验（契约/档位/端口可达性）→ 按依赖拓扑排序拉起组件 → wiring 接线注入 → 运行（事件流持续产生）→ 断言/观测 → 一键重置。

## 5. 契约体系（可组装的核心）

**内核不认识任何具体产品，只认识契约。** ZooKeeper、Spark、Kafka 只是某个契约的某个档位实现。

契约分两类，**类型决定 virtual 档的形态与换档约束**（见 §6）：

| 契约 | 类型 | 角色语义 | 必发事件 | 典型实现（档位） |
| --- | --- | --- | --- | --- |
| `registry` | 标准协议 | 协调中心：会话、临时节点、watch | 会话建立/断开、节点变更 | 内存状态机（virtual）/ Curator TestingServer（embedded） |
| `store` | 标准协议 | 关系库：JDBC、事务、方言差异 | 连接/慢查询/主从切换 | H2（embedded，M2）/ zonky PG（embedded）/ 容器 |
| `worker` | 交互型 | 执行节点：心跳、资源上报、任务收发 | 心跳、槽位变化、任务状态 | VirtualWorker（virtual）/ demo real worker（real） |
| `engine` | 交互型 | 计算引擎：提交→状态流转→终态+日志 | 提交受理、状态流转、完成 | TaskStub（virtual）/ spark-submit（real） |
| `scheduler` | 交互型 | 调度器：依赖编排、重试与失败转移决策 | 任务派发、状态流转、转移/重选主 | demo-scheduler（real，examples）/ 虚拟调度桩（virtual） |
| `resource` | 标准协议 | 资源管理：队列、配额、容器分配 | 配额变化、分配/回收 | YARN/K8s 替身（embedded/container） |
| `message` | 标准协议 | 消息：主题、生产/消费语义 | 积压、分区变化 | embedded Kafka / 桩 |
| `filestore` | 标准协议 | 文件/对象存储：路径、读写、容量 | 容量告警、读写错误 | 本地 FS 桩（virtual）/ MiniDFS（embedded） |

契约接口反推节奏（YAGNI）：**M0 反推 `registry / worker / engine / scheduler` 四个**（闭环场景所需）；`store` 随 M2 的 H2 适配反推；`message / filestore / resource` 按需反推。每个契约定义四件事：**必须实现的行为语义、必须发出的事件、配置项、可观测点**。

## 6. 实现档位

| 档位 | 含义 | 示例（registry / worker 契约位） |
| --- | --- | --- |
| `virtual` | 纯虚拟实现。**按契约类型分两种形态**：标准协议契约＝进程内状态机，无端口；交互型契约＝暴露 Duo 线协议的真实 TCP 端口（不冒充第三方产品协议，但连接与报文是真实的） | registry：进程内状态机（无端口）/ worker：VirtualWorker，Duo 端口 |
| `embedded` | JVM 内运行真实第三方协议实现 | registry：Curator TestingServer |
| `container` | 本地 Docker 按需拉起真容器（Testcontainers 桥，归 `duo-sim-embedded` 模块，M4 交付） | registry：Testcontainers ZK |
| `real` | **真实实现——通常是 SUT，也可以是任意真实组件**（如 M0 验收用的 demo real worker） | scheduler：demo-scheduler（SUT）/ worker：demo real worker（非 SUT） |

关键约定：

- **同一契约位换档位，拓扑其他部分与测试代码零改动**（M0 验收标准）。
- **换档约束**：external 节点的标准协议契约依赖必须指向 `embedded+` 档位（virtual 档无端口可连），校验失败时提示升级档位；in-process 节点无此限制（可选接口直连，见 §7.3）。交互型契约四档全通（virtual 档有 Duo 端口）。
- `real` 档由内核负责"接线"而非实现（接线方式见 §7.3）。
- 第三方产品接入交互型契约 = 为该产品写 Duo 线协议适配器（→ §16 风险 1）。

## 7. 内核 SPI（接口草图）

### 7.1 组件 SPI

```java
public interface VirtualComponent {
    ComponentId id();
    void init(ComponentContext ctx);        // ctx 提供：配置、SimClock、EventBus、wiring 注入
    void start() throws ComponentException;
    void stop(StopMode mode);               // GRACEFUL | CRASH（CRASH 即故障注入的"宕机"）
    void restart();                         // 默认实现：端点与身份保留，内部状态清空，重新 init/start
    HealthReport health();
    List<ExposedEndpoint> endpoints();      // 对应拓扑 exposes 声明的协议端点
}
```

- **组件管理器**：按 wiring 依赖拓扑排序启动；启动失败逆序拆除已启动组件并报告根因；场景结束统一清理。
- **重启语义**：`crash` 后组件处于停止态，必须经 `restart` 恢复上线（端点与身份不变、内部状态视为全新实例）——用于测试 SUT 对节点恢复的感知；时间线动作 `restart` 触发同一语义。

### 7.2 故障注入 SPI

```java
public interface FaultInjectable {          // 契约级可选能力接口
    Set<FaultType> supportedFaults();       // freeze / slow / registry-flap / resource-exhaust ...
    void inject(FaultAction action);        // 带 duration 的动作到期自动 clear
    void clear(FaultAction action);
}
```

时间线与热注入引擎在注入前校验 target 组件支持该动作（`FaultInjectable.supportedFaults`，或 `crash/restart` 的生命周期支持），不支持则作为注入失败处理（→ §12）。

### 7.3 SUT 适配面（断言能否成立的前提）

**in-process SUT**（如 demo-scheduler）：

- 经 `launch.main` 指定启动类，内核通过 `SutLauncher` 调起并注入 `SutContext`：端点清单、wiring 直连对象（可选接口直连）、配置变量。
- SUT 声明 ready 探针（端点监听就绪回调），内核据此判定 SUT 上线。
- **SUT 必须经 `SutEventPublisher` 门面把内部关键事实发布为事件**（任务终态、重试发生、失败转移、选主完成）——这是 `noTaskLost`、`masterReelectedWithin` 等断言的事实源；demo-scheduler 演示此模式。

**external SUT**（用户自行启动）：

- 内核把端点清单写入配置文件/stdout，轮询 ready 探针等待上线。
- 观测三途径：① **替身侧旁路事件**（主途径，M1 断言基于此：心跳、任务状态回报、registry 会话与临时节点变化均为事件——如"重选主"可由 registry 侧临时节点变更旁路推断）；② 日志/指标侧车规则（可选扩展点）；③ 用户自定义探针。
- 明确边界：框架承诺①，②③为扩展点，不承诺 external SUT 内部事实全覆盖（→ 非目标）。

### 7.4 事件总线与 SimClock

- 事件总线：进程内轻量发布/订阅（自研，不引消息中间件）。事件模型：`{type, sourceId, timestamp, payload}`。
- SimClock：首期仅真实时钟；可加速虚拟时钟留作 M4 评估项（依赖 SUT 可注入 `Clock`），接口上预留。

## 8. 场景 DSL（YAML）

```yaml
name: worker-crash-failover
topology:
  - id: zk
    contract: registry
    tier: virtual                        # 进程内状态机；in-process SUT 接口直连
  - id: master
    contract: scheduler
    tier: real
    sut: true                            # 被测对象
    launch: { mode: in-process, main: com.example.DemoScheduler }
    exposes: [ scheduler ]               # 对外端点角色声明（Duo 线协议）
    wiring:
      registry: { node: zk, contract: registry }
  - id: workers
    contract: worker
    tier: virtual
    count: 10
    capacity: { cpu: 4, memGB: 8 }
    wiring:                              # 依赖方向可指向 SUT
      scheduler: { node: master, contract: scheduler }
behaviors:
  profiles:                              # 行为剧本
    default:   { duration: 3s, jitter: 20%, successRate: 0.9 }
    spark-etl: { duration: 5s, failAt: 60% }
  bindings:                              # 剧本绑定到节点 + 任务匹配
    - node: workers
      profile: default
    - node: workers
      match: { taskName: "spark-*" }     # 匹配优先级：精确名 > 标签 > 通配 > default
      profile: spark-etl
timeline:                                # 故障注入时间线
  - at: 10s
    action: crash
    target: workers[3]
  - at: 20s
    action: registry-flap
    target: zk
    duration: 5s
  - at: 25s
    action: restart
    target: workers[3]
assertions:                              # 运行时内置评估，场景结果直接判 pass/fail
  - failoverWithin: { task: any, seconds: 30 }
  - noTaskLost
```

校验规则（启动前快速失败）：

1. 契约名必须已注册；契约位必须有所选档位的实现。
2. wiring 槽必须显式声明 `{node, contract}`；允许简写 `槽名: 节点id`，仅当槽名与已注册契约同名；槽的期望契约必须与目标节点契约一致。
3. external 节点的标准协议契约依赖必须指向 `embedded+` 档位（virtual 档无端口），失败提示升级档位。
4. `sut: true` 必须恰好一个；节点 id 唯一。
5. 时间线动作的 target 必须可解析，且目标支持该动作（→ §7.2）。
6. 每个行为剧本必须有至少一个绑定或作为 default。

## 9. 虚拟组件库（首批）

- **VirtualWorker**：基于 Java 21 虚拟线程，每个逻辑节点一个虚拟线程；职责为心跳上报、资源槽位上报、领取/执行任务、回报状态；对外暴露 Duo 线协议端口。`count × capacity` 决定拓扑规模。
- **TaskStub**：行为剧本驱动的任务响应器，字段首集：

| 字段 | 含义 |
| --- | --- |
| `duration` | 执行时长（支持抖动） |
| `jitter` | 时长抖动幅度（如 `20%`） |
| `successRate` | 成功率 |
| `failAt` | 进度到达百分比时失败 |
| `exception` | 抛出的异常类型（模拟报错） |
| `logLines` | 向日志流输出的假日志模板 |
| `neverReport` | 领取后永不回报（僵尸任务） |
| `progress` | 进度上报模式 |

- **demo-scheduler**（`examples` 模块）：最小参考被测实现，带真实的调度状态机（DAG + 重试 + 失败转移），**M0 版为纯内存实现、不依赖 store 契约**；经 `SutEventPublisher` 发布内部事实，作为 in-process SUT 适配面的示范。仅用于验收，不是产品代码。
- **demo real worker**（`examples` 模块）：worker 契约的 real 档参考实现（讲 Duo 线协议），供 M0 验收"virtual ↔ real 换档"使用。
- **第三方调度器适配（如 DolphinScheduler 的 Netty 协议）不在 M0 范围**，避免版本耦合阻塞内核（→ §16 风险 1）。

## 10. 场景引擎与故障注入

时间线动作首集：`crash`（宕机）、`restart`（重启恢复上线）、`freeze`（假死不响应）、`slow`（延迟劣化）、`registry-flap`（会话闪断）、`resource-exhaust`（资源耗尽）、`task-kill`（终止进行中的桩任务并触发状态回报，**M1 精确定义**）、`custom-hook`（用户钩子）。

热注入：内核从 M0 起就内置 `ScenarioRuntime` API（`inject(FaultAction)`），M3 的 REST/CLI 只是其外层包装——保证"先有内核能力，后有控制面"。

## 11. 观测面与断言

- 事件模型统一为 `{type, sourceId, timestamp, payload}`；输出三通道：结构化日志、事件流录制（JSON Lines，用于事后回放审查与回归比对；真实时钟下不承诺确定性逐字节重放）、Prometheus 格式指标。
- **断言双轨分工**：YAML `assertions`＝运行时内置评估，场景结束直接判 pass/fail（供 CLI/CI 使用）；JUnit 断言库＝测试代码编程式组合（支持时序、窗口、聚合）。二者共用同一事件事实源。
- 断言首集：`failoverWithin`、`noTaskLost`、`masterReelectedWithin`（external SUT 由 registry 侧旁路推断，见 §7.3）、`eventSequence`；`alertFired` 待 M1 定义告警事件语义后纳入。

## 12. 错误处理

- 场景校验失败 → 启动前报错，指明行号与原因，不拉起任何组件。
- 组件启动失败 → 逆序拆除已启动组件，报告根因链。
- 故障注入失败（如 target 不支持该动作、组件已停止）→ 作为一级事件记录并计入场景结果，**不允许静默吞掉**。
- SUT 崩溃视为测试结果而非框架错误：自动保存现场（最近 N 条事件 + 全组件状态快照）。

## 13. 测试策略（框架自身）

- 内核：纯单测（生命周期、拓扑排序、事件总线、restart 语义）。
- 金标准场景集：每个契约至少一个正例一个故障例，CI 全跑。
- **M0 验收场景**：同一份拓扑（zk 用 registry 的 virtual 档接口直连 + master 为 real 档 demo-scheduler + workers），将 `workers` 节点在 `virtual`（VirtualWorker）↔ `real`（demo real worker，Duo 线协议）之间切换档位，测试代码零改动跑通。
- 嵌入式/容器档集成测试：容器档在无 Docker 环境自动 skip。

## 14. 分阶段计划

| 阶段 | 周期（粗估） | 产出 | 验收标准 |
| --- | --- | --- | --- |
| **M0 内核骨架** | 1~2 周 | kernel（SPI/管理器/事件总线/SUT 适配面）+ scenario（YAML 拓扑与校验）+ registry/worker/engine/scheduler 四契约 + VirtualWorker/TaskStub v0 + demo-scheduler（纯内存）与 demo real worker | §13 档位切换验收跑通 |
| **M1 场景与注入** | ~2 周 | 行为剧本全集（profiles/bindings/jitter）、时间线注入（含 restart/task-kill）、事件录制、断言库 v0 | "worker-3 于 T+10s 宕机 → 任务 30s 内转移成功"断言通过 |
| **M2 嵌入中间件** | 1~2 周 | Curator/H2/Fabric8 适配器（store 契约随 H2 反推）、registry-flap 注入、`@VirtualCluster` 扩展 | 注册中心闪断 5s → 重新选主且无任务丢失 |
| **M3 控制面** | ~2 周 | REST/CLI 热注入（可选极简拓扑视图） | 运行中手动注入故障并观察自愈 |
| **M4 规模与桥接** | 按需 | 万级心跳压测（虚拟线程调优）、Testcontainers 桥（embedded 模块容器档）、加速时钟评估、第三方 SUT 协议适配器 | 千~万 Worker 心跳压测报告 |

每个阶段以可运行场景文件 + 通过的验收断言收尾。

## 15. 技术选型

| 选择 | 理由 |
| --- | --- |
| Java 21 LTS | 虚拟线程支撑万级逻辑节点（§9/§14 M4）；生态（Curator/H2/Fabric8）兼容无碍 |
| Maven 多模块 | 大数据生态惯例，模块边界即发布单元 |
| SnakeYAML | 场景 DSL 解析，事实标准 |
| 自研轻量事件总线 | 进程内场景，引入 MQ 得不偿失 |
| SLF4J + Logback | 日志门面惯例 |
| JUnit5 | 扩展模型适合 `@VirtualCluster` |
| License：Apache-2.0 | 大数据生态惯例（可再议） |

## 16. 风险与对策

1. **第三方协议耦合**：第三方 SUT（如 DolphinScheduler）接入交互型契约需实现 Duo 线协议适配器，且其自有 RPC 报文存在版本耦合。对策：M0 用 demo-scheduler / demo real worker 验证内核，第三方适配放 M4 按需。
2. **桩太乖悖论**：行为太理想化测不出问题。对策：行为剧本内置异常分布（jitter/successRate/failAt，M1 必做且作为验收一部分）。
3. **抽象过早**：契约接口膨胀。对策：YAGNI，契约按 §5 反推节奏逐个引入，每接一个新契约才泛化一次接口。
4. **Windows 环境兼容**：Curator 纯 Java 无碍；zonky PG 等托管二进制组件在 Windows 需逐个验证（M2 时确认，容器档可兜底）。

## 17. 开放问题

1. 第三方 SUT 协议适配器（如 DolphinScheduler Worker 协议）的优先级与版本基线——M4 前再定。
2. 虚拟时钟加速是否进路线图——依赖 SUT 是否可注入 `Clock`，M4 评估。

---

## 附录：修订记录

- **v0.1（2026-09-13）**：初稿。
- **v0.2（2026-09-13）**：依用户评审修订——
  1. virtual 档按契约类型重定义（交互型契约暴露 Duo 线协议真实端口，标准协议契约 virtual 档仅进程内），目标 6 加限定，新增换档约束校验（§2/§3/§6/§8）；
  2. 补 `scheduler` 契约进首批清单，示例 YAML 改用已注册契约（§5/§8）；
  3. real 档定义放宽为"真实实现，通常为 SUT 也可为任意真实组件"，架构图同步修正（§4/§6）；
  4. store 契约随 M2 H2 适配反推，demo-scheduler M0 版纯内存，M0 反推契约调整为 registry/worker/engine/scheduler（§5/§9/§14）；
  5. wiring 改为显式契约槽声明 + 简写规则，依赖方向泛化（可指向 SUT），节点增加 exposes 声明（§7.3/§8）；
  6. 新增 FaultInjectable 故障注入 SPI 与注入前校验（§7.2/§10/§12）；
  7. 新增 restart 生命周期语义与时间线动作（§7.1/§8/§10）；
  8. 新增 SUT 适配面：SutContext/ready 探针/SutEventPublisher 事件门面，external SUT 观测三途径与能力边界（§7.3/§11）；
  9. behaviors 增加 profiles/bindings 绑定与任务匹配规则、jitter 字段（§8/§9）；
  10. Java 基线升至 21（虚拟线程）；container 档归入 duo-sim-embedded 模块（§4/§9/§15）；
  11. 小修：四类先例、"统一清理"、回放表述、断言双轨分工、task-kill/alertFired 标注 M1 定义（§1/§7.1/§10/§11）。
