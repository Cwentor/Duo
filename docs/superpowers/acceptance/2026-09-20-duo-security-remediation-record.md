# Duo 安全整改验收记录（2026-09-20）

> **对象**：`docs/security-audit-2026-09-20.md` 提出的全部 20 条发现（C-1 CRITICAL、H-1..H-5 HIGH、
> M-1..M-8 MEDIUM、L-1..L-6 LOW、INFO-1）。
> **口径**：报告的核心判断「信任边界不该画在『能连到 127.0.0.1:7788』上」被完全采纳；
> 落法是把边界画回**输入来源**上（配置档 / 外部输入档），而不是削弱产品能力。决策见
> `docs/DECISIONS.md` D10（控制面信任模型）、D11（输入信任分层）、D12（句柄归属与收摊）。
> **本记录只写可复现的证据**：每条整改都给出"跑什么、看到什么"。

---

## 1. 逐条处置一览

级别与编号沿用报告原文。**处置**列只有三种取值：已修复 / 口径修正（做了什么、为什么与报告字面不同）/
报告不成立（附证据）。

| 编号 | 级别 | 处置 | 落地点 | 证据 |
| --- | --- | --- | --- | --- |
| C-1 | CRITICAL | 已修复 | 令牌必填 + 回环双校验 + 外部输入档校验规则 9–11 | §2.1 用例 ① ② ③ |
| H-1 | HIGH | 已修复 | Bearer 令牌（自定义头，跨站简单请求无法携带）+ `Origin`/`Host` 回环 | §2.1 用例 ② ③ |
| H-2 | HIGH | 已修复 | body 上限 1 MiB（按 `MAX+1` 截断，防谎报/chunked）→ 413；规模上限（节点/实例/时间线/断言） | §2.1 用例 ④ ⑤、§5 用例 ① |
| H-3 | HIGH | 已修复 | 外部输入档禁派生进程；`config` 值禁 URL scheme 与绝对路径 | §2.1 用例 ① ⑤ |
| H-4 | HIGH | **已修复（第二轮）** | ~~第一轮误记为「`MetricsCollector` 事件类型基数上限 256」~~。**审计原文指向 `ScenarioHost.start()` 覆写 `engine` 引用、旧引擎无人 `close()`**——第二轮补上：顶替时引用立即摘除、`close()` 在后台虚拟线程 `duo-host-retire` 执行、`retiredEnginesClosed()` 可观测、失败/超 30 s 上抛告警。类型基数上限那条**继续保留**（它治的是 H-4 编号下的另一个真实问题） | §5 用例 ② |
| H-5 | HIGH | **已修复（第二轮）** | ~~第一轮误记为「`EventRecorder` 有界缓冲」~~。**审计原文指向 `ScenarioEngine.recorded` 无界 `CopyOnWriteArrayList`（`:46/:71/:516`）**——第二轮补上：有锁 `ArrayList` + 50 万上限、追加 O(1)、溢出计数 `droppedEvents()` 并在 `stop()` 上抛告警、写侧单点 `recordEvent(Event)`、`events()` 同步快照。`EventRecorder` 的有界缓冲**继续保留** | §5 用例 ③（`EventRecorderTest`）、§2.3 用例 ① |
| M-1 | MEDIUM | 已修复 | 外部输入档 `config` 键白名单（键取自各组件的**真实读取点**，不是猜的） | §2.1 用例 ⑤ |
| M-2 | MEDIUM | **已修复（第二轮补完）** | 值禁 `jdbc:`/`file:`/`http:` 等 scheme 与绝对路径（第一轮）；**外加** D11 承诺的 `CapabilityMetadata.trustedConfigKeys` 从空承诺变成真契约（`effectiveTrustedConfigKeys` 取静态白名单 ∪ 组件声明的并集） | §2.1 用例 ① ⑤、§5 用例 ④ |
| M-3 | MEDIUM | 已修复 | `HookRegistry.emit` 只接受 `sim.` / `sut.` 前缀（与 `Event` 既有不变式对齐） | §2.3 用例 ④ |
| M-4 | MEDIUM | **降级：非缺陷，已文档化** | 复核推翻第一轮的「已修复」判定：`scrape()` 的副作用**是事实**，但锁方向单一（`MetricsCollector → Host`）、无死锁环、重复抓取得单调累计量不丢数 ⇒ 不当缺陷。口径写入 `docs/METRICS.md` §4（含"别拿 `/metrics` 当探针轮询"的使用建议）。第一轮"未认证读不到 `/metrics`"的结论仍有效，只是它答的不是 M-4 字面问题 | `docs/METRICS.md` §4、§2.1 用例 ② |
| M-5 | MEDIUM | **已修复（第二轮）** | ~~第一轮误记为「`sanitizeReason`」~~。**审计原文指向 `PostgresContainerStore.appendCredentials:351` 明文拼 `password=`**，且复核发现 **embedded 档同样成立**（`H2Store.java:76` 也把 `jdbcUrl` 送进 `sim.store-started`，而 `store.jdbcUrl` 是场景输入可指定的）。第二轮：两档统一走 `CapabilityMetadata.redactUrlCredentials(...)`；**SUT 连接用的 `jdbcUrlWithCredentials` 保持不变**（掩码只作用于事件流口径）。`sanitizeReason` 继续负责 HTTP 错误体 | §5 用例 ⑤ ⑥、§2.1 用例 ⑦ |
| M-6 | MEDIUM | **已修复（第二轮）** | ~~第一轮误记为「删临时文件」（那是 L-1）~~。**审计原文指向 `/events` 异常回显**——第二轮三条回读路径都收口：`/events` 失败 → 500 + `sanitizeReason` 摘要（不再静默 200 空流）；`FaultDiagnostics` 对非 `sim.`/`sut.` 命名空间事件**降级为诊断事实**（`sim.diagnostics-unrecognized-event`），不再把内核前缀校验的 `IllegalArgumentException` 冒到 CLI；启动失败路径删临时 YAML 保留 | §5 用例 ⑦、§2.2 用例 ① ③ |
| M-7 | MEDIUM | **已修复（第二轮）** | ~~第一轮误记为「`close()` 释放管道」~~（那是同一编号的**另一面**，成果保留）。**审计原文指向 `sim.external-process-started` 回显完整命令行（`ExternalSutLauncher.java:141-142`）**——第二轮：载荷改为可诊断摘要（basename + `+N args` + 每参 `i:键=<redacted>`／`i:<N chars, fp xxxxxxxx>`），异常路径同口径。`close()` 口径（代起先 `destroy()` 再关流、attach 不动用户进程）不变 | §2.2 用例 ④ ⑤、§5 用例 ⑧ ⑨ |
| M-8 | MEDIUM | **已修复（第二轮）** | ~~第一轮报告 §5 曾把该条自我否决，台账跟着记成「诊断回显收敛」~~。**审计原文指向 CI 上传 `**/build/scenarios/**/events.jsonl`**——`ci.yml` 两处 `upload-artifact` 的 `path:` 里确有（第 56、110 行），第二轮删除。诊断/异常回显按 M-5 口径收敛这一条继续保留 | §5 用例 ⑩ |
| L-1 | LOW | 已修复 | 临时文件失败路径与正常结束路径都删 | §2.2 用例 ① ③ |
| L-2 | LOW | 已修复 | `FrameConnection` 双重分配：见下"未改"一行；`ReadyProbe` 复用静态 `HttpClient`（原先每次探测泄漏一个连接选择器） | §2.2 用例 ⑥ |
| L-3 | LOW | 口径修正 | 内核**不**对用户 SUT 线程做 `destroyForcibly`（破坏 §7.3）；改为：内核自己持有的资源强制回收（M-7），并修掉"迟到的 `onStop` 注册永不生效"这一真实缺陷 | §2.2 用例 ⑤、§2.4 |
| L-4 | LOW | **口径修正 + 第二轮补点** | 不给长连接 worker 设读超时（会误杀正常空闲 worker，取舍记入 D12）；把**确定性**的 FD 泄漏源全部堵死（`ReadyProbe` / `ExternalSutLauncher.close` / `HttpServer` executor）。**第二个轮补正：报告只点了 worker 侧，`VirtualEngine.java:357-360` 是 SUT 侧同构代码**（首帧 10s 限时 → `setSoTimeout(0)` → 无限期 `conn.read()`），同一取舍、同一理由，但必须进台账 | §2.2 用例 ④ ⑥、§2.1 用例 ⑥ |
| L-5 | LOW | 已修复 | 4 个已跟踪文件的本机路径/用户名改为占位符；`scripts/duo-inject-demo.sh` 的 `JAVA_HOME` 改为必填 | §2.3 用例 ⑤ |
| L-6 | LOW | 已修复 | CI 三个 action 全部 pin 到 commit SHA | §2.3 用例 ⑥ |
| INFO-1 | INFO | 已修复 | 工作区残留物清理 + `.gitignore` 显式封口 | §2.3 用例 ⑦ |

**明确未做的一处（报告 L-2 的前半段）**：`FrameConnection` 的 `new byte[9+len]` + `readFully(len)`
双重分配保持原样。理由是它**没有突破任何上限**（`MAX_PAYLOAD_LENGTH` = 1 MiB 在两次分配**之前**
就已校验），峰值 2 MiB/连接对压测目标（万级 worker）是可接受的常数；改它要动线协议的读写路径，
属"没有失败证据的重构"，与 §12"改要改在有证据的地方"冲突。已在报告台账里如实标注为未做。

> **第二轮更正（2026-09-20 独立复核之后）**：上面这张表的第一轮版本有 **7 行编号与主题不对应**。
> 复核逐行读源码（`e8ea3cc` 对照整改后 HEAD）指出：`H-4 / H-5 / M-5 / M-6 / M-7` 的**原始问题
> 在整改后代码里原样存在**，台账却把"同期做的另一件相邻的好事"记成了它们的落地点
> （例如 H-5 记成 `EventRecorder` 有界缓冲，而审计原文指向 `ScenarioEngine.recorded`）。
> `M-8` 则是报告 §5 自我否决、台账跟着记成另一件事。上表的 **H-4/H-5/M-2/M-4/M-5/M-6/M-7/M-8/L-4
> 五行已按第二轮结果就地更正**（删除线保留第一轮原文，便于对照"当时是怎么记错的"）；
> 完整叙述与逐行对照见 `docs/security-audit-2026-09-20.md` §7.2。
> **教训**：台账的"落地点"必须能对着审计原文那句话读出因果，否则就是没修——
> 用"自己做了的相邻工作"填格，等于用自己的交付物给自己打分。
> 第二轮全量回归：~~**406 测**~~ **388 测 / 0 失败 / 0 错误 / 11 skip**（2026-09-24 复核订正：「406」系误记，实测 388；见 §3 订正注）。

---

## 2. 证据

环境：`JAVA_HOME=<JDK 21>`（本地实测用的具体路径属开发者本机信息，按 L-5 口径不写入文档）；
Maven 3.9.11（wrapper 自备）；命令均在仓库根执行；无 Docker（`-Dduo.docker.enabled=false`）。

### 2.1 控制面（C-1 / H-1 / H-2 / H-3 / M-1 / M-2 / M-4 / M-5 / M-8 / L-4）

复现命令：

```powershell
.\mvnw.cmd -B -o -pl duo-sim-examples -am "-Dduo.docker.enabled=false" `
  "-Dtest=RestControlServerTest,SecurityRemediationAcceptanceTest,DuoCliTest" test
```

| # | 用例 | 断言（要点） | 结果 |
| --- | --- | --- | --- |
| ① | `SecurityRemediationAcceptanceTest.c1PocIsRefusedAtValidationAndLeavesNoTraceOnDisk` | 报告 §3.1 的 PoC YAML（`command: "sh -c 'id > <marker>'"`）经 `POST /scenario` **400** 拒绝；`marker` 与 cron 落点文件**均不存在**；响应体里**不出现**临时文件路径 | 通过 |
| ② | `RestControlServerTest.everyEndpointRequiresTheBearerToken` | 7 个端点无令牌 → 401 且带 `WWW-Authenticate`；`/health` → 200（唯一免认证） | 通过 |
| ③ | `RestControlServerTest.crossSiteOriginAndForeignHostAreForbidden` | 恶意 `Origin` → 403；伪造 `Host` → 403（**原始 socket** 构造，绕开 `HttpClient` 对 `Host` 受限头的拒绝） | 通过 |
| ④ | `RestControlServerTest.oversizedBodyIsRejectedWith413` | 声明与谎报两种超限 body 均 413，进程不 OOM | 通过 |
| ⑤ | `RestControlServerTest.externalInputCannotLaunchProcessOrReachOutsidePaths` | `launch.command` → 400「external process」；`main: java.lang.ProcessBuilder` → 400「framework namespace」；未知 config 键 → 400；超大 `count` → 400 | 通过 |
| ⑥ | `RestControlServerTest.healthAndFullLifecycleRoundTrip` | `DELETE /scenario` 立即 200（不等 SUT 收尾），随后可读 `/topology` 验尸 | 通过 |
| ⑦ | `RestControlServerTest.invalidYamlIs400AndBodyHasNoPaths` | 错误体不含本机路径（`sanitizeReason`） | 通过 |
| ⑧ | `SecurityRemediationAcceptanceTest.unauthenticatedPocNeverReachesScenarioEngine` | 无令牌的 PoC → 401，且 `host.status().state == IDLE`（**根本没进引擎**） | 通过 |
| ⑨ | `DuoCliTest.serveRefusesToStartWithoutTokenWhenEnvAbsent` | 无令牌/无环境变量 → 退出码非 0，错误信息给出三条出路 | 通过 |

合计：`RestControlServerTest` 11/11、`DuoCliTest` 11/11、`SecurityRemediationAcceptanceTest` 3/3。

### 2.2 资源与进程（M-6 / M-7 / L-1 / L-2 / L-3 / L-4）

```powershell
.\mvnw.cmd -B -o -pl duo-sim-kernel,duo-sim-scenario,duo-sim-control -am `
  "-Dduo.docker.enabled=false" test
```

| # | 用例 | 断言（要点） | 结果 |
| --- | --- | --- | --- |
| ① | `SecurityRemediationAcceptanceTest.c1PocIsRefusedAtValidationAndLeavesNoTraceOnDisk` | 启动失败路径不留临时 YAML | 通过 |
| ② | `ScenarioHostTest`（9 条） | 生命周期/状态/事件增量/注入转发 | 通过 |
| ③ | `SecurityRemediationAcceptanceTest.controlPlaneTempScenarioFileIsRemovedAfterFinish` | `POST /scenario` 后 tmpdir 里 `duo-rest-*` 计数上升；`DELETE` 后 ≤20s 回到基线 | 通过 |
| ④ | `ExternalSutLauncherTest.closeDestroysSpawnedProcessAndReleasesPipes` | 代起形态 `close()` 后子进程 20s 内退出、`!process().isAlive()`、二次 `close()` 幂等不卡 | 通过 |
| ⑤ | `ExternalSutLauncherTest.attachModeCloseLeavesUserProcessAlone` | attach 形态 `close()` 后内核无进程句柄，且用户 `ServerSocket` 仍绑定未关闭 | 通过 |
| ⑥ | `ReadyProbeTest`（8 条） | 探针复用静态 `HttpClient` 后行为不变（连接超时/回退路径） | 通过 |
| ⑦ | `SutLauncherTest`（9 条） | 协作式停止、ready 前退出快速失败、`onStop` 迟到注册 | 通过 |

合计：`duo-sim-kernel` 77/77、`duo-sim-scenario` 53/53、`duo-sim-control`（含 examples 内 control 包）30/30。

### 2.3 事件与指标基数、卫生（H-4 / H-5 / M-3 / L-5 / L-6 / INFO-1）

> ⚠️ 本节标题里的 H-4/H-5 是**第一轮的错位记法**（见 §1 末尾的更正说明）：这里记录的是
> 「`MetricsCollector` 类型基数上限」与「`EventRecorder` 有界缓冲」——两件事本身都成立、
> 也都有用，但它们**不是**审计 H-4/H-5 的字面问题。第二轮已按审计原文补齐
> （H-4＝引擎收摊、H-5＝`ScenarioEngine.recorded` 有界），证据见 §3 第二轮表。

| # | 用例 / 检查 | 断言（要点） | 结果 |
| --- | --- | --- | --- |
| ① | `EventRecorderTest.bufferIsBoundedAndOverflowIsCounted` | 灌入 `MAX_BUFFERED_EVENTS + 5` 条：`droppedEvents() == 5`，落盘行数恰为上限 | 通过 |
| ② | `ScenarioEngine` 告警上抛 | 丢弃 >0 时 `stop()` 产生告警并在 `ScenarioResult` 记录（不静默丢数据） | 通过 |
| ③ | `MetricsCollector` 溢出桶 | 超 256 种类型并入 `__other__`；`sum(duo_events_total)` 与事件总数恒等 | 通过 |
| ④ | `HookRegistryTest.hookEmitOutsideSimOrSutPrefixIsRejected` | `ctx.emit("duo.custom-fact", …)` 失败且**不产生任何事件**；`null` 同样拒绝；对照组事件顺序仍为 `sim.custom-probe` → `sim.hook-executed` | 通过 |
| ⑤ | `git grep -n "cwt15"` | 全仓 0 命中（4 个已跟踪文件已改占位符） | 通过 |
| ⑥ | `ci.yml` 复核 | `uses:` 全部为 40 位 SHA + 版本注释（共 9 处） | 通过 |
| ⑦ | `git status --porcelain` | 无 `hs_err_pid*.log` / `replay_pid*.log` 残留；`.gitignore` 显式封口 | 通过 |

### 2.4 两条"实证推翻报告字面"的记录（**这次整改最有价值的部分**）

**(1) M-7：`close()` 不能只关流。** 把空实现补成"关 stdin/stdout/stderr"后，
`ExternalSutLauncherTest` **挂死**。`jcmd Thread.dump_to_file -format=json` 的线程转储显示：

```
"main"                      java.base/java.io.FileDescriptor.close0(Native Method)
                            ... BufferedInputStream.close → ExternalSutLauncher.closeQuietly
"duo-external-stdout-master" java.base/java.io.FileInputStream.readBytes(Native Method)
                            ... BufferedReader.readLine → ExternalSutLauncher.pumpStdout
```

结论：Windows 上的管道读是**同步 `ReadFile`**，`Thread.interrupt()` 与 `close()` **都打不断它**，
而 `close()` 要拿的正是读线程握着的流锁 ⇒ 先关流必死锁。最终口径记入 D12 并写进报告 §7.1：
**代起形态先 `destroy()` 再关流；attach 形态无操作返回。**

**(2) M-4：`setMaxReqTime` 不存在。** 审计建议给单请求加时长上限。`javap` 实证：

```powershell
javap -classpath "$env:JAVA_HOME/jmods/jdk.httpserver.jmod" com.sun.net.httpserver.HttpServer
# → bind / start / setExecutor / getExecutor / stop / createContext / removeContext / getAddress
#   没有 setMaxReqTime（那是包私有的 ServerImpl 才有）
```

故**不实现**该上限，并把常量命名为 `REQUEST_TIME_BUDGET` 且注释写明"这是预算不是强制"——
避免留一个"看起来有上限、实际不生效"的静默谎言。真正的时长约束由有界的启动路径（ready 探针超时、
规模上限）与 1 MiB body 上限承担。

---

## 3. 全量回归（本机实测）

```powershell
$env:JAVA_HOME="<JDK 21 安装目录>"
.\mvnw.cmd -B -o "-Dduo.docker.enabled=false" test
```

结果（9 模块）：

```
protocol 12/0/0/0   kernel 77/0/0/0   scenario 53/0/0/0   components 120/0/0/0
embedded 55/0/0/10  junit  —          control  —          examples 61/0/0/1
Tests run: 378, Failures: 0, Errors: 0, Skipped: 11
BUILD SUCCESS
```

skip 11 条＝设计门控（容器档 10 = ZK 4 + PostgreSQL 6，无 Docker；压测 1 = `ScaleAcceptanceTest`
需 `-Dduo.scale=true`），与整改前一致，`-Dduo.docker.enabled=false` 下为**预期**行为。

对比整改前基线（同一命令）：342 测 → **378 测**（新增 36 条安全回归用例），
`Failures: 0, Errors: 0` 保持不变。

**第二轮（同日，独立复核之后）**：

```
Tests run: 406, Failures: 0, Errors: 0, Skipped: 11   ← 总数系误记；2026-09-24 复跑实测 388（见下方订正注）
BUILD SUCCESS   (9 模块)
```

> **订正（2026-09-24 复核）**：第二轮全量回归的真实数字是 **388 测 / 0 失败 / 0 错误 / 11 skip /
> BUILD SUCCESS**——在本轮提交（`5d17a87`）的干净 worktree 上以 §3 同款命令复跑实测（protocol 12、
> kernel 79、scenario 55、components 120、embedded 57〔+10 skip〕、examples 65〔+1 skip〕，
> control 契约测试当时仍在 examples 步内执行）。净增为 **10** 条而非 28 条，与本节表格所列 10 个
> 新增用例一一对应（表中 `EventRecorderTest.bufferIsBoundedAndOverflowIsCounted` 为第一轮已有用例，
> 此处引作 H-5 录制侧佐证，不计入净增）。交叉验证：`306c817` 新增 3 条、`6f2cb58` 再增 5 条，
> `9ad13ca` 记录的实测 **396 = 388 + 3 + 5** 严丝合缝并据此同步全仓口径——「406 / 净增 28」
> 从未对应过任何真实构建输出。下表用例本身及其锁定的行为经复跑**全部属实**；审计报告 §7.2(6)
> 有同款订正注。

第二轮净增 ~~28~~ **10** 条补充回归（2026-09-24 复核订正），锁的都是**审计原文那句话**本身而不是它旁边的代码：

| 编号 | 用例 | 锁住什么 |
| --- | --- | --- |
| H-4 | `SecurityRemediationAcceptanceTest.repeatedScenarioStartsRetireThePreviousEngine` | POST/DELETE 三轮后 `host.retiredEnginesClosed() >= 2`（旧引擎真被收摊，不是"引用换了"） |
| H-5 | `EventRecorderTest.bufferIsBoundedAndOverflowIsCounted` | 溢出条数精确可读、落盘恰为上限条数（引擎侧与录制侧同一上限） |
| M-2 | `SecurityRemediationAcceptanceTest.componentDeclaredConfigKeysAreAcceptedForExternalInput` | 组件声明的键**必须**被接受；未声明的**仍被拒**；无声明时不得凭空可信 |
| M-5 | `H2StoreTest.storeStartedEventNeverEchoesCredentials`、`PostgresContainerStoreGuardTest.eventPayloadUrlIsMaskedWhileEndpointKeepsCredentials` | 事件载荷无明文口令；**同时**断言 SUT 连接串仍带凭据（别把可用性一起修没） |
| M-6 | `FaultDiagnosticsAcceptanceTest.malformedEventStreamIsReportedNotThrown` | 被改坏的事件流降级为诊断事实，不把内核异常冒给用户 |
| M-7 | `ExternalSutLauncherTest.processStartedEventNeverEchoesRawArgv`、`emptyCommandRedactionIsSafe` | 载荷不含口令/令牌/DSN/路径/位置参数；键名与参数个数保留（可诊断）；摘要可重复 |
| C-1/H-2 | `SecurityRemediationAcceptanceTest.oversizedUploadsAreRejectedByDeclaredAndActualSize` | 声明超限 → 413；**谎报/chunked 流式**上传也被读侧截断 → 413，且场景仍 `IDLE` |
| 录制契约 | `EventRecorderTest.jsonLineKeyOrderIsStableAcrossRuns`、`jsonLineIsReadableByTheFixedFieldContract` | 同一事件两次落盘逐字节相同；行仍是 `type/sourceId/timestamp/payload` 四字段契约（嵌套信封会被 Jackson 静默吞掉） |

---

## 4. 交付物清单

| 类别 | 文件 |
| --- | --- |
| 决策 | `docs/DECISIONS.md`：D10（控制面信任模型）、D11（输入信任分层）、D12（句柄归属与收摊） |
| 架构 | `docs/ARCHITECTURE.md` §12 端点表、新增 §12.1 控制面信任模型、§14 不变式 11/12 |
| 指标 | `docs/METRICS.md` §1 认证行、§4 已知边界（鉴权 + 类型基数上限） |
| DSL | `docs/SCENARIO-DSL.md` §1.3（`allowExternalProcess`）、§1.5（收摊口径）、§4（规则 9–11） |
| README | §5.2/§5.3：令牌用法、`JAVA_HOME` 必填、安全口径摘要 |
| 审计报告 | `docs/security-audit-2026-09-20.md` §7 台账（逐条状态）+ §7.1（M-7 复核实证） |
| 代码 | 见 §1 表"落地点"列 |

**未提交**：`.mimosa/`、`build/`、`target/` 等构建产物（`.gitignore` 覆盖）；审计报告本身是否纳入
版本控制由仓库所有者决定（本记录按"报告是输入、不是交付物"处理，但不阻止后续提交）。
