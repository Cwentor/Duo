# Duo M2 实施计划 —— 嵌入式中间件与选主观测

- 日期：2026-09-14
- 依据：设计文档 v1.0（冻结）§14 M2 行；M0 已验收（29b1a42）、M1 已验收（d377829，清理提交 1956b49）
- 范围：embedded 档（Curator TestingServer / H2 / Fabric8 mock）+ embedded registry-flap（真实会话打断）+ 临时节点变化观察 + `@VirtualCluster` JUnit 扩展 + `masterReelectedWithin` 断言
- 状态：待用户批准（批准前不动代码）

---

## 1. 交付定义（唯一验收口径）

**设计文档 §14 M2 验收场景：注册中心闪断 5s → 重新选主且无任务丢失。** 具体化为一条验收测试：

- 拓扑：`zk` 换 **embedded 档**（Curator TestingServer，真实 ZK 协议与真实会话）+ `master` real SUT（**代码切换为 ZK 客户端**——§13/§335 明示的 SUT 代码变更，非违约）+ `workers` virtual（count 4，slots 1）
- 场景剧本：
  1. master 与 workers 经真实 ZK 会话注册（临时节点）；
  2. timeline `registry-flap zk` @T → **真实会话打断**（embedded 适配器关闭/重建 TestingServer 或强制会话过期）；
  3. workers 在闪断期间观察到会话失效（watch/连接状态），恢复后重新注册并**重新发现 master**（master 端点由 master 在恢复后重新注册——真实 ZK 语义下临时节点随会话消失，这是 SUT 侧要处理的真实逻辑）；
  4. DAG 全部任务 SUCCESS。
- 断言（双轨，M1 断言库复用 + 新增一条）：
  - **`masterReelectedWithin { seconds: N }`（M2 新增）**：从 `sim.fault-injected(action=registry-flap)` 起算，窗口内出现 `sut.leader-elected`（demo-scheduler 在重新注册成功后发布；§7.3 事实发布约定）——即"重新选主"；
  - `noTaskLost { requireAllSuccess: true }`（M1 已有）；
  - `eventSequence [sim.fault-injected, sut.leader-elected]`。
- 通过条件：验收测试全绿 + **M0/M1 全部回归全绿**（TierSwap / FailoverAcceptance）。

## 2. 范围与不做

**做**：

| # | 项 | 依据 |
| --- | --- | --- |
| 1 | `duo-sim-embedded` 的 **Curator registry 适配器**：(REGISTRY, EMBEDDED) provider，TestingServer 生命周期、真实 ZK 客户端接口、`endpoints()` 暴露真实端口（THIRD_PARTY 形态） | §4/§6/§14 |
| 2 | **embedded registry-flap**：真实会话打断（TestingServer 重启或 `KillSession`），`FaultInjectable` 声明；恢复语义 | §14 |
| 3 | **临时节点变化观察**：embedded 适配器把 ZK watch 事件转成框架事件（会话建立/断开、节点变更）——§7.3 观测途径①的 embedded 版 | §7.3/§14 |
| 4 | **store 契约反推 + H2 适配器**：`StoreContract`（JDBC/事务/方言差异语义）+ (STORE, EMBEDDED) H2 provider；契约接口随实现反推（YAGNI） | §5/§14 |
| 5 | **Fabric8 K8s mock 适配器**：(RESOURCE, EMBEDDED) provider，`kubernetes-server-mock` 的 CRUD/事件拦截 | §14 |
| 6 | `@VirtualCluster` JUnit 扩展（duo-sim-junit）：注解加载场景 YAML → 生命周期管理（before/after）→ 断言注入 | §4/§14 |
| 7 | `masterReelectedWithin` 断言 + demo-scheduler 的 `sut.leader-elected` 事实发布（重新注册成功时） | §11/§14 |
| 8 | M2 金标准 YAML + 验收测试 | §14 |

**不做**：container 档（M4）、控制面（M3）、加速时钟（M4）、`alertFired`（无告警组件，继续延后）、多 master 真实选主竞争（单 master + 重注册的"重选主"语义足够验收；多候选竞争属 M4+）、external SUT 场景（M2 用 in-process）。

## 3. 任务分解（8 任务，估 8~10 天）

| # | 任务 | 内容与落点 | 完成判据 |
| --- | --- | --- | --- |
| T24 | embedded registry 适配器 | `duo-sim-embedded`：(REGISTRY, EMBEDDED) provider + `CuratorRegistry`：TestingServer 启停（随机端口，§6）、`RegistryContract` 的真实 ZK 实现（会话/临时节点/watch 映射到 Curator API）、`endpoints()` 返回 THIRD_PARTY 端口、元数据（endpointShape=THIRD_PARTY, interfaceDirect=false） | 单测：会话/临时节点/watch 经真实 ZK 往返；`(REGISTRY, EMBEDDED)` 注册期一致性通过；端口暴露 |
| T25 | embedded registry-flap | `CuratorRegistry` 实现 `FaultInjectable` 声明 registry-flap：**真实会话打断**（对测试服务器强制会话过期/重启 server）；恢复后 master/workers 需重新建立会话与临时节点（真实 ZK 语义，无快照重放——与 virtual 档的语义差异在 javadoc 钉死） | 单测：flap 期间临时节点消失（另一客户端视角）、会话状态变化事件、恢复后重注册可见 |
| T26 | 临时节点变化观察 | embedded 适配器注册 ZK watch → 框架事件：`sim.registry-session-opened/closed`（已有约定）+ 节点变更事件 `sim.registry-node-changed {path, kind}`；供断言与录制 | 单测：外部客户端改节点 → 框架事件流出 |
| T27 | store 契约 + H2 适配器 | kernel `contract/StoreContract`（连接/事务/方言差异语义，随 H2 实现反推）+ `duo-sim-embedded` (STORE, EMBEDDED) H2 provider（内存模式 `jdbc:h2:mem:`，`endpoints()` 返回 JDBC URL，THIRD_PARTY 形态） | 单测：契约往返（建表/事务/查询）；注册期一致性通过 |
| T28 | Fabric8 K8s mock 适配器 | (RESOURCE, EMBEDDED) provider 包 `kubernetes-server-mock`：启停、Pod CRUD/事件拦截可用（供后续 K8s 类 SUT 场景） | 单测：mock server 启停 + 一次 Pod 创建/查询往返 |
| T29 | `masterReelectedWithin` + SUT 事实 | kernel `Assertions.masterReelectedWithin(seconds)`（锚定 action=registry-flap 的 fault-injected；成功＝窗口内 `sut.leader-elected`）+ AssertionParser 注册；**demo-scheduler**：注册端点成功后发布 `sut.leader-elected`（首次与重注册均发，载荷含 epoch/attempt） | 单测：断言正反例；parser 识别新断言名 |
| T30 | `@VirtualCluster` 扩展 | `duo-sim-junit`：注解（value=YAML 路径）+ JUnit5 `BeforeAllCallback/AfterAllCallback` 或 `BeforeEachCallback` 扩展：加载→校验→启动→测试体可注入 `ScenarioEngine`（参数解析器）→after 停引擎；失败时保留录制路径输出 | 单测：一个用注解的场景测试跑通（含注入 engine 与断言） |
| T31 | M2 金标准 + 验收测试 | YAML：zk embedded + master real（**demo-scheduler 切 ZK 客户端**：经 `RegistryContract` 接口不变——interface-direct 换成 wire 到真实 ZK？→ 见 §4 决策）+ workers virtual；timeline registry-flap；assertions 三条；`ReelectionAcceptanceTest` | **全绿＝M2 验收通过** |

## 4. 关键决策（实现前钉死）

**D1：demo-scheduler 如何消费 embedded registry（§6 换档约束）**
embedded 档 `endpointShape=THIRD_PARTY`、`interfaceDirect=false` → **direct 路径不可用**（§6/§8 规则 3 明确拒绝）。两条路：

- **(a) wire-protocol + ZK 客户端**（§13/§335 明示的"SUT 代码需改为 ZK 客户端"）：demo-scheduler 引入 Curator 客户端依赖，连 `zk` 节点暴露的真实端口。最贴近真实 SUT，但 examples 模块引入 ZK 依赖，且 demo-scheduler 要写连接/重连/会话恢复逻辑（这正是 M2 要演练的 SUT 侧真实行为——**推荐**）。
- (b) 框架另提供"同接口 + 走真实协议"的桥（§6 提到 M2 起按需评估）：内核注入一个 `RegistryContract` 实现，内部转发到真实 ZK。SUT 零改动，但**背离"embedded 档验证真实协议交互"的目的**（SUT 仍不感知真实 ZK 行为），且增加内核复杂度。

**决定：(a)**。理由：M2 的验收价值恰在"SUT 在真实注册中心闪断下的自愈"；桥方案会让 SUT 侧的会话恢复逻辑永不被演练。代价（demo-scheduler 引入 Curator、写重连逻辑）正是要验证的代码。

**D2：embedded registry-flap 的"真实会话打断"手段**
Curator `TestingServer` 无官方 KillSession API。可选：(a) `TestingServer.stop()` + `restart()`（整服重启，最真实但端口变化需处理）；(b) `TestingServer.restart()` 的会话保留行为验证；(c) 用 Curator 客户端连接真实 ZK 后调用底层 `KillSession` 四字命令（zkCli 风格）。**决定：优先 (c)（KillSession 精确打断会话，端口不变），(c) 不可行则退 (a) + 端点重注册**。T25 首日验证 (c) 可行性，不可行即改 (a) 并记录。

**D3：virtual/embedded 两档 flap 语义差异的文档化**
virtual 档＝快照重放（对 SUT 透明）；embedded 档＝真实会话丢失（SUT 必须自愈）。差异写进 `RegistryContract` javadoc 与 T25 判据，避免"换档后行为不一致"的误读。

## 5. 风险与对策

1. **Curator TestingServer 与 JDK 21**：Curator 版本需与 ZK 3.9+ 匹配；Windows 上 TestingServer 的临时目录/端口绑定需验证（T24 首日）。对策：锁定 Curator 5.7+ / ZK 3.9+，Windows 问题优先用临时目录显式指定。
2. **KillSession 可行性**（D2）：T25 首日 spike，不可行即切方案 (a)。
3. **Fabric8 mock 依赖体积**：`kubernetes-server-mock` 引入较多传递依赖。对策：只落 `duo-sim-embedded`（M0 起就是占位空模块，无既有约束）。
4. **demo-scheduler 范围蔓延**（M0 风险 1 的延续）：M2 只加"ZK 连接 + 会话恢复 + leader-elected 发布"三件事，不加重连退避策略等额外功能（M4+ 再议）。
5. **`@VirtualCluster` 与场景 YAML 路径约定**：classpath vs 文件系统。对策：先支持 classpath（`resources/scenarios/`），文件系统留参数。

## 6. 执行节奏

T24 → T25 → T26 →（T27/T28 可并行）→ T29 → T30 → T31。每任务全量回归（**整 reactor**：`-pl` 单模块会吃 .m2 旧 jar，M1 教训）；T31 全绿即 M2 关闭。工期 8~10 天。

**批准本计划后即开始 T24 编码。**
