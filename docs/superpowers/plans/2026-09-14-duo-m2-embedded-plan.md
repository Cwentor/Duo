# Duo M2 实施计划 —— 嵌入式中间件与选主观测（v2）

- 日期：2026-09-14（v2 修订）
- 依据：设计文档 v1.0（冻结）§14 M2 行；M0 已验收（29b1a42）、M1 已验收（d377829，清理提交 1956b49）
- 范围：embedded 档（Curator TestingServer / H2 / Fabric8 mock）+ embedded registry-flap（真实会话打断）+ 临时节点变化观察 + `@VirtualCluster` JUnit 扩展 + `masterReelectedWithin` 断言
- 状态：**已实施完成并验收通过**（T24~T31；M2 验收见 `docs/superpowers/acceptance/2026-09-15-duo-m3-acceptance-record.md` 之前置说明与提交 `cd002e8`）。
  本文档停留在批准前的 v2 文本（无实施期修订），实施中的关键改动以提交记录为准。
- 修订记录见文末附录

---

> **勘误（2026-09-18）**：上文状态行为**2026-09-18 补记**——原文为「v2 已按自审修订……待批准后开工」，
> 是 M2 开工前的文本，M2 实际已于 2026-09-15 完成并验收（`cd002e8`，验收记录见 M3 验收记录 §前置说明）。
> 保留原文以便追溯；自本条起，所有计划文档的状态行以**实际收尾状态**为准。

---

## 1. 交付定义（唯一验收口径）

**设计文档 §14 M2 验收场景：注册中心闪断 5s → 重新选主且无任务丢失。** 具体化为一条验收测试：

- 拓扑（两条**不同连接路径**并存，§6 允许逐槽指定）：
  ```yaml
  - id: zk
    contract: registry
    tier: embedded                      # Curator TestingServer（THIRD_PARTY 端口 + 同进程门面）
  - id: master
    contract: scheduler
    tier: real
    sut: true
    launch: { mode: in-process, main: io.duo.sim.examples.scheduler.DemoScheduler }
    wiring:
      registry: { node: zk, contract: registry, path: wire }    # SUT 切真实 ZK 客户端（D1）
  - id: workers
    contract: worker
    tier: virtual
    count: 4
    capacity: { slots: 1 }
    wiring:
      registry: { node: zk, contract: registry, path: direct }  # 框架组件走同进程门面（D1b）
  ```
- 场景剧本：
  1. master（真实 ZK 客户端，经 `SutContext.endpointByContract().get("registry")` 取地址）与 workers（经门面）完成注册/发现；
  2. timeline `registry-flap zk` @T → **TestingServer.restart() 整服闪断**（所有会话失效、临时节点消失）；
  3. master 的 Curator 客户端自动重连 → **重新注册**端点临时节点 → 发布 `sut.leader-elected {epoch:2}`；
  4. workers 的门面（Curator 客户端）自动重连 → 发现恢复；在途任务继续（worker 与 master 的 Duo TCP 连接不受 ZK 闪断影响）；
  5. DAG 全部任务 SUCCESS。
- 断言（双轨，M1 断言库复用 + 新增一条）：
  - **`masterReelectedWithin { seconds: N }`（M2 新增）**：起点＝`sim.fault-injected(action=registry-flap)`；成功＝窗口内出现 `sut.leader-elected`（demo-scheduler 重新注册成功后发布；首次注册的 epoch:1 不在窗口内，不干扰）；
  - `noTaskLost { requireAllSuccess: true }`（M1 已有）；
  - `eventSequence [sim.fault-injected, sut.leader-elected]`。
- 通过条件：验收测试全绿 + **M0/M1 全部回归全绿**（TierSwap / FailoverAcceptance）。

## 2. 范围与不做

**做**：

| # | 项 | 依据 |
| --- | --- | --- |
| 1 | `duo-sim-embedded` 的 **Curator registry 适配器**：(REGISTRY, EMBEDDED) provider，TestingServer 生命周期、真实 ZK 端口暴露（THIRD_PARTY，供 wire 客户端）+ **同进程门面**（§6 的"同接口+走真实协议"桥，供框架组件 direct 消费） | §4/§6/§14 |
| 2 | **embedded registry-flap**：`TestingServer.restart()` 整服闪断（会话全失效），`FaultInjectable` 声明；恢复语义＝真实会话丢失（无快照重放） | §14 |
| 3 | **临时节点变化观察**：embedded 适配器把 ZK watch 事件转成框架事件（会话建立/断开、节点变更）——§7.3 观测途径①的 embedded 版 | §7.3/§14 |
| 4 | **store 契约反推 + H2 适配器**：`StoreContract`（JDBC/事务/方言差异语义）+ (STORE, EMBEDDED) H2 provider；契约接口随实现反推（YAGNI） | §5/§14 |
| 5 | **Fabric8 K8s mock 适配器**：(RESOURCE, EMBEDDED) provider，`kubernetes-server-mock` 的 CRUD/事件拦截 | §14 |
| 6 | `@VirtualCluster` JUnit 扩展（duo-sim-junit）：注解加载场景 YAML → 生命周期管理（before/after）→ 断言注入 | §4/§14 |
| 7 | `masterReelectedWithin` 断言 + demo-scheduler 的 `sut.leader-elected` 事实发布（注册成功时发布，载荷 epoch） | §11/§14 |
| 8 | **`ScenarioEngine` 的 SUT wire 端点注入**（v2 新增，P1-2）：startSut 把 SUT wiring 槽的 wire 端点（目标 exposes 的实际绑定地址）注入 `SutContext.endpointByContract()`——现状硬编码 `Map.of()`，SUT 拿不到 ZK 地址 | v2 自审 |
| 9 | M2 金标准 YAML + 验收测试 | §14 |

**不做**：container 档（M4）、控制面（M3）、加速时钟（M4）、`alertFired`（无告警组件，继续延后）、多 master 真实选主竞争（单 master + 重注册的"重选主"语义足够验收；多候选竞争属 M4+）、external SUT 场景（M2 用 in-process）。

## 3. 任务分解（8 任务，估 8~10 天）

| # | 任务 | 内容与落点 | 完成判据 |
| --- | --- | --- | --- |
| T24 | embedded registry 适配器 | `duo-sim-embedded`：(REGISTRY, EMBEDDED) provider + `CuratorRegistry`：TestingServer 启停（**固定端口分配**，§6）、**wire 面**＝`endpoints()` 返回 THIRD_PARTY 端口；**门面**＝实现 `RegistryContract`（会话/临时节点/watch 映射到 Curator API，`/duo/endpoints/<contract>` 路径与 VirtualRegistry 兼容——`KNOWN_PATHS` 语义一致）；门面会话**按需自动重连**（Curator 重连语义透传）；元数据 `{endpointShape: THIRD_PARTY, interfaceDirect: true}`（v2：两字段独立，注册期一致性校验不冲突）；Curator 依赖进 parent dependencyManagement（curator-test/curator-framework 5.7.x） | 单测：门面会话/临时节点/watch 经真实 ZK 往返；wire 端口可被独立 Curator 客户端连接；`(REGISTRY, EMBEDDED)` 注册期一致性通过；固定端口 + Windows 临时目录验证 |
| T25 | embedded registry-flap | `CuratorRegistry` 实现 `FaultInjectable` 声明 registry-flap：**`TestingServer.restart()` 整服闪断**（首选；端口保持验证）；恢复后所有会话需重连、临时节点需重建（真实 ZK 语义，**无快照重放**——与 virtual 档差异在 javadoc 钉死，D3）。备选：KillSession（若 restart 端口不稳，T25 首日 spike 决定） | 单测：flap 期间另一客户端视角临时节点消失（watch DELETED）、恢复后重注册可见、门面重连后发现恢复 |
| T26 | 临时节点变化观察 | embedded 适配器注册 ZK watch → 框架事件：`sim.registry-session-opened/closed` + 节点变更事件 `sim.registry-node-changed {path, kind}`；供断言与录制 | 单测：外部客户端改节点 → 框架事件流出 |
| T27 | store 契约 + H2 适配器 | kernel `contract/StoreContract`（连接/事务/方言差异语义，随 H2 实现反推）+ `duo-sim-embedded` (STORE, EMBEDDED) H2 provider（内存模式 `jdbc:h2:mem:`，`endpoints()` 返回 JDBC URL，THIRD_PARTY 形态） | 单测：契约往返（建表/事务/查询）；注册期一致性通过 |
| T28 | Fabric8 K8s mock 适配器 | (RESOURCE, EMBEDDED) provider 包 `kubernetes-server-mock`：启停、Pod CRUD/事件拦截可用 | 单测：mock server 启停 + 一次 Pod 创建/查询往返 |
| T29 | `masterReelectedWithin` + SUT 事实 | kernel `Assertions.masterReelectedWithin(seconds)`（锚定 action=registry-flap 的 fault-injected；成功＝窗口内 `sut.leader-elected`）+ AssertionParser 注册；**demo-scheduler**：注册端点成功后发布 `sut.leader-elected {epoch}`（首次 epoch:1，重注册递增）；examples 引入 Curator 客户端依赖（D1 代价） | 单测：断言正反例（窗口外/无事件均失败）；parser 识别新断言名；demo-scheduler 单测覆盖 epoch 递增 |
| T30 | `@VirtualCluster` 扩展 | `duo-sim-junit`：注解（value=classpath YAML 路径）+ JUnit5 扩展（BeforeAll/AfterAll）：加载→校验→启动→参数解析器注入 `ScenarioEngine`→after 停引擎；失败时输出录制路径 | 单测：一个用注解的场景测试跑通（含注入 engine 与断言） |
| T31 | **SUT wire 端点注入 + M2 金标准 + 验收** | (a) `ScenarioEngine.startSut`：把 SUT wiring 的 wire 槽目标端点（`endpoints()` 实际绑定）按契约名注入 `SutContext.endpointByContract()`（含单测：wire 槽地址出现在 SUT 上下文）；(b) M2 金标准 YAML（§1 全部要素：双路径 wiring、timeline registry-flap、3 断言）；(c) `ReelectionAcceptanceTest`：闪断→重选主→无任务丢失 | **全绿＝M2 验收通过** |

## 4. 关键决策（实现前钉死）

**D1：demo-scheduler 消费 embedded registry 走 wire（真实 ZK 客户端）**
§13/§335 明示的"SUT 代码需改为 ZK 客户端"。理由：M2 的验收价值恰在"SUT 在真实注册中心闪断下的自愈"；桥方案会让 SUT 侧的会话恢复逻辑永不被演练。代价（examples 引入 Curator、demo-scheduler 写重连/重注册逻辑）正是要验证的代码。

**D1b（v2 新增）：框架自有组件（VirtualWorker）经同进程门面消费 embedded registry**
`VirtualWorker` 只经 `ctx.directRegistry()` 访问 registry，不 speak ZK 协议；embedded 档必须提供"同接口 + 走真实协议"的门面（§6 明示 M2 起按需评估——**评估结论：对框架组件 YES，对 SUT 仍 NO**）。元数据 `THIRD_PARTY + interfaceDirect=true` 使两种路径并存：SUT 显式 `path: wire`，框架组件显式 `path: direct`。门面持有独立 Curator 会话，与 SUT 会话互不影响（闪断时两者都断、各自重连——忠实于真实多客户端语义）。

**D2（v2 修订）：flap 优先 `TestingServer.restart()`**
整服闪断最贴合"注册中心闪断 5s"语义，且免去 KillSession 的 session-id plumbing。T25 首日验证 restart 后端口稳定性；不稳定则改固定端口 + stop/start，或退 KillSession。

**D3：virtual/embedded 两档 flap 语义差异的文档化**
virtual 档＝快照重放（对 SUT 透明）；embedded 档＝真实会话丢失（SUT 必须自愈）。差异写进 `RegistryContract` javadoc 与 T25 判据。

## 5. 风险与对策

1. **Curator TestingServer 与 JDK 21**：锁 Curator 5.7.x / ZK 3.9+；Windows 临时目录/端口绑定 T24 首日验证（显式指定临时目录）。
2. **restart 端口稳定性**（D2）：T25 首日 spike；退路已定。
3. **Fabric8 mock 依赖体积**：只落 `duo-sim-embedded`（无既有约束）。
4. **demo-scheduler 范围蔓延**：M2 只加"ZK 连接 + 会话恢复重注册 + leader-elected 发布"三件事。
5. **门面自动重连的时序**：门面重连是异步的（Curator 后台重连）→ workers 的发现重试（500ms×20）需覆盖重连窗口；T24 判据含"重连后发现恢复"。
6. **`@VirtualCluster` 路径约定**：先 classpath（`resources/scenarios/`），文件系统留参数。

## 6. 执行节奏

T24 → T25 → T26 →（T27/T28 可并行）→ T29 → T30 → T31。每任务全量回归（**整 reactor**：`-pl` 单模块会吃 .m2 旧 jar，M1 教训）；T31 全绿即 M2 关闭。工期 8~10 天。

---

## 附录：修订记录

- **v1（2026-09-14）**：初稿 8 任务 + D1/D2/D3。
- **v2（2026-09-14）**：自审修订（批准前）——
  1. **[P1] 补 D1b 同进程门面**：v1 只规划 wire 面，但框架组件（VirtualWorker）经 `directRegistry()` 消费 registry，embedded 档声明 `interfaceDirect=false` 会使 M2 拓扑的 workers→zk 槽被规则 3 拒绝。修订：embedded 适配器同时提供门面（§6 桥，对框架组件 YES 对 SUT NO），元数据 `THIRD_PARTY + interfaceDirect=true`，拓扑逐槽显式 path；
  2. **[P1] 补 T31(a) SUT wire 端点注入**：`ScenarioEngine.startSut` 现状硬编码 `Map.of()`（ScenarioEngine.java:195），SUT 拿不到 ZK 地址，D1 无从落地；
  3. **[D2 修订] flap 改 TestingServer.restart() 优先**（整服闪断更贴合语义且免 session-id plumbing），KillSession 降备选；
  4. §5 补风险 5（门面异步重连 vs 发现重试窗口）。

