# Duo 架构深化（10 卡）实施计划

- 日期：2026-10-24（同日开工）
- 状态：**实施中**——T1–T8 已完成（卡 1–5 ✅，最新 `4f011f5`）；T9–T13 未开始；T14 收尾未开始
- 完成判据（每卡硬门槛 + 总验收）：
  - 每卡：受影响模块契约测试**零改动全绿**；行为变化逐条有新测试钉住并在提交信息说明；整 reactor `test` BUILD SUCCESS
  - 总验收：整 reactor 全绿（当前口径 433 测/0 失败/0 错误/12 skip）+ `-Dquality -DskipTests verify` 8 模块零告警 + ARCHITECTURE/模块 README/DECISIONS 同步 + 卡 10 决策落档
- 勾选口径：`- [x]`＝已落地且有提交哈希/实测数字佐证；`- [ ]`＝未落地。仅凭勾选不足以判定完成，以状态行+提交哈希为准
- 数字口径：测试数按 surefire 报告**当日实跑**过滤汇总（教训：target 里残留 2026-10-01 的 `SerializationProbeTest` 陈旧报告，含 1 个 error，不清洗会误读）

> 本计划交 dev-executing-plans 逐任务执行；与设计方案（Spec）配套阅读。

**Goal:** 把 2026-10-24 架构体检的 10 张候选卡按序修复：4 个实证缺陷（安全口径分叉/两档语义分叉/运行期三查缺失/槽位竞态）+ 5 个结构摩擦（上帝模块/镜像枚举/手抄投影/样板复制/观测读面）+ 1 个不拆决策落档。

**Architecture:** 深模块原则——守卫阶梯、判定表、投影、样板各收敛为唯一一份；公开接口面（REST 端点签名、ScenarioEngine 对外方法、事件名、错误码）保持冻结，收敛发生在包内接缝（RestRoutes/EventJournal/ComponentBootstrapper/DuoPortServer/FaultState/EventProjections）。

**Tech Stack:** Java 21 · 既有依赖零新增 · JUnit5（既有分层：kernel/scenario/components 单测、control HTTP 契约测试、examples 验收）

**Spec:** [docs/superpowers/specs/2026-10-24-duo-architecture-deepening-design.md](../specs/2026-10-24-duo-architecture-deepening-design.md)

## Global Constraints（所有任务默认隐含）

- 决策台账 D1–D16 不动摇：信任模型/输入分层（D10/D11）、句柄归属（D12）语义不变。
- 核心 REST 端点签名冻结；控制台专属面在 `/api/` 下演进。
- 控制面改动零内核改动；kernel 新增符号仅限 scenario/components 真实需要（判定表、事件投影），kernel 零第三方依赖不破。
- 事件名、错误码、消息文案＝对外契约：既有测试断言即冻结面；有意变化必须新测试钉住。
- 每任务收尾跑整 reactor：`JAVA_HOME=~/devtools/jdk-21.0.12.1+1 ./mvnw -o -B test`（Git Bash 口径）。
- 提交信息按实测数字；单模块定向回归可用 `-pl <module> -am`，发布前必须整 reactor。

## Review Focus（规格隐含、高危回归面；各挂测试到所属任务）

1. **REST 错误码全表**：401/403/405/409/413/400/404/500 的每个触达路径在管道化后逐一不变（T1 已由 RestConsoleApiTest 15 + RestControlServerTest 14 零改动钉住；后续 T12 动 ScenarioHost 观测面时同套裁判）。
2. **判定表消息兼容**：WiringResolver 抛异常文案与校验器收集文案各自保持（WiringResolverAndManagerTest 10、ScenarioValidatorTest 20 零改动）；新增 UNKNOWN_PATH 校验行有新用例（T5）。
3. **热注入语义**：三查补齐后，不支持动作/带下标无 instanceControl 从「dispatch threw」变「precheck 早失败」——两个方向（更早、消息更准）都有新用例，且 /inject 的 404 unknown-target 契约不变（T6）。
4. **事件流下标稳定**：M3 D3 的 1-based 切片语义在 eventsSince 增量化后不变（next=since+size、不重不漏，RestControlServerTest 既有用例零改动；T10 补 journal 增量单测）。
5. **worker 槽位上限**：restartInstance 后 freeSlots 不得超 totalSlots——冻结门确定性复现测试（T8 红→绿）。

---

### Task 1（卡 1）: 控制面 REST 端点管道化 ✅

**状态：已完成 `2d1db7b`（2026-10-24）**

- [x] 新建 `rest/RestRoutes`（guard/回环校验/405/异常→错误映射总表/sanitizeReason 唯一实现）+ `rest/HttpCall`（body 上限读取/query/pathTail/ok/json/text/error/require）+ `rest/BodyTooLargeException`
- [x] RestControlServer 977→505 行：15 端点改声明式注册；`/api/scenarios` 124 行 lambda 拆分；路径段解码+白名单一次完成；L336 未掩码分支结构性消灭
- [x] 行为变化钉住：`/assertions`、`/topology`、`/api/capabilities`、`/api/meta` 统一 405（提交信息说明，对齐 T33 口径）
- [x] 验证：duo-sim-control 46 测全绿（HTTP 接缝零改动）；duo-sim-examples 全绿（含 SecurityRemediation/ControlPlaneAcceptance/DuoCli）

### Task 2（卡 2a）: ScenarioEngine 拆三个内部模块 ✅

**状态：已完成（代码与模块测试）**

- [x] `EventJournal`（内存流+录制+世代闸门+收尾三告警；`events/eventCount/droppedEvents/recordingPath/epoch/withEpochGuard/finish`）
- [x] `ComponentBootstrapper`（装配核心单点；O(1) 元数据索引；SUT 直接依赖不展平 behaviors 的既有差异保持；splitCommand 随迁）
- [x] `NodeSpecs`（launchMode/isExternal/firstExposedPort 唯一一份；校验器私有副本删除）
- [x] ScenarioEngine 重写为薄编排层，公开接口面零变化；`warn()` 收拢告警双写；`specById` 字段删除
- [x] 测试：EventJournalTest 2 + ComponentBootstrapperBehaviorsTest 3 新增；ScenarioEngineCommandTest→ComponentBootstrapperCommandTest 迁移（5 例不变）；duo-sim-scenario 60 测全绿

### Task 3（卡 2b）: 卡 2 全量验证与提交 ✅

**状态：已完成 `5d924af`（2026-10-24）**

- [x] 整 reactor 433 测/0 失败/0 错误/12 skip BUILD SUCCESS（当日实跑口径；428 基线 + 5 新增）

---

### Task 4（卡 3a）: `WiringResolver.decide()` 判定表唯一权威 ✅

**状态：已完成 `13be711`（2026-10-24，kernel 88 测 = 79+9 新增；整 reactor 437/0/0/12）**

**Files:**
- Modify: `duo-sim-kernel/src/main/java/io/duo/sim/kernel/core/WiringResolver.java`（新增嵌套类型 + 静态方法；`resolveNode` 改为消费 `decide()`）
- Test: Create `duo-sim-kernel/src/test/java/io/duo/sim/kernel/core/WiringDecisionTest.java`

**Interfaces（Produces，后续任务依赖）:**
```java
public enum SlotFailure { UNKNOWN_PATH, WIRE_WITHOUT_ENDPOINT, DIRECT_EXTERNAL_CONSUMER, DIRECT_NOT_SUPPORTED }

public record SlotDecision(io.duo.sim.kernel.api.ComponentContext.ConnectionPath path,
                           SlotFailure failure) {
    public static SlotDecision ok(ConnectionPath path);
    public static SlotDecision fail(SlotFailure failure);
}

public static SlotDecision decide(boolean consumerExternal, String explicitPath,
                                  boolean targetHasEndpoint, boolean targetInterfaceDirect);
// 语义：explicitPath==null 时按端点形态推断（有端点→wire，否则 direct）；
// wire 且目标无端点 → WIRE_WITHOUT_ENDPOINT；direct 且消费方 external → DIRECT_EXTERNAL_CONSUMER；
// direct 且目标 !interfaceDirect → DIRECT_NOT_SUPPORTED；path ∉ {wire,direct} → UNKNOWN_PATH
```

- [x] **Step 1**: 写失败测试——判定表每行 + 两条缺省推断（有端点→WIRE、无端点→DIRECT）+ ok/fail 构造不变量（fail 时 path==null，ok 时 failure==null）
- [x] **Step 2**: `./mvnw -o -B -pl duo-sim-kernel test` 确认新用例 FAIL（类型不存在）
- [x] **Step 3**: 实现 `SlotFailure/SlotDecision/decide()`；`resolveNode` 的显式 path switch 与缺省推断段替换为 `decide()` 调用，异常文案**逐字保持现状**（含 UNKNOWN_PATH 的 `"unknown path 'x' on slot 'y' (expect wire|direct)"`）
- [x] **Step 4**: WiringResolverAndManagerTest 10 例零改动全绿 + WiringDecisionTest 全绿
- [x] **Step 5**: Commit（`refactor: §6.1 接线判定表收敛 kernel 唯一权威——decide() 结构化失败`）

### Task 5（卡 3b）: 校验器规则 2/3 消费 decide() + 补 UNKNOWN_PATH 校验行 ✅

**状态：已完成 `df1b06a`（2026-10-24，ScenarioValidatorTest 21 = 20 零改动 + 1 新增；整 reactor 438/0/0/12）**

- [x] **Step 1**: 新增失败测试——`wiring: { registry: { node: zk, path: bogus } }` 场景校验必须报错（含 `unknown path`），不再静默放过到启动期
- [x] **Step 2**: 确认 FAIL（当前实现无此校验行）
- [x] **Step 3**: 规则 2/3 的手写判定（`"wire".equals(path)…`/`"direct".equals(path)…` 两段）替换为 `WiringResolver.decide(isExternal(n), slot.path(), hasEndpoint, m.interfaceDirect())`，按 `SlotFailure` 映射**既有文案**（WIRE_WITHOUT_ENDPOINT→"upgrade target tier or use direct path" 等），UNKNOWN_PATH 用 resolver 同款文案
- [x] **Step 4**: ScenarioValidatorTest 21 例全绿（20 既有零改动 + 1 新增）
- [x] **Step 5**: Commit（`refactor: 校验器规则 2/3 消费 kernel 判定表，补未知 path 校验行`）

### Task 6（卡 3c）: 运行期三查补齐（Target 携带元数据）✅

**状态：已完成 `7187228`（2026-10-24，kernel 91 = 88+3 新增；整 reactor 441/0/0/12）**。实现比计划更简：Target 只增 `supportedFaults` 一个字段，第③查以 `InstanceControl` 接口形态判定（注册期一致性校验保证与元数据等价，少一个可漂移的传递字段）。

- [x] **Step 1**: 失败测试 ×3——热注入不支持动作 → precheck 早失败且消息含 `unsupported`；带下标对无 instanceControl 组件 → 早失败含 `instanceControl`；`/inject` unknown-target 仍 404（RestControlServerTest 14 例零改动即裁判）
- [x] **Step 2**: 确认 FAIL（Target 4 参构造不存在 → 编译红；语义红由旧实现「运行期直接分发」承担）
- [x] **Step 3**: 扩展 Target + precheck ②③ + clear ②；ComponentBootstrapper 两处传元数据（metaOf 辅助）；5 处测试构造点同步（ScenarioRuntimeTest 3、TimelineSchedulerTest 1、helper 1）
- [x] **Step 4**: kernel + scenario + components + control 全绿（`-pl duo-sim-control -am test`：91/61/120/46）
- [x] **Step 5**: Commit（`fix: 运行期三查补齐——Target 携带 supportedFaults，precheck 实现第②③查`）

### Task 7（卡 4）: DUO_PORT 服务端连接循环共享（两档 scheduler 同源）✅

**状态：已完成 `8e9cdb3`（2026-10-24，WorkerFeedServerTest 5 新例；VirtualSchedulerTest 10 零改动；整 reactor 446/0/0/12）**。共享件定名 `WorkerFeedServer`（比计划的 DuoPortServer 更准域：它承载的是 worker 连接面，且直接持有 DispatchSelector——槽位记账全进循环，G10 类漏接结构性不可能）；VirtualEngine 是另一协议形状（首帧 task-dispatch、无注册握手），不入共享件（真实差异，类注释明示边界）。

**Files:**
- Create: `duo-sim-components/src/main/java/io/duo/sim/components/server/DuoPortServer.java`
- Modify: `duo-sim-components/.../scheduler/VirtualScheduler.java:276-361,474-488`（循环体改消费共享件；closeAll join 语义并入共享件）
- Modify: `duo-sim-examples/.../scheduler/DemoScheduler.java:167-254,484-533`（同款接入；**补 `onDispatchRolledBack` 钩子——G10 漏接修复**）
- Test: `duo-sim-components/src/test/.../VirtualSchedulerTest.java`（新增派发失败回滚用例）；examples 侧 DemoScheduler 守卫用例

**Interfaces（Produces）:**
```java
/** DUO_PORT 服务端连接循环（§13）：accept → 连接生命周期 → 读帧分发 → 失联回调。
 *  close() 含 join barrier（沿用 1148cb8 的教训）。 */
public final class DuoPortServer implements AutoCloseable {
    public interface FeedListener {
        void onRegistered(String name, FrameConnection conn);
        void onMessage(String name, DuoMessage msg);
        void onLost(String name);
        default void onDispatchRolledBack(String name) { }  // 两档必须同接（G10 类漏接在结构上不可能）
    }
    public DuoPortServer(int port, FeedListener listener) throws IOException;
    public int port();
    public void close();
}
```

- [x] **Step 1**: **差异清单先行**——实测：真差异保留（VS freeze 停摆、VS 20ms pump 线程 vs Demo 50ms 内联环、Demo 心跳 meter 窗口/ZK 接入/accept 计数）；伪差异收敛（线程命名、事件汇点、backlog/超时常量）。engine 首帧差异判定为**另一协议形状**，不入共享件
- [x] **Step 2**: 失败测试——G10 回滚改在共享件上钉住（WorkerFeedServerTest.rejectedStatusRollsBackLocalSlotReservation：单格容量拒绝后必须可再选中、回滚不超过上报容量）；两档经同一份代码自动获得（DemoScheduler 的 e2e 守卫＝examples 既有验收）
- [x] **Step 3**: 实现 `WorkerFeedServer`（从 VirtualScheduler 循环体上收，逐行语义保持；槽位记账含回滚沉入）；VirtualScheduler 改消费（552→415 行）
- [x] **Step 4**: DemoScheduler 接入——G10 回滚经共享件自动补齐；心跳 meter/采样保留消费方
- [x] **Step 5**: VirtualSchedulerTest 10 例零改动全绿 + examples 验收全绿
- [x] **Step 6**: Commit（`fix: 两档 scheduler 连接面同源共享——DemoScheduler 补 G10 槽位回滚漏接`）

### Task 8（卡 5）: worker 槽位代次守卫 + engine/worker join parity ✅

**状态：已完成 `4f011f5`（2026-10-25，复现测试红→绿且连续 10 次稳定；VirtualWorkerTest 16 / VirtualEngineTest 12 零改动；整 reactor 447/0/0/12）**。发现：`InstanceState.generation` 本已存在（连接生命周期用），缺的是任务线程归还比对与 restartInstance 的「先增代次再重置」硬顺序；join 预算取全局 2s（万级实例不逐线程各等 1s）。

- [x] **Step 1**: 冻结门确定性复现（红）——实测 freeSlots=3（totalSlots=2）越限
- [x] **Step 2**: 派发时捕获代次传入任务线程，finally 过期即放弃归还；restartInstance 先增代次再重置（discoverMaster 等待窗硬顺序）
- [x] **Step 3**: worker/engine stop 收尾 join barrier（全局 2s 预算；join 自身跳过防死锁）
- [x] **Step 4**: VirtualWorkerTest 16 / VirtualEngineTest 12 零改动全绿 + 复现测试连续 10 次稳定绿
- [x] **Step 5**: Commit（`fix: worker 槽位归还加代次守卫——restartInstance 不再越限；engine/worker 补 join parity`）

### Task 9（卡 6）: 信任档三枚举收敛

**Files:**
- Create: `duo-sim-scenario/src/main/java/io/duo/sim/scenario/InputTrust.java`（`CONFIG, EXTERNAL_INPUT`）
- Delete: `ScenarioEngine.InputPolicy`、`ScenarioValidator.InputTrust`（嵌套）、`ScenarioHost.Trust`
- Modify: ScenarioEngine.validated / ScenarioValidator 内部 / `duo-sim-control/.../ScenarioHost.java:76,144-146` / `RestControlServer`（startScenario 传参）/ `cli/DuoCli.java` / `library/ScenarioLibrary.java:170-171` + 各测试引用（grep `Trust\.|InputPolicy|InputTrust` 全库清点）

- [ ] **Step 1**: 全库 grep 清点三个旧枚举的全部引用点（含测试），列入提交描述
- [ ] **Step 2**: 新枚举落地 + 调用方直传（映射代码与散查删除；`EXTERNAL`→`EXTERNAL_INPUT` 更名）
- [ ] **Step 3**: SecurityRemediationAcceptanceTest + 信任档相关测试零改动全绿；grep 确认旧枚举名零残留
- [ ] **Step 4**: ARCHITECTURE §12.1 的 `Trust.CONFIG/EXTERNAL_INPUT` 表述同步
- [ ] **Step 5**: Commit（`refactor: 输入信任档三枚举收敛为 scenario.InputTrust——加一档只改一处`）

### Task 10（卡 7）: 事件投影统一 + 增量读取

**Files:**
- Create: `duo-sim-kernel/src/main/java/io/duo/sim/kernel/api/EventProjections.java`（`static Map<String,Object> toMap(Event)`——纯 Map，无 Jackson）
- Modify: `EventJournal` 增 `List<Event> eventsSince(int since)`（锁内 subList 拷贝，O(new)）
- Modify: `ScenarioHost.eventsSince`、`RestControlServer.reportToJson`、`EventRecorder.toJsonLine`、`MetricsCollector`（四处投影改走 EventProjections；host 改走 journal 增量）
- Test: EventJournalTest 增量用例 + RestControlServerTest 既有游标契约零改动

- [ ] **Step 1**: 失败测试——journal.eventsSince 增量语义（since 后只含新增，锁内切片）
- [ ] **Step 2**: EventProjections + journal.eventsSince 落地；四处消费方切换（Map 形状逐键保持——`timestamp` 仍 `toString()`）
- [ ] **Step 3**: control 契约测试零改动全绿（`/events` next=since+size 裁判）
- [ ] **Step 4**: Commit（`refactor: 事件投影统一 EventProjections，/events 增量读取 O(new)`）

### Task 11（卡 8）: 故障态样板与行为执行块收敛

**Files:**
- Create: `duo-sim-components/src/main/java/io/duo/sim/components/support/FaultState.java`（三态+slow 因子+`apply(action, inject)` switch 与事件发布唯一一份；事件名前缀构造注入）
- Create: `duo-sim-components/.../taskstub/TaskBehaviors.java`（`profile(entry)` 工厂 + emitLogs/reject 共享助手）
- Modify: VirtualWorker / VirtualEngine 故障段与执行块改委托；DemoRealWorker 执行块改用工厂
- Test: 既有 VirtualWorkerTest 16 / VirtualEngineTest 12 零改动（事件名即契约的裁判）

- [ ] **Step 1**: FaultState 落地 + worker 委托（`resolveSlowFactor` 逐字重复 ×2 消失）
- [ ] **Step 2**: engine 委托；TaskBehaviors 工厂（BehaviorProfile 8 参拷贝 ×3、emitLogs ×3、reject ×3 消失）
- [ ] **Step 3**: 组件测试零改动全绿；新增 1 例「FaultState.apply 发布的前缀事件名与旧实现逐串一致」守卫
- [ ] **Step 4**: Commit（`refactor: 故障态与行为执行块收敛——新故障动作改一处`）

### Task 12（卡 9）: 观测读面投影 + CLI 双模命令表

**Files:**
- Modify: `ScenarioHost`（eventsSince/诊断链投影走 EventProjections；Map 形状=REST 契约不变）
- Modify: `cli/DuoCli.java:215-422`（`dualMode(remote, local)` 助手收敛 7 命令 ×8 分支；命令注册表化）
- Test: DuoCliTest（examples）+ control 契约测试零改动

- [ ] **Step 1**: CLI 双模助手抽取，7 命令分支删除
- [ ] **Step 2**: host 观测投影切换（与 Task 10 连作时可直接复用）
- [ ] **Step 3**: 契约测试零改动全绿；核对完工判据「加一个控制台功能从 4 处缩到 2 处」（在提交描述中给出对照清单）
- [ ] **Step 4**: Commit（`refactor: 观测读面收敛投影，CLI 双模命令表化`）

### Task 13（卡 10）: ZkBackedRegistry 不拆决策落档

**Files:**
- Modify: `docs/DECISIONS.md`（新增 D17 + §3 对照表行 + 修订记录）

**D17 要点**: 删除测试未通过——剩余为 embedded 档双面实现（wire 面/门面面/watch）的真实复杂度，硬拆在两个子类重新冒头；M4 T38 已抽取过一轮。触发条件：出现第二个 embedded 契约实现（届时门面/wire 分离才有第二个 adapter 证明接缝真实）。

- [ ] **Step 1**: D17 落档（决定/理由/触发条件/落地点四段俱全）
- [ ] **Step 2**: Commit（`docs: D17 注册中心门面维持单类——架构体检删除测试未通过，防止重复提出`）

### Task 14: 收尾——总验收与文档同步

- [ ] 整 reactor `test` 全绿 + `./mvnw -o -B -Dquality -DskipTests verify` 8 模块零告警
- [ ] ARCHITECTURE.md 同步：§6.1「两处执行语义一致」段简化（判定表唯一权威）；§8 三查表述补实；§12.1 Trust 枚举名；§12.2 错误映射指向 RestRoutes 管道；模块 README 测试数按实跑回填
- [ ] 本计划状态行回填完成态与实测数字；设计方案 §2 状态列同步
- [ ] Commit（`docs: 架构深化收尾——总验收数字与文档同步`）

---

## 执行方式

本会话逐任务执行（任务间接口已在本计划锁定，卡 1–3 已由设计+实现同会话完成）；每任务一次提交，契约测试零改动全绿为过门条件。
