# duo-sim-control

**控制面**：REST + CLI 热注入（M3）＋ Web 控制台后端 `/api/**` 与 SPA 托管（M10）。

- 依赖：`duo-sim-kernel`、`duo-sim-scenario`、`jackson-databind`、`slf4j-api`
- 测试：**46 条，本模块 `src/test` 内执行**（`./mvnw -o -pl duo-sim-control -am test`；2026-10-01 实测）——
  `ScenarioHostTest` 10、`ScenarioHostFaultControlTest` 2、`ScenarioLibraryTest` 5、
  `RestControlServerTest` 14、`RestConsoleApiTest` 15。
  与示例强耦合的编排用例（`DuoCliTest` 15 条、`ControlPlaneAcceptanceTest`）**留在 `duo-sim-examples`**，
  避免 `control ↔ examples` 循环依赖（本模块以 `test` 作用域依赖 `components`，依赖方向仍单向）

## 设计约束：§10 零内核改动

本模块**只消费** `ScenarioEngine` / `ScenarioRuntime` / `ScenarioResult` 的既有公开 API，
不新增/修改任何内核符号。事件增量序号在 `ScenarioHost` 侧按 1-based 下标分配。

## 关键类

| 类 | 职责 |
| --- | --- |
| `ScenarioHost` | 内核适配层：启动/停止/状态/事件增量（`eventsSince`）/注入转发/断言/拓扑；`State ∈ {IDLE, RUNNING, FINISHED, FAILED}`；同进程 attach 表（`attach`/`attached`） |
| `RestControlServer` | JDK 内置 `HttpServer` + 虚拟线程 executor；核心端点（下表）+ `/api/**` 控制台端点族 + `/console/**` SPA 托管 |
| `ScenarioLibrary` | **场景库**（M10）：模板目录（classpath `/console-templates/`，只读）＋用户库（`--library-dir`，缺省 `./duo-console-library`）；`validId` 只接受 `[A-Za-z0-9._-]+`（路径穿越防护，路由前先解码再逐段校验） |
| `MetricsCollector` | **Prometheus 文本格式导出**（M8 交付物 1，决策 D4＝零依赖手写、不引客户端）：只读既有事件流，口径见 [`docs/METRICS.md`](../docs/METRICS.md) |
| `FaultDiagnostics` | **故障因果链**（M8 交付物 3）：把「注入 → 组件反应 → SUT 事实 → 断言」四段拼成一等报告；断链显式可见（不静默） |
| `FaultLog` | 故障注入的结构化日志（`io.duo.sim.fault`）——事件流是机器的事实源，日志是人的因果链 |
| `DuoCli` | 子命令 `run` / `serve` / `stop` / `inject` / `status` / `events` / `assert` / `topology` / `metrics` / `diagnose` / `help`；退出码 0=成功/断言通过，1=失败 |

## 端点

核心端点（M3，签名冻结——被 CLI 依赖，故控制台专属面另起 `/api/` 前缀）：

`GET /health`（**唯一免认证**，供探针）、`POST|DELETE /scenario`、`GET /scenario/status`、
`GET /events?since=N`（响应含 `since`/`next`/`dropped`，M10 新增后两者）、`POST /inject`、
`GET /assertions`、`GET /topology`、`GET /metrics`、`GET /diagnose`（M10）。

控制台端点族（M10，`/api/` 前缀）：`GET /api/scenarios`、`GET|PUT|DELETE /api/scenarios/{id}`、
`POST /api/scenarios/{id}/fork`、`POST /api/scenarios/validate`、`GET /api/capabilities`、
`GET /api/meta`、`POST /api/inject/clear`。

静态托管：`GET /console/**`（M10；classpath `/console`，无扩展名回 `index.html`，
`CSP default-src 'self'` + `nosniff`）。

完整端点表与错误映射见[架构说明 §12](../docs/ARCHITECTURE.md#12-控制面10--m3)。

## 两种运行模式

| 模式 | 用法 | 说明 |
| --- | --- | --- |
| 同进程直连（缺省） | `run <yaml> --keep --name N`，后续命令按名接管 | `--inject-after <dur> "<action> <target>"` 提供「启动+到点注入+等待+报结果」单命令形态 |
| REST 客户端 | `serve [scenario.yaml] --port 0` 起服务端，各命令加 `--url` | 跨进程热注入；`serve` 打印 `listening on http://127.0.0.1:<port>` 供脚本发现端口（令牌**不**回显）。**场景参数自 M10 起可选**——缺省以 `IDLE` 态启动，供 Web 控制台主导；`--library-dir` 指定场景库目录 |

```bash
java -cp <classpath> io.duo.sim.control.cli.DuoCli run <yaml> --inject-after 3s "crash workers[2]" --wait
```

完整演练：`bash scripts/duo-inject-demo.sh`（同进程 + 跨进程两个演示，退出码即结论）。

## 信任模型与错误映射

除 `/health` 与 `/console/**` 外**全部端点要求** `Authorization: Bearer <token>`，且
`Host`/`Origin` 必须回环（挡 DNS-rebinding 与 CSRF）；`serve` 不给令牌则**拒绝启动**
（除非显式 `--insecure-no-auth`）。控制面 body 一律按**外部输入档**校验——详见
[架构说明 §12.1](../docs/ARCHITECTURE.md#121-控制面信任模型安全审计-2026-09-20-c-1h-1h-2m-4)。

`400` 解析失败/坏 id/校验不过 · `404` 未知 target · `405` 方法不符/模板只读 · `409` 未启动/双启动/未运行注入 ·
`413` body 超限 · `401` 缺令牌 · `403` 跨站/非回环 Host · `503` 未配置场景库 · `500` 其余内部错误。

→ [架构说明 · 控制面](../docs/ARCHITECTURE.md#12-控制面10--m3) · [指标口径](../docs/METRICS.md) · [README · 快速开始](../README.md#53-跑一个场景cli-控制面)
