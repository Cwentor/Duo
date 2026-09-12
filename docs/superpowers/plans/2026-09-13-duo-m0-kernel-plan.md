# Duo M0 实施计划 —— 内核骨架与档位切换闭环

- 日期：2026-09-13
- 依据：设计文档 v1.0（定稿）：`docs/superpowers/specs/2026-09-13-duo-virtual-bigdata-sim-design.md`
- 范围：设计文档 §14 的 M0 行；验收标准＝§13 档位切换场景跑通
- 状态：待用户批准（批准前不动代码）

---

## 1. 交付定义（唯一验收口径）

**同一份拓扑 YAML，`workers` 节点在 `virtual`（VirtualWorker）↔ `real`（demo real worker）之间切换档位，测试代码零改动跑通**。具体化为一条验收测试：

- 拓扑：`zk`（VirtualRegistry，interface-direct）+ `master`（demo-scheduler，real 档 in-process SUT）+ `workers`（count=10，档位参数化）
- 运行内容：demo-scheduler 执行一个带依赖的小型 DAG（如 5 个任务、含一次失败重试），任务经 Duo 协议派发到 workers 执行、状态回报
- 通过条件：两份仅 `workers.tier` 不同的拓扑，**同一测试类、同一测试体**全绿——场景启动成功、DAG 全部任务到达终态、事件流含 `sim.scenario-finished`、无注入失败/停止失败事件

## 2. 全局约定

| 约定 | 决定 | 理由 |
| --- | --- | --- |
| 构建 | Maven 多模块，JDK 21 release | 设计文档 §15 |
| 模块依赖方向 | `examples → components / scenario → kernel → protocol` | 协议工件最底层，第三方适配器可独立依赖 |
| Duo 协议传输 | JDK `ServerSocket`/`Socket` + 虚拟线程（每连接一线程），长度前缀帧 + Jackson JSON 载荷 | 不引 Netty，M0 依赖最小化；虚拟线程模型与 §9 一致 |
| 事件 | `sim.*` 最小集（scenario/component 生命周期）+ `sut.*`（门面 + 内核探测） | §7.4 |
| 安全约束 | M0 无 SQL、无外呼 HTTP（仅本机回环 TCP）、无可变静态安全配置 | Mimosa 钩子验收基线，M2/M3 涉及时再逐条对照 |

## 3. 里程碑与任务分解

### M0-a 协议与内核骨架（约 2~3 天）

| # | 任务 | 内容与落点 | 完成判据 |
| --- | --- | --- | --- |
| T1 | Maven 骨架 | 父 pom + 7 模块（protocol/kernel/scenario/components/embedded/junit/control/examples；embedded/junit/control 仅占位空模块）；锁定 Jackson、SnakeYAML、JUnit5 版本 | `mvn install` 全绿 |
| T2 | `duo-sim-protocol` | 帧格式（magic + version + length 前缀）、`FrameCodec`、消息集：worker 契约 `Register/Heartbeat/SlotReport/TaskDispatch/TaskAck/TaskStatus`（scheduler 契约 M0 复用同一组报文的 server 侧语义） | 编解码 round-trip 单测、畸形帧拒绝单测 |
| T3 | kernel-api | `VirtualComponent / ComponentContext / StopMode / HealthReport / ExposedEndpoint(带类型) / FaultInjectable / InstanceControl / FaultAction / ComponentAddress / CapabilityMetadata / SutMain / SutContext / SutEventPublisher` 接口与记录类 | 编译期契约完整，javadoc 引用设计文档节号 |
| T4 | kernel-core 注册表 | `ContractRegistry`：ServiceLoader 按 `(contract, tier)` 注册 + 能力元数据 + `default: true`；**注册期一致性校验**（NONE⇒interfaceDirect、instanceControl⇒InstanceControl、supportedFaults 非空⇒FaultInjectable）与**缺省唯一性校验**（§7.5）；`EventBus`（`sim.*`，sourceId 约定） | 注册表单测：三类一致性违规拒绝注册、多缺省/零缺省报错、事件发布订阅与 sourceId 约定 |

### M0-b 组件与拓扑执行（约 3~4 天）

| # | 任务 | 内容与落点 | 完成判据 |
| --- | --- | --- | --- |
| T5 | VirtualRegistry | 会话/临时节点/watch 的内存状态机（端点 NONE、interfaceDirect=true）；`registry-flap` 仅留接口，M1 实现 | 状态机单测：会话建立/过期/临时节点清理/watch 触发 |
| T6 | TaskStub 行为模型 | M0 子集：`duration / jitter / successRate / exception / logLines`（`failAt / neverReport / progress` 留 M1）；作为独立模型类，VirtualWorker 与 demo real worker 共同消费 | 行为分布单测（确定性种子）：成功率、异常抛出、日志产出 |
| T7 | VirtualWorker | 每 Instance 一个虚拟线程；心跳 + 槽位上报 + 任务执行（TaskStub）+ 状态回报；`InstanceControl` 实现；Duo 协议服务端（DUO_PORT 端点） | 单测：心跳节律、实例级 stop/restart（端点身份保留）、与协议服务端集成 |
| T8 | kernel 组件管理器与接线 | 拓扑排序启动、启动失败逆序拆除、场景结束统一清理（含 SUT 协作停止）；`WiringResolver`：槽解析、`path` 显式/缺省推断、direct 的消费方（in-process）与目标（interfaceDirect）双校验、external exposes 校验——规则 2/3 | 管理器单测：排序、逆序拆除、路径推断三分支、两类 direct 违规报错文案 |
| T9 | `duo-sim-scenario` | SnakeYAML 加载拓扑/behaviors（M0 仅 default 绑定）/timeline/节点 config；**Validator 规则 1–8 全量**（timeline 仅校验不执行）；`ScenarioEngine` 最小执行（start → run → stop，结束条件＝SUT DAG 完成事件或显式 stop） | 校验器单测逐规则覆盖；YAML 示例（设计文档 §8）作为金标准用例 |

### M0-c SUT 适配面与示例（约 3~4 天）

| # | 任务 | 内容与落点 | 完成判据 |
| --- | --- | --- | --- |
| T10 | SUT 适配面 | `SutLauncher`（独立线程调阻塞 `run()`）、`SutContext`（端点清单/直连对象/SutEventPublisher/config/onStop/ready）、ready 回调 60s 超时、`sut.exited` / `sut.crashed` 探测、停止超时与 interrupt 兜底 | 生命周期单测：ready 超时、正常停止、未注册 onStop interrupt、自行退出两事件 |
| T11 | demo-scheduler | 内存调度状态机：DAG 依赖 + 重试 + 失败转移；scheduler 侧 Duo server（收注册/心跳/槽位、派发任务、收状态）；`sut.*` 事实发布；`SutMain` + `onStop` 完整实现 | 状态机单测：依赖触发、失败重试、worker 失联转移（直接注入组件级停机模拟） |
| T12 | demo real worker | worker 契约 real 档实现：kernel-hosted、Duo 协议客户端、**复用 TaskStub 消费同一 bindings**（保证换档后行为剧本仍生效——零改动验收的关键） | 与 VirtualWorker 同一组行为单测通过（模型复用的证明） |
| T13 | 场景文件与验收测试 | 两份仅 `workers.tier` 不同的 YAML + `TierSwapAcceptanceTest`（§1 通过条件） | **两档位全绿＝§13 验收通过，M0 完成** |

## 4. 测试与设计文档映射

| 设计文档条款 | 验证位置 |
| --- | --- |
| §13 档位切换验收 | T13 验收测试 |
| §8 校验规则 1–8 | T4（规则 1/注册期）、T8（规则 2/3）、T9（规则 4–8）单测 |
| §7.5 注册期一致性/缺省唯一性 | T4 单测 |
| §7.3 SUT 生命周期 | T10 单测 + T11 示范实现 |
| §7.2 实例寻址与无降级 | T7 单测（实例级 stop/restart；对未实现 InstanceControl 的桩组件断言校验期报错） |
| §7.1 生命周期/restart 语义 | T8 单测 |
| §7.4 事件模型/sourceId/命名空间 | T4 单测 + T13 事件流断言 |

## 5. M0 明确不做（防蔓延）

timeline 动作执行（仅校验）、registry-flap 实现、`failAt/neverReport/progress` 字段、assertions 断言库、`@VirtualCluster` JUnit 扩展、embedded 适配器（Curator/H2/Fabric8）、控制面 REST/CLI、性能压测、第三方 SUT 适配。

## 6. M0 特有风险与对策

1. **demo-scheduler 范围蔓延**：最大风险是"参考被测实现"越长越大。对策：状态机钉死为 DAG + 重试 + 失败转移三件事，告警/补数/补拉等一概不做；代码量预算 ~600 行内。
2. **虚拟线程 + 阻塞 IO 行为**：JDK 21 官方支持，M0 仅回环小规模连接（10 worker × 1 连接），无规模风险；万级压测属 M4。
3. **依赖漂移**：Jackson/SnakeYAML/JUnit 版本在父 pom 锁定并集中管理。
4. **Windows 环境**：M0 纯 Java、无托管二进制，预期无兼容问题；如遇文件锁/路径分隔符问题在 T1 骨架期即暴露。

## 7. 执行节奏

单人按 M0-a → M0-b → M0-c 顺序推进，每个里程碑收尾跑一次全量测试并向用户同步；T13 通过后 M0 关闭，进入 M1 计划（时间线注入 + 断言库 + registry-flap）。

**批准本计划后即可开始 T1 编码**（此前仍不动代码）。
