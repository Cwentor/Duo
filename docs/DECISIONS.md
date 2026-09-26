# Duo 决策记录（拍板台账）

- 日期：2026-09-18
- 基线：HEAD `8dc3b97`，`0.1.0-SNAPSHOT`，M0–M4 已完成并验收
- 依据：`docs/ROADMAP.md` §6「需要拍板的决策点」、§5「建议节奏与优先级」、
  设计文档 v1.0 §14/§16/§17
- 约定：一条决策一次拍板，**不悬空**——每条都写明「决定 / 理由 / 触发条件（何时重开）/
  落地点（哪个里程碑兑现）」。后续变更走本文「修订记录」，不改历史条目。

---

## 1. 本轮拍板（ROADMAP §6 D1–D6）

### D1 首个第三方 SUT 选型

- **决定：① 暂不选产品**——M6 只做 external 机制 + 自造 external 样例（零 Duo 依赖的独立进程）。
- **理由**：① M4 已就同类问题（协议适配器）拍板「不绑定具体产品，维持 SPI 就绪」（M4 计划 D5），
  两条决策必须一致，否则 M6 会推翻一个月前的口径；② 设计文档从未指定第三方产品与版本基线，
  凭空选型＝无验收口径的版本耦合（§16 风险 1）；③ M6 验收标准（不可改码 external 进程接入、
  端点告知、ready 探针、崩溃/退出事件）用自造样例即可全部覆盖，真实适配器的增量价值为零。
- **触发条件**：出现真实接入需求（有具体产品要接）→ 立专项，范围＝该产品报文 ↔ Duo 帧翻译层 +
  契约映射，只依赖 `duo-sim-protocol` 与内核公开 SPI，启动时钉死版本基线。
- **落地点**：M6（机制 + 样例）；适配器专项按需。

### D2 `message` / `filestore` 的深度

- **决定：① 只做 virtual 桩**，不做 embedded 真协议（不引 embedded Kafka / MiniDFS）。
- **理由**：① 设计文档对这两个契约**没有任何真实用例**（§9 首批虚拟组件库只列 worker/registry/engine），
  按 §16 风险 3「抽象过早」的对策，坚持 YAGNI：每接一个新契约才泛化一次接口；
  ② embedded Kafka / MiniDFS 会引入重依赖与分钟级启动，直接侵蚀 T7「秒级反馈回路、常规档零 Docker」，
  为两个无人使用的契约付出这个代价不划算；③ 桩的形态已够验证 T1「可组装」与 T3「换档」。
- **触发条件**：出现真实链路需求（如 SUT 必须读 Kafka topic）→ 该契约单独立 embedded 档，按需引入依赖。
- **落地点**：M5 交付物 1（virtual 桩）。

### D3 容器档是否支持 flap/restart

- **决定：① 维持「显式不支持」**（当前行为：校验期显式拒绝，不静默降级）。
- **理由**：① 选②需先做固定宿主端口（`PortBinding`）改造，且要重写 `restart()` 语义
  （容器整服重启会换端口，接线表失效）——这是 M5 之外的独立改造，且 M5/M6 才是关键路径；
  ② 「显式不支持」本身就是设计承诺（§7.2 无降级：不支持即校验期失败），维持它比半吊子支持更安全。
- **触发条件**：出现必须在容器档验证「注册中心闪断/进程重启」的场景 → 先做端口绑定改造，再重写语义。
- **落地点**：不做（M5 范围内维持现状）。

### D4 是否引入 Prometheus

- **决定：① 做（M8），但零依赖**——按 Prometheus 文本暴露格式手写薄端点，与 `RestControlServer` 同层，
  不引 Web 框架、不引 `simpleclient`。
- **理由**：① §11 三通道只落地了「事件流录制」一条，指标通道是设计承诺，砍掉（选②）等于自认目标缩水；
  ② 但 M8 是 P2，指标口径应在契约面（M5/M6）稳定后再定，故仍放最后；
  ③ 文本暴露格式是 HTTP 纯文本，手写即可，引依赖只会加重 T7 负担。
- **触发条件**：若 M8 启动时 `RestControlServer` 已具备端点复用能力，直接挂 `/metrics`；无需重开本决策。
- **落地点**：M8 交付物 1。

### D5 加速时钟是否立项

- **决定：① 维持推迟**（当前）。
- **理由**：与 M4 计划 D4 的评估结论一致——加速时钟的价值前提是 SUT 可注入时间源，
  而 demo-scheduler 与 TaskStub 全用 `Thread.sleep` 挂真实时钟，改造面覆盖全部 SUT 与行为剧本，
  收益（场景 wall-time 缩短）与当前验收无关；`SimClock` 接口已在内核预留，推迟无接口返工成本。
- **触发条件**：出现「小时级长稳场景」且目标档位为 virtual → 立项，范围＝`SutContext` 时间源注入 +
  行为剧本时钟统一。
- **落地点**：不做（M8 交付物 4 只做评估结论）。

### D6 是否保留 `mvnw.sh`

- **决定：① 改造为标准 Maven Wrapper 并删除 `mvnw.sh`**。
- **理由**：① M7 验收标准是「新机器上 `git clone && ./mvnw test` 一条命令成功（无需改任何文件）」，
  `mvnw.sh` 硬编码了一条**开发者本机 JDK 路径**（`<用户目录>\devtools\...`；原文含真实用户名，
  安全审计 2026-09-20 L-5 后改为占位描述，台账不改史、只就地加注），对任何他人与任何 CI 都不可用；
  ② 保留为兼容壳（选②）会让「文档写 `./mvnw`、老手用 `mvnw.sh`」双轨长期存在，且旧壳里的
  本机路径仍是新人第一个坑。
- **落法**：Maven Wrapper **script-only 形态**（`mvnw` / `mvnw.cmd` + `.mvn/wrapper/maven-wrapper.properties`，
  不把 `maven-wrapper.jar` 提交进仓库），`distributionUrl` 钉 Maven 3.9.11；JDK 由 `JAVA_HOME` 解析，
  与旧壳的语义等价但不含本机路径。
- **落地点**：M7 交付物 1（本轮完成）。

---

## 2. 本轮推进中新增的决策（M6/M7 实现前钉死）

### D7 external SUT 的启动形态

- **决定**：`launch.mode=external` 支持两种形态——
  ① **代起形态**：声明 `launch.command`，内核用 `ProcessBuilder` 拉起该命令，持有进程句柄并观测退出；
  ② **attach 形态**：不声明 `command`，内核只生成端点配置文件 + 轮询 ready 探针（进程退出不可观测，
  文档明示该限制）。
  `command` 支持 `${java}` / `${java.home}` 占位符（解析为当前 JVM 的 java 可执行文件/家目录），
  使场景 YAML 可跨机器复用而不写死路径。
- **理由**：① M6 验收标准明文要求「external SUT 中途崩溃/退出分别产生 `sut.crashed`/`sut.exited`
  并终止场景」——**只有持有进程句柄才能区分退出与崩溃**，纯 attach 形态无法满足；
  ② 设计 §7.3「生命周期归用户」在本实现中的落法是：场景结束**不杀**进程、终态事件与警告提示用户
  自行终止、并把进程句柄暴露给调用方（用户/测试自行处置）——而不是「内核不碰进程」；
  ③ attach 形态保留设计原文「用户自行启动的外部进程」路径，代价是明示的观测缺口（无退出事件）。
- **落地点**：M6（本轮完成）。

### D8 CI 的依赖解析策略

- **决定**：CI 的 `regression` / `container` job **在线解析依赖**（`actions/setup-java` 的 `cache: maven`
  缓存 `~/.m2`），本地开发维持 `-o`（离线）纪律；ROADMAP §4 M7 原文的 `./mvnw -o test` 在 CI 冷缓存下
  必然失败，故按此修正。
- **理由**：① 离线构建的前提是 `.m2` 已被预热，本地成立、CI 首次运行不成立；
  ② 「不许静默」的要求（skip 必须可见）与在线/离线无关，用 `-B`（非交互）+ 输出 skip 汇总即可满足；
  ③ 缓存命中后 CI 的解析成本≈0，不牺牲速度。
- **落地点**：M7 交付物 2（本轮完成）。

### D9 external 启动失败时的子进程处置

- **决定**：external 子进程**启动失败**（ready 探针超时、ready 前退出）时，内核**销毁**该子进程并抛
  `ComponentException`；与 D7 的「场景结束不杀进程」不冲突。
- **理由**：① §12 要求启动失败走逆序拆除 + 根因链；② 未就绪的子进程从未成为 SUT，
  留着必然泄漏（每个失败用例一个 JVM）；③ 「不杀」的语义前提是「进程已成为 SUT 且场景正常结束」。
- **落地点**：M6（本轮完成）。

---

## 2.1 安全整改决策（安全审计 2026-09-20 之后）

审计报告 `docs/security-audit-2026-09-20.md` 的核心结论是：REST 控制面**完全无认证** ⇒
`POST /scenario` 即任意命令执行 + 任意文件写（C-1 CRITICAL），且浏览器可跨站触发（H-1）。
下列两则是把该结论文档化的**设计决定**（不是补丁记录）：它们定的是**信任边界画在哪里**，
后续任何改动若要动这条边界，必须先改这两则决策。

### D10 控制面信任模型：令牌必填 + 回环双校验 + 请求体/规模上限

- **决定**：
  ① `RestControlServer` 的**令牌必填**——构造时不给令牌即抛 `IllegalArgumentException`，
  `duo serve` 没有令牌（`--token` / `--token-file` / `DUO_TOKEN`）时**拒绝启动**并给出三种给法；
  唯一例外是调用方**显式**传 `--insecure-no-auth`（启动横幅明示风险）。
  ② `/health` 免认证（活性探针），其余端点（含只读的 `GET /metrics`）一律要 `Authorization: Bearer`；
  未认证统一 401 + `WWW-Authenticate`，**不**区分「没令牌」与「令牌错」。
  ③ 每个请求先过来源校验：`Host` 必须是回环**字面量**（DNS rebinding 防护），
  `Origin`/`Referer` 若存在必须是回环（CSRF 防护）；命中即 403，且在认证之前判。
  ④ 请求体上限 1 MiB：优先看 `Content-Length`，再按上限+1 字节**流式截断**（防谎报长度与 chunked），
  超限回 413。
- **理由**：① 控制面**按设计就是执行面**（能起进程、能写文件），因此它的默认态不可能"安全"，
  只能"默认不可用"——"必须显式给令牌"是把安全性建立在**必须做一次显式决定**上，
  而不是建立在"用户记得加一个加固参数"上（审计原文：默认裸奔是根因）；
  ② 令牌 + 自定义头同时挡掉两类攻击：跨站"简单请求"无法携带 `Authorization`（浏览器不发预检就到不了
  业务分支），`Host`/`Origin` 校验再挡掉 DNS rebinding 与同机恶意页面；
  ③ 认证之前不解析 body（先 `guard` 后 `readBody`），使未认证请求连"读一大坨字节"的代价都不产生。
- **触发条件**：产品化到"多人共享一台 runner"或"控制面跨机暴露"时，本决策**必须**重开——
  届时需要的是真认证（双向 TLS / 平台 IAM）+ 授权分级（谁能 start / 谁能 inject），不是调大上限。
- **落地点**：`RestControlServer`（guard/readBody/MAX_BODY_BYTES/executor）、`ScenarioHost`
  （`Trust` 档、`stopWithoutAwait`）、`DuoCli`（`serve` 的令牌解析、客户端 `--token`/`DUO_TOKEN`）；
  回归用例 `RestControlServerTest`（401/403/413/构造期拒绝）、
  `SecurityRemediationAcceptanceTest`（PoC 真发包 + 断言磁盘无副作用）。
- **修订记录**：2026-09-20 首版。

### D11 输入信任分层：本机配置档不缩水，外部输入档收窄到"最小必要能力"

- **决定**：把信任边界从「能连到控制面端口」改画到「**输入是谁给的**」：
  - **CONFIG 档**（本机 YAML / CLI / 测试 / classpath 资源 / `duo-sim-junit`）：能力与整改前**完全一致**
    ——可 `launch.command` 起外部进程、可加载任意 `launch.main`。
    实现上它没有额外的"配置项校验"：能改本机文件的攻击者本来就能直接起进程（本机文件权限即边界）。
  - **EXTERNAL_INPUT 档**（REST `POST /scenario` body 及一切远程来源）：`ScenarioValidator` 新增
    规则 9–11——
    规则 9：`config`/`capacity` 键必须命中白名单（组件可通过 `CapabilityMetadata.trustedConfigKeys` 扩展），
    值拒绝绝对路径与 `file:/jdbc:/jar:/classpath:` 等式 scheme；
    规则 10：机级副作用默认关闭——`launch.command` 一律拒绝，`launch.allowExternalProcess: true` 是
    **显式声明**（要求能力时不必靠"猜实现"），`launch.main` 必须落在 `io.duo.sim.*` / `com.duo.*`；
    规则 11：规模有界（节点 ≤256、实例 ≤4096、每节点 ≤1024、时间线 ≤1000、断言 ≤200）。
  - 档位通过 `ScenarioHost.Trust` / `ScenarioEngine.InputPolicy` / `ScenarioValidator.InputTrust`
    三级传递，**没有**从外部输入设置档位的入口（环境属性只在本地测试/嵌入式里用）。
- **理由**：① 审计提出的"REST 提交的场景禁用 `mode: external`"是**过度收窄**：external 的 attach 形态
  （不派生进程、只写端点配置 + 探针）在 CI 里有用，禁掉它等于砍掉产品功能；
  真正危险的是"派生进程"与"写任意路径"，这两点可以各自精确关闭。
  ② 反向的"给所有输入都加白名单"是**假安全**：本机文件档的用户本来就是机器主人，
  在那里加校验只会制造"文档说支持、实际拒绝"的挫败感，并把攻击者推向更隐蔽的路径。
  ③ 分层让"能力"与"来源"一一对应，评审时只需回答一个问题：**这段输入是谁给的**。
- **触发条件**：出现"远程/浏览器输入需要起外部进程"的真实用例时，不是放开本档，而是新增第三条档
  （例如 `EXTERNAL_TRUSTED`，要求签名或一次性令牌交换）；届时必须在新档上重跑本决策的规则 9–11 清单。
- **落地点**：`ScenarioValidator`（规则 9–11、`isTrustedMainClass`）、`ScenarioEngine.startExternalSut`
  与 `assertMainAllowed`、`ScenarioHost.Trust` + 隔离临时文件后缀 `untrusted`、
  `CapabilityMetadata.trustedConfigKeys`（唯一的内核侧改动，纯增量）、`Launch.allowExternalProcess`；
  回归用例 `RestControlServerTest.externalInputCannotLaunchProcessOrReachOutsidePaths`、
  `SecurityRemediationAcceptanceTest`，以及既有 M6 验收（CONFIG 档）**不改一行**仍通过。
- **修订记录**：2026-09-20 首版（同日并入 M-7 复核结论，见 D12）。

### D12 句柄归属：谁持有句柄，谁负责收摊（M-7 / L-3 / L-4 的合并口径）

- **决定：外部 SUT 的进程与管道句柄按「归属」划分收摊责任**——内核**代起**的子进程
  （`launch.command` 非空，D7 ①）由内核负责 `destroy()` 并释放 stdin/stdout/stderr；
  **attach** 形态（`command` 为空，D7 ②）的进程归用户，内核 `close()` 无操作返回，一根手指都不碰。
  同理，用户 SUT 的 `SutMain` 线程不设强杀（不引入 `destroyForcibly` 等价物），
  长连接 worker 不设读超时。
- **理由**：报告 M-7 的字面要求是"`close()` 释放 stdin/stdout 句柄"。照做后
  `ExternalSutLauncherTest` **挂死**，`jcmd Thread.dump_to_file` 证据为：
  主线程停在 `java.io.FileDescriptor.close0`（native），而虚拟线程 `duo-external-stdout-*`
  停在 `FileInputStream.readBytes → BufferedReader.readLine`。机理：Windows 上的管道读是
  **同步 `ReadFile`**，`Thread.interrupt()` 与 `close()` **都打不断它**；而 `close()` 需要
  获取读线程正持有的流锁 ⇒ 先关流必**永久死锁**，比漏一个 FD 严重得多。
  唯一能解开这个读的做法是对端进程退出。因此"关流"必须以"先让对方退出"为前提，
  而**只有当那个进程是我们自己起的**，我们才有资格让它退出——这正是"归属"的含义。
  报告 L-3 的"10s 宽限后仍存活"、L-4 的"注册后静默的 worker 永久占用 socket"是同一枚硬币的
  另一面：那两处的线程/连接**归用户 SUT**，内核单方面格杀会破坏 §7.3「生命周期归用户」。
- **确定性的泄漏仍然全部堵死**（本决策的另一半，不能只谈"不该动"）：① `ReadyProbe` 改用
  静态共享 `HttpClient`（原先每次探测泄漏一个连接选择器 FD）；② 代起形态的 `close()`
  先 `destroy()` 再关三条管道；③ `RestControlServer.close()` 显式 `executor.shutdown()`
  （`HttpServer` 不会替我们关外部 executor）。另修报告指出的真实缺陷：迟到的 `ctx.onStop(handler)`
  注册此前永不生效（SUT 已退出时 `runner` 是死线程，"等下次 stop()"不存在）——现在若停止请求已在途，
  注册时立即补跑一次；`stop()` 也不再 `interrupt()` 一个已结束的线程（那是自欺）。
- **已知代价（如实记录）**：用户 SUT 若忽略停止回调，10s 宽限后会留下一个存活线程/进程；
  长连接 worker 注册后静默会让一个 socket/虚拟线程/`feeds` 槽位一直被占。两者都**不静默**——
  前者有 `sim.external-process-left-running` 事件 + 终态警告，后者由 `supportedFaults`
  与状态机拒绝兜底。触发条件：出现"用户 SUT 反复忽略停止回调导致宿主不可用"的真实事故时，
  再加"停止超时后标记为僵尸且不再计入容量"的显式机制（而不是偷偷格杀）。
- **落地点**：`ExternalSutLauncher.close()`（先 destroy 后关流）、`SutLauncher.onStop/stop`、
  `ReadyProbe` 的静态 `HttpClient`、`RestControlServer.close()`；
  回归用例 `ExternalSutLauncherTest.closeDestroysSpawnedProcessAndReleasesPipes` 与
  `attachModeCloseLeavesUserProcessAlone`（后者用真实 `ServerSocket` 证明 attach 形态不碰用户进程）。
- **修订记录**：2026-09-20 首版；同日因 M-7 复核实证（死锁线程转储）把原订的"关流"口径
  修正为"先杀自己起的进程，再关流；不是自己起的一律不碰"。

---

## 2.2 M9 真实系统接入决策（2026-09-25）

### D13：真实 SUT 演练断言以**观测到的**容错语义为准——不预设第三方系统行为

- **背景**：M9 Phase A 首个真实 SUT（DolphinScheduler 3.4.3）registry-flap 演练。计划原假设
  「整服 ZK 闪断 ⇒ DS Curator 客户端重连同端口、重注册、任务续跑 ⇒ 工作流终态 SUCCESS」，
  并为此写了全套断言。第 8 轮实测推翻：DS 对会话死亡（整服重启 ⇒ 会话与临时节点不可恢复，
  Duo embedded 档 flap 的设计语义）的真实选择是 **Master/Worker/Alert 全进程受控自停**
  （Curator LOST 后优雅关停，exit 0——反脑裂设计，生产侧续跑由 HA 多 master 承接，无配置开关）。
  Duo 侧无缺陷：flap 39ms 同端口复活、门面重连成功。
- **拍板**：演练断言改判为观测事实——`sut.exited`(0) 晚于 flap（因果）+ 死前经 SUT API 确认过
  `RUNNING_EXECUTION` + 真实任务执行过；撤除断言在测试注释留痕。**演练的价值在于拿到真答案**，
  不在于把第三方系统的行为掰成我们期望的形状；「假设被推翻」是演练的成功而非失败。
- **对 Duo 机制的含义**：embedded 档 `registry-flap` 建模的是**整服死亡**（会话必丢）。
  「会话可存活」的故障面（网络分区/延迟——可触发 SUT 重注册路径）当前无对应故障类型，
  列为 Phase B 内核故障类型扩展的输入。
- **落地点**：`duo-sim-examples` `DsFailoverAcceptanceTest`（语义改判的断言集 + 撤除断言留痕注释）；
  实证链见 [`superpowers/acceptance/2026-09-25-m9-ds-registry-flap-drill.md`](superpowers/acceptance/2026-09-25-m9-ds-registry-flap-drill.md) §3。
- **修订记录**：2026-09-25 首版（drill 第 8/9 轮实测钉死；第 9 轮全绿）。

## 2.3 口径收口决策（2026-09-26）

### D14：CI 不归档事件录制（events.jsonl）——维持审计 M-8；本地录制物不是证据

- **背景**：两套互斥口径曾并存——①安全审计 M-8（2026-09-20 第二轮）以「录制物是内容敏感的
  运行产物」为由，删掉了 CI 两处 `**/build/scenarios/**/events.jsonl` artifact 上传；②同日
  worker-SUT 轮的收尾总结以「CI regression job 会归档事件录制用于失败回放」为由保留本地
  `build/scenarios/**/events.jsonl`——②引用的是①已删除的行为，两者必有一个不成立。
- **决定**：维持 M-8——事件录制**不作为 CI artifact 归档**。CI 失败回放的取证链＝surefire
  报告 artifact（`regression-surefire-reports` / `container-surefire-reports`）＋断言失败
  消息自带的事件现场（types / relayEdges / payload）＋可本地复跑。本地
  `build/scenarios/**/events.jsonl` 是 `.gitignore` 覆盖的**本地调试产物**，不构成证据——
  删留与 CI 无关（本机现存的一份 worker-sut 录制就是调试期崩溃 run 的现场，不是任何验收证据）。
- **理由**：录制物含凭据掩码之外的全部运行语义（内部路径、业务拓扑、告警原文）；M-5/M-7 的
  掩码只覆盖凭据与 argv，不等于「其余内容可公开」。而失败回放真正需要的最小事实（哪个用例、
  什么断言、什么事件表）已由 surefire 报告与断言消息承载——413 间歇红从定位到修复全程
  没用到事件录制。
- **触发条件**：出现「必须拿到完整事件流才能复现的 CI 失败」的真实案例 → 再评估对录制物做
  字段级脱敏后归档，而不是恢复原文归档。
- **落地点**：`docs/ROADMAP.md` G8 取证段与 `docs/DEVELOPMENT.md` §2.0 CI 表（2026-09-26
  同步改掉「上传事件录制」的陈旧表述）；`ci.yml` 维持现状（M-8 已删，无需改动）。
- **修订记录**：2026-09-26 首版（收口 M-8 与 worker-SUT 轮保留理由的矛盾）。

### D15：SUT 会话拆卸时在途任务滞留 PENDING＝设计语义——不是缺陷，不补「丢弃」事实

- **事实链（代码 + 实测）**：SUT 是「一个会话」——`run()` 返回 ⇒ `sut.exited` ⇒ 场景收摊
  （§7.3/§9）。SUT 停止 ⇒ 与 master 的连接断开 ⇒ 内核侧 `VirtualScheduler.onFeedLost`：
  实例从 `feeds`/`DispatchSelector` 摘除，`SchedulerStateMachine.onInstanceLost` 把该实例
  **在途**任务重置回 PENDING（受 `MAX_ATTEMPTS` 约束，达上限的直接 FAILED 并跳过下游）并发布
  `sut.instance-lost {requeued}`。此后场景拆卸、派发循环停转：重置回 PENDING 的任务**没有
  再派对象**，滞留至场景结束——即便拆卸瞬间恰有在途任务被重置，发出的也只是单次「事实」
  （RETRYING / `sut.task-retry` / `sut.failover`），永远不会有再派——**事实发得出、任务派不出**；
  `sut.dag-terminal` 只在拆卸前已全终态时才会发。
- **拍板**：这是「**拆除优先于任务完成**」的设计语义——拆卸是编排边界，不是故障恢复路径。
  验收断言**不得**把 `sut.task-retry` / `sut.task-terminal state=FAILED` / `sut.dag-terminal`
  当作「SUT 会话先于组件结束」场景的判据——出现与否取决于拆卸瞬间的在途时序，两侧都不赌
  （worker-SUT 验收实测 10s 窗口无 retry、无 dag-terminal，留痕见
  `WorkerSutAcceptanceTest` 断言 5 注释与 `docs/DEVELOPMENT.md` §3.2）。要验证 retry 的线协议
  语义，用调度器在途、worker 为内核组件的场景（m1/m3 验收形态）。
- **理由**：拆卸时刻把在途任务强判 FAILED、或强行续跑，都要给「收摊」引入新的失败语义与
  停止时序耦合（谁先停、等多久、等谁），换来的只是让一组断言从「不可达」变「可达」——
  没有真实场景需要它。
- **触发条件**：M9 Phase B（「会话可存活」故障面）或出现「拆卸后滞留任务必须显式终态」的
  真实需求 → 重开；届时补显式的 sut.* 事实类型，而不是静默改语义。
- **落地点**：`WorkerSutAcceptanceTest`（断言分层注释已按此语义写，本条补上缺失的「为什么」）；
  本决策同时是 `DEVELOPMENT` §3.2「结构上不可达」记录的定性背书。
- **修订记录**：2026-09-26 首版（收口 worker-SUT 轮遗留的语义悬案）。

---

## 3. 与 ROADMAP 的对照

| 决策 | 拍板 | ROADMAP 落点 | 状态 |
| --- | --- | --- | --- |
| D1 | ① 暂不选产品，M6 做机制 + 自造样例 | M6 | **本轮兑现**（`FakeThirdPartySut` 零依赖假第三方） |
| D2 | ① 只做 virtual 桩 | M5 交付物 1 | 已拍板（下一轮执行） |
| D3 | ① 维持显式不支持 | 不做 | 已拍板（维持现状，含守卫用例） |
| D4 | ① 做，零依赖手写 `/metrics` | M8 交付物 1 | 已拍板（M8 执行） |
| D5 | ① 维持推迟 | M8 交付物 4 | 已拍板（维持现状，`SimClock` 接口已预留） |
| D6 | ① 标准 Wrapper，删 `mvnw.sh` | M7 交付物 1 | **本轮兑现** |
| D7 | 代起 + attach 双形态，`command` 占位符 | M6 | **本轮兑现** |
| D8 | CI 在线 + m2 缓存，本地 `-o` | M7 交付物 2 | **本轮兑现** |
| D9 | 启动失败即销毁子进程 | M6 | **本轮兑现** |
| D10 | 控制面令牌必填 + 回环双校验 + body/规模上限 | 安全整改（审计 2026-09-20） | **本轮兑现** |
| D11 | 输入信任分层：CONFIG 不缩水 / EXTERNAL_INPUT 收窄 | 安全整改（审计 2026-09-20） | **本轮兑现** |
| D12 | 句柄归属：代起形态内核收摊，attach 形态不碰用户进程 | 安全整改（审计 2026-09-20 M-7/L-3/L-4） | **本轮兑现** |
| D13 | 真实 SUT 演练断言以观测到的容错语义为准 | M9 Phase A | **已兑现**（2026-09-25 演练全绿） |
| D14 | CI 不归档事件录制；本地录制物＝调试产物非证据 | 工程口径（M-8/G8 收口） | **已兑现**（2026-09-26 文档对账同步） |
| D15 | SUT 拆卸时在途任务滞留 PENDING＝设计语义 | 验收断言口径（worker-SUT 轮） | **已兑现**（用例注释即按此口径） |

**节奏（照 ROADMAP §5）**：M7 最小子集（Wrapper + CI）✅ → M6 external SUT 主线 ✅ →
M5 契约与档位补全（含 G7 DSL 断链与 G4 金标准场景集）⏭ → M7 余项（LICENSE/发布配置）⏭ → M8 观测面 ⏭。

### 实施中派生的一条实现纪律（非决策点，但已固化为回归用例）

`sim.fault-injected` / `sim.fault-cleared` 必须**先于组件反应事件**落流（先因后果）：它们是 §11 断言的
观测窗口起点，顺序倒置会让 `eventSequence: [sim.fault-injected, <反应>]` 恒不可满足。M6 验收时暴露并
修正（`ScenarioRuntime.inject/clear`），回归用例 `faultInjectedEventPrecedesComponentReactionEvents` 守住。

---

## 修订记录

| 日期 | 变更 |
| --- | --- |
| 2026-09-18 | 首版：拍板 D1–D6，新增 D7–D9（M6/M7 实现前钉死） |
| 2026-09-18 | 补记实施结果：D1/D6/D7/D8/D9 本轮兑现；派生「注入事件先因后果」纪律；M6 验收记录见 `superpowers/acceptance/2026-09-18-duo-m6-external-sut-record.md` |
| 2026-09-20 | 新增 D10/D11（安全审计整改的信任模型与输入分层）；报告见 `security-audit-2026-09-20.md`，验收记录见 `superpowers/acceptance/2026-09-20-duo-security-remediation-record.md` |
| 2026-09-20 | 新增 D12（句柄归属与收摊），由 M-7 复核的实测死锁证据逼出；L-3/L-4 的"不照字面实现"取舍并入 D12 |
| 2026-09-25 | 新增 D13（真实 SUT 演练断言以观测语义为准），由 M9 drill 第 8 轮实测「DS 整服闪断 ⇒ 受控自停」逼出；验收记录见 `superpowers/acceptance/2026-09-25-m9-ds-registry-flap-drill.md` |
| 2026-09-26 | 新增 D14（CI 不归档事件录制，收口 M-8 与 worker-SUT 轮保留理由的矛盾）/ D15（SUT 拆卸时在途任务滞留 PENDING 定性）；§3 对照表补 D13–D15 行 |
