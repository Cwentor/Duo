# Duo M4 实施计划 —— 规模与桥接（万级心跳压测 / Testcontainers 桥 / 开放问题收口）

- 日期：2026-09-15
- 依据：设计文档 v1.0（冻结）§14 M4 行「万级心跳压测（虚拟线程调优）、Testcontainers 桥（embedded 模块容器档）、加速时钟评估、第三方 SUT 协议适配器（按需）」；§16 风险 1、§17 开放问题 1/2
- 前置：M3 已验收（2026-09-15）；验收报告发现的两处缺陷（SutLauncher 字段遮蔽 + 诊断回归）与 LOW 观察（eventsSince O(n²)）已在 7ac458b 修复——eventsSince 改下标切片正是 M4 万级事件规模的前置条件
- 状态：**已实施完成**（2026-09-15）。验收记录见 `docs/superpowers/acceptance/2026-09-15-duo-m4-acceptance-record.md`，压测报告见 `docs/superpowers/acceptance/2026-09-15-duo-m4-scale-report.md`

---

## 1. 交付定义（唯一验收口径）

**设计文档 §14 M4 验收标准：千~万 Worker 心跳压测报告。** 具体化为：

- **千档压测（1000 worker 实例）**：完整跑通「注册 → 心跳 → 槽位上报 → 测量」闭环，采集吞吐/时延/资源指标；
- **万档压测（10000 worker 实例）**：同一场景文件改 `count` 即可运行，采集同口径指标；
- **压测报告**：注册爬坡耗时、master 侧帧吞吐（帧/s）、心跳事件量、JVM 内存与 GC、瓶颈分析与调优点，落 `docs/superpowers/acceptance/`；
- **Testcontainers 桥**：`registry` 契约新增 `container` 档实现（Testcontainers ZK），无 Docker 环境测试自动 skip（§13 明文）；
- **开放问题收口**：加速时钟评估结论 + 第三方 SUT 适配器优先级决策，落验收记录。

## 2. 范围与不做

**做**：

| # | 项 | 依据 |
| --- | --- | --- |
| 1 | 心跳可配化：VirtualWorker `heartbeat.interval.ms`（现硬编码 100ms）、连接退避重试；DemoScheduler `heartbeat.eventSampleRate` 事件采样 + accept backlog 调大 | §14 万级规模的前置 |
| 2 | 压测 harness：千/万档场景 YAML + `ScaleAcceptanceTest`（`-Dduo.scale=true` 显式触发，常规回归不跑）+ 指标采集 | §14 验收 |
| 3 | Testcontainers ZK 容器档 provider（embedded 模块）+ 无 Docker 自动 skip 测试 | §6/§13 |
| 4 | 压测报告 + 开放问题决策记录 | §14/§17 |

**不做**：真实第三方产品适配器实现（§17 开放问题 1 决策为「不做」，见 §4 D5）、加速时钟的代码实现（评估结论为推迟，见 §4 D4）、Web 前端（非目标）、Prometheus 指标通道（§11 扩展点，按需另立项）。

## 3. 任务分解（4 任务）

| # | 任务 | 内容与落点 | 完成判据 |
| --- | --- | --- | --- |
| T36 | 心跳压测基建 | `VirtualWorker`：`heartbeat.interval.ms` 可配（缺省 100 不变）、`connect` 失败退避重试×3（万级并发 accept 下防误判离线）；`DemoScheduler`：`heartbeat.eventSampleRate`（每 N 条发 1 条 `sut.heartbeat` 事件，缺省 1=全量，压测档采样）、accept backlog 4096；场景 YAML `scale-1k.yaml` / `scale-10k.yaml`；`ScaleHarness` 指标采集（注册爬坡、master 帧吞吐、事件量、内存/GC） | 压测场景千档跑通，指标可采集 |
| T37 | 压测执行与调优 | `ScaleAcceptanceTest`（`Assumptions` 门控 `-Dduo.scale=true`，千档必跑于压测模式、万档同开关）：断言注册完成率 100%、压测窗口无 worker 掉线、`sut.heartbeat` 事件流量符合采样预期；实测千/万档并产出 `2026-09-15-duo-m4-scale-report.md` | 报告落盘，含瓶颈分析与调优结论 |
| T38 | Testcontainers 桥 | `duo-sim-embedded`：`ZookeeperContainerProvider`（`(registry, container)` 档，Testcontainers `GenericContainer(zookeeper:3.9)`，端点 THIRD_PARTY、`interfaceDirect=false`）；Curator wire 客户端经真实容器端口往返的集成测试，`Assume.assumeTrue(dockerAvailable())` 无 Docker 即 skip | 本机无 Docker：测试 skip 且构建绿；注册期一致性校验通过 |
| T39 | 开放问题收口 + M4 验收 | 加速时钟评估、第三方适配器决策（§4 D4/D5）；全量回归；验收记录 `2026-09-15-duo-m4-acceptance-record.md` | 全绿 + 记录落盘＝M4 关闭 |

## 4. 关键决策（实现前钉死）

**D1：压测的 SUT 形态**——复用 demo-scheduler vs 专用压测 SUT。**决定：复用 demo-scheduler（允许改 SUT 自身代码）**。理由：压测测的是「框架连接模型 + 虚拟线程」在千/万节点下的表现，demo-scheduler 正是 §13 的参考被测实现；§13 明文「测试代码零改动约定不含 SUT 自身」，故心跳采样/backlog 属合法 SUT 变更，不动内核。

**D2：压测的运行位置**——常规回归内 vs 显式触发。**决定：`-Dduo.scale=true` 显式触发，常规回归不含压测**。理由：万档预计分钟级耗时 + GB 级内存，塞进每次回归会让 2.5 分钟的全仓变成 10+ 分钟且在弱机上抖动；千档同开关一并触发，保证「报告可复现」（同一命令重跑）。回归全绿判据不含压测测数，报告单独给出压测命令与实测输出。

**D3：万级规模的事件策略**——每条心跳一个 `sut.heartbeat` 事件（10k 实例 × 1s 心跳 = 1 万事件/s，事件流分钟级即百万条）vs 采样。**决定：SUT 侧采样（`heartbeat.eventSampleRate`）**。理由：内核事件流是只追加快照（M4 前置修复已把它改成 O(新增) 切片），但录制 JSONL 的体积与断言扫描成本仍随事件量线性涨；心跳聚合语义上就是可采样的（断言库无逐心跳断言需求，M1 断言集只依赖任务/故障/主事件）。缺省 1 保持旧行为，只有压测场景显式采样。

**D4：加速时钟（§17 开放问题 2）**——**评估结论：推迟，不进 M4 交付**。理由：①加速时钟的价值前提是 SUT 可注入 `Clock`/时间源，demo-scheduler 的主循环与 TaskStub 均以 `Thread.sleep` 挂真实时钟，改造面覆盖全部 SUT 与行为剧本，收益（场景 wall-time 缩短）与 M4 验收无关；②SimClock 接口已在内核预留（`kernel/api/SimClock`），推迟无接口返工成本；③压测报告给出的真实吞吐数据反而说明当前瓶颈在连接与调度而非时钟等待。结论落验收记录，路线图维持「按需评估」。

**D5：第三方 SUT 协议适配器（§17 开放问题 1 / §16 风险 1）**——**决策：不绑定具体产品，维持 SPI 就绪状态**。理由：设计文档从未指定第三方产品与版本基线（开放问题 1 本身就是「M4 前再定」），凭空选一个产品做适配会产生无验收口径的版本耦合；接入所需的全部要素已就绪——`duo-sim-protocol` 协议工件独立成模块、内核公开 SPI（`ComponentProvider`/契约接口）、M0 已用 demo real worker 验证过「第三方进程讲 Duo 协议」的接入路径。结论：适配器按需触发（出现真实接入需求时立专项），M4 交付决策记录而非占位实现。

**D6：容器档的验证边界**——本机无 Docker（`docker` 命令不存在）。**决定：实现 + 单测 + 无 Docker 自动 skip，skip 路径本身验证**；容器真实往返留待有 Docker 的环境按报告中的命令复验。理由：§13 明文「容器档在无 Docker 环境自动 skip」是设计承诺而非妥协；provider 的注册期校验（元数据一致性）不依赖 Docker，可离线验证。

## 5. 风险与对策

1. **万级连接的 accept 瓶颈**：DemoScheduler 缺省 backlog 过小会在注册爬坡期拒连 → D1 允许改 SUT：backlog 4096 + worker 侧重试兜底；爬坡指标入报告。
2. **Windows 环境的端口与句柄上限**：10k×2 端（worker 拨号 + master 接受）全走 127.0.0.1，动态端口范围（Windows 默认 ~16k）与句柄上限可能触顶 → 压测报告记录 OS 配置；万档若触顶如实报告（报告允许「万档部分降级运行」的诚实结论，千档达标即满足验收下限）。
3. **Testcontainers 依赖引入**：新增依赖需联网拉取（此前构建一直 `-o` 离线）→ 单独执行在线解析一次后回归离线；不引 testcontainers 的 spring 集成，只用 core。
4. **压测指标的解释口径**：吞吐数字依赖机器 → 报告固定记录环境（CPU/内存/OS/JVM 参数），结论以相对对比（千 vs 万的扩展性曲线）为主，绝对值仅参考。

## 6. 执行节奏

T36 → T37 → T38 → T39；每任务完成后全量回归（整 reactor，离线）；T37 压测与 T38 容器档互不阻塞可并行。T39 全绿 + 压测报告 + 验收记录落盘即 M4 关闭。
