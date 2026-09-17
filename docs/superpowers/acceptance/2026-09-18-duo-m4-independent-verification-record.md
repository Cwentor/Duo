# M4 规模判据独立复验记录（压测数据链核对 + 容器档 HIGH 整改）

- 日期：2026-09-18
- 范围：M4 独立验收报告（2026-09-18）中的规模判据核对与缺陷处置取证
- 性质：规模数据为**核对既有产物**（未重跑万档，理由见 §3）；缺陷处置为本轮实际改动

---

## 1. 核对对象与产物快照

| 产物 | 路径 | 大小/时间 |
| --- | --- | --- |
| 千档指标 | `duo-sim-examples/build/scale/scale-heartbeat-1k.json` | 2026-09-15 12:46 |
| 万档指标 | `duo-sim-examples/build/scale/scale-heartbeat-10k.json` | 2026-09-15 12:47 |
| 千档录制 | `duo-sim-examples/build/scenarios/scale-heartbeat-1k/events.jsonl` | 396,861 B / 3,246 行 |
| 万档录制 | `duo-sim-examples/build/scenarios/scale-heartbeat-10k/events.jsonl` | 3,267,280 B / 26,528 行 |

两个产物均位于 `.gitignore` 覆盖的 `build/` 下，**不是提交快照的一部分**，只能就地核对（见 §3 限制）。

## 2. 核对结果：指标 JSON ↔ 录制流逐项自洽 ✅

| 指标 | 千档 JSON | 千档录制流实测 | 万档 JSON | 万档录制流实测 |
| --- | --- | --- | --- | --- |
| 注册成功数 | 1000 | `sut.worker-registered` **1000** | 10000 | `sut.worker-registered` **10000** |
| 峰值吞吐 | 993.0/s | 末条 meter `ratePerSec` 990.8 | 9928.4/s | 末条 meter `ratePerSec` **9928.4**（逐位一致） |
| 累计心跳 | 29717 | 末条 meter `total` **29717** | 252624 | 末条 meter `total` **252624** |
| meter 窗口数 | 6 | `sut.heartbeat-meter` **6** | 6 | `sut.heartbeat-meter` **6** |
| 事件总数 | 3246 | 总行数 **3246** | 26528 | 总行数 **26528** |
| 末态 | 场景进行至 SUCCESS | 末行 `sim.scenario-finished` | 同 | 末行 `sim.scenario-finished` |

- **峰值口径说明**：JSON 的 `maxHeartbeatRatePerSec` 取 6 个窗口的**最大值**，末条 meter 只是最后一窗，
  二者不等属正常（千档 993.0 vs 990.8；万档末窗恰为峰值 9928.4）。两条链均无矛盾。
- **采样率生效**：千档 `sut.heartbeat` 300 条（≈1000 feed × 30 窗 ÷ 100 采样），
  万档 254 条（≈10000 × 30 ÷ 1000）；`sut.heartbeat-meter` 均 6 条为真实计数源，未随采样失真。
- **规模线性**：10 倍实例 ≈ 10.0 倍吞吐（993.0 → 9928.4）、8.5 倍累计心跳（30s 窗口内注册爬坡占 10s）。

## 3. 未重跑万档的理由与由此产生的限制

本机无 Docker、`mvnw.sh` 为 bash 脚本、压测门控 `-Dduo.scale=true` 需数分钟与 GB 级堆。
本轮**未重跑**，因此：

- 上述数字是**既有产物的自洽性核对**，不是新一次独立复现；
- 尤其是断言「运行窗口内无实例掉线」在录制流上**可通过**（见 §5），但这只能证明那次运行；
- 若需要一次真正的独立复现，应执行
  `bash -lc "./mvnw.sh -o -pl duo-sim-examples test -Dduo.scale=true"`（预计 ≥5 分钟、堆峰值 >1GB），
  并把新产物与本节表格逐项比对。**本轮未执行该命令**，记录在此以免与「已复现」混淆。

## 4. `sut.instance-lost` 噪声的定性（独立验收报告问题 5 / LOW）

万档录制流中 `sut.instance-lost` **8123 条**，全部：
- 归属 `workers-*`（无陌生注册名）；
- 时间戳集中在 **04:47:10.772996Z ~ 04:47:11.304950600Z（约 0.53s）**，
  即首条 `sut.dag-terminal`（04:47:10.726322Z）**之后**约 0.05s 起——正是场景收尾批量关闭连接的时刻。

按 `ScaleAcceptanceTest` 的既有口径（「只有早于首个 `sut.dag-terminal` 的丢失才算规模故障」）复算：

| 录制流 | 首个 `dag-terminal` 行号 | 其前 `instance-lost` 数 |
| --- | --- | --- |
| 千档 | 1320 | **0** |
| 万档 | 10273 | **0** |

→ 两档的「运行窗口无掉线」判据在录制流上**成立**，8123 条属**拆除期批量失联噪声**（既有注释已定性）。
独立验收报告中「陌生注册名（`bad-*`/`flap-*`）污染 `instance-lost`」的现象**不在本轮核对的两份规模产物中**
（那是其对抗探针自造连接的产物），故此处不背书也不否认其存在。

## 5. 未决项（需方向决策，本轮不改语义）

1. **陌生注册名归属**：master 对「从未被派发任务、且非拓扑实例名」的连接是否应发一级
   `sut.instance-lost`，还是应显式失败/忽略。当前实现为前者；改变属语义变更，需单独决策。
2. **`ControlPlaneAcceptanceTest` 的 `sleep(1000)`**：本轮已整改（见 §6）。
3. **`restart` 与 `supportedFaults` 的层次**：`ScenarioValidator` 对 `crash`/`restart` 显式豁免
   `supportedFaults`（§7.2 生命周期动作设计使然），故「哪些档位不支持 restart」**无法**用元数据表达，
   只能由组件自身守卫（本轮已为容器档补上）。若将来出现第二个「不支持 restart」的实现，
   应考虑把该能力显式化进 `CapabilityMetadata`，而不是散落在各实现里。

## 6. 本轮处置（对应独立验收报告的问题 1/2/3）

| 报告项 | 严重度 | 处置 | 证据 |
| --- | --- | --- | --- |
| 容器档 `restart()` 绕过能力守卫 → 换宿主端口 → wire 永久挂起 | HIGH | `ZookeeperContainerRegistry` 覆写 `restart()` 抛 `UnsupportedOperationException`（§7.2 无降级）；元数据注释同步明确 | `317e9af`；新增 `ZookeeperContainerRegistryGuardTest` 5 条**脱离 Docker 门控**，修复前实测 `expected UnsupportedOperationException but was NullPointerException` |
| 容器档「真容器往返」证据全部被 Docker 门控吃掉 | MEDIUM | 拆出纯元数据/守卫用例（不标 `@EnabledIf`），无 Docker 也能验证「不支持＝显式拒绝」 | 同上；embedded 模块 39 测 0 失败（容器档 4 条按 §13 仍 skip） |
| `ControlPlaneAcceptanceTest` 注入点靠 `sleep(1000)`，非断言 | MEDIUM | 新增 `awaitInFlightTaskOnWorkers2()`：轮询事件流断言 workers-2 上「已派发且未终态」才注入 | `6d44ccc`；该测试通过（21.41s） |

**未做**：固定宿主端口（`PortBinding`）——本轮选择「明确不支持重启」，而非「支持并保端口重建」；
若将来需要支持容器档 flap/restart，须先引入固定端口再覆写 `restart()`。
