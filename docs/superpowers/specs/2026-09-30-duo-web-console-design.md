# Duo Web 控制台设计（M10）—— 把命令行/配置文件操作映射为 Web 端可视化操作

- 日期：2026-09-30
- 状态：设计已确认（分节评审通过），待实施计划
- 前置讨论：优先级 B（降低上手门槛）＞ A（自用演练驾驶舱）＞ C（对外演示）；第一版范围＝场景编辑器 + 场景库 + 运行时驾驶舱 + 实时观测面板四件套全做；技术路线＝方案二（独立前端工程，构建产物内嵌 jar）
- 可视化评审记录：mockup 存于 `.superpowers/brainstorm/`（gitignore，不入库）；关键拍板均以文字形式固化在本文
- 关联决策：**推翻 ROADMAP §7 非目标「精美 Web 控制台」**——本设计即为该决策转向的正式记录，实施时需在 `docs/DECISIONS.md` 登记（建议 D16：Web 控制台立项，理由＝CLI/配置文件操作体验是采用门槛，与「精美」无关的是能力边界仍守非目标清单其余各项）

---

## 1. 背景与动机

现状操作方式：手写 YAML 场景文件 + `duo` CLI（`run`/`serve`/`inject`/`status`/`topology`/`events`/`assert`/`metrics`/`diagnose`）+ 薄层 REST 控制面。对不熟悉 CLI 与 DSL 语法的使用者，完成一次「改场景 → 跑 → 注入 → 看反应」需要多终端多命令协同，体验差、上手门槛高。

Web 端映射的目标不是替换 CLI（CLI 仍是 CI/自动化的一等公民），而是提供**同一条能力面的可视化入口**：场景可视化编辑、一键运行、点选注入、实时观测。

**M3 以来「控制面不碰内核」的纪律延续**：本设计零内核改动（`duo-sim-kernel`/`scenario`/`components` 不动），所有 Java 改动集中在 `duo-sim-control` 与 CLI 参数面。

## 2. 目标与非目标

**目标（v1）**

| # | 目标 | 判据 |
| --- | --- | --- |
| G-W1 | 场景可视化编辑 | 拓扑画布 + 属性面板替代手写 YAML；YAML 双向同步语义（§5） |
| G-W2 | 降低上手门槛 | 新人零文档从模板 fork → 改 → 跑 → 注入 → 看结果，全程 ≤10 分钟、零终端命令 |
| G-W3 | 运行时驾驶舱 | 一键启停、点选注入（动作×节点下拉来自能力元数据） |
| G-W4 | 实时观测 | 事件流 live、拓扑状态灯、断言结果、诊断链渲染 |
| G-W5 | 运行形态不变 | `duo serve` 一条 Java 命令；运行期零新 Java 依赖；`-Dquality` 门禁口径不破坏 |

**非目标（v1 明确不做）**

- 指标图表页（Prometheus `/metrics` + Grafana 已覆盖）
- 远程访问/反向代理（回环绑定是信任边界；未来需要时单独立项：TLS + 代理头校验）
- SSE 流式事件（`EventSource` 无法携带 `Authorization` 头，与 Bearer 令牌模型冲突；轮询已够）
- 多场景并行运行管理（serve 单场景宿主；`/api/runs` 仅预留命名空间）
- 移动端适配、多用户/权限体系、国际化（中文界面优先）
- 替代 CLI/REST/JUnit 既有入口

## 3. 总体架构（方案二：独立前端工程，构建产物内嵌）

```
Duo/
├─ console-ui/                     ★ 新增：前端工程（仅构建期需要 Node 20+）
│   ├─ src/                        Vue 3 + TypeScript + Vite
│   ├─ package.json / lockfile     （lockfile 入库钉版本）
│   └─ vite.config.ts              build 产物 → duo-sim-control 构建输出目录（gitignore，不入库）
│
├─ duo-sim-control/                扩展（Java 侧唯一改动点）
│   ├─ rest/RestControlServer      + SPA 静态资源托管（classpath 读 /console/**，index.html fallback）
│   ├─ rest/                       + /api/ 新端点族（场景库、capabilities、meta、inject/clear）
│   ├─ rest/                       + GET /diagnose 服务端一等化（复用 FaultDiagnostics）
│   ├─ rest/                       + GET /events 响应扩展（next/dropped）、/topology 扩展实例健康
│   └─ cli/DuoCli                  + serve 场景参数改为可选（无参数 → IDLE 态，控制台为主入口）
│
└─ kernel / scenario / components / examples / junit / protocol / embedded    零改动
```

**构建接线原则**：前端构建产物**不入版本库**；接线方式（Maven 插件如 frontend-maven-plugin 托管 npm build，或纯 CI 步骤 + 本地手动构建）在实施计划 spike 后定。Node 只出现在构建期与 CI（`regression` job 加 node build + 前端单测步骤），运行期形态与现状完全一致。

**组件选型原则**：画布、代码编辑器、YAML 库一律用成熟开源库（候选：Vue Flow / CodeMirror 6 / `yaml` npm 包的 CST 模式），具体选型在实施计划 spike 对比后钉死；设计层只钉「不手写画布」「YAML 库须支持注释保留」两条。

## 4. 页面结构（工作区双模式）

信息架构＝**场景库首页 + 工作区（同一块拓扑画布，编辑/运行双模式）**：

- **场景库首页**：内置模板卡片（classpath 中的金标准场景，只读）+ 用户库列表（`--library-dir` 目录，可写）。操作：fork 到我的库、打开编辑、直接运行、删除（仅用户场景）、YAML 导入导出（前端读/存文件，无需专有端点）。
- **工作区 · 编辑模式**：左侧组件库面板（8 契约，按 `ContractRegistry` 能力元数据渲染可支持档位/动作）、中央拓扑画布（拖节点/连线）、右侧属性面板（选中节点的 tier/sut/wiring/config/行为剧本绑定）、底部抽屉（YAML 视图 ⇄ 校验问题面板）。顶部工具栏：保存、另存、校验、**▶ 启动场景**。
- **工作区 · 运行模式**：同一画布切换为活状态——节点状态灯（组件级 `healthy` + 前端从事件流推导实例级故障，如 `workers 3/4 实例故障`）、SUT 节点标星、点节点弹右侧注入面板（动作下拉来自 `/api/capabilities` 的 `supportedFaults` 矩阵 → 实例勾选 → 参数 → 立即注入）；底部三个 tab：**事件流（live，1s 轮询 `/events?since=<next>`）**、**断言结果**（`/assertions`）、**诊断链**（`/diagnose` 四段渲染，断链段显示 `MISSING`）。

「你编辑的图就是你观测的图」：画布只实现一套，编辑→重跑→看反应的反馈回路无页面跳转。

## 5. 画布 ↔ YAML 双向同步语义

**YAML 文本是唯一事实来源，画布是其投影**——不存在并行的第二数据模型。

1. 画布/属性面板操作 → 内存场景模型 → 序列化 → YAML 文本同步更新（编辑器内容跟随）。
2. YAML 抽屉手动编辑 → 解析 → 成功则重投影画布；失败则画布保持上一个好状态 + 问题面板逐条显示（不静默丢弃）。
3. 单用户单页：最后操作者赢，每笔操作 debounce 后全量重序列化，无合并算法。
4. 校验两级：前端快速校验（枚举/引用存在性等 schema 层面）只求输入体验；后端 dry-run（`ScenarioLoader`+`ScenarioValidator` 规则 1–8 + 外部输入档）是**唯一裁判**。
5. 注释保留尽力而为：优先选支持 CST 注释保留的 YAML 库；画布结构重排等无法保留的场景，界面显式提示「将重排 YAML、注释可能丢失」。
6. **画布布局坐标不进 YAML**（存 localStorage）：YAML 只承载语义，保持干净、可进版本库、与 CLI/JUnit 共用同一份文件（T8 口径）。
7. 启动即切换：编辑模式「▶ 启动场景」＝保存到库 → `POST /scenario` → 成功自动切运行模式；已有场景在跑时弹确认「停止当前场景并替换」（`DELETE /scenario` → `POST /scenario`）。

## 6. API 面

**结构性冗余原则**：所有新端点收进 **`/api/` 前缀**（控制台专属面）；既有核心端点被 CLI 依赖、签名冻结不动。旧面稳定 + 新面集中，演进永不破坏旧消费者。

### 6.1 新增端点（v1 全部实现）

| # | 端点 | 方法 | 作用 | 备注 |
| --- | --- | --- | --- | --- |
| 1 | `/api/scenarios` | GET | 场景列表：内置模板组 + 用户库组（含名称/描述/节点数/最后修改元信息） | |
| 2 | `/api/scenarios/{id}` | GET | 读场景 YAML 内容 | |
| 3 | `/api/scenarios/{id}` | PUT | 保存：先 dry-run 权威校验，失败 400 + 逐条问题清单；通过落盘 | 模板 id 拒写 |
| 4 | `/api/scenarios/{id}` | DELETE | 删用户场景 | 模板 405 |
| 5 | `/api/scenarios/{id}/fork` | POST | fork 为新用户场景（请求体带新 id；缺省按源 id 加后缀自动生成） | 「从模板起步」的一等路径 |
| 6 | `/api/scenarios/validate` | POST | 纯校验：不落盘不启动，返回问题清单 | 编辑器「校验」按钮/启动预检 |
| 7 | `/api/capabilities` | GET | 能力元数据：各契约 `supportedFaults`、档位、wiring 方式 | 数据源 `ContractRegistry`，前端不写死 |
| 8 | `/api/inject/clear` | POST | 手动清除注入（请求体带目标） | 薄委托 `ScenarioRuntime.clear`（内核已有，控制面补通路） |
| 9 | `/api/meta` | GET | serve 环境自述：duo 版本、library-dir 绝对路径、认证模式、事件缓冲上限 | 「关于」页 + 前端能力探测 |
| 10 | `/diagnose` | GET | 诊断链服务端一等化（复用 CLI 同一套 `FaultDiagnostics`） | 前端直接渲染四段报告 |
| 11 | `/`、`/console/**` | GET | SPA 静态托管，`index.html` 作路由 fallback | classpath 读取，MIME 表齐 |

### 6.2 既有端点：复用不动 + 向后兼容扩展

| 端点 | 处置 |
| --- | --- |
| `POST /scenario`、`DELETE /scenario`、`GET /scenario/status`、`POST /inject`、`GET /assertions`、`GET /metrics`、`/health` | 原样复用 |
| `GET /events?since=N` | 响应**增加** `next`（下一游标）与 `dropped`（EventRecorder 丢弃计数）字段；旧字段不变——前端轮询驱动 + 「已丢弃 N 条」可见 |
| `GET /scenario/status` | 确保包含 `warnings()`（外部进程遗留等警告控制台可见；若已含则零改动） |
| `GET /topology` | 复核现状（实施计划核对）：响应已含每节点 `id/contract/tier/sut/count/hosted/healthy/endpoints`，v1 **零改动**；实例级状态灯由前端从事件流推导（`sim.worker-instance-crashed` 等事实），不做需内核 API 的实例粒度健康数 |

### 6.3 预留占位（只定命名空间，不实现）

`/api/runs`（多场景运行管理）、`/api/scenarios/{id}/rename`（fork+delete 已可表达）、SSE 流式端点（令牌模型解决前不立）。

## 7. 安全模型

1. **认证口径不变**：除 `/health` 与静态资源外全端点 Bearer 令牌；静态资源为构建产物、不含机密，与 `/health` 同档放行。
2. **令牌进浏览器**：首屏令牌输入页（401 即回此页），存 `sessionStorage`（关标签页失效）；**不支持** URL 查询串带 token（防 history/Referer 泄漏）；`--insecure-no-auth` 模式跳过输入页（仅纯本机调试，口径不变）。
3. **同源自托管**：SPA 由同一 HttpServer 托管，天然满足既有回环 `Host`/`Origin` 校验，零 CORS 配置。
4. **外部输入档延伸到场景库**：`PUT /api/scenarios`、`POST /api/scenarios/{id}/fork` 的 YAML 内容与 `POST /scenario` 同档校验（禁 `launch.command`、config 键白名单、值禁绝对路径/URL scheme、禁框架外 `sut.main`）——经 Web 上传的 YAML 就是外部输入，不因「存成了文件」而升档。
5. **库目录防护**：`{id}` 白名单 `[A-Za-z0-9._-]+`，禁路径分隔符与 `..`，写入严格限定在 `--library-dir` 内；body 1 MiB 上限维持。
6. **纵深防御**：静态资源响应加 `Content-Security-Policy: default-src 'self'` 与 `X-Content-Type-Options: nosniff`。
7. **`--library-dir` 为 `serve` 新启动参数**，缺省 `./duo-console-library/`；启动输出打印实际路径。

## 8. 网络与端口语义

- `--port 0` 内核分配 → serve 启动输出**实际端口与控制台 URL**（现状保持）；控制台同源自托管，不依赖端口配置。
- 端口占用 → 显式报错不静默（现状保持）。
- 组件端点端口（`DUO_PORT`/端点告知/`FS_PATH`）与控制面端口互不相干，边界不混。
- 回环绑定是信任边界：v1 不提供 `--host 0.0.0.0`、不做反代（见 §2 非目标）。

## 9. CLI 扩展

- `duo serve <scenario.yaml>` 的场景参数**改为可选**：无参数时以 IDLE 态启动（`ScenarioHost.State.IDLE` 已有），控制台成为主要入口（从库启动场景）；带参数用法原样保留（向后兼容）。
- 其余 CLI 子命令不动。

## 10. 错误处理（§12「不静默」延伸到 UI 每一层）

**后端**：权威校验失败 → 400 + 逐条结构化问题（行号/字段/规则编号/人话，复用 `ScenarioValidator` 现有文案）；注入失败 → 4xx 带原因（`FaultLog` 双分支记日志的现状保持），控制台 Toast；断链 → `/diagnose` 渲染 `MISSING` 段（复用 gaps 语义）；启动失败 → 运行面板顶部横幅展示引擎错误。

**前端**：快速校验错误 → 行内标注 + 问题面板，画布保持上一个好状态；轮询失败 → 连接指示灯变红 + 退避重试（1s→2s→5s 封顶），不弹窗轰炸；401 → 回令牌页，**未保存编辑内容不清空**（草稿存 localStorage）；未保存切场景/关页 → beforeunload 确认；事件丢弃 → 「已丢弃 N 条」可见。

## 11. 测试策略

对齐现有分层（契约测试在 `duo-sim-control/src/test`，编排验收在 examples，397 测基线做加法）：

- **Java 侧（进常规回归，`-Dquality` 口径不变）**：场景库七端点契约测试（含路径遍历防护、模板不可删/拒写、外部输入档拒绝、fork 语义）；`/diagnose` 复用 `FaultDiagnostics` 既有验收语义；`/events` 扩展字段向后兼容；`/api/capabilities` 与 `ContractRegistry` 声明矩阵一致；`/api/meta`、`/api/inject/clear`、`/topology` 扩展各自契约用例；静态资源托管（SPA fallback/MIME/CSP 头）；安全回归（静态资源免令牌但 API 全 401、CSP 存在性、库目录逃逸拒绝）。预计新增 30–40 条用例。
- **前端侧（console-ui 工程）**：Vitest 单测覆盖纯逻辑层（YAML round-trip、快速校验规则、画布模型投影）；Playwright E2E 只做一条金标准冒烟链（编辑→启动→注入→观测），诚实标注不做全 UI 覆盖。
  - **修订（2026-10-01，计划二实施裁决）**：不引 Playwright（浏览器下载与 CI 复杂度对一条冒烟不成比例），改为「构建产物 + `duo serve` 真实冒烟清单（11 步：令牌→库→编辑→启动→注入→观测→停止→清理）」，与计划一 Task 10 的 E2E 冒烟同型；实现于计划二 Task 11（清单见 `docs/DEVELOPMENT.md` §1.4）。
- **CI**：`regression` job 加 node build + 前端单测步骤；`container`/`scale` job 不动。

## 12. 验收标准

1. **门槛判据（G-W2）**：新人零文档，从模板 fork → 画布改 worker 数 → 启动 → 注入 crash → 事件流看到 SUT 反应 → 断言可见，全程 ≤10 分钟、零终端命令。
2. **等价判据**：画布生成的 YAML 与手写金标准场景语义等价（`ScenarioLoader` 解析结果一致）。
3. **形态判据（G-W5）**：`duo serve` 单命令启动后浏览器全功能可用（令牌页 → 控制台）；全量回归绿且 `-Dquality` 零告警；运行期 Java 依赖零新增。

## 13. v1 边界一览（有意不做清单）

指标图表页 · 远程访问/反代 · SSE · 多场景并行运行管理 · 时间线待发条目视图（`/scenario/status` 不含时间线状态，v1 不为其扩 API） · 移动端/多用户/国际化 · 画布布局进 YAML · 替代 CLI/REST/JUnit 入口 · external SUT 的可视化代起（v1 经 POST /scenario 的外部输入档本就禁止 `launch.command`，控制台不做放宽入口）。
