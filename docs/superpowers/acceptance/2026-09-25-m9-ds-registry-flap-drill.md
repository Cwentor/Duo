# M9 验收记录（Phase A）：DolphinScheduler 3.4.3 真实系统 registry-flap 演练

- 日期：2026-09-25
- 范围：ROADMAP §4 M9 Phase A（T-M9-0~4 全链）——**首个真实第三方系统接入**
- 计划：[`../plans/2026-09-25-duo-m9-dolphinscheduler-integration-plan.md`](../plans/2026-09-25-duo-m9-dolphinscheduler-integration-plan.md)（v1.1 事实修正 + v1.2 执行记录）
- 决策依据：计划 D-M9-1~4；全局 D7/D9/D12（external SUT 生命周期、不代杀）
- 演练命令（环境工件见 `docs/DEVELOPMENT.md` §1.3，不入库）：

```powershell
$env:JAVA_HOME='C:\Users\cwt15\devtools\jdk-21.0.12.1+1'
.\mvnw.cmd -o -B test -pl duo-sim-examples -am `
  "-Dtest=DsFailoverAcceptanceTest" "-Dduo.ds=true" "-Dsurefire.failIfNoSpecifiedTests=false"
# → Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 55.51 s — BUILD SUCCESS
```

---

## 1. 验收标准对照

| # | M9 计划验收标准 | 结论 | 证据 |
| --- | --- | --- | --- |
| T-M9-0 | 环境与形态 spike（Go/No-Go） | ✅ Go（v1.1 已录） | standalone 形态拍板；JDK11 直启冒烟通过 |
| T-M9-1 | 场景模板（真实 SUT + registry 替身） | ✅ | `duo-sim-examples/src/test/resources/scenarios/m9-ds-failover.yaml`（模板占位符由测试物化） |
| T-M9-2 | OpenAPI 端到端编排（不进 SUT 内部） | ✅ | `DsFailoverAcceptanceTest`：login → 建项目 → gen-task-codes → 建 3×HTTP 任务链工作流 → ONLINE → start（v1 接口，cookie 会话 + 表单体） |
| T-M9-3 | 三封闭通道断言 | ✅ | ① Duo 侧 flap 先因后果；② `sut.exited`(0) 晚于 flap；③ 死前 OpenAPI 回读 `RUNNING_EXECUTION` + 慢应答器真实命中；**⑥ 负例验证已取证**（§6.1：不注入 ⇒ 自停断言真实可失败） |
| T-M9-4 | 门控 + 默认可见 skip + **守卫不过门控** | ✅ | `-Dduo.ds=true` 才运行；缺省 skip 逐条可解释（见 §5 回归）；显式开启但 wrapper 缺失时 fail-fast 不静默；常驻守卫 `DsFailoverDrillGuardTest`（无门控，0.029s 离线绿）钉模板语义契约 |
| M9 整体 | 一次真实 E2E 演练 + 容错清单 | ✅ | 本记录 §2/§3 |

**防空真守护**：注入发生在工作流实例 `RUNNING_EXECUTION` 经 OpenAPI 回读确认**之后**（第 7 轮曾实测"实例尚未在途"的注入是无效注入）。

---

## 2. 演练时间线（2026-09-25 12:05–12:06，本地；events.jsonl 逐字）

| 时刻 | 事件 | 事实 |
| --- | --- | --- |
| 12:05:35.04 | `sim.registry-started` | Duo CuratorRegistry（embedded 真 ZK，`127.0.0.1:63757`） |
| 12:05:35.07 | `sim.external-process-started` | DS 3.4.3 standalone 经 wrapper 代起（pid 123640；`registry.type=zookeeper` + connect-string 注入） |
| 12:06:02.94 | `sim.external-sut-ready` | http 探针过（`/dolphinscheduler/ui/`），启动 27.9s |
| 12:06:02.95 | `sim.scenario-started` | 编排开始：建项目/工作流（3×HTTP 任务链，每任务命中 8s 慢应答器）→ 上线 → 启动 |
| 12:06:04.87 | `sim.fault-injected` | 热注入 `registry-flap`（实例在途确认后 1.9s） |
| 12:06:05.00 | `sim.registry-flap-started` | TestingServer 整服关停（oldPort 63757） |
| 12:06:05.04 | `sim.registry-flap-cleared` | 同端口 39ms 复活（`portStable=true`），门面重连成功——**ZK 侧无任何缺陷** |
| 12:06:29.15 | `sut.exited` (exitCode **0**) | DS 全进程受控自停（注入后 24.1s）——通道②忠实捕获 |
| 12:06:29.58 | `sim.registry-stopped` / `sim.scenario-finished` | 引擎收尾；`sim.sut-exited` 同刻落流（M6 语义：SUT 退出即场景终止） |

---

## 3. 核心发现：DS 3.4.3 对整服 registry 闪断的真实容错语义

**计划原假设（v1 T-M9-3）**：「DS Curator 客户端重连同端口 + master/worker 重注册 + 任务续跑 → 工作流终态 SUCCESS」。

**实测推翻（第 8 轮，2026-09-25 11:48；第 9 轮复现）**：

1. 整服重启 ⇒ 会话与临时节点**不可恢复**（embedded 档 flap 的设计语义，Duo 文档明文：
   「任何消费方都必须自行重连并重建节点」）。ZK 侧无缺陷——67ms/39ms 同端口复活、门面重连成功。
2. DS 侧观测链（`dolphinscheduler-standalone.log` 逐字）：
   `socket EndOfStream → Curator SUSPENDED（Master/Worker 记"state RUNNING"，不动作）`
   → **20.0s 后**（TestingServer 会话上限封顶 DS 要的 60s）`Registry disconnected`（Curator LOST）
   → Master/Worker/Alert **全进程优雅自停**（`server is stopping, current cause: disconnected from registry`）。
3. **语义判定**：反脑裂设计——单实例 standalone 遇会话死亡选择**受控退出**（exit 0），
   不重注册、不续跑；生产侧的续跑语义由 **HA 多 master 容错**承接（故障实例被其他 master 接管），
   这正是 DS 官方部署拓扑的分工。无配置开关（`MasterConnectionStateListener` 硬编码分支）。
4. **断言改判（诚实纪律）**：演练断言从「假设的自愈」改为「观测到的自停」——
   `sut.exited` 晚于 flap（因果）+ exitCode 0（受控非崩溃）+ 死前 RUNNING + 真实任务执行过。
   撤除断言（无 sut.exited / 进程存活 / left-running / 终态 SUCCESS / 慢任务×3）逐条在测试注释留痕。

> 这是 M9 Phase A 的**目的本身**：拿 Duo 的机制假设去撞真实第三方系统，把「SUT 会自愈」
> 替换成「SUT 3.4.3 单实例对整服闪断的选择是自停」。演练不是失败——是拿到了真答案。

**Phase B 输入（后续）**：若要演练「会话可存活」的故障面（网络分区/延迟而非整服死亡），
需内核新增故障类型（当前 embedded 档只有整服 flap——Phase A 约束内核零改动）。
DS 的重注册路径（Curator RECONNECTED 分支）将只能由这类温和故障触发。

---

## 4. 证据通道清单（D-M9-3，三封闭，不新增第四条）

| 通道 | 内容 | 本轮证据 |
| --- | --- | --- |
| ① Duo 旁路事件 | flap 先因后果 + 注入事实 | `fault-injected → flap-started(39ms)→ flap-cleared(portStable)` 顺序断言过 |
| ② SUT 生命周期 | `sut.exited`(0) / `sut.crashed` 判定 | 自停被记为 `sut.exited` exitCode 0（非崩溃）；时序晚于 flap |
| ③ SUT API 回读 | 死前状态 + 真实执行 | `RUNNING_EXECUTION` 确认后才注入；慢应答器命中 ≥1（真实任务执行） |
| （补充）SUT 文件日志 | 环境侧取证材料，不入断言 | `dolphinscheduler-standalone.log`：SUSPENDED→LOST→自停逐字链（§3） |

---

## 5. 环境适配事实（全部环境侧操作，无 DS 源码改动；详见 `docs/DEVELOPMENT.md` §1.3）

| 适配 | 动因 | 实测 |
| --- | --- | --- |
| 负载保护阈值 0.8→0.99（8 键） | 本机磁盘 91%/内存 95% 常态，缺省值让 master 无限拒消费（假死） | 修复了 SUBMITTED_SUCCESS 永挂 |
| `sudo.enable=false` | Windows 原生 sudo 无 `-u` | shell 任务改判 Windows 不可用（error=2 无 bash），转 HTTP 任务（纯 Java） |
| `plugins\task-plugins\` 补 task-http | 3.3.0 起插件不随二进制分发 | 缺失时保存工作流报 10001 |
| **registry-jdbc jar 拔除**（4 副本移入 `devtools\m9-jar-disabled\`） | DS 3.4.3 standalone 聚合扫描会无条件实例化 `JdbcRegistryClientRepository`（`@Repository`），其 mapper 只在 jdbc 的 `@MapperScan`（`@ConditionalOnProperty`）下注册——zk 模式缺 mapper，Spring 上下文崩 | 拔 jar 后 zk 模式启动干净；回退 jdbc smoke 时移回即可 |
| wrapper 脚本 UTF-8 **带 BOM** | `powershell.exe` 5.1 对无 BOM UTF-8 按 GBK 解码，中文注释尾字节吞行尾换行，把下一行代码并进注释（实测赋值语句被并入注释 → `$cfg` 恒空） | BOM 后 wrapper 在 5.1/7 下双态正常 |

---

## 6. 踩坑登记（执行链，含对自身纪律的违例记录）

### 6.1 负例取证（T-M9-3⑥：断言集在「不注入」时真实可失败）

`-Dduo.ds=true -Dduo.ds.noflap=true`（drill 内置负例开关，缺省 false）——跳过 `registry-flap`
注入，其余路径完全一致（DS 启动 → 编排 → `RUNNING_EXECUTION` 确认 → 不注入）：

```text
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 119.9 s  ← 预期红
org.opentest4j.AssertionFailedError: DS 未在 90000ms 内按其容错语义自停
——若 DS 行为变化（如新增重注册路径），请按新实测改写本演练断言
```

红点即「自停断言」本身：不注入 ⇒ DS 无因自停 ⇒ `awaitSutExit` 超时 ⇒ 断言集**不恒真**，
防空真得证。缺省路径复跑复绿（56.97s，Failures: 0）——开关不污染正常语义。
`eventSequence` 断言的可失败性由内核既有负例族承担（缺事件即红，M5/M6 用例库既有覆盖）。

### 6.2 执行链踩坑（按发现顺序）

1. **`-Dduo.ds=true` 未加引号**（PS 5.1 把 `.` 当分隔符 → "Unknown lifecycle phase .ds=true"）——
   违反了 DEVELOPMENT.md §1.2 自己写下的规则；如实记录，此后所有演练命令一律引用。
2. **`$env:duo.config` 对带点变量名非法**（PS 解析为属性访问 → `InvalidOperation`）——
   取/设都必须走 `[Environment]::Get/SetValueEnvironmentVariable`；wrapper 与复现夹具两侧各踩一次。
3. **start 响应 `data` ≠ 实例 id**：第 7 轮取证 `data=0/空`——按老习惯 `data.asInt()` 当 id 会静默得到 0，
   等待循环永不匹配、以「超时:null」掩盖真实原因。修法：实例 id 一律从列表查询按
   `workflowDefinitionCode` 发现（`awaitInstance`），并在 Javadoc 立碑。
4. **查询失败 ≠ 实例不存在**：曾把 API 故障静默折算成"实例缺失"——违反 §12 不静默；改为 code≠0 即抛。
5. **BOM 解码坑**（§5 末行）——两轮"wrapper 在测试里必死、手跑却好"的假象，根因是**两个 PowerShell
   不是同一个**（pwsh7 容无 BOM、powershell.exe 5.1 容 GBK）；以 cmd dump 原生环境块 + 逐层隔离复现定位。

---

## 7. 回归口径

- 演练用例：`DsFailoverAcceptanceTest`（1 例，`-Dduo.ds=true` 门控，缺省可见 skip）；
  常驻守卫：`DsFailoverDrillGuardTest`（1 例，**无门控**，钉模板语义契约，0.029s 离线）。
- 全量回归（缺省门控）：**397 测 / 0 失败 / 0 错误 / 12 skip**（11 条既有 + M9 1 条），
  逐条可解释——详见 ROADMAP §10 当轮记录与 CHANGELOG。
- Phase A 约束遵守：Duo 内核零改动（kernel/scenario/embedded 无一文件变更），
  全部适配落在环境工件与 examples 测试侧。
