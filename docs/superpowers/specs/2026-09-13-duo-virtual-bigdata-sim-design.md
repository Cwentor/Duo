# Duo —— 通用可组合虚拟大数据仿真系统 · 设计文档

- 日期：2026-09-13
- 状态：v0.4 修订版，待用户复核
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
3. **可替换**：同一契约位可在档位间切换（可用档位与切换约束见 §6），测试代码零改动。
4. **行为可控**：任务桩按剧本产生状态流转（延迟/成败/异常/假日志/永不回报）。
5. **故障可注入**：时间线剧本（如 T+10s 杀 Worker）+ 运行时热注入 API；支持实例级寻址（如只杀 10 个虚拟 Worker 中的第 3 个）与崩溃后重启。
6. **真实反馈**：分契约类型——标准协议契约（registry/store/message 等）在 `embedded` 档以上暴露真实第三方协议端口，SUT 无感知直连（个别契约的 virtual 档存在可达形态，如 filestore 本地 FS 桩）；交互型契约（worker/engine/scheduler 等）的 `virtual` 档暴露框架自定义的 **Duo 线协议**真实端口（定义见 §3）。即：对端是替身，但连接、端口与报文是真实的。第三方 SUT 接入交互型契约需协议适配器（→ §16 风险 1）。
7. **秒级反馈回路**：单 JVM 运行，容器档之外零 Docker 依赖。
8. **CI 友好**：JUnit5 扩展 + 断言库，场景文件可进版本库。

### 非目标

- 比特级网络模拟（半开连接、乱序）——状态层仿真，不做流量仿真。
- 真实计算引擎内部行为（Spark shuffle、Flink 反压）——留给容器档/预发。
- 分布式仿真内核本身——首期单 JVM；多进程仿真按需演进。
- 性能压测数字的真实性承诺——虚拟心跳吞吐仅作参考。
- external SUT 内部事实的全量可观测承诺——观测能力按 §7.3 三途径界定；in-process 适配面也仅适用于可改码的 SUT（§7.3 显式边界）。
- SUT 作为故障注入目标——in-process 无法安全强杀，external 生命周期归用户（→ §7.2/§7.3）。
- 精美 Web 控制台——仅薄层 CLI/REST，视图为可选项。

## 3. 术语

| 术语 | 含义 |
| --- | --- |
| SUT | System Under Test，被测对象，拓扑中标 `sut: true` 的节点 |
| 契约 Contract | 一类组件在链条中的角色语义（如"注册中心"），内核唯一认识的抽象 |
| 档位 Tier | 契约的实现方式：`virtual / embedded / container / real`；`embedded+` 记法指 embedded 及以上（档位序 virtual < embedded < container < real） |
| Duo 线协议 | 框架自定义的交互型契约线协议（真实 TCP 端口 + 报文格式），供 real/virtual 档的交互型组件与 SUT 之间**任意方向**直连；不冒充任何第三方产品协议 |
| 连接路径 | 节点消费依赖的两种方式：**wire-protocol**（走真实协议端口/线协议）与 **interface-direct**（同 JVM 注入契约 Java 接口）；由 wiring 槽的 `path: wire\|direct` 显式指定或缺省推断（推断规则见 §6） |
| external 节点 | 不由内核拉起、由用户自行启动的节点（当前仅 real 档可能出现）；内核只负责生成端点配置与探测就绪 |
| 实例 | `count > 1` 的组件展开出的逻辑节点；寻址记法 `componentId[index]`，**索引从 1 开始**（`workers[3]` 即实例 `workers-3`） |
| 能力元数据 | 实现在契约注册表注册时声明的静态能力（端点形态、是否支持同进程直连/实例级操作、支持的故障类型等）；**启动前校验的唯一事实源**（§7.5） |
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
      VirtualWorker / VirtualRegistry /  ZK(Curator) / DB(H2) / K8s /   真实实现（其一是 SUT）：
      TaskStub（virtual 档，Duo 协议      Testcontainers 桥              kernel-hosted 或 external
      或进程内状态机）                                                   （demo real worker 为前者）
                     │                            │                            ▲
                     └──── 真实协议 / Duo 协议 / 状态事件 ──── 接线 ────────────┘
                                                  │
                                                  ▼
                                    观测面（事件流/指标/录制/断言）◄── 控制面（REST/CLI 热注入）
```

模块职责（Maven 多模块）：

| 模块 | 职责 | 阶段 |
| --- | --- | --- |
| `duo-sim-protocol` | **Duo 线协议的帧格式、编解码与各契约报文定义**；第三方适配器只依赖此协议工件、不依赖内核内部 | M0 |
| `duo-sim-kernel` | SPI、契约注册表与能力元数据、组件管理器、实例寻址、事件总线、wiring 接线、SUT 适配面 | M0 |
| `duo-sim-scenario` | YAML 解析校验、场景编排、时间线故障注入 | M0 起，M1 深化 |
| `duo-sim-components` | virtual 档组件：VirtualWorker（内嵌 TaskStub 行为模型）、VirtualRegistry（registry 内存状态机）等 | M0 |
| `duo-sim-embedded` | embedded 档适配（Curator TestingServer / H2 / Fabric8 mock）**及 container 档适配（Testcontainers 桥，M4 交付）** | M2 起 |
| `duo-sim-junit` | JUnit5 扩展 `@VirtualCluster` + 断言库 | M1/M2 |
| `duo-sim-control` | REST/CLI 控制面 | M3 |
| `duo-sim-examples` | 场景示例 + demo-scheduler / demo real worker（参考被测实现） | M0 起 |

数据流：YAML → 启动前快速校验（契约/档位/连接路径/端点形态/固定端口可用性，均读静态元数据）→ 按依赖拓扑排序拉起组件 → wiring 接线注入 → 启动后 ready 探测（端口可达性在此阶段确认）→ 运行（事件流持续产生）→ 断言/观测 → 一键重置（含 SUT 协作式停止）。

## 5. 契约体系（可组装的核心）

**内核不认识任何具体产品，只认识契约。** ZooKeeper、Spark、Kafka 只是某个契约的某个档位实现。

契约分两类，**类型决定 virtual 档形态与实际可用档位**（见 §6）：

| 契约 | 类型 | 角色语义 | 必发事件 | 典型实现（档位） |
| --- | --- | --- | --- | --- |
| `registry` | 标准协议 | 协调中心：会话、临时节点、watch | 会话建立/断开、节点变更 | VirtualRegistry（virtual）/ Curator TestingServer（embedded） |
| `store` | 标准协议 | 关系库：JDBC、事务、方言差异 | 连接/慢查询/主从切换 | H2（embedded，M2）/ zonky PG（embedded）/ 容器 |
| `worker` | 交互型 | 执行节点：心跳、资源上报、任务收发 | 心跳、槽位变化、任务状态 | VirtualWorker（virtual）/ demo real worker（real，kernel-hosted） |
| `engine` | 交互型 | 计算引擎：提交→状态流转→终态+日志 | 提交受理、状态流转、完成 | 虚拟 engine 组件（virtual，M1+，复用 TaskStub 行为模型）/ spark-submit（real） |
| `scheduler` | 交互型 | 调度器：依赖编排、重试与失败转移决策 | 任务派发、状态流转、转移/重选主 | demo-scheduler（real，SUT 示范）/ 虚拟调度桩（virtual） |
| `resource` | 标准协议 | 资源管理：队列、配额、容器分配 | 配额变化、分配/回收 | YARN/K8s 替身（embedded/container） |
| `message` | 标准协议 | 消息：主题、生产/消费语义 | 积压、分区变化 | embedded Kafka / 桩 |
| `filestore` | 标准协议 | 文件/对象存储：路径、读写、容量 | 容量告警、读写错误 | 本地 FS 桩（virtual，端点形态 FS_PATH，可达）/ MiniDFS（embedded） |

**TaskStub 定位**：任务执行**行为模型**（duration/jitter/failAt/neverReport 等，见 §9），不是独立的契约组件；内嵌于 VirtualWorker 执行派发任务，未来虚拟 engine 组件复用同一模型。

契约接口反推节奏（YAGNI）：**M0 反推 `registry / worker / scheduler` 三个**（闭环场景所需），`engine` 以接口骨架形式一并反推（TaskStub 行为模型的事件语义即 engine 契约语义，但 M0 闭环场景不含独立 engine 节点）；`store` 随 M2 的 H2 适配反推；`message / filestore / resource` 按需反推。每个契约定义四件事：**必须实现的行为语义、必须发出的事件、配置项、可观测点**。

## 6. 实现档位

| 档位 | 含义 | 示例（registry / worker / scheduler 契约位） |
| --- | --- | --- |
| `virtual` | 纯虚拟实现。**按契约类型分两种形态**：标准协议契约＝通常为进程内状态机（端点形态 NONE，如 registry/store/message）；个别契约有可达形态（如 filestore 本地 FS 桩）。交互型契约＝暴露 Duo 线协议真实端口（端点形态 DUO_PORT，不冒充第三方产品协议，但连接与报文是真实的） | registry：VirtualRegistry 进程内状态机（NONE）/ worker：VirtualWorker（DUO_PORT）/ scheduler：虚拟调度桩（DUO_PORT） |
| `embedded` | JVM 内运行真实第三方协议实现（仅标准协议契约存在此形态） | registry：Curator TestingServer |
| `container` | 本地 Docker 按需拉起真容器（Testcontainers 桥，归 `duo-sim-embedded` 模块，M4 交付；仅标准协议契约存在此形态） | registry：Testcontainers ZK |
| `real` | **真实实现——通常是 SUT，也可以是任意真实组件**。宿主分两类（见下） | scheduler：demo-scheduler（SUT，kernel-hosted）/ worker：demo real worker（非 SUT，kernel-hosted） |

关键约定：

- **换档零改动的范围**：指测试代码与拓扑其余部分。SUT 自身代码是否需要修改取决于其连接路径（见下）——例如 M0 的 demo-scheduler 经 interface-direct 访问 virtual registry，M2 换 embedded Curator 时它的注册中心访问代码需改为 ZK 客户端。
- **连接路径的选择规则（确定性，无两可）**：wiring 槽可用 `path: wire | direct` 显式指定；缺省时**按目标实现的能力元数据推断**——端点形态 ≠ `NONE` 则 wire-protocol，否则 interface-direct。显式声明的校验：`wire` 要求目标端点形态 ≠ NONE；`direct`（无论显式或缺省推断得出）要求**消费方为 in-process 节点**，否则校验失败（external 消费方 + 无端点目标＝不可满足，报错并提示为目标更换有端点的档位）。
- **启动前校验读静态能力元数据，不调 `endpoints()`**：`endpoints()` 是实例的运行时接口，校验期实例尚不存在。校验只读契约注册表中的能力元数据（→ §7.5）；`endpoints()` 留给运行时接线与 ready 阶段确认实际绑定地址。
- **real 档两类宿主**：
  - **kernel-hosted**：由内核作为 `VirtualComponent` 在同一 JVM 拉起（`launch: { mode: in-process }`），组件间走真实 TCP 回环；demo real worker 归此类（M0 验收用）。
  - **external**：用户自行启动的外部进程；内核生成端点配置文件并轮询 ready 探针（→ §7.3），生命周期归用户。
- **交互型契约的可用档位**：不设档位限制，但所选档位必须有实现；实际上只有 virtual（Duo 协议）与 real 两类——embedded/container 的定义是"真实第三方协议实现"，交互型契约无此形态，声明即被校验拒绝。
- 第三方产品接入交互型契约 = 为该产品写 Duo 线协议适配器（只依赖 `duo-sim-protocol` 工件，→ §16 风险 1）。

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
    List<ExposedEndpoint> endpoints();      // 运行时实际绑定地址；对应拓扑 exposes 声明
}
```

- **组件管理器**：按 wiring 依赖拓扑排序启动；启动失败逆序拆除已启动组件并报告根因；场景结束统一清理（含 in-process SUT 的协作式停止，→ §7.3）。
- **重启语义**：`crash` 后组件处于停止态，必须经 `restart` 恢复上线（端点与身份不变、内部状态视为全新实例）——用于测试 SUT 对节点恢复的感知；时间线动作 `restart` 触发同一语义。实例级重启见 §7.2。

### 7.2 故障注入 SPI 与实例寻址

```java
public interface FaultInjectable {          // 契约级可选能力接口
    void inject(FaultAction action);        // 带 duration 的动作由场景引擎计时到期自动 clear
    void clear(FaultAction action);
}

public interface InstanceControl {          // count > 1 组件的可选能力接口
    void stopInstance(int index, StopMode mode);    // index 从 1 开始
    void restartInstance(int index);
    void injectOnInstance(FaultAction action);      // 实例下标唯一来源＝action.target.instanceIndex
}
```

- **故障动作模型**：`FaultAction { type, target: ComponentAddress, params, duration? }`；`ComponentAddress { componentId, instanceIndex? }`，`instanceIndex` 缺省表示作用于整组。`crash workers[3]` 即 `target = {workers, 3}`——内核经 `InstanceControl` 只停第 3 个实例。
- **注入前校验（读能力元数据，无降级路径）**：① target 可解析，下标 ∈ [1, count]；② 实现支持该动作（元数据 `supportedFaults`，或 `crash/restart` 生命周期）；③ 动作携带实例下标时，实现必须具备 `instanceControl` 能力。**任一不满足即失败：时间线场景在校验期直接报错（不启动），热注入记为注入失败一级事件并计入场景结果。** 实现能力在注册期静态可知，不存在"实例级请求被改写为整组生效"的降级——那会让场景为它没验证过的语义亮绿灯。
- **target 不得为 SUT**：in-process SUT 无法安全强杀/冻结，external SUT 生命周期归用户；对 SUT 的注入意图在校验期拒绝（SUT 崩溃属测试结果，→ §12）。kernel-hosted 的非 SUT real 组件（如 demo real worker）可实现上述能力并作为 target。

### 7.3 SUT 适配面（断言能否成立的前提）

**in-process SUT**（如 demo-scheduler）：

- **入口契约**：SUT 启动类实现 `SutMain { void run(SutContext ctx) }`；**`run()` 阻塞直至 SUT 退出**，内核在独立线程调用它。demo-scheduler 示范。
- `SutContext` 提供：端点清单、wiring 直连对象（可选 interface-direct）、`SutEventPublisher`、配置变量（来自节点可选 `config:` 字段，自由键值原样注入）、**协作式停止句柄 `ctx.onStop(Runnable handler)`**——内核停止 in-process SUT＝触发 handler 请求退出 + 带超时等待 `run()` 返回；超时记为停止失败一级事件，计入场景结果。SUT 停止纳入统一拆除顺序（逆依赖序）。
- **就绪**：in-process 默认走回调（`ctx.ready()`），回调须在 ready 超时内到达（默认 60s，`ready.timeout` 可覆盖）；超时归启动失败路径（§12）。可显式声明探针覆盖回调。
- **事实发布**：SUT 必须经 `SutEventPublisher` 把内部关键事实发布为事件（任务终态、重试发生、失败转移、选主完成）——这是 `noTaskLost`、`masterReelectedWithin` 等断言的事实源。事件类型命名约定：**`sut.` 前缀**（如 `sut.task-terminal`、`sut.failover`、`sut.leader-elected`），载荷为自由 JSON，内核不解释语义、仅转发与录制。
- **显式边界**：in-process 要求 SUT 可改码（埋点发布事实、实现 SutMain/onStop）。不可改码的第三方 SUT 只能走 external + 旁路观测（→ 非目标）。

**external SUT**（用户自行启动）：

- **内核 → SUT 端点告知**：内核生成端点配置文件（主途径，路径经 `launch.configOut` 指定）；stdout 解析为兜底途径，行格式约定 `duo.endpoint.<contract>=host:port`。
- **SUT → 内核端点发现**：external 节点自身监听的端口内核无从得知，因此**必须在 `exposes` 中显式声明端口/地址**（或作为扩展：ready 时向内核控制端点上报端点清单）。
- **就绪探针**：external 节点必须在 DSL 声明 `ready`（类型 `tcp / http`，含参数与超时）；探针超时归入启动失败路径（§12）。
- **生命周期归用户**：场景结束时内核只拆接线、不杀 external 进程（终态提示用户自行终止）。
- 观测三途径：① **替身侧旁路事件**（主途径，M1 断言基于此：心跳、任务状态回报、registry 会话与临时节点变化均为事件——如"重选主"可由 registry 侧临时节点变更旁路推断；embedded registry 适配器支持临时节点变化观察，M2 交付）；② 日志/指标侧车规则（可选扩展点）；③ 用户自定义探针。
- 明确边界：框架承诺①，②③为扩展点，不承诺 external SUT 内部事实全覆盖（→ 非目标）。

### 7.4 事件总线与 SimClock

- 事件总线：进程内轻量发布/订阅（自研，不引消息中间件）。事件模型：`{type, sourceId, timestamp, payload}`。**sourceId 约定**：组件级事件用 componentId（如 `workers`）；实例级事件用 `componentId-N`（如 `workers-3`）——断言与录制依赖此约定。
- SimClock：首期仅真实时钟；可加速虚拟时钟留作 M4 评估项（依赖 SUT 可注入 `Clock`），接口上预留。

### 7.5 契约注册表与能力元数据

- **注册**：实现经 Java SPI（ServiceLoader）向契约注册表注册，键为 `(contract, tier)`，注册时声明**静态能力元数据**：`{ endpointShape: NONE | DUO_PORT | THIRD_PARTY | FS_PATH, interfaceDirect: bool, instanceControl: bool, supportedFaults: Set<FaultType> }`。启动前所有校验（§8 规则 1/3/6）只读元数据；实例运行期行为（实际绑定地址、故障执行）分别由 `endpoints()` 与 `FaultInjectable` 承接。
- **实现解析**：节点未显式指定实现时，解析到该 `(contract, tier)` 的默认实现；同一 `(contract, tier)` 存在多个实现时，节点可用可选 `impl:` 字段指定实现名。M0 验收把 workers 节点在 `virtual ↔ real` 间切换，即靠此解析规则分别落到 VirtualWorker 与 demo real worker。

## 8. 场景 DSL（YAML）

```yaml
name: worker-crash-failover
topology:
  - id: zk
    contract: registry
    tier: virtual                        # VirtualRegistry：进程内状态机（端点形态 NONE）
  - id: master
    contract: scheduler
    tier: real
    sut: true                            # 被测对象
    launch: { mode: in-process, main: com.example.DemoScheduler }
    config: { clusterName: demo }        # 自由键值，原样注入 SutContext
    exposes: [{ contract: scheduler, port: 0 }]   # port: 0 = 内核分配；external 节点必须显式声明端口
    wiring:
      registry: { node: zk, contract: registry }  # zk 无端点 → 推断 direct（master 为 in-process，合法）
    # 设计意图：master 无 workers 槽是有意的——worker 经 registry 发现 master，
    # 故障转移路径正依赖该发现机制，而非 wiring 遗漏
  - id: workers
    contract: worker
    tier: virtual
    count: 10
    capacity: { cpu: 4, memGB: 8 }
    wiring:                              # master 有 Duo 端点 → 推断 wire-protocol；依赖可指向 SUT
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
    target: workers[3]                   # 索引从 1 开始，即实例 workers-3
  - at: 20s
    action: registry-flap
    target: zk
    duration: 5s                         # VirtualRegistry 的内存会话闪断（M1 交付）
  - at: 25s
    action: restart
    target: workers[3]
assertions:                              # 运行时内置评估，场景结果直接判 pass/fail
  - failoverWithin: { task: any, seconds: 30 }
  - noTaskLost
```

校验规则（启动前快速失败；除端口占用外均读契约注册表的静态能力元数据）：

1. 契约名必须已注册；实现按 `(contract, tier)` 从注册表解析（多实现可用节点 `impl:` 指定），解析不到即失败；交互型契约声明 embedded/container 档会被拒绝——该形态不存在。
2. wiring 槽必须显式声明 `{node, contract}`；允许简写 `槽名: 节点id`，仅当槽名与已注册契约同名；槽的期望契约必须与目标节点契约一致。
3. **连接路径校验**：显式 `path: wire` 要求目标端点形态 ≠ NONE；显式或推断为 `direct` 时消费方必须为 in-process 节点；缺省推断——目标端点形态 ≠ NONE 则 wire，否则 direct。推断落空（external 消费方遇无端点目标）即失败，提示为目标更换有端点的档位。
4. external 节点被 wiring 引用的契约槽，必须在 `exposes` 中显式声明端口；external 节点必须声明 `ready` 探针。
5. `sut: true` 必须恰好一个；节点 id 唯一。
6. 时间线动作：target 必须可解析；**target 不得为 SUT**；下标 ∈ [1, count] 且携带下标时实现必须具备 `instanceControl` 能力；动作类型须在实现 `supportedFaults`（或属 crash/restart 生命周期）。任一不满足即校验失败，不启动（无降级，→ §7.2）。
7. 每个行为剧本必须有至少一个绑定或作为 default。
8. exposes 声明的固定端口（非 0）未被占用（端口可用性属启动前检查；端口可达性属启动后 ready 探针）。

其余语法示例：

```yaml
# wiring 简写与显式路径（槽名与契约同名时等价）：
wiring: { registry: zk }                          # 推断路径
wiring: { registry: { node: zk, path: direct } }  # 显式 interface-direct
# external SUT 节点（端点告知 + 端点声明 + 就绪探针 + 配置注入）：
  - id: master
    launch: { mode: external, configOut: build/sut.properties }  # 内核生成端点配置文件（主）；stdout 兜底，行格式 duo.endpoint.<contract>=host:port
    config: { clusterName: demo }                                 # 同时写入端点配置文件
    exposes: [{ contract: scheduler, port: 8123, addr: 127.0.0.1 }]
    ready:   { type: tcp, port: 8123, timeout: 30s }
```

## 9. 虚拟组件库（首批）

- **VirtualWorker**：基于 Java 21 虚拟线程，每个逻辑节点一个虚拟线程；职责为心跳上报、资源槽位上报、领取/执行任务、回报状态；对外暴露 Duo 线协议端口（编解码由 `duo-sim-protocol` 提供）；实现 `InstanceControl` 支持实例级操作。`count × capacity` 决定拓扑规模。
- **VirtualRegistry**：registry 契约的 virtual 档实现——会话/临时节点/watch 的内存状态机（端点形态 NONE，供 interface-direct）；支持 `registry-flap` 注入（内存会话闪断）。
- **TaskStub**：任务执行**行为模型**（内嵌于 VirtualWorker，供未来虚拟 engine 组件复用），字段首集：

| 字段 | 含义 |
| --- | --- |
| `duration` | 执行时长 |
| `jitter` | 时长抖动幅度（如 `20%`） |
| `successRate` | 成功率 |
| `failAt` | 进度到达百分比时失败 |
| `exception` | 抛出的异常类型（模拟报错） |
| `logLines` | 向日志流输出的假日志模板 |
| `neverReport` | 领取后永不回报（僵尸任务） |
| `progress` | 进度上报模式 |

- **demo-scheduler**（`examples` 模块）：最小参考被测实现，带真实的调度状态机（DAG + 重试 + 失败转移），**M0 版为纯内存实现、不依赖 store 契约**；实现 `SutMain`（阻塞式 run）与 `ctx.onStop` 协作式停止、经 `SutEventPublisher` 发布内部事实，作为 in-process SUT 适配面的完整示范。仅用于验收，不是产品代码。
- **demo real worker**（`examples` 模块）：worker 契约的 real 档参考实现，**kernel-hosted**（内核同 JVM 拉起，走真实 TCP 回环，协议栈依赖 `duo-sim-protocol`），供 M0 验收"virtual ↔ real 换档"使用。
- **第三方调度器适配（如 DolphinScheduler 的 Netty 协议）不在 M0 范围**，避免版本耦合阻塞内核（→ §16 风险 1）。

## 10. 场景引擎与故障注入

时间线动作首集：`crash`（宕机）、`restart`（重启恢复上线）、`freeze`（假死不响应）、`slow`（延迟劣化）、`registry-flap`（会话闪断）、`resource-exhaust`（资源耗尽）、`task-kill`（终止进行中的桩任务并触发状态回报，**M1 精确定义**）、`custom-hook`（用户钩子，**注册接口与断言参与方式随 M1 一并定义**）。

所有动作支持实例级寻址（→ §7.2，无降级路径）。热注入：内核从 M0 起就内置 `ScenarioRuntime` API（`inject(FaultAction)`），M3 的 REST/CLI 只是其外层包装——保证"先有内核能力，后有控制面"。

## 11. 观测面与断言

- 事件模型统一为 `{type, sourceId, timestamp, payload}`（sourceId 约定见 §7.4）；输出三通道：结构化日志、事件流录制（JSON Lines，用于事后回放审查与回归比对；真实时钟下不承诺确定性逐字节重放）、Prometheus 格式指标。
- **断言双轨分工**：YAML `assertions`＝运行时内置评估，场景结束直接判 pass/fail（供 CLI/CI 使用）；JUnit 断言库＝测试代码编程式组合（支持时序、窗口、聚合）。二者共用同一事件事实源。
- 断言首集：`failoverWithin`、`noTaskLost`、`masterReelectedWithin`（external SUT 由 registry 侧旁路推断，见 §7.3）、`eventSequence`；`alertFired` 待 M1 定义告警事件语义后纳入。
- **断言语义随 M1 钉死**（此处先定基准）：`failoverWithin` 计时起点＝故障注入事件（`FaultInjected`），"转移成功"判定＝受影响任务在新实例上产生首次状态回报；其余断言的精确语义在 M1 断言库实现时逐条定义。

## 12. 错误处理

- 场景校验失败（含连接路径落空、实例能力缺失、target 为 SUT）→ 启动前报错，指明行号与原因，不拉起任何组件。
- 组件启动失败（含 ready 超时——in-process 回调或 external 探针）→ 逆序拆除已启动组件，报告根因链。
- 故障注入失败（target 不支持、下标越界、能力缺失、组件已停止）→ 作为一级事件记录并计入场景结果，**不允许静默吞掉**。
- in-process SUT 停止超时 → 记为停止失败一级事件，计入场景结果（→ §7.3）。
- SUT 崩溃视为测试结果而非框架错误：自动保存现场（最近 N 条事件 + 全组件状态快照）。

## 13. 测试策略（框架自身）

- 内核：纯单测（生命周期、拓扑排序、事件总线、restart 语义、实例寻址、连接路径推断与校验）。
- 金标准场景集：每个契约至少一个正例一个故障例，CI 全跑。
- **M0 验收场景**：同一份拓扑（zk 用 VirtualRegistry 接口直连 + master 为 real 档 demo-scheduler + workers），将 `workers` 节点在 `virtual`（VirtualWorker）↔ `real`（demo real worker，kernel-hosted）之间切换档位，测试代码零改动跑通。**范围说明**：两端均讲 Duo 线协议——M0 证明的是换档机制与测试代码零改动，不是第三方真实组件接入（见 §16 风险 1）。
- **SUT 代码边界说明**：demo-scheduler 在 M0 经 interface-direct 访问 virtual registry；M2 换 embedded Curator 时其注册中心访问代码需改为 ZK 客户端（SUT 代码变更）。"测试代码零改动"约定不含 SUT 自身。
- 嵌入式/容器档集成测试：容器档在无 Docker 环境自动 skip。

## 14. 分阶段计划

| 阶段 | 周期（粗估） | 产出 | 验收标准 |
| --- | --- | --- | --- |
| **M0 内核骨架** | 1~2 周 | **duo-sim-protocol（帧格式/编解码/契约报文）**+ kernel（SPI/注册表与能力元数据/管理器/实例寻址/事件总线/SUT 适配面）+ scenario（YAML 拓扑与校验）+ registry/worker/scheduler 契约与 engine 骨架 + VirtualWorker/TaskStub/VirtualRegistry + demo-scheduler（纯内存、interface-direct）与 demo real worker（kernel-hosted） | §13 档位切换验收跑通 |
| **M1 场景与注入** | ~2 周 | 行为剧本全集（profiles/bindings/jitter）、时间线注入（含实例寻址/restart/task-kill）、**VirtualRegistry 的 registry-flap（内存会话闪断）**、custom-hook 定义（注册接口+断言参与）、事件录制、断言库 v0（含 `failoverWithin` 等语义钉死） | "worker-3 于 T+10s 宕机 → 任务 30s 内转移成功"断言通过 |
| **M2 嵌入中间件** | 1~2 周 | Curator/H2/Fabric8 适配器（store 契约随 H2 反推）、**embedded（Curator）registry-flap（真实会话打断）+ 临时节点变化观察**、`@VirtualCluster` 扩展 | 注册中心闪断 5s → 重新选主且无任务丢失 |
| **M3 控制面** | ~2 周 | REST/CLI 热注入（可选极简拓扑视图） | 运行中手动注入故障并观察自愈 |
| **M4 规模与桥接** | 按需 | 万级心跳压测（虚拟线程调优）、Testcontainers 桥（embedded 模块容器档）、加速时钟评估、第三方 SUT 协议适配器（仅依赖 duo-sim-protocol） | 千~万 Worker 心跳压测报告 |

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

1. **第三方协议耦合**：第三方 SUT（如 DolphinScheduler）接入交互型契约需实现 Duo 线协议适配器（只依赖 `duo-sim-protocol` 工件），且其自有 RPC 报文存在版本耦合。对策：M0 用 demo-scheduler / demo real worker 验证内核，第三方适配放 M4 按需。
2. **桩太乖悖论**：行为太理想化测不出问题。对策：行为剧本内置异常分布（jitter/successRate/failAt，M1 必做且作为验收一部分）。
3. **抽象过早**：契约接口膨胀。对策：YAGNI，契约按 §5 反推节奏逐个引入，每接一个新契约才泛化一次接口。
4. **Windows 环境兼容**：Curator 纯 Java 无碍；zonky PG 等托管二进制组件在 Windows 需逐个验证（M2 时确认，容器档可兜底）。

## 17. 开放问题

1. 第三方 SUT 协议适配器（如 DolphinScheduler Worker 协议）的优先级与版本基线——M4 前再定。
2. 虚拟时钟加速是否进路线图——依赖 SUT 是否可注入 `Clock`，M4 评估。

---

## 附录：修订记录

- **v0.1（2026-09-13）**：初稿。
- **v0.2（2026-09-13）**：依首轮评审修订（Duo 线协议与 virtual 档分型、scheduler 契约、real 档放宽、store 归 M2、显式 wiring 槽、FaultInjectable、restart、SUT 适配面、profiles/bindings/jitter、Java 21、container 归 embedded 模块等，详见 v0.2 提交说明）。
- **v0.3（2026-09-13）**：依二轮评审修订 15 条（VirtualRegistry 归位、实例寻址 InstanceControl、连接路径×端点可达性换档判定、real 档宿主分类、交互型契约档位收紧、external 端点发现与 ready 探针、SutMain 契约、in-process 可改码边界、TaskStub 定位拆分、registry-flap 按 M1/M2 拆分、exposes 语法、示例设计意图注释等，详见 v0.3 附录）。
- **v0.4（2026-09-13）**：依三轮评审修订——
  1. **[P1] 连接路径选择规则**：wiring 槽增加可选 `path: wire|direct`，缺省推断规则成文（目标端点形态 ≠ NONE → wire，否则 direct 且消费方须 in-process）；显式声明同样校验；推断落空报错并提示升档（§6/§8 规则 3）；
  2. **[P1] SUT 生命周期补全**：`run()` 钉死阻塞至退出（内核独立线程调用）；`SutContext` 增加 `ctx.onStop(handler)` 协作式停止（内核请求+带超时等待，超时记停止失败一级事件）；in-process ready 回调纳入超时（默认 60s）；SUT 不得作为故障注入 target（in-process 非 FaultInjectable、external 生命周期归用户，场景结束只拆接线），"SUT 非注入目标"进非目标（§2/§7.2/§7.3/§12）；
  3. **[P1] 删除实例降级路径**：`InstanceControl` 能力并入注入前校验（能力静态可知，无降级存在必要）——时间线校验期报错、热注入记注入失败，杜绝"杀 1 个变杀 10 个"的假绿（§7.2/§8 规则 6/§12）；
  4. **[P2] 静态能力元数据**：新增 §7.5 契约注册表——实现经 SPI 按 `(contract, tier)` 注册并声明能力元数据（端点形态 NONE/DUO_PORT/THIRD_PARTY/FS_PATH、interfaceDirect、instanceControl、supportedFaults），启动前校验只读元数据，`endpoints()` 留给运行时接线与 ready（§3/§6/§7.5/§8）；
  5. **[P2] 新增 `duo-sim-protocol` 模块**：承载 Duo 线协议帧格式/编解码/契约报文，VirtualWorker、demo real worker、demo-scheduler 依赖它，M4 第三方适配器只依赖协议工件（§4/§9/§14/§16）；
  6. 小项：`injectOnInstance` 去掉冗余 index 参数（下标唯一来源＝`action.target.instanceIndex`）；实例级事件 sourceId 约定 `componentId-N`；补 `FaultAction.duration` 到期自动 clear（场景引擎计时）；实现解析机制（默认实现 + `impl:` 字段）；stdout 兜底行格式 `duo.endpoint.<contract>=host:port`；SutContext 配置变量来源定为节点 `config:` 字段；`failoverWithin` 计时起点/成功判定基准定出、全部断言语义 M1 钉死；目标 6 补 filestore virtual 可达形态括号注（§7.2/§7.3/§7.4/§7.5/§8/§11/§14）。
