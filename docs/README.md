# Duo 文档索引

本目录是 Duo 项目的全部文档入口。文档分两类：

- **工程文档**（本目录根下）——面向使用者与贡献者，随代码演进持续维护。
- **过程文档**（`superpowers/`）——设计冻结稿、各阶段实施计划、验收与复验记录，属**历史证据**，
  按「当时提交的快照」语义理解，不随后续改动回填（除非显式标注勘误）。

> 仓库根另有一份**统一英文文档** [`../README.en.md`](../README.en.md)，
> 与 [`../README.md`](../README.md) 同口径同步维护（工程文档与模块 README 目前仍为中文）。

---

## 1. 阅读路径

| 你是 | 建议顺序 |
| --- | --- |
| **第一次接触项目** | [`../README.md`](../README.md) → [`ARCHITECTURE.md`](ARCHITECTURE.md) §1–§3 → [`SCENARIO-DSL.md`](SCENARIO-DSL.md) §7 示例 |
| **想写一个场景** | [`SCENARIO-DSL.md`](SCENARIO-DSL.md)（字段全集 + 校验规则）→ [`../README.md`](../README.md) §7 最小示例 |
| **想接一个自己的 SUT** | [`ARCHITECTURE.md`](ARCHITECTURE.md) §10（SUT 适配面：in-process 与 external）→ [`SCENARIO-DSL.md`](SCENARIO-DSL.md) §1.3–§1.5（`launch.command` / 端点告知 / 生命周期） |
| **想加一个契约/档位实现** | [`DEVELOPMENT.md`](DEVELOPMENT.md) §5（扩展点）→ [`ARCHITECTURE.md`](ARCHITECTURE.md) §4（注册表与能力元数据） |
| **想评审设计一致性** | [`superpowers/specs/2026-09-13-duo-virtual-bigdata-sim-design.md`](superpowers/specs/2026-09-13-duo-virtual-bigdata-sim-design.md)（冻结稿）→ [`ROADMAP.md`](ROADMAP.md) §2 目标达成度对照 |
| **想知道下一步做什么** | [`ROADMAP.md`](ROADMAP.md) |

## 2. 工程文档

### 2.1 全局

| 文档 | 内容 | 何时需要改它 |
| --- | --- | --- |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | 模块依赖、内核 SPI、契约与档位、能力元数据与注册期校验、wiring 连接路径规则、启动/停止时序、事件总线与命名空间、SUT 适配面、断言与录制、控制面、Duo 线协议 | 改动内核 SPI、新增契约/档位、改变接线或生命周期语义 |
| [`SCENARIO-DSL.md`](SCENARIO-DSL.md) | 场景 YAML 字段全集、校验规则 1–8、行为剧本字段、时间线动作、断言清单与语义、内置组件 config 键、完整示例、常见报错 | 新增/修改 DSL 字段、校验规则、断言或内置组件配置键 |
| [`DEVELOPMENT.md`](DEVELOPMENT.md) | 环境与工具链、构建/测试命令、测试分层与门控、扩展点操作步骤、模块依赖纪律、编码与提交约定、已知工程债 | 构建方式、测试门控、开发流程变化 |
| [ROADMAP.md](ROADMAP.md) | 原始目标达成度盘点（T1–T8 判据与取证）、差距清单（G1–G11）、M5–M8 阶段计划与验收口径、优先级与决策点、进展记录 | 阶段立项/关闭、差距状态变化 |
| [METRICS.md](METRICS.md) | `/metrics` 指标口径：19 个指标族的语义与刷新时机、三条观测通道的分工、已知边界与后续可扩充项 | 新增/修改指标、调整口径或分桶 |
| [DECISIONS.md](DECISIONS.md) | 决策台账 D1–D9（决定/理由/触发条件/落点）、决策→交付物映射、修订记录 | 任何需要「拍板」的设计取舍被确定或被推翻时 |

### 2.2 模块级

每个模块根目录各有一份 `README.md`（职责 / 依赖 / 关键类 / 测试 / 踩坑）：

[`duo-sim-protocol`](../duo-sim-protocol/README.md) ·
[`duo-sim-kernel`](../duo-sim-kernel/README.md) ·
[`duo-sim-scenario`](../duo-sim-scenario/README.md) ·
[`duo-sim-components`](../duo-sim-components/README.md) ·
[`duo-sim-embedded`](../duo-sim-embedded/README.md) ·
[`duo-sim-junit`](../duo-sim-junit/README.md) ·
[`duo-sim-control`](../duo-sim-control/README.md) ·
[`duo-sim-examples`](../duo-sim-examples/README.md)

## 3. 过程文档（`superpowers/`）

### 3.1 设计（冻结）

| 文档 | 状态 |
| --- | --- |
| [`specs/2026-09-13-duo-virtual-bigdata-sim-design.md`](superpowers/specs/2026-09-13-duo-virtual-bigdata-sim-design.md) | **v1.0 定稿并冻结**（经五轮评审收敛）。所有实现以此为唯一依据；文中 `§n` 引用即指该文档章节 |

### 3.2 实施计划

| 阶段 | 计划 | 状态 |
| --- | --- | --- |
| M0 内核骨架 | [`plans/2026-09-13-duo-m0-kernel-plan.md`](superpowers/plans/2026-09-13-duo-m0-kernel-plan.md) | 已实施完成（`TierSwapAcceptanceTest` 通过；⚠️ 计划文件状态行未回填，仍写「待用户批准」——以验收测试与提交记录为准） |
| M1 场景与注入 | [`plans/2026-09-13-duo-m1-timeline-plan.md`](superpowers/plans/2026-09-13-duo-m1-timeline-plan.md) | 已实施完成并验收通过（`d377829` T23；⚠️ 同上，状态行未回填） |
| M2 嵌入中间件 | [`plans/2026-09-14-duo-m2-embedded-plan.md`](superpowers/plans/2026-09-14-duo-m2-embedded-plan.md) | 已实施完成并验收通过 |
| M3 控制面 | [`plans/2026-09-14-duo-m3-control-plan.md`](superpowers/plans/2026-09-14-duo-m3-control-plan.md) | 已实施完成，M3 验收通过（2026-09-15） |
| M4 规模与桥接 | [`plans/2026-09-15-duo-m4-scale-bridge-plan.md`](superpowers/plans/2026-09-15-duo-m4-scale-bridge-plan.md) | 已实施完成，M4 关闭（2026-09-15） |
| M5–M8 收尾 | 见 [`ROADMAP.md`](ROADMAP.md) §4 各阶段交付物与状态表 | M5/M6/M7/M8 均已实施完成并验收（G1–G11 全部闭合；M8 交付物 4 待触发） |
| M7 依赖门禁基线 | [`plans/m7-quality-gate-baseline.md`](superpowers/plans/m7-quality-gate-baseline.md) | 已落地并进 CI（2026-09-19 第 11 轮），9 模块零告警 |

### 3.3 验收与复验记录

| 文档 | 内容 |
| --- | --- |
| [`acceptance/2026-09-15-duo-m3-acceptance-record.md`](superpowers/acceptance/2026-09-15-duo-m3-acceptance-record.md) | M3 验收：REST/CLI 热注入、错误映射、内核零改动审计、CLI 双模式演练 |
| [`acceptance/2026-09-15-duo-m4-acceptance-record.md`](superpowers/acceptance/2026-09-15-duo-m4-acceptance-record.md) | M4 验收：压测、Testcontainers 桥、加速时钟与第三方适配器两项决策收口 |
| [`acceptance/2026-09-15-duo-m4-scale-report.md`](superpowers/acceptance/2026-09-15-duo-m4-scale-report.md) | 千~万 Worker 心跳压测报告（993/s、9,928/s；注册 100%；线性扩展） |
| [`acceptance/2026-09-18-duo-m4-independent-verification-record.md`](superpowers/acceptance/2026-09-18-duo-m4-independent-verification-record.md) | M4 独立复验：压测数据链核对 + 容器档 HIGH 整改取证 |
| [`acceptance/2026-09-18-duo-m6-external-sut-record.md`](superpowers/acceptance/2026-09-18-duo-m6-external-sut-record.md) | M6 验收：external 第三方 SUT 端到端（端点告知双途径 / ready 探针 / 退出与崩溃事实 / 不杀进程）+ M7 最小子集（标准 Wrapper + CI 三 job）+ 注入事件顺序缺陷处置 |
| [`acceptance/2026-09-19-duo-m8-observability-record.md`](superpowers/acceptance/2026-09-19-duo-m8-observability-record.md) | M8 验收：观测面三条通道（`/metrics` 指标 / logback 日志 / `duo diagnose` 因果链）+ 交付物 4 为何仍 ⏸ 的判定 |
| [`plans/m7-quality-gate-baseline.md`](superpowers/plans/m7-quality-gate-baseline.md) | M7 依赖门禁的**基线取证**：7 类告警逐条原文 + 真修复 vs 有意保留的取舍 + 再基线命令 |

> **快照语义**：验收记录中的测试合计值（213 / 219 / 221 / 226 / **258** / **263** / **280**）都是**对应提交那一代**的实测数，
> 不是可复算到任意 HEAD 的不变量。当前 HEAD 的实测值（**366 测 / 0 失败 / 0 错误 / 11 skip**，第 11 轮质量门禁后）见 [`../README.md`](../README.md) 顶部，
> 逐模块分布见 [`DEVELOPMENT.md`](DEVELOPMENT.md) §3.2。

## 4. 文档维护约定

1. **设计文档冻结**：`specs/` 下的 v1.0 不再改动；语义变更以「计划 + 验收记录 + 本目录工程文档」承载，
   必要时在路线图中登记为偏离项。
2. **`§` 引用**：代码注释与文档中的 `§n` 一律指设计文档章节；引用实现细节时写类名/文件路径。
3. **验收记录命名**：`YYYY-MM-DD-duo-mN-<主题>-record.md`；独立复验另起 `-independent-verification-record.md`。
4. **数字口径**：测试数、吞吐等实测值必须标注**提交号或快照时间**，且可从产物复算（见 M3 记录的「口径说明」）。
5. **改代码同步文档**：按 §2 表格的「何时需要改它」一列执行；新增 DSL 字段必须同时更新
   [`SCENARIO-DSL.md`](SCENARIO-DSL.md) 与校验规则说明。
