# M6 验收记录：external SUT 与第三方接入

- 日期：2026-09-18
- 范围：ROADMAP §4 M6（G2 闭合）+ §5 第 1 项（M7 最小子集：标准 Wrapper + CI）
- 决策依据：[`DECISIONS.md`](../../DECISIONS.md) D1、D6–D9
- 设计依据：设计文档 v1.0 §7.3（SUT 适配面）、§11（观测面与断言）、§12（失败路径）、§13（测试策略）

---

## 1. 验收标准对照

| # | ROADMAP 验收标准 | 结论 | 证据 |
| --- | --- | --- | --- |
| 1 | 一个**不可改码**的 external 进程作为 SUT 接入，跑通「启动 → 端点告知 → ready 探针 → 故障注入 → 旁路断言通过 → 场景结束不杀进程」 | ✅ | `duo-sim-examples` `ExternalSutAcceptanceTest.externalThirdPartySutEndToEndWithoutKillingProcess` |
| 2 | external SUT 中途崩溃/退出分别产生 `sut.crashed`/`sut.exited` 并终止场景 | ✅ | 同测试类 `externalSutNormalExitPublishesExitedAndEndsScenario`、`externalSutCrashPublishesCrashedAndEndsScenario` |
| 3 | 端点配置文件与 stdout 兜底两条途径都有测试覆盖 | ✅ | 内核 `ExternalSutLauncherTest`（10 例，含两条途径各一例）+ examples 端到端（两条途径同时生效） |
| 4 | `ready.timeout` 全链路消费（in-process 与 external 一致） | ✅ | `ScenarioEngine.readyTimeoutMs`；内核 `ReadyProbeTest`（超时单位/缺省/非法值）+ `ExternalSutLauncherTest` 超时用例 |
| 5 | M7-1 新机器 `git clone && ./mvnw test` 一条命令成功 | 🟡 本地成立，待干净机器/远端取证 | `mvnw.cmd -v` → Maven 3.9.11 / JDK 21.0.12.1（仅依赖 `JAVA_HOME`） |
| 6 | M7-2 CI 三 job 全绿且 skip 可解释 | 🟡 配置与脚本就绪，待远端首跑 | `.github/workflows/ci.yml`、`.github/scripts/skip-summary.sh`（本地实跑输出 258/0/5） |

---

## 2. 实测数字（本机，2026-09-18）

```bash
./mvnw -o -B test "-Dduo.docker.enabled=false"     # → BUILD SUCCESS，03:22 min
bash .github/scripts/skip-summary.sh               # → TOTAL 258 run / 0 fail / 0 error / 5 skip
```

| 模块 | 测试数 | skip |
| --- | --- | --- |
| `duo-sim-protocol` | 9 | 0 |
| `duo-sim-kernel` | 76 | 0 |
| `duo-sim-scenario` | 41 | 0 |
| `duo-sim-components` | 42 | 0 |
| `duo-sim-embedded` | 39 | 4（无 Docker，`-Dduo.docker.enabled=false` 强制） |
| `duo-sim-junit` | 0 | 0 |
| `duo-sim-control` | 0 | 0 |
| `duo-sim-examples` | 51 | 1（未开 `-Dduo.scale`） |
| **合计** | **258** | **5** |

skip 逐条可解释（脚本输出）：

```
- eventsCarryContainerKind                    [ZookeeperContainerRegistryTest]
- facadeRoundTripThroughRealZk                [ZookeeperContainerRegistryTest]
- providerMetadataRejectsFlapAndPassesRegistration [ZookeeperContainerRegistryTest]
- wireEndpointExposesContainerZkPort          [ZookeeperContainerRegistryTest]
- heartbeatScaleScenarioPasses(String)        [ScaleAcceptanceTest]
```

T7 预算（常规回归 < 5 分钟）保持：实测 3.4 分钟。

---

## 3. 交付物清单

### 3.1 M6 内核侧

| 文件 | 内容 |
| --- | --- |
| `duo-sim-kernel/.../sut/ReadyProbe.java` | 探针规格 `Spec(type,host,port,path,timeoutMs)` + `spec(config, fallbackPort)` 解析（缺 type/端口/非法单位一律**启动前**抛错）+ `once(Spec)` 单次探测（tcp 建连 / http < 500） |
| `duo-sim-kernel/.../sut/ExternalSutLauncher.java` | 写端点配置文件（`duo.endpoint.*` + 节点 config，剔除 `ready.*`，另设环境变量 `duo.config`）→ 代起子进程 → stdout 端点兜底（**行首**严格前缀）→ 轮询 ready（200ms，与进程死亡赛跑）→ 退出观测（`sut.exited`/`sut.crashed`，仅 ready 之后）→ 启动失败销毁子进程 |
| `duo-sim-kernel/.../api/Event.java` | 新增 4 个框架事件类型：`sim.external-process-started` / `sim.external-endpoint` / `sim.external-sut-ready` / `sim.external-process-left-running` |

### 3.2 M6 场景层

| 文件 | 内容 |
| --- | --- |
| `model/Scenario.java`、`ScenarioLoader.java` | `Launch` 增 `command` 字段（3 参兼容构造器保留） |
| `ScenarioValidator.java` | 规则 4 加固：external 必填 `launch.configOut`；探针经 `ReadyProbe.spec` 启动前校验；文案改 `launch.ready` |
| `ScenarioEngine.java` | `startSut()` 分派 in-process / external；`splitCommand`（引号感知 + `${java}`/`${java.home}` 展开）；`readyTimeoutMs` 消费 `ready.timeout`；`onSutEvent` 统一发布并映射退出/崩溃；`stop()` 对 external 发 `sim.external-process-left-running` + 终态警告（不杀进程）；`externalSut()` 暴露句柄 |

### 3.3 M7 最小子集

| 文件 | 内容 |
| --- | --- |
| `mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties` | 标准 Wrapper（script-only），`distributionUrl` 钉 Maven 3.9.11；删除 `mvnw.sh` |
| `.github/workflows/ci.yml` | `regression`（无 Docker，skip 可见）/ `container`（断言 skip=0）/ `scale`（nightly，产物 artifact） |
| `.github/scripts/skip-summary.sh` | surefire XML → 逐报告 + TOTAL 表 + 逐条 skip 名；`--fail-on-skip` 门禁 |
| `ZookeeperContainerRegistryTest` | `dockerAvailable()` 受 `-Dduo.docker.enabled=false` 强制关闭（让「零 Docker 依赖」成为确定事实） |

### 3.4 测试

| 测试 | 条数 | 覆盖 |
| --- | --- | --- |
| `ExternalSutLauncherTest` | 10 | ready + 配置文件 + stdout 兜底 + 事件；`exposes` 兜底端口；ready 超时**销毁子进程**；ready 前退出/崩溃快速失败；正常退出 → `sut.exited`；崩溃 → `sut.crashed`（含 exitCode）；attach 形态；`close()` 不杀进程；端点行严格解析 |
| `ReadyProbeTest` | 8 | 规格解析与非法值拒绝、tcp/http 单次探测 |
| `ScenarioValidatorTest`（+5） | 18 | `configOut` 必填、未知探针类型、缺端口、非法时长单位、合规用例通过 |
| `ScenarioEngineCommandTest` | 5 | 空白切分、引号、`${java}`/`${java.home}` 展开、未知 token 原样保留 |
| `ExternalSutAcceptanceTest` | 3 | 端到端（配置文件到达 SUT + stdout 发现 + 旁路断言 + 不杀进程 + 警告）、正常退出、崩溃 |
| `ScenarioRuntimeTest`（+1） | 8 | 注入事件先因后果 |

假第三方 SUT fixture：`duo-sim-kernel/src/test/resources/external/FakeTcpSut.java`（最小形态）与
`duo-sim-examples/src/test/resources/external/FakeThirdPartySut.java`（增 report 文件，证明端点告知到达 SUT）。
两者**零 Duo 依赖**，经 `java <file>.java` 单文件源码模式启动（无 classpath/manifest-jar 问题）。

---

## 4. 缺陷处置

| # | 缺陷 | 级别 | 处置 |
| --- | --- | --- | --- |
| 1 | `sim.fault-injected` 在 `dispatch` **之后**落流 → 组件在同一注入调用内发布的反应事件（embedded `sim.registry-flap-started/cleared`）排在「因」之前，`eventSequence: [sim.fault-injected, <反应>]` 恒不可满足；以它为窗口起点的断言（`failoverWithin`/`masterReelectedWithin`）变脆 | **HIGH**（静默削弱断言） | 改为**先因后果**（`ScenarioRuntime.inject/clear` 先落流再派发）；派发失败另记 `sim.fault-inject-failed`；回归用例 `faultInjectedEventPrecedesComponentReactionEvents` 守住；文档记入 DSL §8 注与 ARCHITECTURE §9 |
| 2 | stdout 端点行若用 `indexOf("duo.endpoint.")` 容错匹配，会把 SUT 回显的内核配置行误判为自身端点宣告 | MEDIUM | 改为**行首严格前缀**解析，并加用例（`ExternalSutLauncherTest`） |
| 3 | 内核 jar 陈旧导致 `-pl <module>` 报「找不到符号」（构建纪律） | MEDIUM | 文档固化：单模块一律带 `-am`（DEVELOPMENT §2、排查手册） |
| 4 | PowerShell 传 `-Dx.y=z` 会被按 `.` 拆参（`-Dmaven=3.9.11` 生成过错误的 `distributionUrl`） | MEDIUM | 文档固化：PowerShell 下 `-D` 属性加引号（DEVELOPMENT §1.2） |

无未处置的高级别缺陷。

---

## 5. 限制与未覆盖（诚实声明）

1. **第三方真实产品未接入**（决策 D1 ①）：M6 交付的是**机制** + 自造零依赖样例；真实适配器（报文 ↔ Duo 帧
   翻译层 + 契约映射 + 版本基线）仍是触发式专项。
2. **attach 形态的退出不可观测**：省略 `launch.command` 时内核没有进程句柄，故无 `sut.exited`/`sut.crashed`
   （已在 DSL §1.5 明确写出，不静默）。
3. **CI 未在远端跑过**：本轮只交付配置与脚本，本地以同一脚本实跑验证输出；远端首跑后需回填结论。
4. **无 LICENSE/发布配置**（M7 余项）：法务状态仍不明确，已留在 ROADMAP G8。
5. **`ready` 声明位置未做别名兼容**（M5 交付物 5）：本轮只修正文案与启动前校验，节点级 `ready` 兼容别名未做。
6. **stdout 端点兜底依赖 SUT 主动打印**：内核不解析日志行中的其他格式（不做模糊匹配，避免误判）。

---

## 6. 复现步骤

```bash
# 1) 全量回归（零 Docker 依赖的确定性档）
./mvnw -o -B test "-Dduo.docker.enabled=false"
bash .github/scripts/skip-summary.sh

# 2) M6 端到端（单独跑，约 8 秒）
./mvnw -o -pl duo-sim-examples -am test -Dtest=ExternalSutAcceptanceTest

# 3) 内核侧 external 通路（约 30 秒）
./mvnw -o -pl duo-sim-kernel -am test -Dtest='ExternalSutLauncherTest,ReadyProbeTest'
```
