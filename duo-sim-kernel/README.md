# duo-sim-kernel

**仿真内核**：SPI、契约注册表与能力元数据、组件管理器、实例寻址、事件总线、wiring 接线、SUT 适配面、断言内核。

- 依赖：**零第三方依赖**（`pom.xml` 无 `<dependencies>`）——内核必须能独立演进
- 测试：57 条（`mvn -o -pl duo-sim-kernel test`）

## 包结构

| 包 | 内容 |
| --- | --- |
| `api` | `VirtualComponent`、`ComponentContext`（wiring 绑定/端点）、`ComponentProvider`、`CapabilityMetadata`、`EndpointShape`、`Contract`、`Tier`、`FaultAction`、`FaultInjectable`、`InstanceControl`、`Event`、`EventBus`、`ExposedEndpoint`、`HealthReport`、`SimClock`、`StopMode`、`SutMain`、`SutContext`、`SutEventPublisher` |
| `core` | `ContractRegistry`（注册期一致性校验 + 缺省唯一性）、`ComponentManager`（拓扑启动/逆序拆除）、`WiringResolver`（连接路径推断与校验 + 拓扑排序）、`ScenarioRuntime`（注入三层校验 + 分发，**无降级**）、`SimpleEventBus` |
| `sut` | `SutLauncher`（独立虚拟线程调阻塞 `run()`、ready 握手、协作式停止、退出/崩溃事件） |
| `assertion` | `Assertion`、`Assertions`（5 条语义钉死的断言）、`AssertionParser`（YAML 形态） |
| `contract` | 契约接口：`RegistryContract`、`WorkerContract`、`SchedulerContract`、`StoreContract`、`EngineContract`（骨架） |
| `util` | `Durations`（带单位时长解析） |

## 不变式（改动前必读）

见 [架构说明 · 不变式清单](../docs/ARCHITECTURE.md#14-不变式清单改动时的自检项)：
注册期三条一致性校验、注入无降级、事件命名空间、内存流与录制同一订阅点等。

→ [架构说明](../docs/ARCHITECTURE.md) · [开发指南 · 扩展点](../docs/DEVELOPMENT.md#4-扩展点操作步骤)
