# Duo 发展规划 —— 如何达成最初的目标

- 日期：2026-09-18
- 基线：`0.1.0-SNAPSHOT`，M0–M4 已完成并验收；**本轮（2026-09-18）已完成 M7 最小子集 + M6 主线**，
  全量回归 **258 测 0 失败 / 5 skip**（3.4 分钟，确定性无 Docker 档）
- 依据：设计文档 v1.0 §2（目标与非目标）、§13（测试策略）、§14（分阶段计划）、§16（风险）、§17（开放问题）
- 决策台账：[`DECISIONS.md`](DECISIONS.md)（D1–D9 已全部拍板，无悬空决策）

---

## 1. 最初的目标（设计文档 §2 原文口径）

> 补齐业界缺失的一层：**行为可配置、可注入故障、可组装成任意链路拓扑的统一仿真框架**。

八条可验收目标：

| # | 目标 | 判据 |
| --- | --- | --- |
| T1 | **可组装** | 一份 YAML 描述组件拓扑与连线，一键拉起/重置 |
| T2 | **任意项可测** | 拓扑中任一节点可标记为 SUT，其余节点用替身 |
| T3 | **可替换** | 同一契约位可在档位间切换，测试代码零改动 |
| T4 | **行为可控** | 任务桩按剧本产生状态流转（延迟/成败/异常/假日志/永不回报） |
| T5 | **故障可注入** | 时间线剧本 + 运行时热注入 API；支持实例级寻址与崩溃后重启 |
| T6 | **真实反馈** | `embedded` 档以上暴露真实第三方协议端口，SUT 无感知直连；交互型契约暴露 Duo 线协议真实端口 |
| T7 | **秒级反馈回路** | 单 JVM 运行，容器档之外零 Docker 依赖 |
| T8 | **CI 友好** | JUnit5 扩展 + 断言库，场景文件可进版本库 |

**非目标**（不做，避免范围蔓延）：比特级网络模拟；真实计算引擎内部行为（Spark shuffle/Flink 反压）；
分布式仿真内核；性能数字的真实性承诺；external SUT 内部事实的全量可观测；SUT 作为故障注入目标
（`custom-hook` 协作式操作除外）；精美 Web 控制台。

---

## 2. 目标达成度盘点

| # | 目标 | 达成度 | 证据 | 缺口 |
| --- | --- | --- | --- | --- |
| T1 | 可组装 | ✅ **达成** | `ScenarioLoader` + `ScenarioValidator` 规则 1–8 + `WiringResolver` 拓扑排序启动 | 契约种类少（G1/G3） |
| T2 | 任意项可测 | 🟡 **部分** | in-process SUT 全链路闭环（`SutMain`/`SutContext`/`SutLauncher`）；**external SUT 已闭环（M6：零依赖第三方进程端到端验收）**；替身覆盖 registry/worker/store/resource | scheduler/engine 无替身档位（G3） |
| T3 | 可替换 | 🟡 **部分** | M0 `TierSwapAcceptanceTest`：同一拓扑 `workers` 在 `virtual ↔ real` 间切换、测试代码零改动 | 仅 registry（3 档）/worker（2 档）有多档；其余单档（G3） |
| T4 | 行为可控 | 🟡 **部分** | `BehaviorProfile` 8 字段全集（duration/jitter/successRate/failAt/exception/logLines/neverReport/progress）+ 四级匹配 | **`logLines` 未接入 DSL**；`jitter`/`failAt` 不接受百分号（G7） |
| T5 | 故障可注入 | 🟡 **部分** | 时间线（`TimelineScheduler`，duration 到期自动 clear）+ 热注入（`ScenarioRuntime`，M3 REST/CLI 包装）；实例级寻址无降级；`crash`/`restart`/`registry-flap`/`task-kill` 已落地 | **`freeze`/`slow`/`resource-exhaust` 未落地；`custom-hook` 引擎无注册入口**（G5） |
| T6 | 真实反馈 | 🟡 **部分** | embedded 档暴露真实 ZK 端口（SUT 用真实 Curator 客户端）/JDBC URL/K8s REST；Duo 线协议帧+8 报文；container 档 Testcontainers 桥 | 第三方 SUT 接入需 external（G2）；适配器未做（§17 决策：按需立专项） |
| T7 | 秒级反馈回路 | ✅ **达成** | 全量回归 2.5 分钟 / 226 测；常规档零 Docker 依赖；万级规模单 JVM 实测 9,928 HB/s | — |
| T8 | CI 友好 | 🟡 **部分** | `@VirtualCluster` 扩展 + `DuoAssertions` + YAML 断言双轨；场景文件入版本库；**标准 Wrapper + CI 三 job（M7 最小子集，本轮）** | **无 LICENSE**；CI 待远端首跑取证（G8） |

**一句话结论**：框架的**内核与机制层已经完整**（T1/T7 达成，T3/T4/T5/T6 的机制已具备），
**external SUT 宿主已于 M6 打通**（T2 的两种宿主形态齐备），
缺口集中在**广度**（契约/档位/故障动作种类）与**工程化交付收尾**（LICENSE/发布配置）。

---

## 3. 差距清单

| # | 差距 | 证据（可核对） | 影响 | 优先级 |
| --- | --- | --- | --- | --- |
| **G1** | **契约覆盖不全**：`engine` 只有接口骨架（`EngineContract`）无任何实现；`message`/`filestore` 只有枚举占位 | `Contract` 枚举 vs SPI 注册清单（4 个 Provider 文件） | 「可组装任意链路拓扑」的拓扑种类受限；无法仿真引擎/消息/文件存储链路 | P1 |
| **G2** | ~~**external SUT 引擎未实现**~~ **✅ 已闭合（M6，本轮）**：`launch.mode=external` 代起/attach 两形态、端点告知双途径（配置文件 + stdout）、`tcp`/`http` ready 探针、`ready.timeout` 全链路消费、`sut.exited`/`sut.crashed` 事实与「场景结束不杀进程」全部落地 | `ExternalSutLauncher` / `ReadyProbe`；`ExternalSutAcceptanceTest`（3 例）+ `ExternalSutLauncherTest`（10 例） | — | ~~P0~~ 已闭合 |
| **G3** | **档位覆盖窄**：`store`/`resource` 仅 embedded；`scheduler` 仅 real（无 virtual 调度桩）；`engine` 无实现 | 契约 × 档位矩阵 | T3「同一契约位可换档」只在 registry/worker 上被真正验证过 | P1 |
| **G4** | **金标准场景集不完整**：§13 要求「每个契约至少一个正例一个故障例」，目前只有 registry（flap/重选主）与 worker（crash/task-kill）有成对场景 | `duo-sim-examples/src/*/resources/scenarios/` | 契约语义回归无门禁，新契约容易「实现了但没验证」 | P1 |
| **G5** | **故障动作与钩子未闭环**：`freeze`/`slow`/`resource-exhaust` 仅常量声明（无实现声明 `supportedFaults`，写入即校验期拒绝）；`HookRegistry` 无 `ScenarioEngine` 注入入口，YAML 时间线里的 `custom-hook` 必然「no hook registered」 | `FaultAction` 常量 vs Provider `Set.of(...)`；`ScenarioEngine` 内部 `new HookRegistry()` | T5「故障可注入」的动作面窄；用户扩展点不可用 | P1 |
| **G6** | **观测面缺两条**：Prometheus 指标未实现；结构化日志无 logback 配置（SLF4J 版本已管理但未成通道） | 全仓无 `logback*.xml`、无 metrics 端点 | §11 承诺的三通道只落地「事件流录制」一条 | P2 |
| **G7** | **DSL 断链与设计偏差**：`jitter`/`failAt` 不接受 `%` 形态；`logLines` 未接入；~~`ready` 校验文案写 `config.ready.*`~~（M6 已修正为 `launch.ready` 并改为启动前校验）；~~`ready.timeout` 未消费~~（M6 已消费） | 见 [DSL §8 偏差表](SCENARIO-DSL.md#8-现状与设计偏差务必先读) | 照抄设计文档示例会直接抛异常；「写了不生效」类缺陷无门禁 | P1（剩余项） |
| **G8** | **工程化交付**：~~无 CI 配置~~（M7 三 job 已落地，待远端首跑取证）；~~`mvnw.sh` 硬编码本机路径~~（M7 换标准 Wrapper）；**无 `LICENSE`**；~~压测产物不留存~~（CI scale job 上传 artifact） | 仓库根目录清单 | 法务状态不明确；本地压测数据仍不随提交留存 | P1（剩余：LICENSE/发布） |

---

## 4. 后续阶段计划

依赖关系（→ 表示「先于」）：

```
        ┌─────────────────────── M7 工程化与 CI（可与 M5/M6 并行，越早越好）
        │
M6 external SUT ──► M5 契约与档位补全 ──► M8 观测面与可诊断性
   （P0 关键路径）      （P1 广度）            （P2 运营增强）
```

### M6 — external SUT 与第三方接入 ✅ **已实施完成（2026-09-18）**

**为什么现在做**：这是唯一同时卡住 T2（任意项可测）与 T6（真实反馈）的缺口，也是「Duo 能不能用来测
真实系统」的分水岭。设计（§7.3）已经把语义定死，实现面清晰、无设计不确定性。

**交付物**（全部落地）

1. `ScenarioEngine` 支持 `launch.mode=external` ✅：
   - 生成端点配置文件（`launch.configOut`），写入 `duo.endpoint.<contract>=<endpoint>` 与节点 `config`
     （`ready.*` 不写入），路径另经环境变量 `duo.config` 告知子进程；
   - stdout 兜底解析（行首格式同上 → `sim.external-endpoint`）；
   - ready 探针 `tcp` / `http`（`launch.ready`），超时归启动失败路径，且**ready 前退出优先报退出根因**；
   - 生命周期归用户：场景结束只拆接线、不杀 external 进程，发 `sim.external-process-left-running`
     + 终态警告，句柄经 `ScenarioEngine.externalSut()` 暴露；**启动失败则销毁子进程**（决策 D9）。
2. `ready.timeout` 全链路消费（in-process 与 external 一致）✅。
3. 旁路观测：替身侧事件为主途径 ✅（验收用 embedded registry 的 `sim.registry-flap-*` 做旁路断言）。
4. 第一个第三方适配器**专项**（触发条件：出现真实接入需求）——按决策 D1 维持不选型，本轮不做。

**验收标准**（全部满足，证据见 §10 进展记录）

- ✅ 一个**不可改码**的 external 进程作为 SUT 接入（`FakeThirdPartySut.java`：零 Duo 依赖、JDK 单文件
  源码模式启动），跑通「启动 → 端点告知 → ready 探针 → 故障注入 → 旁路断言通过 → 场景结束不杀进程」；
- ✅ external SUT 中途崩溃/退出分别产生 `sut.crashed`/`sut.exited` 并终止场景；
- ✅ 端点配置文件与 stdout 兜底两条途径都有测试覆盖。

**实施中暴露并修正的缺陷**：`sim.fault-injected` 原在派发**之后**落流，导致组件反应事件排在「因」
之前——以它为窗口起点的断言与 `eventSequence: [sim.fault-injected, <反应>]` 失效。已改为先因后果
（`ScenarioRuntime.inject/clear`），并加回归用例 `faultInjectedEventPrecedesComponentReactionEvents`。

**风险与对策**：第三方协议版本耦合（→ 适配器专项化、钉版本基线，§16 风险 1）；
external 就绪判定不稳（→ 探针可配 + 明确超时归启动失败，不静默）。

---

### M5 — 契约与档位补全（广度）

**为什么现在做**：T1「可组装任意链路拓扑」与 T3「换档零改动」的**可信度取决于覆盖了多少契约位**。
当前 8 个契约里只有 5 个有实现，且只有 2 个有多档。

**交付物**

1. **契约补全**：`engine` 虚拟组件（复用 `TaskStub`/`BehaviorProfile` 行为模型，§9 已预留定位）；
   `scheduler` 虚拟调度桩（让 SUT 可以是 worker 侧而不是调度侧）；`filestore` virtual 本地 FS 桩
   （端点形态 `FS_PATH`，可达）；`message` virtual 桩（按需 embedded Kafka）。
2. **档位补全**：`store` 补 container 档（或 zonky PG embedded）；`resource` 补 virtual/container 档。
3. **故障动作落地**：`freeze`、`slow`、`resource-exhaust`（各契约按需声明 `supportedFaults` +
   实现 `FaultInjectable` + 单测幂等/拒绝语义）。
4. **`custom-hook` 闭环**：`ScenarioEngine` 暴露 `HookRegistry` 注入入口（构造参数或 setter），
   YAML 时间线可用自定义钩子；钩子事件参与断言。
5. **DSL 断链修复（G7）**：`jitter`/`failAt` 兼容百分号；`logLines` 接入 config；`ready` 声明位置统一
   （保留 `launch.ready`，节点级 `ready` 作为兼容别名）+ 校验文案修正。
6. **金标准场景集（G4）**：每个契约至少一个正例 + 一个故障例，全部进常规回归。

**验收标准**

- 8 个契约全部至少有 1 个可运行实现（`message`/`filestore` 至少 virtual 桩）；
- `registry`/`worker`/`scheduler`/`engine` 至少各有一个「同拓扑换档」验收（测试代码零改动）；
- 每个新契约的故障例在 CI 常规回归中执行；
- `custom-hook` 有 YAML 端到端用例。

**风险与对策**：抽象过早（§16 风险 3）→ 坚持「每接一个新契约才泛化一次接口」的 YAGNI 节奏；
无真实用例的契约（message/filestore）→ 只做 virtual 桩，不做 embedded/container 真协议实现。

---

### M7 — 工程化与 CI（最小子集 ✅ **已实施完成（2026-09-18）**；LICENSE/发布待后续轮次）

**为什么现在做**：这是**交付门槛**而非功能。它不提升能力，但决定别人能否用、改动能否被守住。
成本低、收益立即兑现，建议与 M5/M6 并行启动。

**交付物**

1. **标准 Maven Wrapper** ✅：`mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`
   （script-only 形态，`distributionUrl` 钉 Maven 3.9.11）；硬编码本机路径的 `mvnw.sh` 已删除（决策 D6）。
2. **CI（GitHub Actions）** ✅：
   - `regression` job：无 Docker（`-Dduo.docker.enabled=false` 强制确定性），`./mvnw -B test`，
     **skip 必须可见**（`.github/scripts/skip-summary.sh` 打印逐条 skip）；
   - `container` job：有 Docker，跑容器档（`ZookeeperContainerRegistryTest` 4 条）并**断言 skip=0**；
   - `scale` job（nightly / 手动触发）：`-Dduo.scale=true`，把 `build/scale/*.json` 与录制上传为 artifact
     ——直接解决 G8「规模数据不可追溯」。
3. **许可与发布**（**未做**，下一轮）：`LICENSE`（Apache-2.0，§15 已选）；`maven-source-plugin` / `javadoc`；
   版本策略与 `CHANGELOG.md`。
4. **依赖与质量门禁**（**未做**，下一轮）：`dependency:analyze`、可选覆盖率报告（JaCoCo）。

**验收标准**

- 🟡 新机器上 `git clone && ./mvnw test` 一条命令成功（无需改任何文件）——**本地已按「仅需 JAVA_HOME」
  验证**（`mvnw.cmd -v` → Maven 3.9.11 / JDK 21.0.12.1），**待一台干净机器/远端 CI 首跑取证**；
- 🟡 CI 三个 job 全绿且 skip 数可解释——**配置与脚本已就绪，待远端首跑**；
- ✅ 压测产物作为 artifact 可从 CI 下载并与报告逐项比对——配置已就位（`scale` job 的 upload-artifact）。

---

### M8 — 观测面与可诊断性

**为什么放最后**：它提升「用起来爽不爽」，但不阻塞目标达成；且指标口径最好在契约面稳定后再定。

**交付物**

1. **Prometheus 指标导出**（§11 三通道之一）：心跳吞吐、注入次数/失败数、断言结果、
   组件健康、连接数；端点形态与 `RestControlServer` 一致（薄层，不引 Web 框架）。
2. **logback 配置 + 结构化日志**：默认控制台可读；可选 JSON encoder；与事件流录制形成互补。
3. **可诊断性**：一次故障注入的完整因果链可被单命令导出（注入 → 组件反应 → SUT 事实 → 断言）。
4. **加速时钟评估**（§17 开放问题 2，触发条件：出现「小时级长稳场景」且目标档位为 virtual；
   `SimClock` 接口已预留，无返工成本）。

**验收标准**：`/metrics` 可被 Prometheus 抓取且指标口径有文档；日志能定位一次故障注入的完整因果链。

---

## 5. 建议节奏与优先级

| 顺序 | 内容 | 状态 | 并行性 |
| --- | --- | --- | --- |
| 1 | **M7 的 CI + Wrapper（最小子集）** | ✅ **本轮完成**（待远端首跑取证） | 与 M6 并行 |
| 2 | **M6 external SUT** | ✅ **本轮完成**（G2 闭合） | 主线 |
| 3 | **M5 契约与档位补全** | ⏭ 下一轮主线（P1） | 可与 M6 部分并行（不同契约互不干扰） |
| 4 | **M5 的 DSL 断链修复 + 金标准场景集** | ⏭ 下一轮（P1；`ready.timeout` 与 ready 文案已随 M6 修复） | 可与 M5 主线并行 |
| 5 | **M7 的 LICENSE/发布配置** | ⏭ 下一轮（交付合规） | 随时 |
| 6 | **M8 观测面** | ⏭ 最后（P2） | 最后 |

**里程碑判定**：M6 + M5 完成 ⇒ T1–T6 全部达成；M7 完成 ⇒ T8 达成；T7 已达成。
即 **M6 + M5 + M7 完成时，「最初的目标」八条全部可验收**。
当前进度：**M6 ✅ + M7 最小子集 ✅ ⇒ T2 的两种宿主形态齐备、T8 主体就位；剩余 M5（广度）+ M7 收尾 + M8**。

---

## 6. 需要拍板的决策点

> **已全部拍板（2026-09-18）**：决策结果、理由、触发条件与落点见 [`DECISIONS.md`](DECISIONS.md)。
> 本节保留原始决策清单，仅加注拍板结论（①/② 与兑现状态）。

| # | 决策 | 选项 | 拍板结论 | 影响 |
| --- | --- | --- | --- | --- |
| D1 | 首个第三方 SUT 选型 | ① 暂不选，M6 只做 external 机制 + 自造 external 样例；② 现在就钉一个产品（如 DolphinScheduler）立专项 | **①** ✅ 本轮兑现（自造零依赖假第三方） | 决定 M6 是否含真实适配器；选②则需同时钉版本基线 |
| D2 | `message` / `filestore` 的深度 | ① 只做 virtual 桩（快）；② 直接上 embedded 真协议（如 embedded Kafka / MiniDFS） | **①**（M5 执行） | 决定 M5 工作量与依赖面 |
| D3 | 容器档是否支持 flap/restart | ① 维持「显式不支持」（当前）；② 引入固定宿主端口（`PortBinding`）后支持 | **①**（维持，含守卫用例） | 选②需先做端口绑定改造，且要重写 `restart()` 语义 |
| D4 | 是否引入 Prometheus | ① 做（M8）；② 用事件流 + 录制替代 | **①**（M8，零依赖手写 `/metrics`） | 决定 M8 范围与是否新增依赖 |
| D5 | 加速时钟是否立项 | ① 维持推迟（当前）；② 出现小时级长稳场景时立项 | **①**（维持推迟，`SimClock` 接口已预留） | 立项需同时引入 `SutContext` 时间源注入 + 行为剧本时钟统一 |
| D6 | 是否保留 `mvnw.sh` | ① 改造为标准 Wrapper 并删除；② 保留为兼容壳 | **①** ✅ 本轮兑现 | 影响外部使用者与文档 |

**本轮新增决策**（实施 M6/M7 时暴露，已记入 `DECISIONS.md`）：D7 external 支持 `launch.command`
代起形态（否则验收要求的退出/崩溃事实无法产出）；D8 CI 联网解析依赖 + 缓存 `~/.m2`（本地维持 `-o`）；
D9 启动失败即销毁子进程（与「场景结束不杀进程」不冲突）。

---

## 7. 明确不做的事（守住非目标）

| 不做 | 理由 |
| --- | --- |
| 比特级网络模拟（半开连接、乱序） | 状态层仿真，不做流量仿真 |
| 真实计算引擎内部行为（Spark shuffle、Flink 反压） | 留给容器档/预发 |
| 分布式仿真内核（多进程/多机） | 首期单 JVM；多进程按需演进 |
| 性能压测数字的真实性承诺 | 虚拟心跳吞吐仅作参考（当前报告已标注「绝对值随机器浮动」） |
| external SUT 内部事实的全量可观测 | 只承诺替身侧旁路事件；日志/指标侧车与自定义探针是扩展点 |
| SUT 作为故障注入目标 | in-process 无法安全强杀；唯一豁免是 `custom-hook` 协作式操作 |
| 精美 Web 控制台 | 仅薄层 CLI/REST；拓扑视图保持极简 |

---

## 8. 「最初目标达成」的判定清单

当下列全部为真时，可以宣告最初的目标已达成（每一条都可执行、可复现）：

- [ ] **T1** 一份 YAML 拉起任意拓扑：8 个契约均有实现，金标准场景集（每契约一正例一故障例）全绿
- [x] **T2 的部分**：任一节点可作 SUT——in-process **与 external 两种宿主都有端到端验收**（M6）；
      ⏳ 剩余：任一契约位都有可用替身（含 `scheduler`/`engine` 的 virtual 桩，M5）
- [ ] **T3** 换档零改动：`registry`/`worker`/`scheduler`/`engine` 各有「同拓扑换档、测试代码零改动」验收
- [ ] **T4** 行为可控：8 个行为字段全部可从 DSL 生效（含 `logLines`、百分号形态）
- [ ] **T5** 故障可注入：7 类动作全部有实现与场景级验收；实例级寻址无降级；`custom-hook` 可从 YAML 使用
- [x] **T6** 真实反馈：embedded/container 档暴露真实第三方端口；**external SUT 无感知直连（M6）**
- [x] **T7** 秒级反馈回路：常规回归 < 5 分钟（实测 3.4 分钟）、零 Docker 依赖（`-Dduo.docker.enabled=false` 确定性）
- [ ] **T8** CI 友好：⏳ CI 配置与 skip 可见性已就绪、新机器一条命令构建已成立（标准 Wrapper）；
      ⏳ 剩余：远端首跑取证、LICENSE/发布产物
- [ ] **工程化**：标准 Wrapper、LICENSE、发布产物（source/javadoc）、压测产物可追溯

---

## 9. 文档基线

| 文件 | 内容 |
| --- | --- |
| `README.md` | 项目总览：问题、概念、架构图、模块地图、快速开始、契约矩阵、最小场景、现状速览 |
| `docs/README.md` | 文档索引与阅读路径、过程文档（设计/计划/验收）清单、维护约定 |
| `docs/ARCHITECTURE.md` | 架构详解：模块依赖、SPI、契约与档位、接线规则、生命周期时序、注入通路、事件命名空间、SUT 适配面、控制面、线协议、**不变式清单** |
| `docs/SCENARIO-DSL.md` | DSL 参考：字段全集、校验规则 1–8、行为剧本、时间线动作与实现状态、断言语义、组件 config 键、**现状与设计偏差**、报错速查 |
| `docs/DEVELOPMENT.md` | 开发指南：环境、命令、测试分层与门控、**五类扩展点操作步骤**、依赖纪律、编码约定、工程债、排查手册 |
| `docs/ROADMAP.md`（本文） | 目标达成度盘点、差距清单 G1–G8、M5–M8 阶段计划、优先级、决策点、达成判定清单 |
| `docs/DECISIONS.md` | 决策台账 D1–D9：决定/理由/触发条件/落点 + 决策→交付物映射表 + 修订记录 |
| 8 份模块 `README.md` | `duo-sim-{protocol,kernel,scenario,components,embedded,junit,control,examples}/README.md`：各模块职责、依赖、关键类、测试、踩坑 |

---

## 10. 进展记录

### 2026-09-18：M7 最小子集 + M6 主线

| 项 | 结果 |
| --- | --- |
| 拍板 | D1–D6 全部拍板（`DECISIONS.md`），实施中新增 D7–D9 |
| M7-1 Wrapper | `mvnw` / `mvnw.cmd` / `.mvn/wrapper/maven-wrapper.properties`（Maven 3.9.11，script-only）；删除 `mvnw.sh` |
| M7-2 CI | `.github/workflows/ci.yml` 三 job（regression/container/scale）+ `.github/scripts/skip-summary.sh`（skip 逐条可见，`--fail-on-skip` 门禁）；`-Dduo.docker.enabled=false` 确定性无 Docker 门控 |
| M6 | `ReadyProbe`（探针规格与单次探测）、`ExternalSutLauncher`（配置文件/stdout 兜底/退出观测/失败销毁）、`ScenarioEngine` external 通路（代起与 attach、`${java}` 展开、结束不杀进程 + 警告）、校验规则 4 加固（`configOut` 必填、探针启动前校验） |
| 缺陷修正 | `sim.fault-injected`/`sim.fault-cleared` 改为**先因后果**落流（原顺序使「以注入事件为窗口起点」的断言失效） |
| 测试 | 全量回归 **258 测 / 0 失败 / 5 skip**（3.4 分钟）：kernel 76、scenario 41、examples 51（含 M6 端到端 3 例）、embedded 39、components 42、protocol 9 |
| 未做（下一轮） | M7 的 LICENSE/发布配置/质量门禁；M5 全部；M8 全部；CI 远端首跑取证 |

> **下一轮的入口建议**：按 §5 顺序启动 **M5（契约与档位补全 + DSL 断链修复 + 金标准场景集）**，
> 并行补 **M7 的 LICENSE/发布配置**；M8 待契约面稳定后再定指标口径。
