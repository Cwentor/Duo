# Duo M9 实施计划 —— DolphinScheduler 真实系统接入（整体 SUT 演练先行 / 适配器专项跟进）

- 日期：2026-09-25
- 依据：设计文档 v1.0 §17 开放问题 1（第三方 SUT 协议适配器——「出现真实第三方接入需求时立专项」）、§16 风险 1（协议版本耦合）、§2 非目标（external SUT 内部事实的全量可观测 / SUT 非故障注入目标）；M4 验收记录 §三 D5（触发条件与专项范围）；决策 D1①、D9、D10/D11、D12
- 触发：2026-09-25 项目所有者将达成口径改判为「接真实系统」（此前口径＝设计 §2 的 T1–T8，已达成并于当日独立现跑取证 395/0/0/11）——**本计划即 §17 开放问题 1 悬置多年的触发条件的兑现**
- 前置：M0–M8 全部验收、G1–G12 全部闭合；M6 external SUT 机制（代起/attach、端点告知双途径、`tcp`/`http` ready 探针、`sut.exited`/`sut.crashed` 事实、「场景结束不杀进程」）已验收——Phase A 预期**零内核改动**
- 状态：**T-M9-0 spike 已完成（2026-09-25，Go）**——形态拍板 standalone、冒烟通过；T-M9-1~4 待实施（Phase A 排期；Phase B 只登记触发条件，不排期）

---

## 1. 交付定义（唯一验收口径）

**M9 分两阶段，验收标准不同，不允许混用。Phase A 是「真实系统口径」的达成判定；Phase B 是价值最大化路线。**

### Phase A —— 真实 DS 整体 SUT 端到端演练（里程碑判定）

**不可改码**的真实 Apache DolphinScheduler **3.4.3** 进程作为 external SUT 接入，Duo 只提供其注册中心替身并注入一次故障：

| # | 判据（可执行） |
| --- | --- |
| A1 | DS 3.4.3 standalone（或回退形态，见 D-M9-2）以 `launch.mode=external` 代起，ready 探针通过，**进程内零 Duo 依赖** |
| A2 | DS 的 registry 连接指向 Duo `CuratorRegistry`（embedded 档真 ZK 端口），DS 无感知直连 |
| A3 | DS 跑通一个含依赖边的最小 shell 任务 DAG（2–3 节点），终态 SUCCESS |
| A4 | DAG 在途期间注入一次 `registry-flap`（ZK 会话抖动），DS 自愈：会话重建、重注册；断言以 DS **实测**容错语义为基准（DS 真实行为可能「无感弱抖动」或「任务中断后恢复」——基准化见 T-M9-3，不拍脑袋定判据） |
| A5 | Duo 断言全部通过 + **防空真守护**（注入必须落在 DAG 在途期间——M5 第 5 轮「对空气注入」教训）+ 事件录制落盘可回放 |
| A6 | 验收记录落 `docs/superpowers/acceptance/`（环境、命令、逐项取证、已知边界），可被第三方按命令复现 |

**A1–A6 全真 ⇒ Phase A 关闭 ⇒ 项目按「真实系统」口径的初步目标判定达成**（2026-09-25 共识 Q6(a)）。

### Phase B —— 适配器专项（本计划只登记，见 §7）

---

## 2. 范围与不做

**做（Phase A）**：

| # | 项 | 依据 |
| --- | --- | --- |
| 1 | DS 3.4.3 环境与形态 spike：本机启动路径 + registry 改指核实 + Go/No-Go | M4-D5「版本基线在专项启动时钉死」 |
| 2 | `m9` 场景（external SUT + embedded registry 替身）+ 代起/ready/端点告知装配 | M6 机制复用 |
| 3 | 工作流 provisioning 自动化（DS OpenAPI，**编排层代码**，非内核能力） | 验收必须可脚本复现，无 UI 人工步骤 |
| 4 | 断言语义映射（替身侧旁路事件 + SUT 生命周期事实 + API 终态回读） | 设计 §7.3 旁路观测 |
| 5 | 演练执行（`-Dduo.ds=true` 门控）+ 验收记录 | 压测/容器档门控纪律（M4 D2/D6） |

**不做**：

- **DS 内部事实的全量可观测**（设计 §2 非目标；旁路观测是唯一途径）
- **对 DS 进程注入故障**（SUT 非注入目标；external 生命周期归用户，D12——**DS 若挂死，Duo 不杀它**，演练编排必须自带超时与进程监督）
- **修改 DS 源码或依赖树**（「不可改码」是 A1 的前提；conf 配置文件改造属环境准备，不是改码）
- **Duo 内核/线协议改动**（Phase A 若需要，即为范围蔓延信号 → 触发 §5 风险 6 的红线）
- **加速时钟改造**（真实 SUT 挂真实时钟，D5 维持推迟——**Duo 加速不了 DS 的时钟**，演练耗时以 DS 真实重试参数为准）
- **Web 前端**（非目标）；**Phase B 适配器实现**（见 §7）

---

## 3. 任务分解（5 任务）

| # | 任务 | 内容与落点 | 完成判据 |
| --- | --- | --- | --- |
| T-M9-0 | 环境与形态 spike（Go/No-Go） | ① DS 3.4.3 二进制包 + 插件依赖（`dolphinscheduler-task-shell`、`dolphinscheduler-storage-hdfs`——官方 standalone 文档最小集）获取，落 `devtools`，不入库；② DS 进程 JDK 基线＝**11**（官方口径 1.8/11；external 独立进程，Duo 侧 JDK 21 不变）；③ **本机启动路径钉死**：DS daemon 脚本是 bash，本机无 Docker、WSL 无发行版 → 候选＝直接 `java` 调 standalone 主类（自构 classpath）/ 安装 WSL 发行版 / CI Linux 侧跑演练（本机只跑编排层单测）；④ **registry 改指核实**：DS standalone 默认内嵌 ZooKeeper Testing Server（官方文档原文），核实其 `registry.*` 配置能否指向 Duo CuratorRegistry 端口；不能 → 回退 pseudo-cluster 形态（master/worker/api 分进程，registry 明确可配，+2~3 进程编排成本）；⑤ 冒烟：启动 → UI（`:12345/dolphinscheduler/ui`）与 API 可达 → 登录成功 | 形态拍板（standalone vs pseudo-cluster）+ 冒烟通过；Go/No-Go 结论写入本计划修订记录 |
| T-M9-1 | 场景与替身装配 | `duo-sim-examples` 新增 `m9-ds-failover.yaml`：`zk`（registry / embedded / CuratorRegistry，声明 `registry-flap`）+ `ds`（real / external / `sut: true` / `launch.command` 代起 / `launch.configOut` 端点告知 / `launch.ready` http 探针）；DS 侧 conf 指向 Duo ZK 端口（conf 属环境准备产物，不入库，路径登记进 DEVELOPMENT.md） | 场景过 `ScenarioValidator` 规则 1–8 与外部输入档规则 9–11；DS 从 configOut 拿到 ZK 端点并注册成功（`sim.registry-*` 旁路可见） |
| T-M9-2 | 工作流 provisioning 自动化 | 测试编排代码（examples 测试内）：DS OpenAPI 登录（令牌经 env/token-file 注入，**不进 YAML 明文**——D-M9-5）→ 创建项目 + 最小 shell DAG（D-M9-6）→ 触发运行 → 轮询终态 | 同一脚本可重复跑通「创建 → 触发 → SUCCESS」；无任何 UI 人工步骤 |
| T-M9-3 | 断言与旁路观测 | ① CuratorRegistry 门面侧：DS 注册/断连/重注册旁路事件（`sim.registry-*`；flap 的 `sim.registry-flap-*` 事实对齐 M6 验收先例）；② SUT 生命周期事实（`sut.exited`/`sut.crashed`）；③ DS API 终态回读（编排层直查，非内核）；④ 断言语义：`eventSequence: [sim.fault-injected, <重注册证据>]` + `failoverWithin`（窗口以 DS 实测自愈参数为基准，同 M1 基准化纪律）+ 防空真守护（flap 必须命中在途 DAG）；⑤ flap `duration` 可配（DS `session-timeout` 与 flap 强度的相互作用必须实测后定）；⑥ **负例验证**：断言集在「不注入」时必须真实可失败（防空真） | 断言集通过负例验证；flap 窗口有实测依据 |
| T-M9-4 | 演练执行 + 验收记录 | `-Dduo.ds=true` 显式门控（同 `-Dduo.scale` 纪律：DS 依赖外部环境——二进制包/JDK 11/端口，常规回归不跑）；DS 环境缺失时 `Assume` skip 且逐条可见（`skip-summary` 收录）；**「环境缺失→skip」守卫用例本身不过门控**（M4 复验教训：守卫被门控一起吞掉＝安全属性无人验证）；执行演练取证；全量回归确认常规档零污染；ROADMAP/CHANGELOG 的 M9 条目随本轮收口同步（第 12 轮文档口径纪律）；验收记录落盘 | 全量回归仍全绿（DS 门控用例 skip 且可解释）+ 演练 1/1 通过 + 记录落盘＝**Phase A 关闭** |

---

## 4. 关键决策（实现前钉死）

**D-M9-1：DS 进程 JDK 基线 = 11**（官方支持的最高 LTS）。官方 standalone 文档口径 JDK 1.8/11；external SUT 是独立进程，`launch.command` 自带 JAVA_HOME，与 Duo 的 JDK 21 **进程级解耦**。不为省环境赌「21 也许能跑」——官方口径优先。

**D-M9-2：接入形态首选 standalone-server，回退 pseudo-cluster，T-M9-0 钉死**。standalone 启动面最小（单进程、内存 H2、停止即清库＝天然场景重置），但官方文档明示其**默认内嵌 ZooKeeper Testing Server**——registry 能否改指 Duo 是 Phase A 的命门。核实顺序：读 3.4.3 实配 `registry.*` 键 → 试改指 → 不行即回退 pseudo-cluster（registry 可配性确定，代价是多进程编排）。**任何形态下都不改 DS 源码**。

**D-M9-3：断言的信息来源＝三条封闭通道，不新增第四条**。① 替身侧旁路事件（CuratorRegistry 视角，Duo 的唯一「事实源」）；② SUT 生命周期事实（`sut.exited`/`sut.crashed`）；③ DS OpenAPI 工作流终态（编排层直查，不进内核）。external SUT 内部事实全量可观测是设计非目标——不承诺就不做。

**D-M9-4：门控与回归纪律**。`-Dduo.ds=true` 显式触发；常规回归**零新增依赖、零新增墙钟**；skip 逐条可见；skip 守卫用例不过门控（守卫必须永远跑）。

**D-M9-5：凭据与安全口径**。DS 登录令牌经 env/`--token-file` 注入，不进场景 YAML（外部输入档禁令 + 审计 M-5「口令进事件」教训）；DS API 端口口径「仅本机回环」写入验收记录；Duo 控制面既有安全口径（D10/D11）全部沿用。

**D-M9-6：工作流素材＝最小 shell 任务 DAG**。2–3 节点、含依赖边、总时长 30–60s（保证 flap 窗口落在在途期间）；任务脚本入 `duo-sim-examples` 资源目录；不依赖 DS UI 人工操作。

---

## 5. 风险与对策

1. **DS standalone 内嵌 ZK 不可旁路**（`registry.*` 键不存在或硬编码内嵌服务）→ T-M9-0 直接回退 pseudo-cluster；**形态拍板前不启动 T-M9-1**。
2. **Windows 无官方 daemon 脚本路径**（bash 脚本；本机无 Docker、WSL 无发行版）→ T-M9-0 三路径钉死（直接 java 主类 / 装 WSL 发行版 / CI Linux 侧跑演练）。**本机最终跑不成演练是可接受结局，但必须如实记录并给出可复现命令**（同容器档纪律：环境缺失的 skip 是承诺路径，不是失败）。
3. **flap 强度 × DS 自愈参数的相互作用**（DS `session-timeout` 等可能让弱 flap 无感、强 flap 杀任务）→ T-M9-3 先实测基准化再定断言窗口；判据允许「DS 无感」为合法结局（此时断言退化为注册重建验证，如实记录）。
4. **DS 产物不入库**（二进制包数十 MB + 插件依赖）→ `devtools` 登记 + `.gitignore` 封口 + 一次性人工步骤写入 `docs/DEVELOPMENT.md` 新节。
5. **JDK 11 与 DS 插件在 Windows 的兼容性尾巴**（官方测试面偏 Linux）→ T-M9-0 冒烟先行，不通过即触发风险 2 路径切换。
6. **范围蔓延红线**：Phase A 任何一步要动 Duo 内核/线协议 → 立即停（M6「控制面零内核改动」同款红线），登记为 Phase B 范围重新评估。

---

## 6. 执行节奏

T-M9-0（spike → Go/No-Go + 形态拍板）→ T-M9-1 ∥ T-M9-2 → T-M9-3 → T-M9-4。T-M9-1/T-M9-2 可并行。每任务完成跑全量回归确认常规档零污染（395 基线不因 M9 任何改动出现新失败/新耗时）。**T-M9-4 验收记录落盘即 Phase A 关闭**——按 2026-09-25 共识，此时「真实系统」口径的初步目标判定达成。

---

## 7. Phase B 登记项（不排期）

- **范围**（M4-D5 原文口径）：「该产品报文与 Duo 帧的翻译层 + 契约映射」——virtual worker 替身以 DS 协议接活，把 Duo 完整注入面（`task-kill`/`freeze`/`slow`/`resource-exhaust`）打开到 DS 身上。
- **前置**：Phase A 验收 + DS master↔worker 通信协议的实测摸底（报文形态、序列化、版本基线）。
- **触发**：Phase A 关闭后由项目所有者拍板立项，届时在 `DECISIONS.md` 补正式决策编号（顺延 D13）。
- **验收口径**（共识 Q6(c)）：DS 容错行为清单逐条断言——master 重选、任务 failover、worker 断连自愈——全部进常规回归。

---

## 修订记录

- **v1（2026-09-25）**：初稿（T-M9-0~4 + D-M9-1~6）。依据当日 grill 共识：达成口径改判「接真实系统」（Q1）、三个登记在案项判边界决策非欠账（Q2）、选型 DolphinScheduler 3.4.3（Q4）、Phase A 整体 SUT 先行 + Phase B 适配器跟进（Q5）、(a) 里程碑判定与 (c) 专项完成判定双口径（Q6）。
  - DS 事实基线：[3.4.3 为当前最新稳定版](https://github.com/apache/dolphinscheduler/releases)；[standalone 官方文档](https://github.com/apache/dolphinscheduler/blob/dev/docs/docs/en/guide/installation/standalone.md)（默认内嵌 ZooKeeper Testing Server + 内存 H2、停止即清库、JDK 1.8 or 11、最小插件集 task-shell + storage-hdfs、UI 端口 12345）。
  - 本机事实基线（2026-09-25 实测）：无 Docker；WSL 无发行版；`devtools` 下有 JDK 21.0.12.1（Duo 侧复跑取证用）；DS 侧 JDK 11 属环境准备项（尚缺，T-M9-0 获取）。
- **v1.1（2026-09-25，T-M9-0 spike 完成）**：**Go**。形态拍板＝**standalone-server**（D-M9-2 正向落定，无需 pseudo-cluster 回退）；冒烟通过（Windows 直接 java 启动，UI ~45s HTTP 200，admin 登录 `code=0` 签发会话）。§1~§4 原文未动，以下述事实修正为准：
  1. **[事实修正，D-M9-2 的前提]** DS 3.4.3 standalone 的缺省 registry 是 **jdbc**（复用其内存 H2），并非 dev 分支文档所称"内嵌 ZooKeeper Testing Server"——该表述描述 dev 分支，非 3.4.3 发品行。改指 Duo ZK 是**纯配置变更**：`registry.type: zookeeper` + `zookeeper.connect-string`；`dolphinscheduler-registry-zookeeper-3.4.3.jar` + Curator 5.5.0 + ZooKeeper 3.8.3 客户端全部已在发行包 libs 中。master 侧 conf 给出全套 ZK 键缺省值（`session-timeout: 60s` / `connection-timeout: 15s` / retry `base-sleep 1s, max-sleep 3s, max-retries 5` / `block-until-connected: 15s`）——**T-M9-3 的 flap 强度校准以此为输入**。
  2. **[环境就绪]** `devtools` 下：JDK 11.0.32.1+1（`jdk11\`）；DS 3.4.3 已解压且 tar 的 SHA512 与官方 `.sha512` 逐字一致；插件 jar 就位于 `plugins\task-plugins\` 与 `plugins\storage-plugins\`，`conf\plugins_config` 按官方文档裁到 task-shell + storage-hdfs 两项。发行包内各 server 的 `libs\` 是指向根 `libs\` 的**相对 symlink**——Windows 解压需两遍（实体文件 → symlink 转复制；脚本留存 `devtools\ds-extract.py` / `ds-links.py`）。启动器＝`devtools\ds-launch-standalone.ps1`（工作目录须为 `standalone-server\`）。
  3. **[踩坑登记]** 本机 PowerShell 5.1 调原生 java 的参数传递会截断 `-D` 型 token（`-Duser.timezone=UTC` 被当作主类名）；`@argfile` 传引号 classpath 亦失效（整个 classpath 不被解析）。可行配方＝纯 CLI、无 `-D`、`-cp` 用单变量、JVM 参数只保留 `-X*`/`-XX:*`——`ds-launch-standalone.ps1` 即此配方。DS 自带 JVM 参数里的 `${SPRING_JACKSON_TIME_ZONE}` 是 bash 插值，Windows 直启不适用（冒烟未设 timezone，无碍）。
  4. **[待用事实]** DS `master`/`worker` 心跳上限 `max-heartbeat-interval: 10s`、`master.kill-application-when-task-failover: true`、API 端口 12345 / master 5678 / worker 1234 / alert 50052（standalone conf 实测值）——演练断言窗口与容错语义基准化的输入；DS login 端点为表单参数（非 JSON）。
- **v1.2（2026-09-25，T-M9-1~4 执行完毕，drill 全绿 55.51s）**：验收记录
  [`../acceptance/2026-09-25-m9-ds-registry-flap-drill.md`](../acceptance/2026-09-25-m9-ds-registry-flap-drill.md)。
  §1~§4 原文未动，以下述执行事实为准：
  1. **[语义改判，本计划最重要的一条]** T-M9-3 原假设「DS 自愈续跑→SUCCESS」被第 8 轮实测**推翻**：
     整服 ZK 闪断 ⇒ 会话不可恢复 ⇒ Curator LOST（SUSPENDED 后 20.0s，TestingServer 会话上限封顶
     DS 要的 60s）⇒ Master/Worker/Alert **全进程受控自停**（exit 0，反脑裂设计，无配置开关）。
     Duo 侧无缺陷（flap 39ms 同端口复活、门面重连成功）。断言按「观测到的真实语义」改写：
     `sut.exited` 晚于 flap（因果）+ exitCode 0 + 死前 `RUNNING_EXECUTION` + 真实任务执行 ≥1；
     撤除断言在测试注释留痕。**这正是 Phase A 的目的：不预设 SUT 行为，拿真答案。**
     「会话可存活」故障面（网络分区/延迟，可触发 DS 重注册路径）属 Phase B 内核故障类型扩展。
  2. **[DS 3.4.3 配置事实修正]** registry 翻 jdbc→zookeeper 后 standalone 仍会因聚合组件扫描
     实例化 `JdbcRegistryClientRepository`（`@Repository` 无条件扫），其依赖的 mapper bean 只在
     jdbc 的 `@MapperScan`（`@ConditionalOnProperty`）下注册——zk 模式 Spring 上下文崩。
     环境侧解法＝把 `dolphinscheduler-registry-jdbc-3.4.3.jar` 从各 server `libs\` 拔出
     （`devtools\m9-jar-disabled\` 留档），无源码改动；DEVELOPMENT.md §1.3 已登记。
  3. **[API 事实 + D-M9-6 改判]** start-workflow-instance 响应 `data` **不携带实例 id**（实测 data=0/空）；
     实例 id 须经 instances 列表按 `workflowDefinitionCode` 查询发现。查询 code≠0 须即抛
     （不静默折算成"实例缺失"，§12）。**D-M9-6「最小 shell 任务 DAG」被 Windows 实测否定**
     （task-shell 不可用：sudo 无 `-u` + `.sh` CreateProcess error=2），改判＝**HTTP 任务链**
     （纯 Java 任务类型）+ 测试托管慢应答器（8s/请求）制造在途窗口——依赖边（t1→t2→t3）与
     「30–60s 在途窗口」意图不变，仅任务类型替换。
  4. **[Windows 工件纪律]** 被 `powershell.exe` 5.1 加载的 `.ps1` 必须 **UTF-8 带 BOM**：
     无 BOM 时按 GBK 误解码，中文注释 UTF-8 尾字节被当 GBK 双字节前导**吞掉行尾换行**，
     把下一行代码并进注释（wrapper `$cfg` 赋值被吞 → 恒空 → 两轮"测试必死、手跑却好"假象；
     根因是 pwsh7 容无 BOM 而 5.1 不容）。环境变量名带点（`duo.config`）：`$env:` 语法非法，
     须 `[Environment]::Get/SetValueEnvironmentVariable`。`-D` token 在 PS 5.1 传参必须引用。
  5. **[交付物]** `duo-sim-examples`：`m9-ds-failover.yaml`（模板）+ `DsFailoverAcceptanceTest`
     （门控 `-Dduo.ds=true`；wrapper 路径 `-Dduo.ds.wrapper` 可覆盖）；wrapper/模板/jdk11/DS 包
     均环境工件（`C:\Users\cwt15\devtools\`，DEVELOPMENT.md §1.3 登记表格化）。
     内核零改动（Phase A 约束守住）；回归口径 396/0/0/12（11 既有 + M9 1 skip，逐条可解释）。
- **v1.3（2026-09-25，判据字面缺口补齐——负例取证 + 常驻守卫）**：v1.2 后对照 §1/§3 逐条复检
  发现两处计划字面要求未落，本轮补齐（Phase A 判据至此逐条闭环）：
  1. **[T-M9-3⑥ 负例验证]** drill 增 `duo.ds.noflap` 开关（缺省 false）：true 时跳过注入——实测
     `-Dduo.ds=true -Dduo.ds.noflap=true` 跑红（119.9s，Failures: 1，红点＝`awaitSutExit` 的
     「DS 未在 90000ms 内自停」断言）⇒ **自停断言依赖真实注入、防空真得证**；缺省路径复跑复绿
     （56.97s，Failures: 0）。eventSequence 断言的可失败性由内核既有负例族承担
     （eventSequence 缺事件即红，M5/M6 用例库既有覆盖）。
  2. **[T-M9-4「守卫不过门控」]** 新增 `DsFailoverDrillGuardTest`（**无门控**，常规回归常驻，
     M4 容器守卫同构）：物化模板（与 drill 共享同一 `materializeScenario`，不漂移）→ 过
     `ScenarioLoader`+`ScenarioEngine.validated` 全规则 → 钉拓扑语义契约（sut:true / real /
     external / configOut / http 探针 :12345 / wiring / flap 先因后果断言）。实测 0.029s 离线绿。
     drill 本体的「显式开启但 wrapper 缺失 fail-fast 不静默」由模板守卫 + 负例取证共同覆盖
     （fail-fast 行为本身即 drill 首行断言，开错 wrapper 必红——不做为独立常驻用例：常规回归
     不开 `duo.ds`，无法在常规档内真实复现该分支，如实记录此边界）。
  3. **[回归口径]** 常规回归 **397 测 / 0 失败 / 0 错误 / 12 skip**（396 + 守卫 1）；数字口径随
     本轮同步 ROADMAP/README/DEVELOPMENT/CHANGELOG。
- **v1.4（2026-09-25，总核验补痕——三处留痕缺口，非行为缺口）**：e80deb4 提交后按
  「全部任务/计划/目标是否真实达成」逐字面总核验发现三处**文档留痕**缺口（演练与回归
  证据不受影响），发现即修：
  1. **[D-M9-5 尾款]** 「仅本机回环」端口口径此前未按 D-M9-5 承诺写入验收记录——已补
     （验收记录 §5 新行：DS API/UI 一律 `127.0.0.1:12345`，全程无外部暴露面）。
  2. **[D-M9-5 注入方式偏差]** 计划原文「令牌经 env/token-file 注入」；实测落地＝测试代码
     常量（`admin`/`dolphinscheduler123`，DS standalone 出厂缺省值、官方文档公开、演练专用
     独立进程 + 全新 H2 + 仅回环，不构成秘密）。意图条款（不进场景 YAML、不进事件流）达成；
     若未来演练改用真实凭据，必须回退 env/token-file 注入。已在测试 `login()` Javadoc 与
     验收记录 §5 留痕。
  3. **[T-M9-3 ① 字面不可达的解释]** 原文「DS 注册/断连/重注册旁路事件」写过头：门面只对
     **门面中介的写**发节点变更事件（M6 既定边界），DS 经 wire 端口的注册/心跳活动不进
     Duo 事件流；通道①实际承载 registry 生命周期事实（原文括号「flap 的 sim.registry-flap-*
     事实对齐 M6 验收先例」即此意），DS 注册成功的证据走间接事实（就绪探针 + OpenAPI 回读 +
     任务真实执行）。已在验收记录 §4 通道①行留痕。
