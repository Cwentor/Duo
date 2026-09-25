# 变更日志

本项目的所有重要变更都记录在此文件。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## 版本策略

| 版本形态 | 含义 | 触发条件 |
| --- | --- | --- |
| `0.x.y-SNAPSHOT` | 开发中版本，**不保证**接口稳定 | 每次合并到 `main` |
| `0.x.y` | 里程碑版本，对外可依赖 | ROADMAP 的某个里程碑（M0–M8）验收通过 |
| `1.0.0` | 首个稳定版 | 全部里程碑完成、契约面冻结 |

- **主版本**（`x`，1.0 之前）：契约/API 不兼容变更，例如 `ComponentProvider`、
  `RegistryContract`、场景 DSL 的必填字段变化；
- **次版本**（`y`）：向后兼容的功能新增（新契约实现、新档位、新故障动作、新 DSL 字段）；
- **修订号**（`z`）：向后兼容的问题修复与文档/工程化调整。

> 1.0 之前每个次版本都可能包含不兼容变更，届时会在对应小节用 **BREAKING** 标注并给出迁移说明。

## [未发布]

### 新增

- **M9 Phase A：首个真实第三方系统接入（DolphinScheduler 3.4.3，第 34 轮）**：DS standalone 以
  external SUT 形态接入，registry 由缺省 jdbc 翻转到 Duo 的 embedded 真 ZK（CuratorRegistry），
  完成 registry-flap 端到端演练并全绿（55.51s）。交付物：`m9-ds-failover.yaml` 场景模板 +
  `DsFailoverAcceptanceTest`（`-Dduo.ds=true` 门控，缺省可见 skip；`-Dduo.ds.wrapper` 可覆盖
  wrapper 路径）。**语义发现（DECISIONS D13）**：原假设「DS 自愈续跑→SUCCESS」被实测推翻——
  整服 ZK 闪断（会话不可恢复）⇒ DS 3.4.3 单实例**受控自停**（Curator LOST 后优雅关停，exit 0，
  反脑裂设计；生产续跑由 HA 多 master 承接）。演练断言按观测语义改写：`sut.exited`(0) 晚于 flap
  （因果）+ 死前经 OpenAPI 确认 `RUNNING_EXECUTION` + 真实任务执行 ≥1；撤除断言在测试注释留痕。
  内核零改动；环境适配（负载阈值/sudo/task-http 插件/registry-jdbc jar 拔除/UTF-8 BOM wrapper
  （SUT 自停语义，v1.2/验收记录）。负例取证（T-M9-3⑥）：`-Dduo.ds.noflap=true` 跳过注入跑红
  （119.9s，红点＝自停断言）——断言集不恒真；常驻守卫 `DsFailoverDrillGuardTest`（无门控）
  钉模板语义契约。内核零改动；环境适配（负载阈值/sudo/task-http 插件/registry-jdbc jar 拔除/
  UTF-8 BOM wrapper 纪律）全部环境侧并登记 `docs/DEVELOPMENT.md` §1.3。验收记录
  `docs/superpowers/acceptance/2026-09-25-m9-ds-registry-flap-drill.md`；回归口径
  **397 测 / 0 失败 / 0 错误 / 12 skip**（11 既有 + M9 门控 1 条，逐条可解释）。
- **worker 侧真实 SUT（`RealWorkerSut`，第 32 轮）**：`duo-sim-examples` 交付完整档
  worker 侧 `SutMain`——真连 master（显式 `scheduler.endpoint` / `master.list` 文件 /
  registry 自注册三条发现路径）、周期心跳、领取→执行→回报任务、断连后重连自愈。
  此前 `virtual` 档调度器只有 wire 级假 worker 的覆盖，**仓库里没有任何 worker 侧 `SutMain` 示例**；
  这条把它补上了，且 master 侧仍复用内核同一份 `SchedulerStateMachine` + `DispatchSelector`
  （两档不各写一套状态机）。配置读取统一走 `SutConfigs`（去 UTF-8 BOM 口径）：
  首个键名被 BOM 污染时不再静默退回缺省值，而是把污染键名报进 `sut.worker-sut-started` 载荷；
  同一键被写了干净+BOM 两份时**显式拒绝启动**（读取口径看不见歧义，只有问才有答案）。
- **控制面安全整改（安全审计 2026-09-20，第 13 轮）**：`duo serve` 的令牌**由可选改为必填**
  （`--token` / `--token-file` / `DUO_TOKEN`；缺失即拒绝启动，除非显式 `--insecure-no-auth`）——
  **破坏性变更**，所有 REST/CLI 客户端需带 `--token` 或 `Authorization: Bearer`。除 `/health` 外
  全部端点要求令牌；`Host`/`Origin` 必须回环；请求体上限 1 MiB。
  场景校验分两档：**外部输入档**（`POST /scenario` body）禁 `launch.command`、
  禁框架外 `sut.main`、`config` 键白名单、值禁绝对路径与 URL scheme、规模有界；
  **配置档**（本机 YAML/CLI/测试/资源）能力与整改前完全一致。
  另有：`MetricsCollector` 事件类型基数上限 256（`__other__` 溢出桶，总量恒等）、
  `EventRecorder` 有界缓冲 50 万（溢出计数上抛告警）、`HookRegistry.emit` 只接受
  `sim.`/`sut.` 前缀、`launch.allowExternalProcess` 显式声明、`ReadyProbe` 复用静态 `HttpClient`、
  临时场景 YAML 与 SUT 端点配置在收尾时清理。决策见 `docs/DECISIONS.md` D10/D11/D12。
- **依赖门禁（M7 交付物 4）**：根 POM 新增 `quality` profile，`-Dquality` 激活
  `maven-dependency-plugin:analyze-only`（绑 `verify`）且 `failOnWarning=true`；
  缺省不激活 ⇒ 常规 `mvnw test` 零额外开销。CI 的 `regression` job 已接入
  （`-Dquality -DskipTests verify`），9 个模块**零未声明/零未使用**。
  门禁同时逼出 3 处真修复：`protocol` 未声明 `jackson-annotations`、`control` 未声明
  `jackson-core`、`examples` 未声明 `curator-test`（此前都靠传递依赖编译，上游改版即断）。
- **观测面（M8 交付物 1/2/3）**：`GET /metrics`（零依赖手写 Prometheus 文本格式，
  19 个指标族）、生产/测试双档 logback 配置、`duo diagnose` 单命令导出四段因果链
  （断链显式报 `gaps` 且退出码 1）。
- **发布配置（M7 交付物 3）**：根 POM 补全 `licenses`/`scm`/`url` 元数据；
  `-Drelease` 一键产出源码 jar 与 javadoc jar（`maven-source-plugin` 3.3.1 +
  `maven-javadoc-plugin` 3.11.2，缺省 `skip=true`，**常规构建行为不变**）。
- **DSL `autoStart`（G11）**：节点字段 `autoStart: false` 表达「声明但不启动」，用于
  「只验某契约、不让 worker 把 DAG 一起跑完」的拓扑裁剪；缺省 `true`＝声明即启动。
  未知节点键改为**显式报错**（此前静默忽略）；SUT/external 节点上的 `autoStart: false`
  由校验器拒绝（那两类节点由 `startSut()` 启动，写在这里无意义）。

### 修复

- **413 超限拒绝的确定性投递（CI 间歇红，2026-09-25 修复）**：`RestControlServer` 的 413 路径
  在响应后带着未读请求体关闭连接，Linux 上间歇性让客户端收到
  `HTTP/1.1 header parser received no bytes` 而非 413——实证链：09-23
  `RestControlServerTest.oversizedBodyIsRejectedWith413` 与 09-25
  `SecurityRemediationAcceptanceTest.oversizedUploadsAreRejectedByDeclaredAndActualSize`
  两红、同一代码一次红一次绿。机理（JDK 21.0.12 `ServerImpl` 源码实证）：响应写完后请求体
  未读至 EOF 即 `c.close()` 硬关连接；超限体（>1 MiB）此时客户端仍在发送，带未读接收数据
  close 触发内核 RST，已到达未读的 413 响应字节一并作废。修法：`rejectTooLarge` 先有界排空
  （64 MiB 预算，读到 EOF 后 JDK 不再硬关）再响应——413 必达，连接优雅收尾；小体量拒绝
  用例（401/403/405/400）因客户端早已转入读态从未受影响。
- **控制面测试夹具的退出规则不再依赖自定义 config 键（第 33 轮）**：`ControlFixtureSut`
  原按「收齐 `fixture.expectedWorkers` 个注册后退出」收敛，但 REST 层测试经
  `POST /scenario` 走**外部输入档**——`ScenarioValidator` 的 config 键白名单根本不收
  `fixture.*`，于是场景被 400 拒掉、后续断言全数落空。改为**注册静默期**判据：
  首个注册到达后一段时间无新注册即视为收齐，再维持可配观察窗后返回（任何路径都封顶，
  场景不会假 RUNNING）。这样同一个夹具在两个信任档下都可用，无需为测试放宽外部输入边界。
- **REST 档「事后审查」用例恢复停止收尾语义**：`topologyReadableAfterFinishForPostmortem`
  曾试图等 SUT 自退后读拓扑——但 REST 层下「结束」只有 `DELETE /scenario`
  （＝`host.stop()`，断言评估与终态固化都在那里）才对外可见，SUT 线程自行返回后
  `/scenario/status` 仍报 RUNNING。用例改回 DELETE 后读取，与产品行为一致。
- **安全审计 20 条发现全部闭合（第 13 轮）**：C-1（未认证即可 RCE / 任意文件写）、H-1..H-5
  （跨站与 DNS-rebinding、body 无上限、进程派生跳板、指标基数、事件缓冲与收尾阻塞）、
  M-1..M-8、L-1..L-6、INFO-1。两处**按实证修正报告字面**并如实记录：
  ① `ExternalSutLauncher.close()` 不能"只关流不杀进程"——Windows 上会在
  `FileDescriptor.close0` **永久死锁**（`jcmd` 线程转储取证），最终口径是"谁持有句柄谁收摊"；
  ② 报告建议的单请求时长上限**无法实现**——`com.sun.net.httpserver.HttpServer` 没有
  `setMaxReqTime`（`javap` 实证），故不写"看着在配、其实没生效"的假配置。
- **G10：worker 拒绝派发后的槽位记账不再泄漏**——`DispatchSelector.onDispatchRolledBack`
  在收到 `TaskStatus.REJECTED` 时退还本地预留（以最近一次 `SlotReport` 为上界），
  被拒任务能继续被重派，受状态机 `MAX_REJECTIONS` 兜底。修复前实例仅 1 格容量时
  最后一格会被永久占用、DAG 永不收敛。
- **G9（更早）：派发通路不再静默丢弃**——槽位不足时改为显式 `TaskStatus.REJECTED`，
  调度侧回滚重排，槽位计数原子化并即时上报。

### 测试

- `ZkSchedulerDiscoveryTest`（3 例）：把「real 档 SUT 写的端点对内核 registry 可见」
  钉成契约，并记录「跨档位组合要求 registry 后端同源」这条设计约束（异源时发现为空、
  显式失败，不做假成功）。
- 观测面 5 例（`MetricsEndpointAcceptanceTest` 2 / `FaultCausalChainLoggingTest` 2 /
  `FaultDiagnosticsAcceptanceTest` 1），其中 1 例是**日志配置回归护栏**：
  禁止再引入 logback `<if>/<else>` 条件块（1.5.16 上会抛 `EmptyStackException`
  并让**全部日志静默丢失**，踩过一次）。
- `WorkerSutAcceptanceTest`（3 例，**worker 侧真实 SUT 的端到端验收**）：真连 master
  （显式端点 / `master.list` / registry 自注册三条发现路径）、BOM 配置键必须仍被认到、
  断连后自愈重连。夹具按真实协议走——注册/心跳/领取/回报全部经 `FrameConnection`，
  不是进程内假装通过。
- 全量回归 **395 测 / 0 失败 / 0 错误 / 11 skip**（reactor 内 8 模块），逐模块计数见
  `docs/DEVELOPMENT.md` §3.2。**口径已简化**：`duo-sim-control` 的契约测试
  （`ScenarioHostTest` 10 + `RestControlServerTest` 12）已在本模块 `src/test` 内执行，
  不再需要"另有 30 条在 examples 步内执行"这种跨模块换算（旧文那份换算本身是错的）。
  第 33 轮删掉 `RestControlServerTest` 里 1 条**恒真用例**（探针 `return true` +
  `assertTrue`，不构成任何检查）——「用例归属」的事实由类 Javadoc 与
  `duo-sim-examples/pom.xml` 的注释承载，不需要一条假测试来记。
- `-Dquality -DskipTests verify` 抓到并修掉一处真实依赖违规：控制面测试夹具讲了 19 处协议
  却没声明 `duo-sim-protocol`，靠传递依赖白用——夹具"真的讲协议"正是它作为验收样本的意义。

### 已知限制（如实记录）

- `engine` 只有 `virtual` 档，无档可换；
- Docker 相关的 container 档用例在本机跳过（共 10 条），由 CI 的 `container` job 承担；
- **`FrameConnection` 帧的两段式分配保留**（`new byte[9+len]` + `readFully(len)`，峰值 2×）。
  审计列为 L-2；未突破 1 MiB 上限，改动要动线协议读写路径，属"没有失败证据的重构"，**有意不做**；
- **内核不对用户的 SUT 线程/进程做 `destroyForcibly`**（审计 L-3 的字面建议）。协作式停止
  （§7.3）要求生命周期归用户；内核改为强制回收**自己持有**的资源（代起进程 + 管道）。
  代价：用户 SUT 忽略停止回调时会留下一个存活线程/进程，由用户负责；
- **不给长连接 worker 设读超时**（审计 L-4）：空闲 worker 与卡死 worker 在 socket 上无法区分，
  设超时会误杀正常空闲实例。取舍见 `docs/DECISIONS.md` D12；
- 观测面只做 counter/gauge，**没有直方图**（任务时延等分桶口径未定，不为凑指标拍脑袋）；
  指标为进程级累计值，场景重启不归零（口径见 `docs/METRICS.md`）；
- `duo diagnose` 只读事件流，日志侧入口（`io.duo.sim.fault` + `grep FAULT`）已设计未实现；
- JaCoCo 覆盖率门禁**评估后决定不引入**（理由见 `docs/ROADMAP.md` M7 §）；
- M8 交付物 4「加速时钟评估」保持 ⏸：触发条件「小时级长稳场景 + virtual 档」未出现，
  评估无输入（`SimClock` 接口已预留，无返工成本）。

## [0.1.0] - 2026-09-18

首个里程碑批次：M0–M4 主线 + M5 交付物 1–6 + M6 + M7 最小子集。

### 新增

- 8 个契约（`registry`/`store`/`worker`/`engine`/`scheduler`/`resource`/`message`/`filestore`）
  与 4 个档位（`virtual`/`embedded`/`container`/`real`），每个契约至少一个正例与一个故障例；
- 7 个故障动作：`crash`、`restart`、`freeze`、`slow`、`registry-flap`、`resource-exhaust`、
  `task-kill`，含 `supportedFaults` 声明与未声明动作的显式拒绝；
- 场景 DSL：`ScenarioLoader`/`ScenarioValidator`/`WiringResolver`（拓扑排序启动）、
  timeline 与热注入、`custom-hook`、external SUT（代起/attach 两形态 + ready 探针）；
- 交付工程：标准 Maven Wrapper、三 job CI（`regression`/`container`/`scale`）、
  Apache-2.0 `LICENSE`、`@VirtualCluster` JUnit5 扩展与 `DuoAssertions`。
