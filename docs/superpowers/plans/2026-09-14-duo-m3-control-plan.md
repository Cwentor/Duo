# Duo M3 实施计划 —— 控制面（REST/CLI 热注入）

- 日期：2026-09-14
- 依据：设计文档 v1.0（冻结）§10/§14 M3 行；M0（29b1a42）/M1（d377829）/M2（cd002e8）均已验收
- 范围：`duo-sim-control` 的 REST + CLI 热注入（可选极简拓扑视图）
- 状态：待用户批准（批准前不动代码）。**前置：M2 验收待用户确认、M3 方向待用户选定**——本计划为默认路线（spec §14 顺序 M3→M4）预起草

---

## 1. 交付定义（唯一验收口径）

**设计文档 §14 M3 验收场景：运行中手动注入故障并观察自愈。** 具体化为一条验收测试 + 一次人工 CLI 演练：

- **自动化验收（`ControlPlaneAcceptanceTest`）**：启动 M1 金标准场景（virtual 档，快速收敛）→ 场景运行中经 **REST API** 注入 `crash workers[2]` → 断言：注入返回成功、事件流出现 `sim.fault-injected`、任务在窗口内转移、`noTaskLost` 通过、场景最终 SUCCESS；
- **CLI 演练（脚本化，纳入验收）**：`duo inject crash workers[2] --scenario <yaml>` 单命令完成"启动场景 + 注入 + 等待 + 打印结果"，退出码反映场景结果；
- **前置要求**：注入内核通路（`ScenarioRuntime`）**零改动**——M3 是纯外层包装（§10）。若发现需要改内核，即视为违背 §10 定位，须停下重新评审。

## 2. 范围与不做

**做**：

| # | 项 | 依据 |
| --- | --- | --- |
| 1 | `duo-sim-control` REST 服务：内嵌 HTTP（JDK `com.sun.net.httpserver`，**不引 Spring**——与 M0 起的零框架依赖取向一致）、端点：`POST /scenario`（启动/停止）、`GET /scenario/status`、`GET /events`（事件流，支持 `?since=` 增量）、`POST /inject`（FaultAction JSON）、`GET /assertions`（当前结果） | §10/§14 |
| 2 | CLI：`duo` 命令（子命令 `run` / `inject` / `status` / `events` / `assert`），薄客户端调 REST 或直连内核（同进程模式） | §14 |
| 3 | 极简拓扑视图（可选）：`GET /topology` 返回节点/档位/实例数/在线状态 JSON；CLI `duo topology` 表格输出 | §14 |
| 4 | M3 验收测试 + CLI 演练脚本 | §14 |

**不做**：Web 前端/图形化控制台（非目标：仅薄层 CLI/REST）、鉴权/多用户（单机仿真工具，M4 再议）、container 档（M4）、加速时钟（M4）、第三方 SUT 适配（M4）、新的故障类型（M1/M2 已定义的动作集复用）。

## 3. 任务分解（4 任务，估 3~5 天）

| # | 任务 | 内容与落点 | 完成判据 |
| --- | --- | --- | --- |
| T32 | 控制面内核适配层 | `duo-sim-control`：`ScenarioHost`——持有 `ScenarioEngine` 生命周期（start/stop/status）、暴露事件快照（含 `since` 游标）、注入转发（`engine.inject`）、断言结果读取；**不新增内核 API**（只用已公开的 `ScenarioEngine`/`ScenarioRuntime`/`ScenarioResult`） | 单测：启动/停止/状态/增量事件/注入转发/结果读取；确认未 import 任何新内核符号 |
| T33 | REST 服务 | JDK HttpServer 端点（§2 表）+ JSON 序列化（Jackson，已管理）；端口 0 自动分配 + `GET /health`；错误映射（校验失败 400 / 未启动 409 / 未知 target 404） | 单测：各端点往返（用 `HttpClient`）；错误码；端口自动分配 |
| T34 | CLI | `duo` 主类（`--help`/子命令解析，无外部 CLI 框架——手写参数解析保持零依赖）：`run <yaml> [--wait]`、`inject <action> <target>`、`status`、`events [--since]`、`assert`、`topology`；两种模式：REST 客户端（`--url`）或同进程直连（缺省，便于脚本化验收） | 单测：参数解析、每子命令走通（同进程模式）；`--help` 输出 |
| T35 | 拓扑视图 + M3 验收 | `GET /topology`（节点/契约/档位/count/在线）+ CLI 表格；`ControlPlaneAcceptanceTest`（§1 自动化部分）；CLI 演练脚本 `build/duo-inject-demo.sh` + 一次实际运行记录 | **全绿 + CLI 演练成功＝M3 验收通过** |

## 4. 关键决策（实现前钉死）

**D1：HTTP 服务器选型**——JDK 内置 `com.sun.net.httpserver.HttpServer`（零依赖、单机仿真工具足够）vs 引入 Javalin/Spark。**决定：JDK 内置**。理由：M3 是薄包装，引入 Web 框架与项目"零框架依赖"取向（proto/kernel 至今只有 Jackson+SnakeYAML）冲突；JDK HttpServer 的线程模型（虚拟线程 executor）与项目一致。

**D2：CLI 与内核的进程关系**——REST 客户端（跨进程）vs 同进程直连。**决定：两者都支持**，同进程模式为缺省（脚本化验收最简：`duo run m1-golden.yaml --inject-after 10s "crash workers[2]" --wait`）。跨进程模式用于"运行中的独立进程注入"（更贴近真实运维演练）。

**D3：事件流增量语义**——`GET /events?since=<seq>` 返回序号大于 since 的事件（内核事件流加单调序号）。**决定：序号在控制面侧按读取顺序分配**（不改内核 Event 结构——`Event` 是 record，加字段会破坏 M0/M1/M2 的构造点）。
**实现前提（T32 判据）**：`ScenarioEngine.events()` 返回的是 `CopyOnWriteArrayList` 的不可变快照（`List.copyOf`），故控制面侧 `ScenarioHost` 必须维护 `lastSeenIndex` 游标并对每次 `events()` 快照做"从 lastSeenIndex 起"的切片；序号在此切片上分配。**并发注意**：ConcurrentHashMap 之外的事件顺序由写入线程决定，`since` 语义保证"不重不漏"（同一快照内），跨快照的严格全序不承诺（与 §11"真实时钟不承诺确定性重放"一致）。

## 5. 风险与对策

1. **§10"零内核改动"约束**：若实现中发现缺 API（如事件游标），**优先在控制面侧补**（如 D3 的方案）；确需内核改动则停手评审。
2. **JDK HttpServer 的 JSON/错误处理较原始**：封装薄层 `RestSupport`（路由 + JSON + 错误映射），单测覆盖。
3. **验收依赖时序**（注入窗口）：复用 M1 的"4 并行长任务"剧本模式，注入点选在任务在途期；断言窗口宽。

## 6. 执行节奏

T32 → T33 → T34 → T35；每任务全量回归（整 reactor）；T35 全绿 + CLI 演练成功即 M3 关闭。工期 3~5 天（§14 估 ~2 周，M3 是薄层故按下限估）。

**批准本计划后即开始 T32 编码。**
