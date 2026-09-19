# 变更日志

本项目的所有重要变更都记录在此文件。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## 版本策略

| 版本形态 | 含义 | 触发条件 |
| --- | --- | --- |
| `0.x.y-SNAPSHOT` | 开发中版本，**不保证**接口稳定 | 每次合并到 `main` |
| `0.x.y` | 里程碑版本，对外可依赖 | ROADMAP 的某个里程碑（M0–M8）验收通过 |
| `1.0.0` | 首个稳定版 | 全部里程碑完成、契约面冻结 |

- **主版本**（`x`，1.0 之前）：契约/API 不兼容变更，例如 `ComponentProvider`、
  `RegistryContract`、场景 DSL 的必填字段变化；
- **次版本**（`y`）：向后兼容的功能新增（新契约实现、新档位、新故障动作、新 DSL 字段）；
- **修订号**（`z`）：向后兼容的问题修复与文档/工程化调整。

> 1.0 之前每个次版本都可能包含不兼容变更，届时会在对应小节用 **BREAKING** 标注并给出迁移说明。

## [未发布]

### 新增

- **依赖门禁（M7 交付物 4）**：根 POM 新增 `quality` profile，`-Dquality` 激活
  `maven-dependency-plugin:analyze-only`（绑 `verify`）且 `failOnWarning=true`；
  缺省不激活 ⇒ 常规 `mvnw test` 零额外开销。CI 的 `regression` job 已接入
  （`-Dquality -DskipTests verify`），9 个模块**零未声明/零未使用**。
  门禁同时逼出 3 处真修复：`protocol` 未声明 `jackson-annotations`、`control` 未声明
  `jackson-core`、`examples` 未声明 `curator-test`（此前都靠传递依赖编译，上游改版即断）。
- **观测面（M8 交付物 1/2/3）**：`GET /metrics`（零依赖手写 Prometheus 文本格式，
  19 个指标族）、生产/测试双档 logback 配置、`duo diagnose` 单命令导出四段因果链
  （断链显式报 `gaps` 且退出码 1）。
- **发布配置（M7 交付物 3）**：根 POM 补全 `licenses`/`scm`/`url` 元数据；
  `-Drelease` 一键产出源码 jar 与 javadoc jar（`maven-source-plugin` 3.3.1 +
  `maven-javadoc-plugin` 3.11.2，缺省 `skip=true`，**常规构建行为不变**）。
- **DSL `autoStart`（G11）**：节点字段 `autoStart: false` 表达「声明但不启动」，用于
  「只验某契约、不让 worker 把 DAG 一起跑完」的拓扑裁剪；缺省 `true`＝声明即启动。
  未知节点键改为**显式报错**（此前静默忽略）；SUT/external 节点上的 `autoStart: false`
  由校验器拒绝（那两类节点由 `startSut()` 启动，写在这里无意义）。

### 修复

- **G10：worker 拒绝派发后的槽位记账不再泄漏**——`DispatchSelector.onDispatchRolledBack`
  在收到 `TaskStatus.REJECTED` 时退还本地预留（以最近一次 `SlotReport` 为上界），
  被拒任务能继续被重派，受状态机 `MAX_REJECTIONS` 兜底。修复前实例仅 1 格容量时
  最后一格会被永久占用、DAG 永不收敛。
- **G9（更早）：派发通路不再静默丢弃**——槽位不足时改为显式 `TaskStatus.REJECTED`，
  调度侧回滚重排，槽位计数原子化并即时上报。

### 测试

- `ZkSchedulerDiscoveryTest`（3 例）：把「real 档 SUT 写的端点对内核 registry 可见」
  钉成契约，并记录「跨档位组合要求 registry 后端同源」这条设计约束（异源时发现为空、
  显式失败，不做假成功）。
- 观测面 5 例（`MetricsEndpointAcceptanceTest` 2 / `FaultCausalChainLoggingTest` 2 /
  `FaultDiagnosticsAcceptanceTest` 1），其中 1 例是**日志配置回归护栏**：
  禁止再引入 logback `<if>/<else>` 条件块（1.5.16 上会抛 `EmptyStackException`
  并让**全部日志静默丢失**，踩过一次）。
- 全量回归 **366 测 / 0 失败 / 0 错误 / 11 skip**（reactor 内 8 模块）；
  另有 `duo-sim-control` 的 25 条在 examples 步内执行。

### 已知限制（如实记录）

- `engine` 只有 `virtual` 档，无档可换；
- Docker 相关的 container 档用例在本机跳过（共 10 条），由 CI 的 `container` job 承担；
- 观测面只做 counter/gauge，**没有直方图**（任务时延等分桶口径未定，不为凑指标拍脑袋）；
  指标为进程级累计值，场景重启不归零（口径见 `docs/METRICS.md`）；
- `duo diagnose` 只读事件流，日志侧入口（`io.duo.sim.fault` + `grep FAULT`）已设计未实现；
- JaCoCo 覆盖率门禁**评估后决定不引入**（理由见 `docs/ROADMAP.md` M7 §）；
- M8 交付物 4「加速时钟评估」保持 ⏸：触发条件「小时级长稳场景 + virtual 档」未出现，
  评估无输入（`SimClock` 接口已预留，无返工成本）。

## [0.1.0] - 2026-09-18

首个里程碑批次：M0–M4 主线 + M5 交付物 1–6 + M6 + M7 最小子集。

### 新增

- 8 个契约（`registry`/`store`/`worker`/`engine`/`scheduler`/`resource`/`message`/`filestore`）
  与 4 个档位（`virtual`/`embedded`/`container`/`real`），每个契约至少一个正例与一个故障例；
- 7 个故障动作：`crash`、`restart`、`freeze`、`slow`、`registry-flap`、`resource-exhaust`、
  `task-kill`，含 `supportedFaults` 声明与未声明动作的显式拒绝；
- 场景 DSL：`ScenarioLoader`/`ScenarioValidator`/`WiringResolver`（拓扑排序启动）、
  timeline 与热注入、`custom-hook`、external SUT（代起/attach 两形态 + ready 探针）；
- 交付工程：标准 Maven Wrapper、三 job CI（`regression`/`container`/`scale`）、
  Apache-2.0 `LICENSE`、`@VirtualCluster` JUnit5 扩展与 `DuoAssertions`。
