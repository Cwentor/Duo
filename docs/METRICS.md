# Duo 指标口径（M8 观测面）

> 本文是 `/metrics` 的**口径契约**：每个指标的名字、类型、语义、刷新时机与已知边界。
> 口径与实现同源——`MetricsCollector` 是本文件描述的唯一实现，改口径必须同时改这里。

## 1. 端点

| 项 | 值 |
| --- | --- |
| 路径 | `GET /metrics`（`RestControlServer`；非 GET → `405`） |
| Content-Type | `text/plain; version=0.0.4; charset=utf-8`（Prometheus 文本格式） |
| 可抓取时机 | **始终可读**（场景 `IDLE`/`RUNNING`/`FINISHED`/`FAILED` 都返回 200）。与 `/scenario/status` 不同——后者在场景未启动时返回 `409`，会让 Prometheus target 反复掉线 |
| 其它入口 | `duo metrics`（同进程直读）、`duo metrics --summary`（单行摘要，人读用） |
| 认证 | 无（与其余控制面端点一致：面向本机/受控网络，生产部署需自行加前置代理） |

## 2. 设计原则

1. **薄只读层，零内核改动**（§10）：指标不改变内核行为，只消费**既有事件流**。
   指标口径因此天然与断言、录制文件同源——三者看到的是同一批事实。
2. **游标消费，抓取不丢不重**：`MetricsCollector` 持有事件游标，每次抓取只消费新增事件。
   重复抓取不会重复计数；两次抓取之间到达的事件会在下一次抓取时全部计入。
3. **只用 `counter`/`gauge`**：不输出 histogram/summary——分桶口径需要先有明确的时延 SLO，
   拍脑袋的分桶比没有更糟（TODO 见 §5）。
4. **计数不随进程内场景重启归零**：进程级计数器跨场景累加（Prometheus 的 `rate()` 依然正确，
   因为它是单调递增序列）。需要"本次场景的增量"请用 `delta(duo_injections_total[...])`。

## 3. 指标清单

### 3.1 存活与场景状态

| 指标 | 类型 | 语义 |
| --- | --- | --- |
| `duo_up` | gauge | 恒为 `1`；进程存活即存在（配合 `up` 做 target 判活） |
| `duo_scenario_running` | gauge | 场景处于 `RUNNING` 为 `1`，其它状态 `0` |
| `duo_scrapes_total` | counter | 本进程被成功抓取的次数（含本次） |
| `duo_events_total` | counter | 已消费的**事件流总条数**（含已在场景前产生的事件） |

### 3.2 事件分类

| 指标 | 类型 | 语义 |
| --- | --- | --- |
| `duo_events_by_type_total{type="…"}` | counter | 按事件类型分桶的条数；`type` 为事件流原始类型名（如 `sut.heartbeat`、`sim.fault-injected`） |
| `duo_heartbeat_events_total` | counter | `sut.heartbeat` 的条数（吞吐主指标） |
| `duo_sut_events_total` | counter | 全部 `sut.*` 事件条数 |
| `duo_sim_events_total` | counter | 全部 `sim.*` 事件条数 |

> **判读提示**：`duo_heartbeat_events_total` 的**增长率**才是吞吐；绝对值受抓取窗口影响。
> 心跳速率下降通常先于断言失败出现，是"要出问题"的早期信号。

### 3.3 故障注入

| 指标 | 类型 | 语义 |
| --- | --- | --- |
| `duo_injections_total` | counter | **成功下达**的注入次数（对应事件 `sim.fault-injected`） |
| `duo_injections_failed_total` | counter | 被拒绝的注入次数（对应事件 `sim.fault-inject-failed`） |
| `duo_injection_failures_reported` | gauge | 场景结果快照里记录的注入失败条数（收口视角，与上面的事件视角互补） |

> 两个视角**都要看**：事件视角逐次发生（可用于告警），快照视角在场景结束后归纳
> （可用于报告）。若注入被持久化失败，`sim.cluster-degraded` 亦会出现在
> `duo_events_by_type_total` 里用于告警。

### 3.4 断言

| 指标 | 类型 | 语义 |
| --- | --- | --- |
| `duo_assertions_total` | gauge | 已评估的断言条数（场景未结束时为 `0`） |
| `duo_assertions_passed` | gauge | 通过的条数 |
| `duo_assertions_failed` | gauge | 失败的条数 |

> 断言只在场景收口后才有值：`duo_scenario_running == 0 && duo_assertions_failed > 0`
> 是 CI/值班最直接的一条告警表达式。

### 3.5 拓扑与健康

| 指标 | 类型 | 语义 |
| --- | --- | --- |
| `duo_components_hosted` | gauge | **本进程承载**的组件数（`hosted=true` 的拓扑节点数） |
| `duo_component_instances` | gauge | 拓扑声明的实例总数（含 real 档 SUT） |
| `duo_components_unhealthy` | gauge | `health().healthy()==false` 的组件数 |
| `duo_endpoints_online` | gauge | 在线端点数（按拓扑节点累加） |

> **`hosted` 的口径**：只有**进程内**组件算 hosted。real 档 SUT 由外部进程承载，
> 因此 `duo_components_hosted` 会小于节点数——这不是缺陷，是档位语义
> （见 `TopologyAcceptanceTest` 与 M8 验收记录里的实测：3 节点拓扑下 `hosted=2`、
> `instances=6`）。

## 4. 已知边界（如实记录）

- 计数器**不区分场景**：同一进程跑第二个场景时计数继续累加，无法从指标反推单场景数值
  （用事件流或 `duo diagnose` 做单场景归因）。
- 指标只覆盖**事件流能观察到的事实**：进程内部态（如线程池队列深度）不在此列。
- 无直方图：分位数需另行接入（§5）。
- `/metrics` 无鉴权（与既有控制面一致）。

## 5. 后续可扩充项（未做，非缺失）

| 项 | 前置条件 |
| --- | --- |
| 任务时延直方图（`sut.task-terminal` 的 `dispatched→terminal` 差值） | 需先定分桶口径（SLO 未定，分桶无依据） |
| SUT 侧队列深度 gauge | 需要 SUT 主动上报（当前 `sut.*` 无该事实） |
| 按场景维度的标签（`scenario="…"`） | 需要决定"多场景共存"是否成为产品形态；当前一进程一场景 |
