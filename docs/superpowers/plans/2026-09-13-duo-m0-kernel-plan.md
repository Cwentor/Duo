# Duo M0 实施计划 —— 内核骨架与档位切换闭环（v2）

- 日期：2026-09-13（v2 修订）
- 依据：设计文档 v1.0（定稿）：`docs/superpowers/specs/2026-09-13-duo-virtual-bigdata-sim-design.md`
- 范围：设计文档 §14 的 M0 行；验收标准＝§13 档位切换场景跑通
- 状态：待用户批准（批准前不动代码）
- 修订记录见文末附录

---

## 1. 交付定义（唯一验收口径）

**同一份拓扑 YAML，`workers` 节点在 `virtual`（VirtualWorker）↔ `real`（demo real worker）之间切换档位，测试代码零改动跑通**。具体化为一条验收测试：

- 拓扑：`zk`（VirtualRegistry，interface-direct）+ `master`（demo-scheduler，real 档 in-process SUT）+ `workers`（count=10，档位参数化）
- 运行内容：demo-scheduler 执行一个带依赖的小型 DAG（如 5 个任务），其中**一个任务配置 `exception`（每次尝试必失败），由调度器有界重试后进入 FAILED 终态**——重试路径为确定性构造，不依赖概率字段
- DAG 完成定义：**全部任务到达终态（SUCCESS 或 FAILED，因依赖失败而未执行的记 SKIPPED）**
- 通过条件：两份仅 `workers.tier` 不同的拓扑，**同一测试类、同一测试体**全绿——场景启动成功、全部任务终态、失败任务的重试事实事件 ≥ 1（demo-scheduler 发布 `sut.task-retry`）、事件流含 `sim.scenario-finished`、无注入失败/停止失败事件

## 2. 全局约定

| 约定 | 决定 | 理由 |
| --- | --- | --- |
| 构建 | Maven 多模块（**8 个模块**），JDK 21 release | 设计文档 §15 |
| 模块依赖方向 | `examples → components / scenario → kernel → protocol`，**且 `components → protocol`、`examples → protocol`**（VirtualWorker 与 demo real worker 均需协议工件） | 协议工件最底层，第三方适配器可独立依赖 |
| Duo 协议传输 | JDK `ServerSocket`/`Socket` + 虚拟线程（每连接一线程），长度前缀帧 + Jackson JSON 载荷 | 不引 Netty，M0 依赖最小化；虚拟线程模型与 §9 一致 |
| **Duo 连接模型** | **worker → scheduler 单向拨号，每 worker 实例一条双向长连接**：上行承载注册/心跳/槽位/任务状态，下行承载任务派发/取消；连接地址来自 registry 发现（见下）。VirtualWorker/demo real worker 自身的 DUO_PORT 在 M0 仅监听（供就绪探测与未来 pull 模式），不承载调度路径 | 与"worker 经 registry 发现 master"一致；master 无需维护 worker 地址簿 |
| **master 端点发现** | **经 registry 发现**：demo-scheduler 启动后把自身 Duo 端点写入 registry（临时节点，载荷 host:port）；workers 经 wiring 拿到 registry（interface-direct）后查询发现 master。**M0 验收拓扑中 workers 的 wiring 仅含 `registry: zk` 槽**；设计文档 §8 示例中 workers→scheduler 槽演示的是"依赖可指向 SUT"的 DSL 能力，M0 验收不使用该槽 | 与 §8"故障转移路径依赖发现机制"的设计意图一致；registry 契约客户端接口须含发现查询 API（→ T4） |
| 事件 | `sim.*` 最小集（scenario/component 生命周期/注入）+ `sut.*`（门面 + 内核探测） | §7.4 |
| 安全约束 | M0 无 SQL、无外呼 HTTP（仅本机回环 TCP）、无可变静态安全配置 | Mimosa 钩子验收基线，M2/M3 涉及时再逐条对照 |

## 3. 里程碑与任务分解

### M0-a 协议、契约与内核骨架（约 3~4 天）

| # | 任务 | 内容与落点 | 完成判据 |
| --- | --- | --- | --- |
| T1 | Maven 骨架 | 父 pom + **8 模块**（protocol/kernel/scenario/components/embedded/junit/control/examples；embedded/junit/control 仅占位空模块）；锁定 Jackson、SnakeYAML、JUnit5 版本 | `mvn install` 全绿 |
| T2 | `duo-sim-protocol` | 帧格式（magic + version + length 前缀）、`FrameCodec`、消息集：worker 契约 `Register/Heartbeat/SlotReport/TaskDispatch/TaskAck/TaskStatus`（scheduler 契约 M0 复用同一组报文的 server 侧语义）；**连接模型按 §2 钉死：worker 拨号、单条双向长连接、帧双向流动** | 编解码 round-trip 单测、畸形帧拒绝单测、双向帧交织单测 |
| T3 | kernel-api | `VirtualComponent / ComponentContext / StopMode / HealthReport / ExposedEndpoint(带类型) / FaultInjectable / InstanceControl / FaultAction / ComponentAddress / CapabilityMetadata / **SimClock(真实时钟实现，接口预留)** / SutMain / SutContext / SutEventPublisher` | 编译期契约完整，javadoc 引用设计文档节号 |
| T4 | **契约接口反推（§5）** | kernel-api `contract` 包定义三个契约 + engine 骨架，每个契约 javadoc 钉死**必发事件、配置项、可观测点**：`RegistryContract`（客户端面 Java 接口：会话建立/过期、临时节点创建/删除、**端点注册与发现查询**——interface-direct 注入 SUT 与 workers 的对象类型，T5/T8/T12/T13 依赖它）；`WorkerContract`（语义 + 服务端 handler：注册/心跳/槽位/任务状态，必发事件 heartbeat/slot/task-status）；`SchedulerContract`（server 侧 handler：派发入口 + 状态接收，M0 事件子集 task-dispatched/status/retry）；`EngineContract` 骨架（接口 + 事件语义注释，无实现） | 契约接口编译通过且被 T5/T8/T12/T13 消费；事件类型清单与 §7.4 命名空间一致 |
| T5 | kernel-core 注册表与事件 | `ContractRegistry`：ServiceLoader 按 `(contract, tier)` 注册 + 能力元数据 + `default: true`；**注册期一致性校验**（NONE⇒interfaceDirect、instanceControl⇒InstanceControl、supportedFaults 非空⇒FaultInjectable）与**缺省唯一性校验**（§7.5）；`EventBus`（`sim.*`，sourceId 约定） | 注册表单测：三类一致性违规拒绝注册、多缺省/零缺省报错、事件发布订阅与 sourceId 约定 |

### M0-b 组件、注入通路与拓扑执行（约 4~5 天）

| # | 任务 | 内容与落点 | 完成判据 |
| --- | --- | --- | --- |
| T6 | VirtualRegistry | 会话/临时节点/watch 的内存状态机（端点 NONE、interfaceDirect=true）+ **端点注册/发现查询**（临时节点载荷 host:port）；M0 不实现 registry-flap、不声明该 fault（→ T15 金标准说明） | 状态机单测：会话建立/过期/临时节点清理/watch 触发/发现查询 |
| T7 | TaskStub 行为模型 | M0 子集：`duration / jitter / successRate / exception / logLines`（`failAt / neverReport / progress` 留 M1）；独立模型类，VirtualWorker 与 demo real worker 共同消费 | 行为分布单测（确定性种子）：成功率、异常抛出、日志产出 |
| T8 | VirtualWorker | 每 Instance 一个虚拟线程；心跳 + 槽位上报 + 任务执行（TaskStub）+ 状态回报（沿 §2 连接模型：拨号 master、单长连接）；`InstanceControl` 实现；自身 DUO_PORT 监听（就绪探测用） | 单测：心跳节律、实例级 stop/restart（端点身份保留）、发现→连接→派发→回报闭环 |
| T9 | kernel 组件管理器与接线 | 拓扑排序启动、启动失败逆序拆除、**统一清理先以 `VirtualComponent.stop` 通路收口（SUT 协作停止接入点预留，T12 完成后接入同一拆除序列）**；`WiringResolver`：槽解析、`path` 显式/缺省推断、direct 的消费方（in-process）与目标（interfaceDirect）双校验——**规则 2/3** | 管理器单测：排序、逆序拆除、路径推断三分支、两类 direct 违规报错文案 |
| T10 | **ScenarioRuntime 注入通路（§10）** | 内核侧完整通路：`inject(FaultAction)` → target 解析（含实例下标）→ 能力校验（元数据 supportedFaults / instanceControl / 生命周期，无降级）→ 分发（FaultInjectable 或 crash/restart）→ `sim.fault-injected` 事件。M0 可注入项＝crash/restart（生命周期通路）；FaultInjectable 类动作通路可分发，M0 组件不声明此类 fault，注入即按无降级原则失败（正确行为），分发通路用 fixture 组件单测 | 单测：target 解析（含下标越界）、三类校验失败路径、crash/restart 分发、`sim.fault-injected` 事件与 sourceId（含实例级 `workers-3`） |
| T11 | `duo-sim-scenario` | SnakeYAML 加载拓扑/behaviors（M0 仅 default 绑定）/timeline/节点 config；**Validator：规则 4–8 全量执行，规则 1–3 汇总注册表（T5）与接线解析（T9）的判定**；**对 M0 不支持的节（assertions）输出显式 warning（计入校验结果，不静默忽略）**；`ScenarioEngine` 最小执行（start → run → stop；**结束条件＝in-process SUT 正常退出（`sut.exited`）或显式 stop，不新增 DSL 字段**） | 校验器单测逐规则覆盖（fixtures 自造）；M0 简化金标准 YAML 通过校验并跑通 |

### M0-c SUT 适配面与示例（约 3~4 天）

| # | 任务 | 内容与落点 | 完成判据 |
| --- | --- | --- | --- |
| T12 | SUT 适配面 | `SutLauncher`（独立线程调阻塞 `run()`）、`SutContext`（端点清单/直连对象/SutEventPublisher/config/onStop/ready）、ready 回调 60s 超时、`sut.exited` / `sut.crashed` 探测、停止超时与 interrupt 兜底；**把 SUT 协作停止接入 T9 预留的统一拆除顺序** | 生命周期单测：ready 超时、正常停止、未注册 onStop interrupt、自行退出两事件 |
| T13 | demo-scheduler | 内存调度状态机：DAG 依赖 + **有界重试（发布 `sut.task-retry`）** + 失败转移；启动后向 registry 注册自身 Duo 端点（临时节点）；scheduler 侧 Duo server（收注册/心跳/槽位、沿长连接下行派发、收状态）；`sut.*` 事实发布；`SutMain` + `onStop` 完整实现；**DAG 全部终态后 `run()` 返回（场景结束信号）** | 状态机单测：依赖触发、确定性失败→重试→FAILED 终态、下游 SKIPPED |
| T14 | demo real worker | worker 契约 real 档实现：kernel-hosted、**经 registry 发现 master**、Duo 协议客户端、复用 TaskStub 消费同一 bindings（保证换档后行为剧本仍生效——零改动验收的关键） | 与 VirtualWorker 同一组行为单测通过（模型复用的证明） |
| T15 | 场景文件与验收测试 | **M0 金标准 YAML＝§8 示例的 M0 简化版**：不含 timeline 与 assertions 节（timeline 执行与断言库属 M1，届时完整 §8 示例作为 M1 金标准）；两份仅 `workers.tier` 不同的 YAML + `TierSwapAcceptanceTest`（§1 通过条件） | **两档位全绿＝§13 验收通过，M0 完成** |

## 4. 测试与设计文档映射

| 设计文档条款 | 验证位置 |
| --- | --- |
| §13 档位切换验收 | T15 验收测试 |
| §5 契约反推（registry/worker/scheduler + engine 骨架） | T4（接口 + 必发事件 javadoc） |
| §8 校验规则 1–8 | T5（规则 1 注册期）、T9（规则 2/3）、T11（规则 4–8 及汇总） |
| §7.5 注册期一致性/缺省唯一性 | T5 单测 |
| §7.3 SUT 生命周期 | T12 单测 + T13 示范实现 |
| §7.2 实例寻址与无降级 | T10 单测 |
| §7.1 生命周期/restart 语义 | T9 单测 |
| §7.4 事件模型/sourceId/命名空间 | T5 单测 + T10 注入事件 + T15 事件流断言 |
| §10 ScenarioRuntime 热注入 | T10 单测 |

## 5. M0 明确不做（防蔓延）

时间线的**剧本化执行**（ScenarioRuntime API 与注入通路属 M0 交付，时间线编排执行属 M1）、registry-flap 实现、`failAt/neverReport/progress` 字段、assertions 断言库、`@VirtualCluster` JUnit 扩展、embedded 适配器（Curator/H2/Fabric8）、控制面 REST/CLI、性能压测、第三方 SUT 适配。

## 6. M0 特有风险与对策

1. **demo-scheduler 范围蔓延**：最大风险是"参考被测实现"越长越大。对策：状态机钉死为 DAG + 有界重试 + 失败转移三件事，告警/补数/补拉等一概不做；代码量预算 ~600 行内。
2. **虚拟线程 + 阻塞 IO 行为**：JDK 21 官方支持，M0 仅回环小规模连接（10 worker × 1 连接），无规模风险；万级压测属 M4。
3. **依赖漂移**：Jackson/SnakeYAML/JUnit 版本在父 pom 锁定并集中管理。
4. **Windows 环境**：M0 纯 Java、无托管二进制，预期无兼容问题；如遇文件锁/路径分隔符问题在 T1 骨架期即暴露。
5. **发现路径的单点依赖**：M0 验收依赖"master 端点写入 registry"这一步，若 demo-scheduler 忘记注册，workers 将无法发现 master。对策：T13 完成判据含"注册事实事件"，T15 验收失败时优先检查该链路。

## 7. 执行节奏

单人按 M0-a → M0-b → M0-c 顺序推进，每个里程碑收尾跑一次全量测试并向用户同步；T15 通过后 M0 关闭，进入 M1 计划（时间线执行 + 断言库 + registry-flap + 行为字段全集）。

**工期：约 10~13 天（按上限 ≈ 2 周估）**——较 v1 上调，因补充契约反推（T4）与注入通路（T10）两项设计文档 M0 产出。

---

## 附录：修订记录

- **v1（2026-09-13）**：初稿，13 任务。
- **v2（2026-09-13）**：依计划评审修订——
  1. **[P1] 补契约接口反推任务 T4**：registry/worker/scheduler 三契约 + engine 骨架的 Java 接口落点 kernel-api `contract` 包，必发事件/配置项/可观测点进 javadoc；registry 契约含端点注册/发现查询 API（T5/T8/T12/T13 的依赖前提）；
  2. **[P1] 补 ScenarioRuntime 注入通路任务 T10**：target 解析→能力校验→分发→`sim.fault-injected` 事件，M0 交付 crash/restart 通路，FaultInjectable 类分发用 fixture 单测；§5 不做清单同步改为"时间线剧本化执行属 M1"；
  3. **[P1] 验收口径确定性化（方案①）**：失败重试改由 `exception` + 调度器有界重试确定性构造（不再依赖概率字段），DAG 完成＝全部任务终态（含 FAILED/SKIPPED），通过条件增加 `sut.task-retry` 事件 ≥ 1 断言；
  4. **[P2] master 端点发现钉死为 registry 路径**：workers wiring 仅含 registry 槽，master 端点写临时节点；spec §8 示例的 workers→scheduler 槽注明为 DSL 能力演示、M0 验收不使用；
  5. **[P2] Duo 连接模型钉死**：worker 拨号、每实例一条双向长连接、上行注册/心跳/状态、下行派发；worker 自身 DUO_PORT 仅监听供就绪探测（T2 完成判据同步）；
  6. **[P2] 场景结束条件复用 `sut.exited`**：demo-scheduler 跑完 DAG 即 `run()` 返回，不新增 DSL 字段（T11/T13 同步）；
  7. **[P2] T9/T12 依赖解耦**：统一清理先以 `VirtualComponent.stop` 收口，SUT 协作停止由 T12 接入预留点；
  8. **[P3] 五项小修**：8 模块计数、规则归属统一（T9=规则 2/3，T11=规则 4–8 及汇总，T5=规则 1）、T3 补 SimClock 预留、金标准 YAML 采用 M0 简化版（无 timeline/assertions，完整 §8 示例留 M1）、依赖方向补 components→protocol 与 examples→protocol；工期上调至 ~2 周。
