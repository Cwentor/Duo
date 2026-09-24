# M4 规模判据独立复现记录（2026-09-24）+ 09-18 产物覆盖悬案收口

- 日期：2026-09-24
- 范围：M4 规模判据（千/万 Worker 心跳压测）的**真正独立复现**——兑现
  `2026-09-18-duo-m4-independent-verification-record.md` §3 的可复现承诺；并收口该记录遗留的
  「09-18 14:16 无文档压测重跑」悬案（见 §4）。
- 性质：**实跑**（区别于 2026-09-18 那轮的「只核对未重跑」）；同日另对同一快照做了
  整仓回归与 TDD RED 复核（见 §6）。

## 0. 执行状态与忠实性说明

- 仓库 HEAD 已前进至 `9ad13ca`（M4 之后 47 个提交：M5–M8、安全审计等）。本轮按 2026-09-18
  记录 §3 的可复现命令，**在其所指的 M4 快照 `d52ba4b` 上以 `git worktree` 独立检出执行**，
  主仓库零接触——现存 `build/` 证据产物尤其未被触碰（为避免重蹈 §4 的第三次覆盖，
  压测在 worktree 内运行，产物先归档、后清理 worktree）。
- 命令（与记录 §3 原文的差异仅限构建方式，不改变压测语义与断言）：

  ```bash
  # 在 d52ba4b 的 worktree 内
  bash mvnw.sh -o test -pl duo-sim-examples -am \
    -Dtest=ScaleAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false -Dduo.scale=true
  ```

  （记录原文为 `./mvnw.sh -o -pl duo-sim-examples test -Dduo.scale=true`；本轮补 `-am`
  从快照源码构建上游模块、`-Dtest` 只跑压测类——`ScaleAcceptanceTest` 的全部断言原样执行。）
- 环境：Windows / JDK 21.0.12.1 / Maven 3.9.11 wrapper（`-o` 离线）/ 物理内存 15.8 GB、
  surefire 缺省堆 / 无 Docker（压测为虚拟档，不依赖 Docker）。

## 1. 结果总览：BUILD SUCCESS，2/2 通过

```
[INFO] Running io.duo.sim.examples.acceptance.ScaleAcceptanceTest
[scale] scale-heartbeat-1k:  workers=1000  ramp=1s  wall=31s maxHbRate=994/s  totalHb=29727  events=3328 heapDelta=126MB  gcTimeDelta=48ms
[scale] scale-heartbeat-10k: workers=10000 ramp=10s wall=31s maxHbRate=9963/s totalHb=252332 events=25063 heapDelta=572MB gcTimeDelta=403ms
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 62.78 s
[INFO] BUILD SUCCESS   Total time:  01:16 min
```

断言全部通过：注册全量 1000/10000、**运行窗口内零实例掉线**、峰值速率 ≥50% 理论值
（实际 994/9,962.6 ≈ 99%）、场景 pass、无注入失败。

## 2. 三轮产物对比与本轮实测细节

| 指标 | 09-15 原始（记录 §1/§2 声称，产物已失） | 09-18 14:16（实测，§4） | 09-24 本轮（实测） |
| --- | --- | --- | --- |
| 千档 / 万档事件行数 | 3,246 / 26,528 | 3,266 / 24,104 | **3,328 / 22,695** |
| 千档 / 万档峰值 /s | 993.0 / 9,928.4 | 997.2 / 9,953.0 | **994.0 / 9,962.6** |
| 千档 / 万档累计心跳 | 29,717 / 252,624 | 29,815 / 252,914 | **29,727 / 252,332** |
| meter 窗口数 | 6 / 6 | 6 / 6 | **6 / 6** |
| 万档 instance-lost 总数 | 8,123 | 6,910 | **6,207（落盘）/ ~8,575（内存口径，§3）** |
| 首 `dag-terminal` 前掉线 | 0 / 0 | 0 / 0 | **0 / 0** |
| instance-lost 陌生注册名 | 无 | 无 | **无** |
| 首 `dag-terminal` 行号（千/万） | 1,320 / 10,273 | 1,319 / 10,274 | **1,321 / 10,273** |

三轮数字同量级波动；判据口径（`ScaleAcceptanceTest` 既有断言）三轮全部成立。

本轮实测细节（供后续比对）：

| 项 | 千档 | 万档 |
| --- | --- | --- |
| 注册爬坡 / 运行墙钟 | 1s / 31s | 10s / 31s |
| meter 六窗 ratePerSec | 982.2, 990.6, 993.8, 994.0, 993.6, 991.2（max=JSON 峰值 ✓） | 2935.6, 7885.2, 9931.8, 9889.8, 9861.4, 9962.6（max=JSON 峰值 ✓，且末窗恰为峰值） |
| JSON↔流自洽 | `totalEvents`=3,328=行数 ✓；`totalHeartbeats`=29,727=末窗 `total` ✓；`meterEvents`=6 ✓ | `totalHeartbeats`=252,332=末窗 `total` ✓；`meterEvents`=6 ✓；**`totalEvents`=25,063≠行数 22,695（§3）** |
| 采样心跳（D3） | 253 条（1/100 采样） | 253 条（1/1000 采样） |
| 收尾 instance-lost | 1,000 条（=全部实例拆除失联），窗口 ~95ms，首条在首个 `dag-terminal` 后 ~27ms | 落盘 6,207 条，窗口 ~0.95s，首条在首个 `dag-terminal` 后 ~43ms；全部 `workers-*` |
| 堆（结束用量 / 增量） | ~150MB / 126MB | ~724MB / 572MB |
| GC 累计 | 48ms | 403ms |

## 3. 新发现：`totalEvents` 与落盘行数在万档脱钩（首次实测出现）

本轮万档 JSON `totalEvents=25,063` ≠ 落盘行数 **22,695**，差 **2,368**；千档两者相等。
前两轮万档均相等（26,528、24,104）——该现象为**首次实测出现**。

类型分解归因（差值全部为收尾 `sut.instance-lost` 尾部）：落盘 22,695 行中，
worker-registered 10,000 + instance-lost 6,207 + `sut.dag-terminal` 6,210 + meter 6 +
采样心跳 253 + 任务派发/终态 2+2 + `sim.*` 10，共 22,690 条已逐类型核实，余 5 条为
未逐项列数的系统事件；按 `totalEvents` 反推，内存流中 instance-lost ≈ 8,575，即
**flush 之后仍有 ~2,368 条收尾失联事件只进了内存流、未落盘**。

机制（`d52ba4b` 代码核实）：`ScenarioEngine.stop()` 的顺序是
`manager.stopAll()`（拆除期 master 侧仍在异步发布 instance-lost / dag-terminal）→
`sim.scenario-finished` → `recorder.flush()`（**此刻**对缓冲做整体快照写盘）；此后到达的
事件继续进入引擎内存流（`recorded`），但不再落盘。本轮落盘文件尾部即证据——末三条为
`sut.dag-terminal`（09:16:52.526355Z）→ `sut.instance-lost workers-6650`（同时刻）→
`sim.scenario-finished`（末行）。构造处 T21 注释「录制与内存流同一订阅点：保证两条流
事件数一致」仅对 **flush 时刻之前** 的事件成立。

影响与定性：

- **不影响规模判据**：判据断言基于内存流，且「首 `dag-terminal` 前零掉线」在落盘流上
  同样成立；
- 仅使「JSON `totalEvents` ↔ 落盘行数」这一核对项在重负载下不再可靠——历史两轮相等
  属 flush 赢得与拆除噪声的竞态，不是保证；
- `EventRecorder` javadoc 本身已声明录制是「审查材料，不承诺确定性逐字节重放」——
  本发现是该口径在落盘侧的注脚。若将来要求两流严格一致，应调整 `stop()` 中 flush
  的时机（等待事件沉降或 close 前二次 flush）；属产品小改进，**本轮不动**。

## 4. 悬案收口：2026-09-18 14:16 的无文档压测重跑

本节回答 2026-09-18 记录 §3「本轮未执行该命令」之后实际发生的事：

1. 时间线：`d52ba4b`（09-18 00:50，验证记录入库）→ `2a15234`（01:10）→
   `5b72753`（**13:29**，「有 Docker 环境复验暴露」D6 容器就绪正则缺陷 + testcontainers 升级）；
2. **同日 14:16:55 / 14:17:26，有人在主仓库直接重跑了压测**，覆盖了记录 §1 描述的
   09-15 12:46/12:47 产物（396,861 B / 3,246 行与 3,267,280 B / 26,528 行自此消失）。
   证据：四个产物的文件 mtime 与流内末条事件时间戳（`06:16:55.27Z` / `06:17:25.45Z`，
   UTC+8 后与 mtime 逐秒吻合）；
3. 该次重跑**没有任何文档记载**（验证记录与后续提交均未提及），无法从仓库历史归因到
   具体会话；随后 `8dc3b97`（15:45）起仓库转入 M6/M7 线；
4. 结论：2026-09-18 记录的「本轮未重跑」声明**在其所处时点为真**；但其 §1/§2/§4 的
   具体数字（3,246/26,528 行、29,717/252,624、993.0/9,928.4、8,123、0.53s 窗口等）
   **自产物被覆盖起不可复验**。其结构性结论在 14:16 产物上复算全部成立（本轮实测）：
   注册 1000/10000、meter 6/6、JSON↔流逐项相等、首 `dag-terminal` 前掉线 0/0、
   instance-lost 全部 `workers-*`（970 与 6,910 条，窗口分别 ~30ms 与 ~0.76s，
   均在首个 `dag-terminal` 后 12–15ms 起）——与原记录「收尾拆除批量失联噪声」的定性同构。

14:16 产物已整体归档至
`duo-sim-examples/build/scale-archive/2026-09-18-1416-undocumented-rerun/`
（含原始 mtime 的 MANIFEST）；本轮产物归档于同级
`duo-sim-examples/build/scale-archive/2026-09-24-independent-rerun-at-d52ba4b/`。
归档目录仍在 `.gitignore` 覆盖的 `build/` 下，与项目「产物不入库」口径一致——
但带日期的目录名不会被后续压测覆盖（这正是 §4 悬案的成因，本轮起改用）。

## 5. 限制

- 压测产物（JSON 与 events.jsonl）位于 `.gitignore` 覆盖的 `build/` 下，**不是提交快照**；
  本记录的数字以归档目录与 §2 表为准；
- 本轮在 M4 快照 `d52ba4b` 上执行；当前 HEAD（`9ad13ca`）自身的压测口径属后续里程碑的
  验收记录，不在本记录范围；
- `totalEvents` 与落盘行数的对照项受 §3 竞态影响，重负载下以内存口径（测试断言）为准。

## 6. 同日对同一快照的其他复核（背景）

- 整仓回归 `mvnw.sh -o clean test`（同一 worktree）→ **226 run / 0 失败 / 5 skip，
  BUILD SUCCESS 02:35 min**，逐模块分布与 2026-09-18 记录 §五修正栏一致
  （9+57+31+42+39(skip 4)+0+0+48(skip 1)）；
- TDD RED 复核：还原 `317e9af` 的 restart 守卫后，
  `restartOnContainerTierIsExplicitlyRejected` 以
  `expected UnsupportedOperationException but was NullPointerException` 失败
  （`ZkBackedRegistry.restart()` → `fire()` → 未初始化 `id`），恢复修复后 5/5 通过——
  该记录自称关键的 RED 证据可独立再现。
