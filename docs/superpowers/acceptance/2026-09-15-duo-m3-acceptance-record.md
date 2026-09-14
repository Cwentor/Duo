# Duo M3 验收记录 —— 控制面（REST/CLI 热注入）

- 日期：2026-09-15
- 依据：设计文档 §14 M3 行「运行中手动注入故障并观察自愈」；计划 `2026-09-14-duo-m3-control-plan.md` §1
- 结论：**验收通过**（自动化验收全绿 + CLI 演练两个模式成功）

---

## 1. 验收标准与证据对照

| # | 计划 §1 要求 | 证据 | 结果 |
| --- | --- | --- | --- |
| 1 | 场景运行中经 REST 注入 `crash workers[2]`，注入成功 | `ControlPlaneAcceptanceTest`：`POST /inject` 返回 200 且 `success:true` | 通过 |
| 2 | 事件流出现 `sim.fault-injected` | 同上：`host.eventsSince(0)` 断言含该事件 | 通过 |
| 3 | 任务在窗口内转移、`noTaskLost` 通过、场景 SUCCESS | 同上：`FINISHED` + `"passed":true` | 通过 |
| 4 | CLI 单命令完成「启动 + 注入 + 等待 + 打印结果」，退出码反映结果 | `scripts/duo-inject-demo.sh` 演示 1（退出码 0） | 通过 |
| 5 | 注入内核通路**零改动**（§10 纯外层包装） | 见 §3 内核改动审计 | 通过 |
| 6 | 极简拓扑视图（`GET /topology` + CLI 表格） | 演练演示 2 的 `duo topology` 输出；`ScenarioHostTest.topologyListsNodesWithTierAndHealth` | 通过 |

### 错误映射覆盖（计划 T33 判据）

| 码 | 含义 | 测试 |
| --- | --- | --- |
| 400 | 解析失败（坏 YAML / 坏 JSON / 坏 `since`） | `invalidYamlIs400WithReason`、`malformedJsonIs400`、`healthAndFullLifecycleRoundTrip` |
| 404 | 未知 target | `injectTargetUnresolvableIs404` |
| 405 | 方法不匹配 | `wrongMethodIs405` |
| 409 | 未启动 / 双启动 / 未运行注入 | `healthAndFullLifecycleRoundTrip` |
| 500 | 其余内部错误 | 由 `respond` 兜底（无独立用例） |

## 2. 实测结果

### 2.1 全量回归（整 reactor）

```
./mvnw.sh -o clean test
BUILD SUCCESS   Total time: 02:29 min
```

| 模块 | 测试数 | 失败 | 错误 |
| --- | --- | --- | --- |
| duo-sim-protocol | 9 | 0 | 0 |
| duo-sim-kernel | 54 | 0 | 0 |
| duo-sim-scenario | 31 | 0 | 0 |
| duo-sim-components | 42 | 0 | 0 |
| duo-sim-embedded | 30 | 0 | 0 |
| duo-sim-junit | 0（扩展集成测试在 examples） | 0 | 0 |
| duo-sim-control | 0（测试在 examples，避免模块循环） | 0 | 0 |
| duo-sim-examples | 47 | 0 | 0 |
| **合计** | **213** | **0** | **0** |

### 2.2 CLI 演练（`bash scripts/duo-inject-demo.sh`，退出码 0）

**演示 1 —— 同进程模式（单命令热注入）**

```
$ duo run duo-sim-examples/src/main/resources/scenarios/m3-inject-demo.yaml \
      --inject-after 3s "crash workers[2]" --wait
[inject-after] 3000ms 后注入 crash workers[2]
[inject-after] OK
{state=FINISHED, scenario=m3-inject-demo, injectionFailures=0,
 assertions=[{affectedTasksAtLeast: 1 affected task(s), passed=true},
             {failoverWithin: 1 task(s) failed over within 30s, passed=true},
             {noTaskLost: 4 task(s) terminal, all SUCCESS, passed=true},
             {eventSequence: sequence matched: [sim.fault-injected, sut.task-retry], passed=true}],
 passed=true, events=902}
```

**演示 2 —— 跨进程模式（独立进程 + REST 热注入）**

```
$ duo serve m3-inject-demo.yaml --port 0 &
服务端就绪：http://127.0.0.1:59520

$ duo status --url http://127.0.0.1:59520
200 {"state":"RUNNING","scenario":"m3-inject-demo",...,"events":58}

$ duo topology --url http://127.0.0.1:59520
zk      registry/virtual   count=1  hosted=true   healthy=true
master  scheduler/real     count=1  hosted=false  healthy=-
workers worker/virtual     count=4  hosted=true   healthy=true

$ duo inject crash workers[2] --url http://127.0.0.1:59520
200 {"success":true,"reason":""}

$ duo events --since 0 --url http://127.0.0.1:59520 | grep -E 'fault-injected|task-retry'
148 sim.fault-injected [workers-2] {"action":"crash"}
150 sut.task-retry [sut] {"nextAttempt":2,"taskId":"job-a"}

$ duo status --url ... （轮询至 DAG 终态）
200 {"state":"FINISHED",...,"passed":true}

$ duo assert --url http://127.0.0.1:59520
200 {"passed":true,"assertions":[...4 条全 true...],"injectionFailures":[]}
```

两条关键事实链完整：`sim.fault-injected` → `sut.task-retry`（任务在**运行中**被热注入打断并重派发），
且 `noTaskLost` 证明 4 个任务全部终态 SUCCESS（无任务丢失）。

## 3. §10 内核改动审计（计划 §1 前置要求）

自 T32 开工（`68ad9f5`）至本次验收，`duo-sim-kernel` / `duo-sim-scenario` /
`duo-sim-protocol` 的改动仅有一处：

- `SutLauncher.java`（提交 `016914f`）——**M2 遗留缺陷修复**（ready/crash 竞态），
  由 T34 全量回归暴露，与 M3 控制面功能无关，未新增任何控制面所需的内核能力。

M3 全部实现位于 `duo-sim-control`，只消费既有公开 API
（`ScenarioEngine` / `ScenarioRuntime` / `ScenarioResult` / `ContractRegistry`），
未新增或修改任何内核符号 → **§10「纯外层包装」成立**。

## 4. 遗留与说明

- **事件序号语义（计划 D3）**：序号在控制面侧按读取顺序分配，保证同一快照内不重不漏；
  跨快照的严格全序不承诺（与设计文档 §11「真实时钟不承诺确定性重放」一致）。
- **`duo serve` 自动固化结果**：SUT 自行退出时由后台虚拟线程调用 `awaitFinish` 评估断言，
  客户端只需轮询 `status`，无需主动触发停止。
- **录制文件路径**：演示两个模式共用场景名，故 `build/scenarios/m3-inject-demo/events.jsonl`
  为后运行者覆盖（顺序执行无影响；并发运行同一场景需改名）。
