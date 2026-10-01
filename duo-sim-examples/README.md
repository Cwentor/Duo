# duo-sim-examples

**参考实现 + 场景 + 跨模块验收测试**——既是示例，也是本项目的验收载体（`duo-sim-control` 第 14 轮起自带契约测试，见其 README）。

- 依赖：`kernel`、`protocol`、`components`、`scenario`、`embedded`、`control`、`junit`、`curator-framework`
- 测试：54 条，其中 **2 条按设计 skip**（未开压测开关：`ScaleAcceptanceTest`；未开 `-Dduo.ds=true`：`DsFailoverAcceptanceTest`，M9 真实 SUT 演练）；2026-10-01 实测。
  （57 → 41 → 54：`SchedulerStateMachine`/`DispatchSelector` 及其 18 条测试已迁入 `duo-sim-components`，两档 scheduler 同源；其后 M6/M8/M9 又各补入验收用例）

## 参考实现

| 类 | 契约/档位 | 说明 |
| --- | --- | --- |
| `DemoScheduler` | `scheduler` / `real`（**参考 SUT**） | 实现 `SutMain`（阻塞 `run`）+ `ctx.onStop` 协作停止；真实调度状态机（DAG + 重试 + 失败转移 + 派发选择 + 崩溃转移）；经 `SutEventPublisher` 发布 `sut.*` 内部事实。**仅用于验收，不是产品代码** |
| `DemoRealWorker` | `worker` / `real`（kernel-hosted） | 讲 Duo 线协议的真实 worker；M0 档位切换验收的 `real` 档；与 `VirtualWorker` 同构的**满载显式拒绝**（`TaskStatus.REJECTED`）与槽位即时上报 |
| `RealWorkerSut` | `worker` 侧**真实 SUT**（第 32 轮，提交 `306c817`） | **反差方向**：前面几档是「用真 worker 当组件、拿调度器当 SUT」，这里是**把 worker 当 SUT**——worker 自己在 master 侧注册/心跳/领取/执行/回报，master 变成被测对象。启动入口 `io.duo.sim.examples.worker.RealWorkerSut`，配置由 `SutConfigs` 提供；发现 master 支持显式端点 / `master.list` / registry 自注册三条路径 |

> 调度状态机（`SchedulerStateMachine`：DAG 依赖编排、`onInstanceLost` 崩溃转移、**派发被拒重排**
> 与 `MAX_REJECTIONS` 兜底）与 `DispatchSelector`（最大空闲优先、平局轮转）已于 M5 第 4 轮
> **移入 [`duo-sim-components`](../duo-sim-components/README.md)**（`io.duo.sim.components.scheduler`）：
> `examples` 不能反向被 `components` 依赖，而两档 scheduler 必须共用同一份语义。

SPI 注册：`DemoSchedulerProvider`、`DemoRealWorkerProvider`。

## 场景文件

| 路径 | 用途 |
| --- | --- |
| `src/main/resources/scenarios/m0-acceptance-{virtual,real}-workers.yaml` | M0 档位切换（两份仅 `workers.tier` 不同） |
| `src/main/resources/scenarios/m0-acceptance-real-worker-sut.yaml` | 第 32 轮：**SUT 落在 worker 侧**（master 是内核 virtual 档 `VirtualScheduler`，SUT 是 `RealWorkerSut`） |
| `src/main/resources/scenarios/m1-failover-acceptance.yaml` | M1 金标准（4 并行任务 + crash/registry-flap/restart + 4 断言） |
| `src/main/resources/scenarios/m3-inject-demo.yaml` | M3 热注入（`timeline: []`） |
| `src/test/resources/scenarios/m2-reelection-acceptance.yaml` | M2 金标准（embedded ZK 双路径 wiring + 重选主） |
| `src/test/resources/scenarios/m5-custom-hook-acceptance.yaml` | M5 金标准（时间线 `custom-hook` → 用户 hook → `eventSequence` 断言） |
| `src/test/resources/scenarios/m5-new-contracts-acceptance.yaml` | M5 第 4 轮：新契约（engine/filestore/message/resource）同场景起齐 + 三个故障动作从时间线注入（`eventSequence` + `noTaskLost`） |
| `src/test/resources/scenarios/junit-extension-smoke.yaml` | `@VirtualCluster` 冒烟 |
| `src/test/resources/scenarios/m5-message-contract-acceptance.yaml` | M5 交付物 6：message 契约正例（发布→订阅观察→拉取消费 + `sim.message-published`）/ 故障例（`freeze` 期间发布显式抛错、存量不丢） |
| `src/test/resources/scenarios/m9-ds-failover.yaml` | M9 Phase A：DolphinScheduler 3.4.3 registry-flap 端到端（`-Dduo.ds=true` 门控） |
| `src/test/resources/scenarios/scale-{1k,10k}.yaml` | M4 压测（`-Dduo.scale=true`） |

## 验收测试

| 测试 | 覆盖阶段 |
| --- | --- |
| `TierSwapAcceptanceTest` | M0：同拓扑换档、测试代码零改动 |
| `FailoverAcceptanceTest` | M1：时间线注入 → 故障转移 → 断言驱动等待 |
| `ReelectionAcceptanceTest` | M2：注册中心闪断 → 重新选主 → 无任务丢失 |
| `ControlPlaneAcceptanceTest` / `DuoCliTest` | M3：REST/CLI 热注入、错误映射、拓扑视图（`ScenarioHostTest` / `RestControlServerTest` 第 14 轮起已迁入 `duo-sim-control/src/test`，不再在本模块） |
| `ScaleAcceptanceTest` | M4：千/万 Worker 心跳压测（门控） |
| `ExternalSutAcceptanceTest` | **M6：不可改码第三方 SUT 端到端**（零依赖 `FakeThirdPartySut.java` 代起 → 端点告知双途径 → ready → 时间线注入 → 旁路断言 → 结束不杀进程；另含正常退出/崩溃两例） |
| `CustomHookAcceptanceTest` | **M5：YAML 时间线驱动用户自定义 hook**（`m5-custom-hook-acceptance.yaml` 调 `scale-out` → hook 发 `sut.hook-scale-out` → YAML `eventSequence` 断言；未注册名＝显式 injectionFailure） |
| `NewContractsAcceptanceTest` | **M5 第 4 轮：新契约 + 三个故障动作同场景**（`m5-new-contracts-acceptance.yaml`：engine/filestore/message/resource 四契约起齐 → 时间线注入 resource-exhaust/freeze/slow → 三个事实按序落流 + `noTaskLost`；另含非法 `slow` 倍数显式拒绝） |
| `VirtualClusterExtensionTest` | JUnit 扩展生命周期 |
| `WorkerSutAcceptanceTest` | **worker 侧真实 SUT `RealWorkerSut`**（真连 master：显式端点 / `master.list` / registry 自注册三条发现路径 → 心跳 → 领取/执行/回报 → 断连自愈） |
| `MessageContractAcceptanceTest` | **M5 交付物 6：message 契约正例/故障例成对**（`m5-message-contract-acceptance.yaml`：发布→订阅→消费 + `freeze` 期发布显式拒绝、存量消息不丢、解冻恢复） |
| `MetricsEndpointAcceptanceTest` | **M8：`/metrics` 指标端点**（Prometheus 文本格式合法 + 计数单调不重复 + 故障注入/断言结果在指标里可见；不引入 Prometheus 客户端） |
| `FaultDiagnosticsAcceptanceTest` / `FaultCausalChainLoggingTest` | **M8：故障因果链**（`/diagnose` 四段齐全 + 断链反例必须报错非 0；日志侧钉 `io.duo.sim.fault` 记录器确有事件） |
| `DsFailoverAcceptanceTest` | **M9 Phase A：真实 DolphinScheduler 3.4.3** registry-flap 端到端（`-Dduo.ds=true` 门控，缺省可见 skip） |
| `DsFailoverDrillGuardTest` | M9 常驻守卫（**无门控**，进常规回归）：钉 `m9-ds-failover.yaml` 模板语义契约 |
| `ZkSchedulerDiscoveryTest` | **档位互通守卫**：real 档 SUT 写入 ZK 的端点必须被 virtual 档 scheduler 的 registry 门面发现（`TierSwapAcceptanceTest` 从未覆盖 real SUT × virtual scheduler 这个组合） |
| `SecurityRemediationAcceptanceTest` | 安全审计 2026-09-20 整改的运行时验收：把审计报告的 PoC **真的发出去**，断言本机无副作用（文件不存在、响应体不含绝对路径） |

```bash
./mvnw -o -pl duo-sim-examples -am test                       # 常规（含 2 条 skip：压测门控 + M9 真实 SUT 门控）
./mvnw -o -pl duo-sim-examples -am test -Dtest=ScaleAcceptanceTest "-Dduo.scale=true"   # 压测
./mvnw -o -pl duo-sim-examples -am test -Dtest=ExternalSutAcceptanceTest                # M6 端到端
./mvnw -o -pl duo-sim-examples -am test -Dtest=DsFailoverAcceptanceTest "-Dduo.ds=true" # M9 真实 DS 演练（需按 DEVELOPMENT §1.3 备环境）
```

→ [验收记录](../docs/superpowers/acceptance/) · [压测报告](../docs/superpowers/acceptance/2026-09-15-duo-m4-scale-report.md)
