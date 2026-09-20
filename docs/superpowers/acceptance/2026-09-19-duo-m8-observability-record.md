# M8 验收记录：观测面与可诊断性（G6 闭合）

- **日期**：2026-09-19
- **范围**：ROADMAP M8 交付物 1（Prometheus 指标导出）、2（logback 配置 + 结构化日志）、
  3（单命令导出故障注入因果链）；交付物 4（加速时钟评估）**未触发**，见 §6。
- **结论**：G6 由「观测面缺两条」→**已闭合**。§11 承诺的三条观测通道（事件流录制 / 日志 / 指标）
  全部落地且**各自有可失败的门禁**。
- **全量回归实测**：`366 测 / 0 失败 / 0 错误 / 11 skip`
  （protocol 12、kernel 76、scenario 51、components 120、embedded 55+10 skip、examples 52+1 skip）。

---

## 1. 交付物与取证对照

| 交付物 | 产出 | 门禁用例（可失败断言） |
| --- | --- | --- |
| 1 指标导出 | `MetricsCollector`、`GET /metrics`、`duo metrics` | `MetricsEndpointAcceptanceTest`：scrape 可解析；空闲期 `duo_up=1`/`running=0`；启动+注入后 `injections_total=1`、`components_hosted=2`、`component_instances=6`；二次抓取不重复计数且 `scrapes_total` +1；收口后 `assertions_total=1`/`failed=0`；**暴露格式结构校验**（每样本有 HELP/TYPE、值可解析为 double、样本正则合规） |
| 2 日志 | `logback.xml`（生产）、`logback-test.xml`（测试）、`FaultLog` | `FaultCausalChainLoggingTest`：两份配置均在 classpath；`io.duo.sim.fault` 保持 INFO（降噪不得静默因果链）；ZK 测试档降到 ERROR；注入成功/失败各留一条日志且级别为 INFO/WARN；**回归护栏**——两份配置都不得再出现 logback 条件块 |
| 3 因果链导出 | `FaultDiagnostics`、`duo diagnose` | `FaultDiagnosticsAcceptanceTest`：四段（注入/组件反应/SUT 事实/断言）齐全无 `MISSING`；归并"不丢不重"（各类条数之和 == 窗口内 SUT 事件数）；CLI 退出码 0；删掉注入事件后报告判为不完整 |

## 2. 三条通道的分工（与设计 §11 对齐）

| 通道 | 面向 | 事实粒度 | 取证方式 |
| --- | --- | --- | --- |
| 事件流录制（既有） | **机器** | 每条事实一条 JSONL | `events.jsonl` + 断言引擎 |
| 日志（本轮新增） | **人** | 因果链（谁对谁做了什么、为什么被拒） | `grep FAULT`、`duo diagnose` |
| `/metrics`（本轮新增） | **聚合系统** | 数值（计数/瞬时态） | Prometheus 抓取 |

三者**同源**：日志与指标都从事件流消费，不各自推测语义。因此"日志里看到的一次注入"
与"事件流里的 `sim.fault-injected`"与"`duo_injections_total` 的 +1"必然对应同一次事实。

## 3. 关键设计与取舍

### 3.1 指标：薄只读层，零内核改动

`MetricsCollector` 通过 `ScenarioHost.eventsSnapshot()` 读事件流，**不触碰内核**（§10）。
游标语义：每次抓取消费新增事件 ⇒ 重复抓取不重复计数；两次抓取之间的事件在下一次全部计入。

`eventsSnapshot()` 的实现细节值得记录：它**不持有宿主监视器**。原因是 `awaitFinish`
是 `synchronized` 且可能长驻（等待场景收口），若快照方法持锁，抓取会被长等待阻塞
——监控端点的第一原则是"永远能快速回答"。

### 3.2 `/metrics` 始终可读

`/scenario/status` 在场景未启动时返回 `409`；如果 `/metrics` 同样行为，Prometheus 的 target
会在场景启动前反复标记为 down，产生无意义告警。因此 `/metrics` 在任意状态返回 200，
用 `duo_scenario_running` 表达状态。

### 3.3 日志：降噪但不静默（§12）

生产档把 ZK/Curator/Netty/Testcontainers/H2/K8s mock 降到 WARN——它们在 INFO 下会刷屏，
把 Duo 的因果链淹没。但**降噪不等于静默**：

- 测试档 root 是 WARN（保住 T7「秒级反馈回路」，全量回归不会变成几千行日志），
  但 `io.duo.sim.fault` 与 `io.duo.sim.control.metrics` 显式保持 INFO；
- 注入**被拒绝**时打 WARN（不是 DEBUG、不是静默丢弃），且 `ScenarioHost.inject` 的
  "场景未运行"早退分支同样记录——最容易被漏掉的分支恰恰最需要被看见。

### 3.4 依赖侧实测修正（本轮的"意外发现"）

实施前实测 classpath：

```
duo-sim-embedded 依赖树
  \- org.apache.curator:curator-test:5.7.1
     \- org.apache.zookeeper:zookeeper:3.9.2
        \- ch.qos.logback:logback-core:1.2.13:compile     ← 只有 core，没有 classic
```

即：**没有任何 SLF4J 绑定**（`logback-classic` 不在 classpath，也无其它 binding），
日志调用会被静默丢弃——ROADMAP G6 说的"SLF4J 版本已管理但未成通道"精确地描述了这一点。
本轮修正：父 POM 管理 `logback-classic`+`logback-core` 1.5.16（`${logback.version}`），
`duo-sim-embedded` 与 `duo-sim-examples` 显式声明，`duo-sim-control` 只引 `slf4j-api`
（控制面不越权选后端）。顺带修掉一个隐患：**classic 与 core 版本不一致时 logback 会拒绝装配**，
显式管理 core 版本把它钉死。

### 3.5 踩坑记录：logback 条件块会让日志后端直接不可用（如实记录）

首版配置用 logback 的条件块表达"JSON 开关"：

```xml
<if condition='isDefined("duo.log.json")'>
  <then>…</then>
  <else>…</else>
</if>
```

实测（logback 1.5.16）：`<else>` 抛 `java.util.EmptyStackException`
（`ElseModelHandler.handle → ModelInterpretationContext.peekModel`），
`LoggerContext` 初始化失败 ⇒ **所有日志消失**。
这比"日志刷屏"严重一个数量级，而且**默认静默**（失败信息本身也在日志系统里）。

修复：改用 `${属性:-默认值}` 占位符 + 无条件双出口声明。并把这次踩坑变成护栏：
`FaultCausalChainLoggingTest` 断言两份配置都不得出现条件块——防止日后有人"顺手"加回去。

> 方法论提示：这个坑只有**跑用例**才暴露（配置语法在 IDE 里看不出问题），
> 也只有在用例里断言"logger 级别真的生效"才能守住。配置类交付物同样需要可失败门禁。

### 3.6 因果链：窗口封闭 + 归并 + 断链显式化

- **窗口封闭**：一次注入的链只在「下一次注入 / `sim.scenario-finished` / `sim.sut-exited`」
  才完整，早读会漏掉后续反应。实测窗口端点 = 事件 `#598`（注入在 `#6`）。
- **归并**：20s 场景产出 586 条 SUT 事件 → 归并为 8 类
  （`sut.heartbeat×564`、`sut.task-dispatched×4`、`sut.heartbeat-meter×4`、
  `sut.task-status×4`、`sut.task-terminal×4`、`sut.worker-registered×3`、
  `sut.dag-terminal×2`、`sut.exited×1`）。逐条打印的"完整链"实际不可读也无法断言。
- **断链显式化**：窗口内没有任何 `sut.*` ⇒ `gaps` 记录原因、渲染 `MISSING`、
  `duo diagnose` 退出码 **1**。这是"注入下达了但被测对象没反应"的唯一可见入口（§12）。
- **目标取实例级**：`sim.fault-injected` 的 `sourceId` 是实例源 id（实测 `workers-1`），
  而不是节点名 `workers`——链的起点因此比"节点组"更精确。

**刻意不做的断言**：用例**不**断言"crash 后必须有 `sut.instance-lost`"。
实测该事件可能出现（视注入时机）也可能不出现；SUT 何时感知实例消失是**被测对象的策略**，
不是诊断层的职责。诊断层只保证"发生了什么就报什么"，语义判定留给断言。
（把这两件事混在一起，会让观测面用例变成"测 SUT 行为"的用例——职责错位。）

### 3.7 拒绝路径的归属（口径澄清）

被拒绝的注入（如场景已结束时注入）**不写事件流**（没有注入事实可记），
但**必写日志**（`FaultLog.failed`，WARN）并**体现在 REST 响应**（`409` + 机器可读 reason）。
分工是：事件流只记"发生过的事实"；拒绝由日志与响应承载。
`FaultDiagnostics` 仍保有针对 `sim.fault-inject-failed` 事件的重建分支——
内核若在**运行中**拒绝某次注入，该分支即生效（用例覆盖了 REST 侧 409 的显式性）。

## 4. 实测数据（本轮）

| 项 | 实测 |
| --- | --- |
| 新增/改动用例 | **30 例全绿**：control 25（`DuoCliTest` 10 / `ScenarioHostTest` 9 / `RestControlServerTest` 6）+ examples 观测面 5（`MetricsEndpointAcceptanceTest` 2 / `FaultCausalChainLoggingTest` 2 / `FaultDiagnosticsAcceptanceTest` 1） |
| 全量回归 | **366 测 / 0 失败 / 0 错误 / 11 skip**；`BUILD SUCCESS` |
| 指标族数量 | 19（4 存活/状态 + 5 事件分类 + 3 注入 + 3 断言 + 4 拓扑） |
| `/metrics` 抓取 | 空闲 → 运行 → 收口全过程可抓取；`Content-Type: text/plain; version=0.0.4; charset=utf-8` |
| 因果链规模 | 20s 场景 608 条事件 / 1 次注入 / 586 条 SUT 事实归并为 8 类 |
| 诊断窗口 | 注入在事件 `#6`，窗口封闭于 `#598` |

## 5. 未做与边界（如实标注）

| 项 | 状态 | 原因 |
| --- | --- | --- |
| 任务时延直方图 | 未做 | 分桶需要先有 SLO 口径；拍脑袋分桶比没有更糟 |
| SUT 侧队列深度 | 未做 | `sut.*` 当前无该事实，需 SUT 主动上报 |
| 按场景维度打标签 | 未做 | 需要先决定"多场景共存"是否成为产品形态 |
| 10 个容器测试 | 本机 skip | 本机无 Docker；CI `container` job 覆盖 |
| 指标鉴权 | 未做 | 与既有控制面端点一致（面向受控网络） |

## 6. 交付物 4「加速时钟评估」为何仍未做

ROADMAP 给它的**触发条件**是："出现「小时级长稳场景」且目标档位为 virtual"。
当前场景集最长 `duration: 60s`，且长稳压测（`ScaleAcceptanceTest`）以 real 档为主。

**没有输入就没有评估**：在触发条件未出现时做加速时钟评估，产出只能是纸面推演，
会把"未验证的结论"写进 ROADMAP 冒充已完成——这比留着 TODO 更坏。
`SimClock` 接口已预留，无返工成本；本条保持 ⏸，等真实长稳需求出现。

---

## 7. 复现命令

```powershell
# 依赖与环境（占位符：验收当日实测命令含开发者本机路径，安全审计 2026-09-20 L-5 后改写）
$env:JAVA_HOME="<JDK 21 安装目录>"

# 全量回归
.\mvnw.cmd -o -B test

# 观测面定向验证（30 例）
.\mvnw.cmd -o -B test `
  "-Dtest=DuoCliTest,ScenarioHostTest,RestControlServerTest,FaultDiagnosticsAcceptanceTest,FaultCausalChainLoggingTest,MetricsEndpointAcceptanceTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"

# 手工验证指标与因果链（同进程模式）
.\mvnw.cmd -o -B -pl duo-sim-examples exec:java "-Dexec.mainClass=io.duo.sim.control.cli.DuoCli" `
  "-Dexec.args=metrics --summary"

# 结构化日志（JSON 出口）
# 启动时加 -Dduo.log.json=INFO；root 级别加 -Dduo.log.level=DEBUG
```

## 8. 相关文档

- 指标口径：[`docs/METRICS.md`](../../METRICS.md)
- 场景 DSL：`docs/SCENARIO-DSL.md`
- ROADMAP：[`docs/ROADMAP.md`](../../ROADMAP.md)（§4 M8 状态表 + §6 第 10 轮记录）
