# Duo 开发指南

- 适用版本：`0.1.0-SNAPSHOT`（HEAD `5b72753`，2026-09-18）
- 相关：[架构说明](ARCHITECTURE.md) · [场景 DSL](SCENARIO-DSL.md) · [发展规划](ROADMAP.md)

---

## 1. 环境与工具链

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **21（LTS）** | 项目使用虚拟线程、`record`、文本块、模式匹配 `switch` |
| Maven | **3.9+** | 多模块 reactor（9 个 module 含 parent） |
| Docker | 可选 | **仅** `container` 档需要；无 Docker 时相关测试按设计自动 skip |
| OS | Windows 11 已验证 | Curator/H2/Fabric8 均纯 Java；Testcontainers 需 Docker |

### 1.1 标准 Maven Wrapper（M7 起）

仓库自带标准 Maven Wrapper：`mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties`。

```bash
./mvnw -o clean test        # Git Bash / Linux / macOS
.\mvnw.cmd -o clean test    # PowerShell / cmd
```

- 形态为 **script-only**（不提交 `maven-wrapper.jar`）：首次运行按 `maven-wrapper.properties`
  里的 `distributionUrl` 下载 **Maven 3.9.11** 到 `~/.m2/wrapper/dists/`，之后离线可用。
- **只依赖 `JAVA_HOME`**（JDK 21），不再硬编码任何本机路径——`git clone && ./mvnw test` 即可构建。
- 旧的手写壳 `mvnw.sh`（硬编码作者本机 JDK/Maven 路径）已按决策 D6 删除，见
  [`DECISIONS.md`](DECISIONS.md)。

### 1.2 PowerShell（无需 Git Bash）

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'   # 仅需 JAVA_HOME；Maven 由 wrapper 自备
cd D:\Program\Duo
.\mvnw.cmd -o -B clean test
```

> PowerShell 传 `-D` 属性时要**加引号**（否则 `.` 会被当作参数分隔）：
> `.\mvnw.cmd -o test "-Dduo.docker.enabled=false"`。

---

## 2. 常用命令

| 目的 | 命令 |
| --- | --- |
| 全量回归 | `./mvnw -o clean test` |
| 无 Docker 档（CI 回归 job 同款） | `./mvnw -o clean test "-Dduo.docker.enabled=false"` |
| 单模块 | `./mvnw -o -pl duo-sim-kernel -am test` |
| 单测试类 | `./mvnw -o -pl duo-sim-examples -am test -Dtest=FailoverAcceptanceTest` |
| 安装到本地仓库（跑 CLI 前必须） | `./mvnw -o install -DskipTests` |
| 压测（千/万档） | `./mvnw -o -pl duo-sim-examples -am test -Dtest=ScaleAcceptanceTest "-Dduo.scale=true"` |
| CLI 演练（同进程 + 跨进程热注入） | `bash scripts/duo-inject-demo.sh` |
| 依赖树 | `./mvnw -o -pl duo-sim-embedded dependency:tree` |
| 构建类路径（脚本用） | `./mvnw -o -q -pl duo-sim-examples dependency:build-classpath -Dmdep.outputFile=target/demo-classpath.txt -DincludeScope=runtime` |

`-o` ＝离线（本机依赖已就绪，避免网络抖动）；首次构建可去掉。

> **`-pl <module>` 记得带 `-am`**：不带时该模块会解析 `~/.m2` 里**已安装的旧内核 jar**，
> 改了内核源码却报「找不到符号」——这是本仓最容易踩的坑。

### 2.0 CI（`.github/workflows/ci.yml`，M7）

| job | 触发 | 内容 |
| --- | --- | --- |
| `regression` | push / PR | `./mvnw -B -Dduo.docker.enabled=false test`（确定性无 Docker 档）+ skip 汇总 |
| `container` | push / PR | `-pl duo-sim-embedded -am test -Dtest='ZookeeperContainer*'`，并**断言 skip=0**（有 Docker 时不许静默跳过） |
| `scale` | nightly / 手动 | `-Dduo.scale=true`，把 `build/scale/*.json` 与事件录制上传为 artifact（G8「规模数据可追溯」） |

skip 汇总由 `.github/scripts/skip-summary.sh` 输出（`--fail-on-skip` 用于容器档门禁）。

### 2.1 `surefire` 的 Windows 特殊配置

父 POM 给 surefire 设了：

```xml
<argLine>-Djdk.net.URLClassPath.disableClassPathURLCheck=true</argLine>
```

原因：**Windows 多盘符**（项目在 `D:`、`.m2` 在 `C:`）下，surefire 的 Manifest-JAR 类路径检查会拒绝发现测试。
这是官方提示的规避方式，不是可选项——去掉后 Windows 上会「零测试被发现」。

---

## 3. 测试分层与门控

### 3.1 分层

| 层 | 位置 | 内容 |
| --- | --- | --- |
| 单元测试 | 各模块 `src/test/java` | 内核生命周期/拓扑排序/事件总线/restart 语义/实例寻址/接线推断与校验/注册期一致性；组件行为模型；断言语义；DSL 校验 |
| 集成/验收测试 | `duo-sim-examples/src/test/java/.../acceptance/` | 端到端场景：档位切换（M0）、故障转移（M1）、重选主（M2）、控制面热注入（M3）、规模压测（M4）、**external 第三方 SUT（M6）** |
| JUnit 扩展测试 | `examples`（用 `@VirtualCluster`） | 注解生命周期、引擎参数注入、断言评估、录制路径 |

> `duo-sim-junit` / `duo-sim-control` 自身**不带测试**——它们的集成测试必须放 `examples`，
> 否则会形成 `junit ↔ examples` 循环依赖。

### 3.2 当前分布（2026-09-18 实测，M6/M7 落地后）

| 模块 | 测试数 | skip |
| --- | --- | --- |
| `duo-sim-protocol` | 9 | 0 |
| `duo-sim-kernel` | 76 | 0 |
| `duo-sim-scenario` | 41 | 0 |
| `duo-sim-components` | 42 | 0 |
| `duo-sim-embedded` | 39 | **4**（无 Docker，`-Dduo.docker.enabled=false` 强制） |
| `duo-sim-junit` | 0 | 0 |
| `duo-sim-control` | 0 | 0 |
| `duo-sim-examples` | 51 | **1**（未开压测开关） |
| **合计** | **258** | **5** |

```bash
./mvnw -o -B test "-Dduo.docker.enabled=false"   # → BUILD SUCCESS，约 3.4 分钟
bash .github/scripts/skip-summary.sh             # → 258 run / 0 fail / 5 skip（逐条可解释）
```

> M6 新增 21 条（内核 18：`ExternalSutLauncherTest` 10 + `ReadyProbeTest` 8；examples 3：
> `ExternalSutAcceptanceTest`），M6 修正的注入顺序缺陷另加 1 条（`ScenarioRuntimeTest`），
> scenario 新增 10 条（校验规则 4 的 5 条 + 命令行切分 5 条）。

### 3.3 两条明文门控

| 门控 | 机制 | 设计依据 |
| --- | --- | --- |
| 容器档 | `@EnabledIf(dockerAvailable)` 自动 skip；**同时**有 5 条不标门控的守卫用例（`ZookeeperContainerRegistryGuardTest`）离线验证「不支持＝显式拒绝」 | 设计 §13「容器档在无 Docker 环境自动 skip」；M4 独立验收 MEDIUM 整改 |
| 容器档（确定性关闭） | `-Dduo.docker.enabled=false` 强制 `dockerAvailable()==false`——CI 回归 job 用它让「零 Docker 依赖」成为**确定事实**，而非「恰好这台机器没 Docker」 | M7 / T8「skip 必须可见、可解释」 |
| 压测 | `-Dduo.scale=true` 显式触发（缺省 skip） | M4 计划 D2「压测不进常规回归」 |

**新增测试的纪律**：skip 必须可解释、可复算，且**安全属性不能只被门控覆盖**（容器档守卫用例的教训）。

---

## 4. 扩展点操作步骤

### 4.1 新增一个档位实现

以「为 `registry` 契约新增 `XxxRegistry`（embedded 档的另一种实现）」为例：

1. **实现组件**：`implements VirtualComponent`（生命周期），如需实例级能力再加 `InstanceControl`，
   如需故障注入再加 `FaultInjectable`。
2. **实现契约接口**（如 `RegistryContract`）——**仅 direct 路径需要**；纯 wire 实现可跳过。
3. **写 Provider**：`implements ComponentProvider`，`contract()/tier()/implName()/isDefault()/metadata()/newComponent()`。
   - 同 `(contract, tier)` 已有一个 `default: true` 时，新实现必须 `isDefault() == false`，
     并在场景里用 `impl: <implName>` 显式指定。
4. **声明元数据**：`endpointShape` / `interfaceDirect` / `instanceControl` / `supportedFaults` / `defaultImpl`。
   ⚠️ 三条注册期一致性校验会**在注册时**拒绝元数据与实现不符的 Provider（§7.5）。
5. **登记 SPI**：把全限定类名加进该模块的 `src/main/resources/META-INF/services/io.duo.sim.kernel.spi.ComponentProvider`。
6. **测试 + 文档**：单测（契约语义、生命周期、故障、停止语义）+ 场景级验收；更新
   [README 契约矩阵](../README.md#6-契约--档位当前已实现的实现清单) 与 [DSL 组件配置键](SCENARIO-DSL.md#7-内置组件-config-键参考)。

### 4.2 新增一个契约

契约按「**每个新契约才泛化一次接口**」的 YAGNI 节奏引入（§5）：

1. `Contract` 枚举加值；判断是否**交互型**（`isInteractive()`）——交互型 virtual 档走 Duo 线协议。
2. 在 `duo-sim-kernel/.../contract/` 定义契约接口（行为语义、必发事件、配置项、可观测点四件事）。
3. 若为交互型：在 `duo-sim-protocol` 增报文（`DuoMessage` 的 `@JsonSubTypes` 注册）。
4. 实现至少一个档位（见 §4.1），并让 `ScenarioValidator` 的交互型档位守卫（`InteractiveTierGuard`）覆盖新契约。
5. 金标准场景：**每个契约至少一个正例 + 一个故障例**（§13），进 CI。
6. 更新 [架构文档 §5](ARCHITECTURE.md#5-契约与档位) 与 README 契约矩阵。

### 4.3 新增一个故障动作

1. `FaultAction` 加常量。
2. 目标组件 `implements FaultInjectable`，在 `inject()`/`clear()` 中实现（`clear` 幂等）。
3. Provider 的 `supportedFaults` 加入该动作（否则注册期一致性校验失败/校验期拒绝）。
4. `ScenarioValidator` 规则 6 会自动覆盖（读 `supportedFaults`）——**无需改校验器**。
5. 需要事件与断言参与时，发 `sim.*` 事件（框架动作）或让 SUT 发 `sut.*` 事实。
6. 单测（幂等、未声明动作被拒、停止后注入语义）+ 场景级验证。
7. 更新 [DSL 动作表](SCENARIO-DSL.md#31-动作类型与实现状态)。

### 4.4 新增一个断言

1. 在 `kernel/assertion/Assertions.java` 加静态工厂，返回 `Assertion`（`name()` + `evaluate(List<Event>)`）。
   **语义必须钉死**：起点事件、成功判据、窗口边界（参照 `failoverWithin` 的注释）。
2. `AssertionParser` 加 `case`（YAML 形态）。
3. `ScenarioValidator` 的断言解析校验自动覆盖（未知断言名启动前失败）。
4. 可选：`DuoAssertions` 加编程式包装。
5. 单测必须包含**反例**（无起点事件 / 超窗 / 无受影响对象）——断言最怕「永远为真」。
6. 更新 [DSL 断言表](SCENARIO-DSL.md#6-断言-assertions)。

### 4.5 新增一个 DSL 字段

链路是**四段**，缺一段就会「写了不生效」：

```
Scenario.model（record 字段）
  → ScenarioLoader（解析 + 展平到 config）
  → ScenarioValidator（校验规则；该报错就报错，不静默）
  → ScenarioEngine / 组件（消费）
  → docs/SCENARIO-DSL.md（文档）
```

> 历史上的「写了不生效」正来自这条链路断在最后一段（如 `ready.timeout`、`logLines`、`jitter: 20%`），
> 见 [DSL 现状与偏差](SCENARIO-DSL.md#8-现状与设计偏差务必先读)。

---

## 5. 模块依赖纪律

```
protocol  ← components, examples
kernel    ← scenario, components, embedded, junit, control, examples
scenario  ← junit, control, examples
components/embedded/control/junit ← examples（唯一聚合点）
```

- **禁止环**。`kernel` 不得依赖 `scenario`（拓扑信息由 `WiringResolver.NodeView` 适配传入）。
- 新增第三方依赖：版本进父 POM `dependencyManagement`；**注意传递依赖的 JUnit 版本冲突**
  （`curator-test`、`kubernetes-server-mock` 都要排除 `junit-jupiter-api`/`junit-platform-commons`，
  否则 surefire 测试发现失败——这是踩过的坑）。
- `jackson-annotations` 在 `duo-sim-embedded` 中被显式钉到 `2.18.2`：Testcontainers → docker-java 会传递 `2.10.3`，
  压过 Fabric8 mock 需要的 `2.17+`（`JsonKey` 缺失 → `NoClassDefFoundError`）。

---

## 6. 编码与文档约定

1. **注释写「为什么」而非「是什么」**，且引用设计文档章节（`§7.2`）与计划/提交号（`T18`、`016914f`）。
   现有代码大量使用「修复了哪个真实缺陷」的注释——**保留这种风格**，它是防回归的知识资产。
2. **不可变优先**：`record` + `Map.copyOf`/`List.copyOf`；跨线程集合显式选型（`CopyOnWriteArrayList` 用于只追加事件流）。
3. **无降级路径**：能力不足＝显式失败 + 一级事件，绝不静默改写语义（§7.2）。
4. **不静默**：错误要么抛（启动前/校验期），要么记事件 + 计入 `ScenarioResult`（运行期）。
5. **事件命名**：`sim.`＝框架事件、`sut.`＝SUT 事实；实例级 `sourceId` 用 `componentId-N`。
6. **测试命名**：`<被测行为><期望>`（如 `readyThenImmediateCrashIsNotStartupFailure`）；
   断言消息要能直接定位根因；**避免自旋等待**，用限时轮询（M4 教训：`onSpinWait` 预算在高负载下耗尽）。
7. **中文文档 + 中文提交信息**（仓库既有风格），提交信息包含：做了什么 + 实测结果 + 关键决策。

---

## 7. 提交与验收流程

1. **设计先行**：语义变更先改/新增计划文档（`docs/superpowers/plans/`），设计冻结稿不动（§[文档维护约定](README.md#4-文档维护约定)）。
2. **实现 + 单测**：按 §4 的扩展点步骤落地。
3. **全量回归**：`./mvnw -o -B test "-Dduo.docker.enabled=false"` 必须 BUILD SUCCESS，并记录**实测测试分布**（不要写估算值）。
4. **场景级验收**：金标准场景 + 断言通过；必要时跑 `scripts/duo-inject-demo.sh`。
5. **落验收记录**：`docs/superpowers/acceptance/YYYY-MM-DD-duo-mN-<主题>-record.md`，
   含验收标准对照表、实测数字、缺陷处置、限制说明。
6. **同步工程文档**：按 [文档索引的「何时需要改它」](README.md#2-工程文档) 一栏执行。
7. **提交**：提交信息里写明实测数字与对应提交号（如「实测全仓 258 测全绿」）。

---

## 8. 已知工程债（勿踩坑清单）

| # | 债 | 影响 | 计划 |
| --- | --- | --- | --- |
| 1 | ~~`mvnw.sh` 硬编码本机工具链路径，非标准 Wrapper~~ **已闭合（M7：标准 Wrapper，D6）** | — | ✅ |
| 2 | ~~无 CI 配置~~ **已闭合（M7：三 job + skip 可见性脚本）；待远端首跑取证** | — | ✅/待取证 |
| 3 | 无 `LICENSE` 文件（设计 §15 已选 Apache-2.0） | 法务状态不明确 | M7 |
| 4 | 无 logback 配置 | 「结构化日志」通道名存实亡 | M8 |
| 5 | 无 Prometheus 指标导出 | 观测三通道只落地两条 | M8 |
| 6 | ~~`external` SUT 引擎未实现~~ **已闭合（M6，D7/D9）** | — | ✅ |
| 7 | `ready.timeout` 已消费（M6 ✅）；`logLines` / `jitter: 20%` / `failAt: 60%` 仍断链 | 写了不生效或直接抛异常 | M5 |
| 8 | `custom-hook` 无法从引擎注入 `HookRegistry` | YAML 时间线用不了自定义钩子 | M5 |
| 9 | 压测产物在 `build/`（gitignore 覆盖）；**CI scale job 已上传为 artifact** | 本地仍不随提交留存 | M7 部分 ✅ |
| 10 | `build/verify-016914f/` 等一次性复验脚手架留在工作区（被 `.gitignore` 覆盖） | 历史遗留，可按需清理 | 随时 |

---

## 9. 排查手册

| 现象 | 可能原因 | 处置 |
| --- | --- | --- |
| Windows 上「零测试被发现」 | 缺 `disableClassPathURLCheck` | 检查父 POM surefire `argLine` |
| 测试发现失败 / `NoClassDefFoundError: JsonKey` | 传递依赖版本冲突（JUnit / jackson-annotations） | 见 §5 的排除与钉版本 |
| 场景报 `no implementation registered for X/Y` | 该档位实现不在 classpath | 给 `examples`（或你的模块）加模块依赖 + SPI 文件 |
| 场景校验失败但看不懂 | 按 [DSL 常见报错速查](SCENARIO-DSL.md#9-常见报错速查) 对照 | — |
| 心跳/行为剧本「配置不生效」 | **构建产物陈旧**（改的是源码，跑的是旧 jar） | `./mvnw -o install -DskipTests`，或单模块用 `-pl X -am`（不带 `-am` 会解析旧 jar） |
| CLI 报 `ConnectException` / 端口占用 | `serve` 未就绪或遗留进程占端口 | `--port 0` 让内核分配；清理遗留 JVM |
| 录制文件被覆盖 | 两个运行共用场景名 | 并发运行同一场景需改名 |
