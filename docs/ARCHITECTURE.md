# Duo 架构说明

- 适用版本：`0.1.0-SNAPSHOT`（HEAD `5b72753`，2026-09-18）
- 依据：设计文档 v1.0（冻结）——下文 `§n` 均指
  [`superpowers/specs/2026-09-13-duo-virtual-bigdata-sim-design.md`](superpowers/specs/2026-09-13-duo-virtual-bigdata-sim-design.md)

---

## 1. 设计原则

1. **内核只认识契约，不认识产品**。ZooKeeper、H2、K8s 都只是某个契约的某个档位实现（§5）。
2. **启动前快速失败，且只读静态元数据**。所有校验读契约注册表的**能力元数据**，不触碰运行时实例（§6/§7.5）。
3. **无降级路径**。能力不足即失败并留一级事件，绝不把「实例级请求」悄悄改写成「整组生效」（§7.2）。
4. **换档零改动**＝测试代码与拓扑其余部分零改动；SUT 自身是否要改取决于其连接路径（§6）。
5. **先有内核能力，后有控制面**。M3 的 REST/CLI 只是 `ScenarioRuntime` 的外层包装，未新增内核符号（§10）。

## 2. 模块与依赖

```
                      ┌────────────────────┐
                      │ duo-sim-protocol   │  Duo 线协议帧/报文（只依赖 Jackson）
                      └─────────┬──────────┘
                                │
   ┌────────────────────────────┴─────────────────────────────┐
   │ duo-sim-kernel                                           │  SPI/注册表/管理器/
   │ （零第三方依赖）                                          │  事件总线/接线/SUT 适配/断言
   └───┬───────────┬───────────┬───────────┬─────────────┬────┘
       │           │           │           │             │
 ┌─────▼─────┐ ┌───▼──────┐ ┌──▼───────┐ ┌─▼──────────┐ ┌▼──────────────┐
 │ scenario  │ │components│ │ embedded │ │   junit    │ │    control    │
 │ YAML/编排 │ │ virtual档│ │ embedded │ │ @Virtual   │ │ REST + CLI    │
 │ 时间线/录制│ │ 组件      │ │ container│ │ Cluster    │ │ ScenarioHost  │
 └─────┬─────┘ └───┬──────┘ └──┬───────┘ └─┬──────────┘ └┬──────────────┘
       │           │           │           │             │
       └───────────┴───────────┴─────┬─────┴─────────────┘
                                     ▼
                            ┌──────────────────┐
                            │ duo-sim-examples │  参考 SUT + 场景 + 全部验收测试
                            └──────────────────┘
```

依赖纪律：

- `kernel` **不反向依赖** `scenario`。接线解析需要拓扑信息时，`scenario` 把 `NodeSpec` 适配成
  `WiringResolver.NodeView` 传入（`ScenarioEngine.toView`）。
- `junit` / `control` **自身不带集成测试**；它们的测试放在 `examples`，避免 `junit ↔ examples` 循环依赖
  （`@VirtualCluster` 的集成测试需要真实组件）。
- `examples` 是唯一聚合全部模块的模块，既是示例也是验收载体。

## 3. 运行时数据流

```
YAML ──ScenarioLoader──► Scenario(record)
                          │
                          ├─ ScenarioValidator 规则 1–8（只读能力元数据）──► 失败即抛，不拉起任何组件
                          ▼
                    ScenarioEngine
                          ├─ startSut()        in-process SUT 先于内核组件启动（其端点要写进 registry）
                          ├─ startComponents() 拓扑排序 → init(ComponentContext) → start() → wiring 注入
                          ├─ TimelineScheduler 以「全部组件启动完成」为 t0 执行时间线
                          └─ stop()            关时间线 → SUT 协作停止 → 组件逆依赖序拆除 → 断言评估 → 录制落盘
                          │
        SimpleEventBus ◄──┴── 组件事件 / SUT 事实 / 注入事件（统一单一订阅路径）
             │
             ├─► 内存事件流 List<Event>（CopyOnWriteArrayList，只追加）
             ├─► EventRecorder → build/scenarios/<name>/events.jsonl
             └─► ScenarioHost（控制面）按 1-based 下标增量切片
```

## 4. 内核 SPI

### 4.1 组件生命周期 `VirtualComponent`（§7.1）

```java
public interface VirtualComponent {
    ComponentId id();
    void init(ComponentContext ctx);     // 配置、SimClock、EventBus、wiring 绑定、exposes
    void start() throws ComponentException;
    void stop(StopMode mode);            // GRACEFUL | CRASH（CRASH＝故障注入的"宕机"）
    void restart();                      // 端点与身份保留，内部状态清空，重新 init/start
    HealthReport health();
    List<ExposedEndpoint> endpoints();   // 运行时实际绑定地址（校验期不读）
}
```

`ComponentContext` 提供：`config()`（YAML 自由键值 + `capacity.*` + `count` + `behaviors.*` 展平）、
`clock()`、`eventBus()`、`exposes()`、类型化 direct 访问器（`directRegistry()/directWorker()/directScheduler()`）、
`wireEndpoint(slotName)`。

### 4.2 实现提供者 `ComponentProvider`（§7.5）

```java
public interface ComponentProvider {
    Contract contract();
    Tier tier();
    String implName();          // 同 (contract, tier) 下唯一；节点可用 impl: 指定
    boolean isDefault();        // 同 (contract, tier) 必须恰好一个
    CapabilityMetadata metadata();
    VirtualComponent newComponent();
}
```

经 **Java SPI（`ServiceLoader`）** 注册。当前注册清单：

| 模块 | `META-INF/services/io.duo.sim.kernel.spi.ComponentProvider` |
| --- | --- |
| `duo-sim-components` | `VirtualRegistryProvider`、`VirtualWorkerProvider` |
| `duo-sim-embedded` | `CuratorRegistryProvider`、`H2StoreProvider`、`Fabric8K8sMockProvider`、`ZookeeperContainerProvider` |
| `duo-sim-examples` | `DemoRealWorkerProvider`、`DemoSchedulerProvider` |

### 4.3 能力元数据与注册期校验（§7.5）

```java
public record CapabilityMetadata(EndpointShape endpointShape,   // NONE | DUO_PORT | THIRD_PARTY | FS_PATH
                                 boolean interfaceDirect,        // 是否提供同进程 Java 接口适配
                                 boolean instanceControl,        // 是否支持实例级操作
                                 Set<String> supportedFaults,    // 支持的 FaultInjectable 类动作
                                 boolean defaultImpl) { }
```

`ContractRegistry.register()` 强制执行**三条一致性校验**（元数据与实现脱节即拒绝注册）：

| 校验 | 理由 |
| --- | --- |
| `endpointShape == NONE` ⇒ `interfaceDirect == true` | 无端点又不可直连的实现不存在任何消费路径 |
| `instanceControl == true` ⇒ 实现类 `implements InstanceControl` | 元数据不得超出实现能力 |
| `supportedFaults` 非空 ⇒ 实现类 `implements FaultInjectable` | 同上 |

另加：同 `(contract, tier)` 内 `implName` 唯一；`validateDefaults()` 在场景启动前强制「每个
`(contract, tier)` 恰好一个 `default: true`」——多缺省或零缺省即报错（§7.5 确定性）。

### 4.4 故障注入 SPI（§7.2）

```java
public interface FaultInjectable {                 // 契约级可选能力
    void inject(FaultAction action);               // 带 duration 的动作由场景引擎到期自动 clear
    void clear(FaultAction action);
}
public interface InstanceControl {                 // count > 1 组件的可选能力
    void stopInstance(int index, StopMode mode);   // index 从 1 开始
    void restartInstance(int index);
    void injectOnInstance(FaultAction action);
}
```

### 4.5 组件管理器 `ComponentManager`（§7.1）

- `startAll(order, ...)`：按 `WiringResolver.topoOrder` 依赖序启动；任一失败 → `stopAll()` 逆序拆除 + 抛
  `StartupFailure`（携带根因）。
- `adopt(id, c)`：纳管**已在外部启动**的组件（如 SUT 启动前先起的 registry），使其进入统一拆除序列。
- `registerExtraStop(key, runnable)`：注册 SUT 协作停止，纳入逆序拆除。
- `stopAll()`：`live` 是 `LinkedHashMap`（插入＝启动序），逆序即反依赖序（§7.1）。

## 5. 契约与档位

### 5.1 契约分类（§5/§6）

| 契约 | 类型 | 角色语义 | 已实现的档位 |
| --- | --- | --- | --- |
| `registry` | 标准协议 | 会话、临时节点、watch | virtual / embedded / container |
| `store` | 标准协议 | JDBC、事务、方言 | embedded |
| `resource` | 标准协议 | 队列、配额、容器分配 | embedded |
| `message` | 标准协议 | 主题、生产/消费 | — |
| `filestore` | 标准协议 | 路径、读写、容量 | — |
| `worker` | 交互型 | 心跳、资源上报、任务收发 | virtual / real |
| `scheduler` | 交互型 | 依赖编排、重试、失败转移 | real（参考 SUT） |
| `engine` | 交互型 | 提交→状态流转→终态+日志 | —（仅接口骨架） |

**交互型契约不设 embedded/container 档**：这两个档位的定义是「真实第三方协议实现」，交互型契约无此形态，
`ScenarioValidator` 借 `InteractiveTierGuard` 直接拒绝（§6）。

### 5.2 档位语义（§6）

| 档位 | 含义 | 端点形态 |
| --- | --- | --- |
| `virtual` | 纯虚拟实现：标准协议契约＝进程内状态机；交互型契约＝暴露 **Duo 线协议**真实端口 | NONE / DUO_PORT |
| `embedded` | JVM 内运行真实第三方协议实现 | THIRD_PARTY（可同时 `interfaceDirect=true`） |
| `container` | 本地 Docker 按需拉起真容器（Testcontainers 桥） | THIRD_PARTY |
| `real` | 真实实现（通常是 SUT，也可为任意真实组件）；宿主分 kernel-hosted / external | 视实现 |

### 5.3 双面实现（`THIRD_PARTY` + `interfaceDirect=true` 并存）

`CuratorRegistry` 是典型案例：**同一个组件实例提供两个面**——

- **wire 面**：`endpoints()` 暴露真实 ZK 端口，SUT 用真实 Curator 客户端连接（`path: wire`）；
- **门面面**：同进程实现 `RegistryContract`，框架组件（`VirtualWorker`）经 `path: direct` 消费。

两个字段互相独立，注册期一致性校验不冲突（§7.5；M2 计划 D1b）。门面持有**独立 Curator 会话**，
与 SUT 会话互不影响——闪断时两者都断、各自重连，忠实于真实多客户端语义。

## 6. 接线（wiring）规则

### 6.1 判定决策表（§6/§8 规则 3）

| 槽声明 | 目标端点形态 | 消费方 | 结果 |
| --- | --- | --- | --- |
| `path: wire` | ≠ NONE | 任意 | ✅ wire-protocol |
| `path: wire` | NONE | 任意 | ❌ 校验失败：「目标无端点，请升档或改 direct」 |
| `path: direct` | 任意 | external | ❌ 校验失败：「external 只能走 wire」 |
| `path: direct` | 任意 | in-process | 需 `interfaceDirect=true`，否则 ❌ |
| 缺省（无 `path`） | ≠ NONE | 任意 | 推断为 wire |
| 缺省（无 `path`） | NONE | in-process | 推断为 direct（NONE ⇒ interfaceDirect 由注册期保证，恒自洽） |

校验在**两处**执行，语义一致：`ScenarioValidator`（启动前批量报错，规则 3）与 `WiringResolver`（解析期兜底，
抛 `WiringException`）。缺省推断保证「NONE 实现必然可 direct」，故推断结果永远通过 `interfaceDirect` 检查。

### 6.2 拓扑排序

`WiringResolver.topoOrder` 按 wiring 依赖做 DFS 后序排序（依赖者后启），检测到环即抛
`WiringException("wiring cycle detected ...")`——接线成环不是合法场景。

### 6.3 wiring 简写

```yaml
wiring: { registry: zk }                                # 简写：仅当槽名与已注册契约同名
wiring: { registry: { node: zk, contract: registry } }  # 显式
wiring: { registry: { node: zk, path: direct } }        # 显式连接路径
```

简写（值为纯字符串）时 `contract = null`，由「槽名即契约名」规则补全；槽名与目标节点契约不一致即校验失败（规则 2）。

## 7. 生命周期时序

```
ScenarioHost.start(yaml)
  └─ ScenarioLoader.load
  └─ ContractRegistry.loadFromServiceLoader()
  └─ ScenarioEngine.validated(scenario, registry)      ← 规则 1–8，失败即抛（不拉起任何组件）
  └─ engine.startSut()                                 ← ① SUT 的 direct 依赖先起（纳入 manager.adopt）
  │                                                    ② 反射实例化 launch.main（SutMain），独立线程调 run()
  │                                                    ③ 按 SUT 的 wire 槽注入目标 endpoints() 实际地址
  │                                                    ④ ready 到达后登记协作停止器
  └─ engine.startComponents()                          ← 拓扑排序启动内核托管组件（跳过 SUT/external/已 adopt）
  │                                                    启动完成即发 sim.scenario-started，并以此刻为时间线 t0
  └─ TimelineScheduler.start()（timeline 非空时）
  ...
  └─ engine.stop()
       ① timeline.close()（取消未触发动作）
       ② manager.registerExtraStop("sut", ...) → 组件逆依赖序 stop(GRACEFUL)
       ③ 发 sim.scenario-finished
       ④ recorder.flush()（JSONL 落盘）
       ⑤ evaluateAssertions()（YAML 内置评估写入 ScenarioResult）
```

**为什么 SUT 先启动**：in-process SUT 的 Duo 端点要写进 registry，worker 的「发现 master」语义依赖它；
若 registry 被二次 `start()`（例如 SUT 预启动过又被组件管理器启动），`CuratorRegistry` 会重建 TestingServer
导致已注册节点全丢——所以 `startComponents()` 必须跳过 `manager.live()` 中已存在的节点（这是 M2 修复的真实缺陷）。

## 8. 故障注入通路（`ScenarioRuntime`）

注入分三层校验（§7.2，**无降级**）：

1. **target 可解析**：组件存在；携带实例下标时 `index ∈ [1, count]`。
2. **动作受支持**：动作在实现 `supportedFaults` 中，或属 `crash`/`restart` 生命周期动作。
3. **实例能力**：携带下标时实现必须具备 `instanceControl`。

分发规则：

| 动作 | 整组（无下标） | 实例级（有下标） |
| --- | --- | --- |
| `crash` | `component.stop(CRASH)` | `InstanceControl.stopInstance(i, CRASH)` |
| `restart` | `component.restart()` | `InstanceControl.restartInstance(i)` |
| 其他（`registry-flap`/`task-kill`/…） | `FaultInjectable.inject(action)` | `InstanceControl.injectOnInstance(action)` |

失败处理（§12，**不允许静默吞掉**）：

- 时间线场景：**校验期直接报错，不启动**（`ScenarioValidator` 规则 6）。
- 热注入：记 `sim.fault-inject-failed` 一级事件并计入 `ScenarioResult.injectionFailures`。
- `target` **不得为 SUT**（in-process 无法安全强杀，external 生命周期归用户）；**唯一豁免是 `custom-hook`**
  ——它是用户钩子而非故障动作，允许指向 SUT 做协作式操作（§7.2）。

`duration` 语义：仅对 `FaultInjectable` 类动作有意义，由 `TimelineScheduler` 计时到期自动 `clear()`；
`crash`/`restart` 生命周期动作不可「清除」。**两档 flap 语义不对称**：virtual 档 `registry-flap` 是持续窗口
（`duration` 有效），embedded 档是瞬时整服 restart（`duration` 被忽略）——对 embedded 节点误写 `duration`
校验器给**显式警告**而非静默忽略。

## 9. 事件总线与命名空间（§7.4）

- **模型**：`Event { type, sourceId, timestamp, payload }`，进程内自研轻量发布/订阅（`SimpleEventBus`）。
- **sourceId 约定**：组件级事件用 `componentId`（`workers`）；实例级事件用 `componentId-N`（`workers-3`）。
- **命名空间按「事件所属域」划分，而非「发出者」**：
  - `sim.*` ＝框架自身事件；
  - `sut.*` ＝SUT 相关事实（门面发布的内部事实，**或内核探测的 SUT 生命周期事件** `sut.exited`/`sut.crashed`）。

框架事件类型清单（`Event.SIM_EVENT_TYPES`）：

```
sim.scenario-started      sim.scenario-finished
sim.component-started     sim.component-stopped     sim.component-crashed
sim.fault-injected        sim.fault-cleared         sim.fault-inject-failed
sim.registry-flap-started sim.registry-flap-cleared
sim.registry-node-changed
sim.sut-exited            sim.sut-crashed
```

SUT 事实事件（由参考 SUT `demo-scheduler` 发布，属**事实源**，内核只转发与录制）：
`sut.scheduler-started`、`sut.worker-registered`、`sut.register-failed`、`sut.heartbeat`、
`sut.heartbeat-meter`、`sut.instance-lost`、`sut.leader-elected`、`sut.leader-election-failed`、
`sut.task-dispatched`、`sut.task-status`、`sut.task-terminal`、`sut.task-retry`、`sut.failover`、
`sut.dag-terminal`。

组件自发的 `sim.*` 生命周期/状态事件（不在 `SIM_EVENT_TYPES` 白名单内，属组件实现细节）：

| 组件 | 事件 |
| --- | --- |
| `VirtualRegistry` | `sim.registry-started`、`sim.registry-crashed`、`sim.registry-stopped`、`sim.registry-restarted`、`sim.registry-session-opened`、`sim.registry-session-closed`、`sim.registry-watch-error` |
| `VirtualWorker` | `sim.worker-started`、`sim.worker-crashed`、`sim.worker-stopped`、`sim.worker-instance-crashed`、`sim.worker-instance-offline`、`sim.worker-instance-restarted`、`sim.worker-task-status`、`sim.worker-task-progress`、`sim.worker-task-killed`、`sim.worker-task-unreported` |
| `HookRegistry` | `sim.hook-executed` |

**单一订阅路径**：SUT 侧事件与注入事件都经 `bus.publish` 汇流，内存流与录制共用同一订阅点，
保证两条流事件数一致（M1 T21 修复的真实缺陷：早期 SUT sink 与 ScenarioRuntime 绕过 bus 导致录制不全）。

## 10. SUT 适配面（§7.3）

### 10.1 in-process SUT（已实现）

```java
public interface SutMain { void run(SutContext ctx); }   // run() 阻塞直至 SUT 退出
```

`SutContext` 提供：`endpointByContract()`、`directBindings()` / `direct(contract, type)`、
`events()`（`SutEventPublisher`，事件统一 `sut.` 前缀）、`config()`、`onStop(Runnable)`、`ready()`。

生命周期约定：

| 事件 | 内核行为 |
| --- | --- |
| `ctx.ready()` 到达 | 就绪；超时（默认 60s，DSL `ready.timeout` 可覆盖）归启动失败路径 |
| `run()` 正常返回 | 发 `sut.exited` → 场景终止 |
| `run()` 抛异常 | 发 `sut.crashed` → 场景终止并保存现场 |
| `stop()` | 触发 `onStop` handler + 带超时等待 `run()` 返回；**未注册 handler 时＝interrupt `run()` 线程并记停止失败** |

**显式边界**：in-process 要求 SUT 可改码（埋点发布事实、实现 `SutMain`/`onStop`）。
不可改码的第三方 SUT 只能走 external + 旁路观测。

### 10.2 external SUT（设计已定，**引擎尚未实现**）

设计（§7.3）要求：内核生成端点配置文件（`launch.configOut`）+ stdout 兜底（`duo.endpoint.<contract>=<endpoint>`）；
external 节点必须在 `exposes` 显式声明端口、必须声明 `ready` 探针（`tcp`/`http`）；生命周期归用户。

**现状**：`ScenarioValidator` 已实现规则 4（external 的端点声明与 ready 探针校验），但
`ScenarioEngine.startSut()` 对 `launch.mode=external` 直接抛
`IllegalStateException("M0 engine only supports in-process SUT launch")`。
即：**external 能通过校验但跑不起来**——这是当前最大的功能缺口，已登记为
[路线图 G2 / M6](ROADMAP.md#m6--external-sut-与第三方接入)。

## 11. 观测面与断言（§11）

### 11.1 三通道现状

| 通道 | 状态 |
| --- | --- |
| 结构化日志 | 🟡 依赖 SLF4J API（父 POM 已管理版本），但**无 logback 配置文件**，未形成通道 |
| 事件流录制（JSON Lines） | ✅ `EventRecorder` → `build/scenarios/<name>/events.jsonl`，`readBack()` 可回读 |
| Prometheus 指标 | ❌ **未实现**（`sut.heartbeat-meter` 等是事件而非指标端点） |

> 录制是**审查材料**，不是可复现重放脚本——真实时钟下不承诺确定性逐字节重放（§11）。

### 11.2 断言双轨（共用同一事件事实源）

| 轨道 | 入口 | 用途 |
| --- | --- | --- |
| YAML 内置评估 | `assertions:` 节 → `AssertionParser` → `ScenarioEngine.evaluateAssertions()` | 场景结束直接判 pass/fail，供 CLI/CI |
| JUnit 编程式 | `DuoAssertions.assertThat(engine.events())` | 测试代码里组合时序/窗口/聚合 |

已实现断言（语义见 [`SCENARIO-DSL.md`](SCENARIO-DSL.md#6-断言-assertions)）：
`failoverWithin`、`noTaskLost`、`masterReelectedWithin`、`eventSequence`、`affectedTasksAtLeast`。
未知断言名在**校验期**即拒绝（不静默忽略）。

## 12. 控制面（§10 / M3）

`duo-sim-control` **零内核改动**，只消费 `ScenarioEngine` / `ScenarioRuntime` / `ScenarioResult` 的既有公开 API：

```
DuoCli ──┬── 同进程模式（run --keep 注册进程级 attach 表，后续命令按名接管）
         └── REST 客户端模式（serve 起服务端，各命令加 --url）
                    │
                    ▼
            RestControlServer（JDK 内置 HttpServer + 虚拟线程 executor）
                    │
                    ▼
              ScenarioHost（生命周期/状态/事件增量/注入转发/断言/拓扑）
                    │
                    ▼
              ScenarioEngine（内核）
```

REST 端点与错误映射：

| 端点 | 方法 | 说明 | 错误 |
| --- | --- | --- | --- |
| `/health` | GET | 服务存活 | — |
| `/scenario` | POST / DELETE | 启动（body＝YAML 文本）/ 停止 | 400 解析失败、409 已在运行 |
| `/scenario/status` | GET | 状态/断言/注入失败 | 409 未启动 |
| `/events?since=N` | GET | 事件增量 | 400 `since` 非整数 |
| `/inject` | POST | body＝`FaultAction` JSON | 400 解析失败、404 未知 target、409 未运行、405 方法不符 |
| `/assertions` | GET | 断言与注入失败明细 | 409 未启动 |
| `/topology` | GET | 节点/契约/档位/实例/在线状态 | 409 未启动 |

**事件序号语义（M3 D3）**：序号＝事件在 `engine.events()` 快照中的 **1-based 下标**。内核事件流是只追加的
`CopyOnWriteArrayList`，故下标稳定、天然不重不漏，直接切片即 O(新增)；**跨快照的严格全序不承诺**。
（早期版本用 `LinkedHashMap` + 线性扫描防 `record equals` 折叠，整体 O(n²) 且永不释放，M4 万级规模下不可接受，
已在 `7ac458b` 整改。）

## 13. Duo 线协议（§3）

交互型契约的 `virtual` 档暴露**框架自定义**的真实 TCP 协议（不冒充任何第三方产品协议）：

```
帧布局（大端序）：[magic: 4B "DUO1"][version: 1B][payloadLength: 4B][payload: NB]
HEADER_LENGTH = 9      MAX_PAYLOAD_LENGTH = 1 MiB      payload = UTF-8 JSON
```

报文清单（`DuoMessage`，Jackson `@JsonSubTypes`，`type` 判别字段）：

| 方向 | 报文 |
| --- | --- |
| worker → master | `register`、`heartbeat`、`slot`、`task-ack`、`task-status` |
| master → worker | `register-response`、`task-dispatch`、`task-cancel` |

连接模型：worker 拨号 master，每实例一条双向长连接。
第三方产品接入交互型契约＝为该产品写 Duo 线协议适配器（只依赖 `duo-sim-protocol` 与内核公开 SPI，§16 风险 1）。

## 14. 不变式清单（改动时的自检项）

1. 启动前校验**只读** `CapabilityMetadata`，不调 `endpoints()`（§6）。
2. `endpointShape=NONE ⇒ interfaceDirect=true`；`instanceControl`/`supportedFaults` 必须与实现接口对应（§7.5）。
3. 每个 `(contract, tier)` **恰好一个** `default: true`（§7.5）。
4. 注入**无降级**：能力不满足即失败 + 一级事件（§7.2）。
5. `target` 不得为 SUT（`custom-hook` 除外）（§7.2）。
6. 事件命名空间按域划分：`sim.` / `sut.`；实例级 sourceId 用 `componentId-N`（§7.4）。
7. 内存事件流与录制**同一订阅点**，两条流事件数一致（M1 T21）。
8. `stop(CRASH)` 后必须经 `restart()` 恢复；`restart` 保端点与身份、内部状态全新（§7.1）。
9. 容器档 `restart()` **显式不支持**（换宿主端口会导致 wire 永久挂起）——能力无法用元数据表达，
   已下沉为实现层守卫（M4 独立验收 HIGH 整改）。
10. 内核不得新增仅控制面需要的符号；控制面改动**零内核改动**（§10）。

---

## 附：源码导航

| 想看什么 | 去哪 |
| --- | --- |
| SPI 与数据模型 | `duo-sim-kernel/src/main/java/io/duo/sim/kernel/api/` |
| 注册表/管理器/接线/注入/总线 | `duo-sim-kernel/src/main/java/io/duo/sim/kernel/core/` |
| SUT 启动器 | `duo-sim-kernel/src/main/java/io/duo/sim/kernel/sut/SutLauncher.java` |
| 断言实现 | `duo-sim-kernel/src/main/java/io/duo/sim/kernel/assertion/Assertions.java` |
| YAML 解析/校验/编排/时间线 | `duo-sim-scenario/src/main/java/io/duo/sim/scenario/` |
| virtual 档组件与行为模型 | `duo-sim-components/src/main/java/io/duo/sim/components/` |
| embedded/container 档 | `duo-sim-embedded/src/main/java/io/duo/sim/embedded/` |
| 控制面 | `duo-sim-control/src/main/java/io/duo/sim/control/` |
| 参考 SUT 与场景 | `duo-sim-examples/src/main/java/io/duo/sim/examples/`、`duo-sim-examples/src/*/resources/scenarios/` |
