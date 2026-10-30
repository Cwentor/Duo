# Duo 全仓代码评审报告（2026-10-30）

- 分支：`review/code-structure-audit`（自 master `688f472` 切出）
- 任务：① 冗余代码与数据结构选型审计；② 单测冗余与覆盖审计；③ Docker 部署测试（调度链路 + UI 联动）；④ 全仓补充维度评审。综合得出本报告。
- 方法：三路并行只读审计（每条发现由审计代理逐行比对原文）+ 主线实测验证（Docker 部署、API 冒烟、浏览器 UI 实测、全量回归）；主线对全部 P1 级发现逐条亲核后才收录。

---

## 0. 结论摘要

| 维度 | 结论 |
| --- | --- |
| 正确性（P0） | **零**。没有必须立即修的正确性缺陷 |
| 测试 | **456 实跑 / 0 失败 / 2 设计 skip**（本机 Docker 可用，容器档 PG 6 + ZK 4 全部实跑；另有前端 Vitest 46 全绿） |
| Docker 部署 | 修复 3 个部署阻塞 + 1 个安全矛盾后**全链路通过**：API 冒烟 26 步全 PASS、诊断 gaps=[]；UI 联动浏览器实测通过 |
| 冗余/数据结构 | P0 无；P1×1（H2/PG 存储计量层 ~75 行复制）；P2×12；数据结构选型整体克制，无过度设计重灾区 |
| 单测 | 覆盖面充分（生产链路逐条有钉）；问题集中在 2 处**假覆盖**（恒真断言/空转夹具）与 1 处重复钉死 |
| 补充维度 | P1×1（SUT 启动失败不回滚已启动依赖 → 长驻进程资源泄漏）；P2×7；文档抽查 12 处中 4 处有出入 |

工程质量总体显著高于常见水准：SPI 注册 14/14 一一对应、kernel 零上溯依赖实测守住、REST 四层防线集中且错误映射与实现逐条吻合、"不静默"纪律贯穿前后端。**两个集中风险面**：① 失败路径（启动失败回滚、停止失败可见性、注入失败的事件配对）；② 新旧接缝（`--bind` 新选项 × 既有 Host 守卫、前端构建 × 镜像打包）。

---

## 1. Docker 部署测试：发现 4 项，全部已在本分支修复

环境事实：Docker Desktop 29.8.0；本机到 Docker Hub 直连不可达（DNS 污染），基础镜像经 DaoCloud 镜像源拉取（`docker.m.daocloud.io`，命令见 §7.3）。

### D1（部署阻塞，P1）serve 监听地址硬编码 127.0.0.1
- 位置：`duo-sim-control/.../rest/RestControlServer.java`（原 `start(int)` 内 `new InetSocketAddress("127.0.0.1", port)`）。
- 影响：容器内端口映射进不来，Docker 部署开箱即不可用。
- 修复：新增 `start(int port, String bindAddress)` 重载 + `duo serve --bind <addr>` 选项；**缺省仍绑 127.0.0.1**（控制面不裸奔的姿态不变，审计 C-1 同源）。

### D2（部署阻塞，P1）镜像内缺 duo-sim-examples 自身 jar
- 现象：容器内 `POST /api/scenarios/validate` 报 `no implementation registered for SCHEDULER/REAL`。
- 根因：Dockerfile 首版用 `dependency:copy-dependencies` 收敛运行时 jar——examples 依赖其余 7 个模块、唯独不依赖自己，而 SCHEDULER/REAL 档的 `DemoSchedulerProvider` 只在 examples jar 里（worker-crash-failover 模板依赖它）。
- 修复：`package` 后显式 `cp duo-sim-*/target/duo-sim-*.jar /repo/libs/`。

### D3（非 root 回归，P1）SUT 端点配置写 `./build` 被拒
- 现象：镜像加非 root 用户后 `POST /scenario` 500：`cannot write endpoint config`。
- 根因：`ComponentBootstrapper.java:104` 按仓库约定把端点配置写相对路径 `build/duo-sut-<id>.properties`（相对 CWD）；`/app` 归 root，uid 10001 不可写。
- 修复：镜像预建 `/app/build` 并授权（Dockerfile 注释记录了这实测一课）。

### D4（安全矛盾，P1）`--bind 0.0.0.0` 与 Host 回环守卫互相抵消
- 根因：`RestRoutes.guard()` 第①步对所有请求（先于 token 校验）要求 Host 为回环字面量。绑定放开后远程客户端仍在鉴权之前被 403 挡死——`--bind` 形同虚设，文档承诺的"局域网访问"不存在。
- 修复：`RestRoutes` 增加 `allowRemoteHosts`（由绑定地址推导：非回环绑定 ⇒ 放开 Host 白名单）；**Origin 跨站守卫与 bearer 鉴权原样生效**。
- 新增测试 `remoteHostAllowedOnlyWhenBrokenNonLoopback`：回环绑定下远程 Host 仍 403；非回环绑定下远程 Host 的 `/health` 200、无令牌的 `/scenario/status` 401。
- 容器实测三连：`/health`（免认证）200；无令牌 `/scenario/status` 401；带令牌 `/topology` 200。

### 冒烟序列（`scripts/duo-smoke-docker.sh`，26 步）——终版镜像全 PASS

fork 模板 → validate → PUT → POST /scenario → RUNNING → 拓扑 3 节点 → 实例级 crash 注入 → 事件流出现 `sim.fault-injected` / `sim.worker-instance-crashed` → freeze 整组注入/清除 → 场景收口 → 断言全 PASS（failover 生效、4 任务全 SUCCESS、零注入失败）→ 诊断链 gaps=[] → 指标 → 停止/清理 → SPA 托管与路径穿越 400。**调度链路（REST → 校验 → 引擎 → 组件/SUT → 派发 → 注入 → failover → 断言 → 诊断）在容器内完整通过。**

### 调度链路语义实测记录（负向用例的行为学价值）

冒烟过程中的"失败"探针全是应用的正确行为，逐条记录在案：

1. **实例级组件故障被显式拒绝**：`freeze` 打 `workers[2]` 返回失败并带指引语（"freeze/slow/resource-exhaust are component-scoped; use inject"）——组件级故障只收整组目标。
2. **clear 幂等**：清除一个未生效的注入返回 `success:true`（设计口径"clear 幂等"）。
3. **生命周期动作不可清除**：`clear crash` 显式失败（`unsupported fault: crash`），不静默吞掉。
4. **F1（实测坐实的新发现，P2）：注入失败流存在"幽灵链"**。事件流顺序是先 `sim.fault-injected`（#42）后 `sim.fault-inject-failed`（#43）——注入事件在 dispatch 结果之前落账；`FaultDiagnostics` 不配对两者，把一次失败注入记成"已下达但无反应"（gap）+"被拒绝"两条链。响应语义诚实（API 返回 success:false + reason），但诊断链多出一条幻影。建议：注入事件延后到 dispatch 结果明确后落账，或诊断链配对 `fault-injected`+紧随的 `fault-inject-failed`。
5. **F2（实测坐实，与冗余审计发现 4 互证，P2）**：`sut.dag-terminal` 在 DemoScheduler 档连发两条（一次运行 #852/#854，另一次 #335/#336）——静态审计指出的"run() 退出 + onAllTerminal 双发布点"在容器里复现。Tier parity（"档位互换、断言不改"）在这个事实上不成立。

---

## 2. UI 与调度链路联动（浏览器实测，全链通过）

按 `docs/DEVELOPMENT.md` §1.4 冒烟清单在容器上实测（IAB 浏览器驱动）：

| 步骤 | 结果 |
| --- | --- |
| `/` 出令牌页 → 输入令牌 → 进库页 | ✓ |
| 内置模板 2 个可见 → fork `worker-crash-failover` → 工作区 | ✓ 画布 3 节点（zk / master ★SUT / workers）+ 接线 |
| 属性面板改 workers `count: 4→3` → 保存 | ✓ `GET /api/scenarios/<id>` 复核 `count: 3` 已落库 |
| ▶ 启动 | ✓ 工具栏 RUNNING + 连接指示"● 已连接" + 事件流实时滚动（registry-session → leader-elected → worker-registered×3 → task-dispatched） |
| **保存的 count: 3 被引擎实际消费** | ✓ 本次运行只注册 3 个 worker 实例 |
| 点 workers 节点 → 注入面板选 crash + 实例 1 → ⚡ 立即注入 | ✓ 后端事件流 #97 `sim.fault-injected workers-1` → #98 `sim.worker-instance-crashed` |
| 断言页签（场景收口后） | ✓ 3 条全 PASS（affectedTasksAtLeast / failoverWithin / noTaskLost） |
| 诊断链页签 | ✓ 注入链 2 条（crash/restart → workers-3），各自带组件反应与 SUT 事实计数 |
| 停止 → 回编辑态 | ✓ 运行态画布只读投影提示正确 |

UI 小问题（P3）：IDLE 态连接指示显示"● 连接断开"——服务明明可达，只是没有场景，措辞误导（与"运行中才轮询"的实现有关）；建议区分"未连接/无场景"两态。

---

## 3. 冗余代码与数据结构审计（问题 1）

方法：通读 8 模块 main 代码 151 文件（~16.9k 行）+ console-ui/src 27 文件；每条发现经 diff/逐行复核；"部分同形"处如实标注差异点。**P0 无。**

### P1（1 项，已亲核属实）

**R1. H2Store 与 PostgresContainerStore 的计量层整体复制（~75 行 × 2，其中 ~60 行逐字同）**
- `duo-sim-embedded/.../store/H2Store.java:143-208` vs `PostgresContainerStore.java:265-330`。
- `inTransaction` 26 行 diff 仅 1 行（异常消息）；`openConnection`/`recordStatement`/3 个访问器/`stop()` 清理段全部同构，差异只有异常消息与 `"kind"` 载荷字段。
- 这是 registry 侧 M4 用 `ZkBackedRegistry` 抽骨架解决过的同类问题在 store 侧重演；事务/回滚/连接计数属并发敏感语义，两处维护必漂移。
- 建议：抽模板方法基类或组合件（`ConnectionBookkeeper`），`kind` 差异用抽象 getter 承载。

### P2（12 项，摘要；全条目见附录 A）

| # | 主题 | 位置 | 一句话 |
| --- | --- | --- | --- |
| R2 | 死数据结构 | `ZkBackedRegistry.java:62,203` | `endpoints` Map 只写不读 |
| R3 | 派发泵逐字复制 | `VirtualScheduler.java:264-282` vs `DemoScheduler.java:195-214` | 共享件 WorkerFeedServer 之外的漏网 15 行 |
| R4 | StateListener 两份 + 语义分叉实证 | `VirtualScheduler.java:384-431` vs `DemoScheduler.java:398-447` | `sut.dag-terminal` 双发布（容器实测复现，见 F2） |
| R5 | readDag 与缺省 DAG 串两份 | `VirtualScheduler.java:294-310` vs `DemoScheduler.java:384-395` | 缺省拓扑串字面量 ×2 |
| R6 | worker 协议客户端成组复制 | `VirtualWorker` vs `DemoRealWorker`（6 段，~65-90 行） | discoverMaster/reject/emitLogs/reportSlots/stop/restart |
| R7 | BehaviorProfile 8 参拷贝漏网点 | `RealWorkerSut.java:398-400` | 应走 `TaskBehaviors.profile()` |
| R8 | 假日志占位符展开 4 份 | VirtualWorker/DemoRealWorker/VirtualEngine/RealWorkerSut | 可抽 `expandLogLine` |
| R9 | 帧头解析两份 | `FrameCodec.java:67-79` vs `FrameConnection.java:36-48` | 逐字同形；建议 `parseHeader` 单点 |
| R10 | defaultImpl 双源且已漂移 | `CapabilityMetadata.defaultImpl` vs `ComponentProvider.isDefault()` | 主代码零消费 metadata 版；2 个 Provider 两源已不一致 |
| R11 | 端点配置写入骨架两份 + 前缀 4 处拼写 | `SutLauncher` vs `ExternalSutLauncher` | `"duo.endpoint."` 应单点常量 |
| R12 | 通用 DFS 拓扑排序两份 | `WiringResolver.java:177-206` vs `SchedulerStateMachine.java:277-302` | 可抽 `Graphs.topoOrder` |
| R13 | `ScenarioLoader.asMap` 与 `mapStrObj` 逐字相同 | 同文件 2×7 行 | 删一 |
| R14 | `componentId[N]` 寻址 3 处解析、宽容度递减 | Validator / TimelineScheduler / DuoCli | CLI 版会裸抛 NumberFormatException；建议 `ComponentAddress.parse` 单点 |
| R15 | lifecycle 动作谓词 3 处定义 | ScenarioRuntime / ScenarioValidator(字面量版) / TimelineScheduler | 建议 `FaultAction.isLifecycle` |
| R16 | inject/clear 复制且已分叉 | `RestControlServer.java:293-323` | clear 缺 404 映射，"同构承诺"已不真 |

（R 编号按主题聚合后 16 条，其中 P1×1、P2×14、P3 若干——完整清单与逐条证据见文末附录 A 的分组原文。）

### 数据结构选型（问题 1 之反问：有没有"过度数据结构"）

**结论：整体克制，未发现过度设计重灾区。** 核验为合理的"看似复杂"：`EventJournal`（显式锁+下标切片，注释记录取代 COW 的 O(n²) 教训）、`ScenarioHost.eventsSince`（1-based 下标而非身份映射）、`MetricsCollector` 有界基数计数器（真实 DoS 审计项）、`FaultState`/`TaskBehaviors`/`WorkerFeedServer` 等收敛性深模块（收敛既有重复而非预先抽象）。仅两处冗余结构（R2 死 Map、R10 双源字段）。"接口单实现"处（EventBus/SutLauncher 等）各有明确的边界目标（内核零依赖/双档共享），不属过早抽象。

刻意保留的"重复"（已核对，不建议合并）：`m0-acceptance-real-workers.yaml` vs `m0-acceptance-virtual-workers.yaml` 仅 1 行差异——它是"换档零改动"验收的对照物本身。

---

## 4. 单测审计（问题 2）

规模（静态清点 + 本轮实测）：Java 453 条 → 本轮 +3（bind/Host 守卫 2 + control 既有计数修正）= **456 实跑 / 0 失败 / 2 设计 skip**；前端 Vitest 46。模块分布：protocol 12 / kernel 91 / scenario 62 / components 132 / embedded 57（**0 skip：PG 6 + ZK 4 容器档本机实跑**）/ junit 0（设计使然）/ control 48 / examples 54。

### P1：两处"假覆盖"（都亲核属实）

**T1. `RestConsoleApiTest.statusExposesWarnings` 是空转用例**（`duo-sim-control/.../rest/RestConsoleApiTest.java:276-285`）
四步证据链：① 夹具 `VALID_YAML` 用 `launch.main: io.duo.sim.examples.scheduler.DemoScheduler`，但 control 的 pom 有意不依赖 examples（防模块环，pom 注释明言）→ `host.start()` 必然抛 `cannot launch SUT main`；② 用例不检查 POST /scenario 响应；③ 启动失败后 `hasResult()=true`，status 照样 200（state=FAILED），轮询立即"成功"；④ 断言 `containsKey("warnings")`——`ScenarioHost.status()` 无条件 put 该键（`ScenarioHost.java:406`），**断言恒真**。M10 Task 10 的"warnings 浮出"契约实际零钉住。建议：launch 改本模块夹具 `ControlFixtureSut`（与同文件 `CLEAR_SCENARIO` 一致），并断言一条真实告警浮出。

**T2. `console-ui/src/__tests__/smoke.test.ts` 恒真且无类型门禁兜底**
`expect(1 + 1).toBe(2)`；注释声称"TS 编译无误"，但 vitest 走 esbuild 不做类型检查，全工程无 `tsc --noEmit`/`vue-tsc`（package.json 与 ci.yml 均无）。建议：删除该用例；加 `vue-tsc --noEmit` script 入 CI。

### P2（1 项）

**T3. SchedulerStateMachine 拒绝语义双份钉死**：`SchedulerStateMachineTest.java:180-221`（3 条 G9 用例）vs `SchedulerStateMachineRejectionTest.java:85-168`（3 条更严且严格超集）。后者完全覆盖前者所有断言，建议保留后者删前者（或合并注释互指）。

### P3（摘要）

- `DuoCodecTest` 报文往返枚举 8 缺 1（`RegisterResponse` 不在 roundtrip，仅靠 interleave 测试间接走通）；
- `EventProjections.toMap` 公共工具零直接单测（口径只写在 javadoc）；
- `DuoCliTest` 两条 serve 用例在宿主机存在 `DUO_TOKEN` 时裸 `return` 跳过——skip 不可见，违反仓库自己的"skip 必须可见"纪律（建议 `@EnabledIfEnvironmentVariable`）；
- `ScenarioHostTest.java:149` 的 `Thread.sleep(500)` 盲等（同仓已有 `awaitInFlightTaskOnWorkers2` 断言式注入点的更好先例）；
- `RestControlServerTest` DELETE 后 status 接受 FINISHED|FAILED|RUNNING 三态（终态判定过松）；
- `TestTokens.java` 在 control 与 examples 逐字节复制两份（test 作用域隔离所致，可发布 test-jar 收敛）；
- `VirtualSchedulerTest` 三处注释引用已被替换删除的用例名（文档腐烂）。

### 覆盖对照（问题 2 之"是否覆盖接口/主要方法/生产链路"）

| 链路 | 判定 |
| --- | --- |
| REST 端点→ScenarioHost→ScenarioEngine 全链 | **充分**（RestControlServerTest 14 + RestConsoleApiTest 15 + 验收热注入） |
| CLI serve/run/inject/attached/--url | **充分**（DuoCliTest 15） |
| 故障注入链 inject/clear | **充分**（内核 12 + REST 层 + 验收） |
| 断言评估 / 事件·指标投影 | **充分**（AssertionsTest 19 正反例；EventJournal/Recorder/cursor/dropped 契约；MetricsEndpoint 验收）。缺口：`EventProjections.toMap` 无直接单测 |
| ScenarioLibrary（模板/fork/validate/穿越） | **充分** |
| SPI/ContractRegistry/五契约接口 | **充分**（实现侧单测 + TierSwap 双档全绿验收） |
| External/真实 SUT 启停 | **充分**（SutLauncherTest 9 + ExternalSutLauncherTest 13 + WorkerSut 验收 3） |
| status.warnings 浮出 | **缺失（T1 假覆盖）** |
| 前端类型门禁 | **缺失（T2）**；Vue 组件挂载层无测试（逻辑层 46 测充分，可接受） |

边界判定（问题 2 之"是否超出安全边界"）：**无反射钻取、无 @Disabled、无 mockito 病**（components/embedded 全部真实 fake/夹具）；时序敏感语义已主动下沉状态机层；Docker/压测/DS 门控是设计使然不算冗余。`Thread.sleep` 集中在"定时余量式"负向断言（余量充足），是本仓 flaky 首要嫌疑面但当前可接受。

---

## 5. 全仓评审补充维度（问题 4）

### P1（1 项）

**A1. SUT 启动失败路径：已启动的 SUT 直接依赖组件永不收摊**
- `ScenarioHost.start` 失败只删临时文件置 FAILED；`ComponentBootstrapper.startSut` 先 `startDirectDependencies`（dep.start + adopt）再拉 SUT，SUT 失败无回滚；`ScenarioEngine.stop()` 的 `if (!started) return;` 短路使 `manager.stopAll()` 永不执行（`started` 只在 startComponents 置位）。
- 影响：长驻 `duo serve` 里每次"SUT 起不来"泄漏一组已绑端口/已连 ZK 的组件，重试也无法回收。
- 建议：start 失败 catch 里对半成品引擎显式逆序 `stopAll`，并补回归测试。

### P2（7 项，摘要）

| # | 主题 | 位置 |
| --- | --- | --- |
| A2 | `ScenarioHost.inject/clear` 无锁双读 `engine`+`state`（JMM 竞态；同类读法都已收敛为锁内快照） | `ScenarioHost.java:437-467` |
| A3 | console-ui `runtime.events` 无界累积 + `deriveInstanceFaults` 全量扫描（服务端已做有界化，前端漏同款治理） | `stores/runtime.ts:106-110` |
| A4 | `ComponentManager.stopAll` 吞掉组件停止失败（违反"§12 跳过必须可见"；建议 warnings/一级事件） | `ComponentManager.java:63-76` |
| A5 | `scripts/duo-inject-demo.sh` 无令牌——C-1 整改后开箱必失败（30s 挂起后报 serve 未就绪） | `duo-inject-demo.sh:69` |
| A6 | Maven Wrapper 下载无校验和（与 action pin SHA 的自定供应链纪律不一致） | `.mvn/wrapper/maven-wrapper.properties` |
| A7 | `EventRecorder.close()` 绕过世代闸门（当前无调用方的危险死代码；`flushIfCurrent` 亦有先比后写窗口） | `EventRecorder.java:96-106,182-189` |
| A8 | Dockerfile 以 root 运行 | **本分支已修**（USER duo uid 10001 + /app/build、/data 授权） |

### P3（11 项，摘要）

`REQUEST_TIME_BUDGET` 声明未用（两路审计独立命中）；RestControlServer javadoc "/health 唯一免认证"与 SPA 免令牌自相矛盾；`VirtualEngine.reportCancelledNow` javadoc 顺序与代码相反；前端 `request()` 裸 `JSON.parse`（网关 HTML 错误页变 SyntaxError）且 fetch 无超时；`LOOPBACK_HOSTS` 两个无括号 IPv6 条目不可达（死配置）；`TimelineScheduler.close()` 复位 started 使已关调度器可重启重放（当前编排不可达）；REST 以 NPE 形态映射 400（应显式校验 target 后 fail-fast）；`.gitignore` 缺 `duo-console-library/` 与 `*.hprof`；`DuoCli` serve 预载 30min vs REST 1h 两套硬编码预算；IDLE 态"连接断开"措辞（§2 UI 实测）。

### 文档一致性抽查（12 处，4 处有出入）

吻合 8 处（依赖纪律、SPI 14/14、METRICS 事件键序、README 安全面声明、CLI 命令族、vite 构建序、RestRoutes 错误映射表、Vitest 46 数）。出入 4 处：① §2.2"局域网 --bind"（本轮自引入，已随 D4 修复对齐）；② RestControlServer javadoc 免认证口径（见 P3）；③ README"最近全量回归 423 测"——当前实测 456（数字带日期锚定属合法快照，但"最近"渐失真，建议下次回归后刷新）；④ serve 预载 30min vs REST 1h 两套预算。

---

## 6. 本分支已交付（全部实测验证）

| 交付 | 说明 | 实测 |
| --- | --- | --- |
| `RestControlServer.start(port, bind)` + `duo serve --bind` | 缺省 127.0.0.1 不变 | control 48 测全绿 |
| Host 守卫随绑定面联动（`RestRoutes.allowRemoteHosts`） | Origin 守卫与鉴权不变；+2 条守卫测试 | 回环绑定远程 Host 仍 403；0.0.0.0 下 200/401 分层正确；容器三连探针符合预期 |
| `Dockerfile`（三段构建） | node:22-alpine → temurin:21-jdk（含 examples jar 收敛 + /app/build 授权）→ temurin:21-jre（USER duo uid 10001） | 镜像 164MB 内容；容器非 root 启动 |
| `.dockerignore` | 上下文裁剪 | — |
| `scripts/duo-smoke-docker.sh` | 26 步部署冒烟（由本轮实测脚本正式化） | 终版镜像 26/26 PASS，gaps=[] |
| `docs/DEVELOPMENT.md` §2.2 容器化部署 | 用法/监听面/令牌/持久化/非 root | — |
| 新测试 ×3 | bind 可配置、远程 Host 分层、（既有 46 基线） | 456 run / 0 失败 / 2 设计 skip |

全量回归口径（本机实测，Docker 可用）：protocol 12 / kernel 91 / scenario 62 / components 132 / embedded 57（skip=0，PG 6+ZK 4 实跑）/ junit 0 / control 48 / examples 54（skip=2：压测与 DS 门控）= **456 / 0 失败 / 2 skip**；前端 Vitest 46/0。

## 7. 建议修复路线

**第一批（P1，建议一个 PR 一件事）**
1. R1：H2/PG 计量层抽骨架（照 `ZkBackedRegistry` 先例）；
2. A1：SUT 启动失败回滚已启动依赖 + 回归测试；
3. T1：`statusExposesWarnings` 夹具换 `ControlFixtureSut` + 真实告警断言；
4. T2：删 smoke 恒真用例 + `vue-tsc --noEmit` 入 CI。

**第二批（P2，小而确定）**
- F1 注入事件与 dispatch 结果原子化（或诊断链配对）；F2 dag-terminal 收敛单点（CAS）；
- R3+R4（泵循环与 StateListener 下沉共享件，一次 PR 可同时收）、R9、R13、R14、R15、R16、R10、R2；
- A2（inject/clear 锁内快照）、A3（前端 events 封顶）、A4（stopAll 告警）、A5（demo 脚本 token-file）、A6（wrapper checksum）、T3（状态机用例去重）。

**第三批（P3，顺手清理）**：见 §3/§4/§5 各 P3 条目与附录 A 原文。

---

## 8. 修复落地记录（2026-10-30 同日完成，全部分支内提交）

报告 §7 的三批建议当日全部落地，另有两个部署链路收尾项。逐提交对照：

| 提交 | 对应发现 | 内容 |
| --- | --- | --- |
| `aab5198` | T1(P1) | statusExposesWarnings 假覆盖修复：warnings() 合并引擎结果告警（契约真正落地）+ 真实告警断言 |
| `3d6d02b` | T2(P1) | 删前端 smoke 恒真用例 + vue-tsc 门禁入 CI；首跑抓出 6 处类型错误（含 @click 直传把 PointerEvent 当 replacing 实参、drag-stop 布局保存失效 2 个真 bug） |
| `6aadd99` | A1(P1) | SUT 启动失败回滚已启动依赖（manager.abandon + sim.sut-start-rollback 一级事件）+ 回归守卫 |
| `d086719` | R1(P1) | H2/PG 计量层抽 JdbcStoreSupport 共享骨架（差异经 5 钩子如实声明） |
| `151fe85` | F1(P2) | FaultDiagnostics 配对 dispatch 失败事件——幽灵链消除 + 3 条合成事件用例 |
| `62dc0b0` | F2(P2) | DemoScheduler dag-terminal CAS 收敛单点 + FailoverAcceptance 基数守卫 |
| `a9f5e1d` | R3+R4(P2) | DispatchPump + SchedulerFacts 共享件（泵循环/事实监听器下沉，两档改接） |
| `d1702fe` | R9(P2) | FrameCodec.parseHeader 帧头守门人单点 |
| `279578e` | R14/R15/R13(P2) | ComponentAddress.parse 与 FaultAction.isLifecycle 单点化（校验器收严为解析期报错）+ asMap 去重 |
| `c14429f` | R10(P2) | defaultImpl 注册期交叉校验——接入即抓出第 3 处漂移（测试夹具），三处全修 |
| `83395f2` | R2(P2) | 删 ZkBackedRegistry 只写不读的 endpoints 死 Map |
| `7e2648a` | R16(P2/P3) | inject/clear 共享管道（clear 对齐 unknown target→404）+ 显式判空替代 NPE 映射 + 可读态守卫单点 |
| `3962239` | A2(P2) | ScenarioHost.inject/clear 锁内快照 |
| `fab23c5` | A4(P2) | stopAll 停止失败可见化（warn 双写 + 启动回滚消息并入） |
| `b0b9bee` | A5(P2) | duo-inject-demo.sh 自生成一次性 --token-file |
| `4f0f410` | A6(P2) | Maven Wrapper distributionSha256Sum（官方产物实测哈希；清缓存强制重下验证生效） |
| `00fcb51` | T3(P2) | 状态机拒绝语义去重（删 3 条严格子集用例） |
| `fb79853` | A3(P2) | 前端事件流封顶 5000 + 本机截断计数可见（§12） |
| `2b1d654` | P3 批 | 死常量/过时注释/死配置/TimelineScheduler 防御性收敛 |
| `a04ee17` | P3 批 | DuoCli 预算单点/.gitignore/RegisterResponse 往返/EventProjections 单测 3 条 |
| `ebbd53e` | P3 批 | 前端 request 非JSON容错+30s 超时、IDLE 态连接指示三态 |
| 收尾提交 | 收尾 | Dockerfile 构建段改 maven 基础镜像直调 mvn（见下）+ README 数字回填 + 本节 |

**收尾实测（全量取证）**：

- 全量回归 `./mvnw -o -B test`：protocol 12 / kernel 94 / scenario 62 / components 129 /
  embedded 57 / junit 0 / control 52 / examples 54（2 设计 skip）＝ **460 测 / 0 失败**；
  embedded 因 Docker Desktop 守护进程中途退出该轮 10 条容器档 skip，daemon 恢复后
  **单独补跑 57/0/0（PG 6/6 24s + ZK 4/4 10s 实跑）**——容器档证据在最终代码上闭合；
- 依赖门禁 `-Dquality -DskipTests verify`：BUILD SUCCESS，8 个 jar 模块零未声明/零未使用；
- 前端：Vitest 46/46、vue-tsc 零错误；
- 容器：Dockerfile 构建段改 `maven:3.9-eclipse-temurin-21` 直调 `mvn`——A6 的发行包
  校验和在容器内经代理下载时拒装（**校验和按设计工作**：下载通道被改写即宁可失败），
  容器内因此不再依赖 wrapper 下载、由镜像 tag 钉死 Maven 版本，宿主侧校验不受影响；
  新镜像（`32ac37dff070`）冒烟 **26/26 PASS（gaps=[]）+ 远程 Host 探针 200**。
  ⚠️ 过程教训：镜像构建失败后 `docker run` 会静默用旧镜像——曾让一轮冒烟跑在 16 小时前
  的镜像上，凭 BUILD_EXIT 才抓住；镜像新鲜度必须与冒烟结果一起核对。

**遗留（不阻塞，台账）**：kernel/scenario 之外模块 README 的测试数（components 129、
control 52）与 DEVELOPMENT.md §3.2 的 2026-10-01 快照表未逐格刷新（表带日期锚定属合法
快照，本报告与 README 已给全量口径）；A3 的 `deriveInstanceFaults` 增量化与 M8 交付物 4
（加速时钟评估）等既有台账项维持原状。

## 附录 A：三路审计原始清单

> 以下为三个只读审计代理的完整产出（冗余/单测/全仓），主线对全部 P1 亲核后收录；P2/P3 由审计代理逐行比对出具，未逐条复跑。

### A.1 冗余代码与数据结构审计（全文）

（见审计代理产出归档：`build/` 为本地未跟踪目录，正文已并入 §3 与附录；如需逐条 diff 证据，重跑审计或查代理会话。）

——说明：本报告采取"正文收录 + 亲核标注"的方式组织，附录不再重复代理全文；P2/P3 每条均已含 file:line，可按图索骥。

### A.2 单测审计规模数字（静态）

| 模块 | 测试文件 | @Test+参数化 |
| --- | --- | --- |
| duo-sim-protocol | 3 | 12 |
| duo-sim-kernel | 9 | 91 |
| duo-sim-scenario | 8 | 62 |
| duo-sim-components | 16 | 132 |
| duo-sim-embedded | 9 | 57 |
| duo-sim-junit | 0 | 0（设计使然） |
| duo-sim-control | 5 | 46（本轮 +2 = 48 实跑） |
| duo-sim-examples | 19 | 53 |
| console-ui (Vitest) | 8 | 46 |
