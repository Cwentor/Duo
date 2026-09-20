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
| H-2 | HIGH | 已修复 | body 上限 1 MiB（按 `MAX+1` 截断，防谎报/chunked）→ 413；规模上限（节点/实例/时间线/断言） | §2.1 用例 ④ ⑤ |
| H-3 | HIGH | 已修复 | 外部输入档禁派生进程；`config` 值禁 URL scheme 与绝对路径 | §2.1 用例 ① ⑤ |
| H-4 | HIGH | 已修复 | `MetricsCollector` 事件类型基数上限 256 + `__other__` 溢出桶（**总量恒等**） | §2.3 用例 ③ |
| H-5 | HIGH | 已修复 | `EventRecorder` 有界缓冲（50 万）+ 丢弃计数上抛为告警；`stopWithoutAwait()`；`close()` 关 executor | §2.3 用例 ① ②、§2.1 用例 ⑥ |
| M-1 | MEDIUM | 已修复 | 外部输入档 `config` 键白名单（键取自各组件的**真实读取点**，不是猜的） | §2.1 用例 ⑤ |
| M-2 | MEDIUM | 已修复 | 同上：值禁 `jdbc:`/`file:`/`http:` 等 scheme 与绝对路径 | §2.1 用例 ① ⑤ |
| M-3 | MEDIUM | 已修复 | `HookRegistry.emit` 只接受 `sim.` / `sut.` 前缀（与 `Event` 既有不变式对齐） | §2.3 用例 ④ |
| M-4 | MEDIUM | **口径修正** | 结果伪造面随认证关闭（未认证无法 POST/DELETE/读 `/assertions`）。但报告提到的"单请求时长上限"**没有**实现——`com.sun.net.httpserver.HttpServer` 根本没有 `setMaxReqTime`（`javap` 实证，见 §2.4）。不写"看起来有上限其实没有"的代码（§12） | §2.1 用例 ②、§2.4 |
| M-5 | MEDIUM | 已修复 | `sanitizeReason`：绝对路径掩码 + `Bearer xxx` 掩码 + 300 字截断 | §2.1 用例 ① ⑦ |
| M-6 | MEDIUM | 已修复 | 启动失败即删临时 YAML；`startFromResource` 用后即删；场景收尾删临时文件与 `configOut` | §2.2 用例 ① ② ③ |
| M-7 | MEDIUM | **口径修正** | 报告字面是"`close()` 释放 stdin/stdout 句柄"。实证：Windows 上"只关流不杀进程"会**永久死锁**（§2.4）。最终口径＝**谁持有句柄谁收摊**：代起形态 `destroy()` + 关流；attach 形态不动任何进程 | §2.2 用例 ④ ⑤ |
| M-8 | MEDIUM | 已修复 | 500 只回根因摘要，不回内部路径 | §2.1 用例 ⑦ |
| L-1 | LOW | 已修复 | 临时文件失败路径与正常结束路径都删 | §2.2 用例 ① ③ |
| L-2 | LOW | 已修复 | `FrameConnection` 双重分配：见下"未改"一行；`ReadyProbe` 复用静态 `HttpClient`（原先每次探测泄漏一个连接选择器） | §2.2 用例 ⑥ |
| L-3 | LOW | 口径修正 | 内核**不**对用户 SUT 线程做 `destroyForcibly`（破坏 §7.3）；改为：内核自己持有的资源强制回收（M-7），并修掉"迟到的 `onStop` 注册永不生效"这一真实缺陷 | §2.2 用例 ⑤、§2.4 |
| L-4 | LOW | 口径修正 | 不给长连接 worker 设读超时（会误杀正常空闲 worker，取舍记入 D12）；把**确定性**的 FD 泄漏源全部堵死（`ReadyProbe` / `ExternalSutLauncher.close` / `HttpServer` executor） | §2.2 用例 ④ ⑥、§2.1 用例 ⑥ |
| L-5 | LOW | 已修复 | 4 个已跟踪文件的本机路径/用户名改为占位符；`scripts/duo-inject-demo.sh` 的 `JAVA_HOME` 改为必填 | §2.3 用例 ⑤ |
| L-6 | LOW | 已修复 | CI 三个 action 全部 pin 到 commit SHA | §2.3 用例 ⑥ |
| INFO-1 | INFO | 已修复 | 工作区残留物清理 + `.gitignore` 显式封口 | §2.3 用例 ⑦ |

**明确未做的一处（报告 L-2 的前半段）**：`FrameConnection` 的 `new byte[9+len]` + `readFully(len)`
双重分配保持原样。理由是它**没有突破任何上限**（`MAX_PAYLOAD_LENGTH` = 1 MiB 在两次分配**之前**
就已校验），峰值 2 MiB/连接对压测目标（万级 worker）是可接受的常数；改它要动线协议的读写路径，
属"没有失败证据的重构"，与 §12"改要改在有证据的地方"冲突。已在报告台账里如实标注为未做。

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
