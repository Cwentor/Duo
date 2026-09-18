# duo-sim-examples

**参考实现 + 场景 + 全部验收测试**——既是示例，也是本项目的验收载体。

- 依赖：`kernel`、`protocol`、`components`、`scenario`、`embedded`、`control`、`junit`、`curator-framework`
- 测试：41 条，其中 **1 条压测用例未开 `-Dduo.scale` 时按设计 skip**
  （57 → 41：`SchedulerStateMachine`/`DispatchSelector` 及其 18 条测试已迁入 `duo-sim-components`，两档 scheduler 同源）

## 参考实现

| 类 | 契约/档位 | 说明 |
| --- | --- | --- |
| `DemoScheduler` | `scheduler` / `real`（**参考 SUT**） | 实现 `SutMain`（阻塞 `run`）+ `ctx.onStop` 协作停止；真实调度状态机（DAG + 重试 + 失败转移 + 派发选择 + 崩溃转移）；经 `SutEventPublisher` 发布 `sut.*` 内部事实。**仅用于验收，不是产品代码** |
| `DemoRealWorker` | `worker` / `real`（kernel-hosted） | 讲 Duo 线协议的真实 worker；M0 档位切换验收的 `real` 档；与 `VirtualWorker` 同构的**满载显式拒绝**（`TaskStatus.REJECTED`）与槽位即时上报 |

> 调度状态机（`SchedulerStateMachine`：DAG 依赖编排、`onInstanceLost` 崩溃转移、**派发被拒重排**
> 与 `MAX_REJECTIONS` 兜底）与 `DispatchSelector`（最大空闲优先、平局轮转）已于 M5 第 4 轮
> **移入 [`duo-sim-components`](../duo-sim-components/README.md)**（`io.duo.sim.components.scheduler`）：
> `examples` 不能反向被 `components` 依赖，而两档 scheduler 必须共用同一份语义。

SPI 注册：`DemoSchedulerProvider`、`DemoRealWorkerProvider`。

## 场景文件

| 路径 | 用途 |
| --- | --- |
| `src/main/resources/scenarios/m0-acceptance-{virtual,real}-workers.yaml` | M0 档位切换（两份仅 `workers.tier` 不同） |
| `src/main/resources/scenarios/m1-failover-acceptance.yaml` | M1 金标准（4 并行任务 + crash/registry-flap/restart + 4 断言） |
| `src/main/resources/scenarios/m3-inject-demo.yaml` | M3 热注入（`timeline: []`） |
| `src/test/resources/scenarios/m2-reelection-acceptance.yaml` | M2 金标准（embedded ZK 双路径 wiring + 重选主） |
| `src/test/resources/scenarios/m5-custom-hook-acceptance.yaml` | M5 金标准（时间线 `custom-hook` → 用户 hook → `eventSequence` 断言） |
| `src/test/resources/scenarios/m5-new-contracts-acceptance.yaml` | M5 第 4 轮：新契约（engine/filestore/message/resource）同场景起齐 + 三个故障动作从时间线注入（`eventSequence` + `noTaskLost`） |
| `src/test/resources/scenarios/junit-extension-smoke.yaml` | `@VirtualCluster` 冒烟 |
| `src/test/resources/scenarios/scale-{1k,10k}.yaml` | M4 压测（`-Dduo.scale=true`） |

## 验收测试

| 测试 | 覆盖阶段 |
| --- | --- |
| `TierSwapAcceptanceTest` | M0：同拓扑换档、测试代码零改动 |
| `FailoverAcceptanceTest` | M1：时间线注入 → 故障转移 → 断言驱动等待 |
| `ReelectionAcceptanceTest` | M2：注册中心闪断 → 重新选主 → 无任务丢失 |
| `ControlPlaneAcceptanceTest` / `ScenarioHostTest` / `RestControlServerTest` / `DuoCliTest` | M3：REST/CLI 热注入、错误映射、拓扑视图 |
| `ScaleAcceptanceTest` | M4：千/万 Worker 心跳压测（门控） |
| `ExternalSutAcceptanceTest` | **M6：不可改码第三方 SUT 端到端**（零依赖 `FakeThirdPartySut.java` 代起 → 端点告知双途径 → ready → 时间线注入 → 旁路断言 → 结束不杀进程；另含正常退出/崩溃两例） |
| `CustomHookAcceptanceTest` | **M5：YAML 时间线驱动用户自定义 hook**（`m5-custom-hook-acceptance.yaml` 调 `scale-out` → hook 发 `sut.hook-scale-out` → YAML `eventSequence` 断言；未注册名＝显式 injectionFailure） |
| `NewContractsAcceptanceTest` | **M5 第 4 轮：新契约 + 三个故障动作同场景**（`m5-new-contracts-acceptance.yaml`：engine/filestore/message/resource 四契约起齐 → 时间线注入 resource-exhaust/freeze/slow → 三个事实按序落流 + `noTaskLost`；另含非法 `slow` 倍数显式拒绝） |
| `VirtualClusterExtensionTest` | JUnit 扩展生命周期 |

```bash
./mvnw -o -pl duo-sim-examples -am test                       # 常规（含 1 条 skip）
./mvnw -o -pl duo-sim-examples -am test -Dtest=ScaleAcceptanceTest "-Dduo.scale=true"   # 压测
./mvnw -o -pl duo-sim-examples -am test -Dtest=ExternalSutAcceptanceTest                # M6 端到端
```

→ [验收记录](../docs/superpowers/acceptance/) · [压测报告](../docs/superpowers/acceptance/2026-09-15-duo-m4-scale-report.md)
