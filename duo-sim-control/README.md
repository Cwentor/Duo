# duo-sim-control

**控制面**：REST + CLI 热注入（M3）。

- 依赖：`duo-sim-kernel`、`duo-sim-scenario`、`jackson-databind`
- 测试：**本模块不带测试**（0 条）——`DuoCliTest` / `RestControlServerTest` / `ScenarioHostTest` /
  `ControlPlaneAcceptanceTest` 均在 `duo-sim-examples`（避免模块循环依赖）

## 设计约束：§10 零内核改动

本模块**只消费** `ScenarioEngine` / `ScenarioRuntime` / `ScenarioResult` 的既有公开 API，
不新增/修改任何内核符号。事件增量序号在 `ScenarioHost` 侧按 1-based 下标分配。

## 关键类

| 类 | 职责 |
| --- | --- |
| `ScenarioHost` | 内核适配层：启动/停止/状态/事件增量（`eventsSince`）/注入转发/断言/拓扑；`State ∈ {IDLE, RUNNING, FINISHED, FAILED}`；同进程 attach 表（`attach`/`attached`） |
| `RestControlServer` | JDK 内置 `HttpServer` + 虚拟线程 executor；端点 `GET /health`、`POST|DELETE /scenario`、`GET /scenario/status`、`GET /events?since=N`、`POST /inject`、`GET /assertions`、`GET /topology` |
| `DuoCli` | 子命令 `run` / `serve` / `stop` / `inject` / `status` / `events` / `assert` / `topology` / `help`；退出码 0=成功/断言通过，1=失败 |

## 两种运行模式

| 模式 | 用法 | 说明 |
| --- | --- | --- |
| 同进程直连（缺省） | `run <yaml> --keep --name N`，后续命令按名接管 | `--inject-after <dur> "<action> <target>"` 提供「启动+到点注入+等待+报结果」单命令形态 |
| REST 客户端 | `serve <yaml> --port 0` 起服务端，各命令加 `--url` | 跨进程热注入；`serve` 打印 `listening on http://127.0.0.1:<port>` 供脚本发现端口 |

```bash
java -cp <classpath> io.duo.sim.control.cli.DuoCli run <yaml> --inject-after 3s "crash workers[2]" --wait
```

完整演练：`bash scripts/duo-inject-demo.sh`（同进程 + 跨进程两个演示，退出码即结论）。

## 错误映射

`400` 解析失败 · `404` 未知 target · `405` 方法不符 · `409` 未启动/双启动/未运行注入 · `500` 其余内部错误。

→ [架构说明 · 控制面](../docs/ARCHITECTURE.md#12-控制面10--m3) · [README · 快速开始](../README.md#53-跑一个场景cli-控制面)
