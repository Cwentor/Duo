# Duo M4 验收记录 —— 规模与桥接

- 日期：2026-09-15
- 计划：`docs/superpowers/plans/2026-09-15-duo-m4-scale-bridge-plan.md`
- 依据：设计文档 v1.0（冻结）§14 M4 行、§16 风险 1、§17 开放问题 1/2
- 结论：**M4 完成**（四项交付：万级心跳压测报告 / Testcontainers 桥 / 加速时钟评估 / 第三方适配器决策）

---

## 一、验收标准核验（§14 M4：「千~万 Worker 心跳压测报告」）

| 计划 §1 要求 | 验证方式 | 结论 |
| --- | --- | --- |
| 千档压测完整闭环（注册→心跳→槽位→测量） | `-Dduo.scale=true` 跑 `ScaleAcceptanceTest[1]`（scale-heartbeat-1k） | 通过：注册 1000/1000、爬坡 1s、稳态 993 HB/s（理论 99.3%） |
| 万档同一场景改 count 即可运行 | `ScaleAcceptanceTest[2]`（scale-heartbeat-10k，与 1k 仅差 count/采样率） | 通过：注册 10000/10000、爬坡 10s、稳态 9,928 HB/s（理论 99.3%） |
| 压测报告落盘（吞吐/注册/内存/GC/瓶颈） | `docs/superpowers/acceptance/2026-09-15-duo-m4-scale-report.md` + 指标 JSON（`duo-sim-examples/build/scale/*.json`） | 通过 |
| 压测不进常规回归（D2） | 未设 `-Dduo.scale` 时该类 skip 2 条 | 通过 |
| Testcontainers 桥（registry 容器档） | `ZookeeperContainerRegistry` + `ZookeeperContainerProvider`（(registry, container) 注册、SPI 登记）；`ZookeeperContainerRegistryTest` 4 条 | 通过（本机无 Docker：4 条按 §13 自动 skip；元数据/注册期校验离线验证） |
| 加速时钟评估 | 本文档 §三 D4 | 通过（结论：推迟，非 M4 交付） |
| 第三方 SUT 适配器决策（§17 开放问题 1） | 本文档 §三 D5 | 通过（结论：不绑定产品，按需立专项） |

**压测实测数字**：1k → 993/s、堆增量 114MB、GC 28ms；10k → 9,928/s、堆增量 836MB、GC 319ms；运行窗口实例掉线均为 0。吞吐随规模线性（×10 → ×10.0），万级长连接虚拟线程模型无每实例线程成本瓶颈。

## 二、任务完成情况

| # | 任务 | 落点 | 完成判据 | 结论 |
| --- | --- | --- | --- | --- |
| T36 | 心跳压测基建 | VirtualWorker：`heartbeat.interval.ms` 可配、连接+握手重试/超时、拨号错峰、离线事件；DemoScheduler：`heartbeat.eventSampleRate` 采样、`sut.heartbeat-meter` 吞吐事实、accept backlog 4096、per-connection 隔离；scale-1k/10k 场景 YAML | 千档跑通闭环、指标可采集 | 完成 |
| T37 | 压测执行与调优 | `ScaleAcceptanceTest`（门控 `-Dduo.scale`）+ 压测报告 | 报告落盘（含瓶颈分析与调优结论） | 完成 |
| T38 | Testcontainers 桥 | `ZkBackedRegistry`（双面骨架抽取）+ `ZookeeperContainerRegistry` + Provider + SPI + 测试（无 Docker skip） | 无 Docker 构建绿且 skip 可见；元数据校验通过 | 完成 |
| T39 | 开放问题收口 | 本文档 §三；全量回归 | 全绿 + 记录落盘 | 完成 |

## 三、开放问题收口（设计与计划的显式要求）

### D4：加速时钟（§17 开放问题 2）——评估结论：**推迟，不进 M4 交付**

- **价值前提不成立**：加速时钟的收益是缩短场景 wall-time，但 M4 的验收目标（万级心跳吞吐）恰恰需要真实时钟度量真实吞吐——虚拟时钟反而会掩盖被measure的瓶颈。
- **改造面覆盖全部 SUT 与真实后端**：现所有行为模型（TaskStub duration/jitter）与 SUT 主循环（demo-scheduler `Thread.sleep`）都挂真实时钟；而 embedded/container 档的真实第三方二进制（TestingServer ZK / 容器 ZK）的会话超时不可虚拟化，加速时钟对这两档天然失效。
- **无接口返工成本**：`kernel/api/SimClock` 已预留（M0），推迟不产生返工。
- **触发条件**：出现「小时级长稳场景」且目标档位为 virtual 时立项；届时需同时引入 SutContext 时间源注入与行为剧本时钟统一。

### D5：第三方 SUT 协议适配器（§17 开放问题 1 / §16 风险 1）——决策：**不绑定具体产品，维持 SPI 就绪**

- **无验收口径**：设计文档从未指定第三方产品与版本基线（开放问题 1 本身即「M4 前再定」）；凭空选型会产生无法验收的版本耦合。
- **接入要素已就绪**：`duo-sim-protocol` 协议工件独立成模块、内核公开 SPI（`ComponentProvider`/契约接口）、M0 的 demo real worker 已验证「第三方进程讲 Duo 协议」的接入路径。
- **触发条件**：出现真实第三方接入需求时立专项，版本基线在专项启动时钉死（专项范围＝该产品报文与 Duo 帧的翻译层 + 契约映射）。

## 四、过程中的关键发现（压测暴露并已修复）

1. **[高] DemoScheduler acceptor 可被单连接拖垮**：`register()` 在 accept 线程内串行且只捕 `IOException`——千档实测注册卡死 201/1000（~800 条已建立连接永远无人 accept，客户端无超时则永久挂起）。修复：per-connection 独立虚拟线程 + 注册阶段 10s 限时 + 失败发 `sut.register-failed`。
2. **[中] worker 连接失败不可观测**：实例离线静默无事件（正因如此首次卡死定位困难）。修复：重试耗尽发 `sim.worker-instance-offline`（含原因）；停止/代次更替中的正常收敛不发（避免收尾噪声）。
3. **[中] 万级并发握手洪峰**：1000+ 实例同时拨号致握手批量超时。修复：确定性拨号错峰（index×1ms 封顶 10s）+ 握手重试 ×3 + 连接超时 5s。
4. **[低] SutLauncherTest 自旋轮询在全仓负载下不稳定**：`readyThenImmediateCrashIsNotStartupFailure` 以 10,000 次 `onSpinWait` 作等待预算，高负载 JVM 下会先于线程调度耗尽（实测全仓回归第 198 轮失败）；改为限时轮询（5s，sleep 1ms）。**属测试等待预算问题，非产品竞态**——`exitState` 的写入是 `run()` 线程的保证动作（M3 报告修复后的语义不变）。

## 五、全仓回归

`./mvnw.sh -o clean test` → **BUILD SUCCESS**，02:33 min，测试分布：protocol 9 + kernel 57 + scenario 31 + components 42 + embedded 34（skip 4＝无 Docker 容器档）+ junit 0 + control 0 + examples 48（skip 1＝未开压测开关），合计 **221 run / 0 失败**。

跳过的 5 条均为设计明文门控（§13 容器档无 Docker 自动 skip；计划 D2 压测显式触发），非缺口：

- 4 条 `ZookeeperContainerRegistryTest`：本机无 Docker，`@EnabledIf` 按 §13 自动 skip（skip 路径本身即 D6 的验证目标）；元数据/注册期一致性校验在该类内为纯本地断言，有 Docker 环境即全跑。
- 1 条 `ScaleAcceptanceTest`：压测档由 `-Dduo.scale=true` 显式触发（实测数据见 §一 与压测报告）。

---

## 六、2026-09-18 独立验收后的整改与复验（本记录追加）

独立验收（2026-09-18）对 M4 给出**有条件通过**，两项需处置项与本记录相关的处置如下；
完整取证见 `docs/superpowers/acceptance/2026-09-18-duo-m4-independent-verification-record.md`。

| # | 独立验收结论 | 本轮处置 | 提交 |
| --- | --- | --- | --- |
| 1 | **[HIGH]** 容器档 `restart()` 绕过 `inject()`/`clear()` 守卫：`ZkBackedRegistry.restart()` 是 stop→start，Testcontainers 换宿主端口后 wire 客户端按 D2 复用旧端口永远连不上且无失败路径 → **永久挂起** | `ZookeeperContainerRegistry` 覆写 `restart()` 显式抛 `UnsupportedOperationException`（§7.2 无降级）；provider 元数据注释明确「`supportedFaults=∅` 不足以表达该约束（生命周期动作在校验期被 `ScenarioValidator` 豁免）」 | `317e9af` |
| 2 | **[MEDIUM]** 容器档守卫用例全被 `@EnabledIf(dockerAvailable)` 吃掉，无 Docker 时「不支持＝显式拒绝」这一安全属性无人验证 | 新增 `ZookeeperContainerRegistryGuardTest`（5 条，**不标** `@EnabledIf`）：元数据 + SPI 解析 + restart/flap 显式拒绝 + 守卫先于 Docker 访问 | `317e9af` |
| 3 | **[MEDIUM]** `ControlPlaneAcceptanceTest` 注入点靠 `sleep(1000)` 而非断言 | 改为 `awaitInFlightTaskOnWorkers2()`：轮询事件流断言 workers-2 上有「已派发且未终态」任务才注入 | `6d44ccc` |
| 4 | **[LOW]** M3 记录「合计 213」不可复算 | M3 记录 §2.1 已补快照语义说明（213＝M3 关闭时 `abdfc44` 一代；`7ac458b` → 219；`ed2f054` → 221） | 随本次提交 |

**规模判据复验**：本轮**核对**（未重跑）了两档指标 JSON 与录制流，逐项自洽——
注册 1000/10000、峰值 993.0/9928.4、累计 29717/252624、meter 6/6、事件总行数 3246/26528 全部吻合；
按本记录 §四.4 的既有断言口径复算，首个 `sut.dag-terminal` **之前**的 `sut.instance-lost` 两档均为 **0**。
**限制**：上述产物位于 `.gitignore` 覆盖的 `build/` 下，不是提交快照；本机未执行 `-Dduo.scale=true`，
故这是既存产物的自洽性核对，不是新一次独立复现。

> **修正（2026-09-18）**：§五「全仓回归」的 221 为 **`ed2f054`/`74dd0b1` 快照**；本轮整改后（`317e9af` + `6d44ccc`）
> 实跑 `./mvnw.sh -o clean test` → BUILD SUCCESS，02:41 min，分布协议 9 + kernel 57 + scenario 31 + components 42
> + embedded 39（skip 4） + junit 0 + control 0 + examples 48（skip 1）＝ **226 run / 0 失败 / 5 skip**，
> 与整改前 221 的差额正是新增的 `ZookeeperContainerRegistryGuardTest` 5 条。测试数随改动变化，
> 记录中的合计值一律按对应提交理解。

