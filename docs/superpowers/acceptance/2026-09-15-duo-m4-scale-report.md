# Duo M4 压测报告 —— 千~万 Worker 心跳（§14 M4 验收）

- 日期：2026-09-15
- 承载任务：M4 计划 T36/T37（`docs/superpowers/plans/2026-09-15-duo-m4-scale-bridge-plan.md`）
- 场景文件：`duo-sim-examples/src/test/resources/scenarios/scale-1k.yaml` / `scale-10k.yaml`
- 验收测试：`ScaleAcceptanceTest`（`-Dduo.scale=true` 显式触发，计划 D2）

---

## 1. 环境与口径

| 项 | 值 |
| --- | --- |
| OS | Windows 11 家庭中文版 26200 |
| CPU | 16 逻辑核 |
| 内存 | 15.8 GB |
| JVM | JDK 21（`mvnw.sh` 工具链：jdk-21.0.12.1+1） |
| JVM 参数 | surefire 缺省（无显式 -Xmx 调优） |
| 拓扑 | VirtualRegistry（virtual）+ demo-scheduler（real, in-process SUT）+ VirtualWorker ×N |
| 心跳周期 | 1s（`heartbeat.interval.ms: 1000`，T36 可配化） |
| 心跳事件采样 | 1k：每 100 条发 1 条；10k：每 1000 条发 1 条（计划 D3；吞吐以 `sut.heartbeat-meter` 真实计数为准，不随采样失真） |
| 复现命令 | `./mvnw.sh -o -pl duo-sim-examples test -Dtest=ScaleAcceptanceTest -Dduo.scale=true` |

指标口径：注册爬坡＝`sut.worker-registered` 达到 count 的时刻；稳态吞吐＝`sut.heartbeat-meter` 事件 `ratePerSec` 峰值（5s 滑动窗，master 侧收到的 HeartbeatReport 帧计数）；绝对值随机器浮动，结论以 1k→10k 的扩展性对比为主。

## 2. 实测结果

| 指标 | 千档（1000 workers） | 万档（10000 workers） |
| --- | --- | --- |
| 注册爬坡 | **1 s** | **10 s**（＝拨号错峰设计值 index×1ms，10k 内 99% 在错峰窗内完成） |
| 注册完成率 | 1000/1000（100%） | 10000/10000（100%） |
| 稳态心跳吞吐 | **993 /s**（理论 1000，99.3%） | **9,928 /s**（理论 10000，99.3%） |
| 压测窗口总心跳 | 29,717 | 252,624 |
| 事件流总量（采样后） | 3,246 | 26,528 |
| 场景 wall-time | 30 s（30s 长任务定窗） | 30 s |
| 堆增量（used，MBean） | 114 MB | 836 MB |
| GC 时间增量 | 28 ms | 319 ms |
| 实例掉线（运行窗口） | 0 | 0 |

指标 JSON 落盘：`duo-sim-examples/build/scale/scale-heartbeat-{1k,10k}.json`（测试自动生成）。

## 3. 扩展性结论

1. **吞吐线性扩展到万级**：1000→10000 实例（×10），稳态速率 993→9928 /s（×10.0）——虚拟线程模型在万级长连接下无每实例线程成本，连接数不是瓶颈。
2. **注册爬坡确定性好**：错峰设计（实例号×1ms、封顶 10s）使万档爬坡恰为 10s 且 100% 成功；千档 1s。爬坡时间由设计参数控制而非负载副作用——「确定性，无两可」原则在压测口径同样成立。
3. **GC/内存温和**：万档 30s 窗口 GC 仅 319ms（约 1% wall），堆增量 836MB ≈ 84KB/实例（连接缓冲 + 虚拟线程栈 + 状态对象），15.8GB 普通机器可承载万档，无需 JVM 调优即达标。
4. **事件采样有效（D3）**：10k 原始心跳 25 万条，采样后事件流仅 2.6 万条（含框架事件），录制与断言扫描成本与规模解耦。

## 4. 压测过程发现并修复的缺陷

压测的真正价值——三项缺陷在千档/万档暴露并在本轮修复：

1. **[高] DemoScheduler acceptor 串行注册可被单连接拖垮**（T36 修复）：`register()` 在 accept 线程内串行执行且只捕 `IOException`，一个坏连接（如注册阶段抛 `RuntimeException`）即令整个 acceptor 静默死亡——千档实测注册卡死在 201/1000，~800 条已建立连接永远无人 accept。修复：每连接独立虚拟线程（注册+稳态读隔离），注册阶段限时 10s（`SoTimeout`），失败记 `sut.register-failed` 一级事件（§12 不静默）。
2. **[中] worker 连接失败不可观测**（T36 修复）：`runInstance` 吞掉 `IOException`，离线实例无任何事件——注册卡死时 800 实例凭空消失无从定位。修复：连接/注册重试耗尽后发 `sim.worker-instance-offline` 事件（错误原因入载荷）；停止/代次更替中的正常收敛不发（防拆除噪声）。
3. **[中] 万级并发握手洪峰**（T36 修复）：1000+ 实例同时拨号 → 握手读超时批量失败。修复：确定性拨号错峰（index×1ms 封顶 10s）+ 握手整体重试 ×3（退避 500ms×attempt）+ 连接超时 5s。

（另：千档首轮诊断曾显示「心跳周期配置不生效」，实为组件 jar 未重新 install 的构建产物陈旧问题，非代码缺陷——诊断为现象复现环境问题后排除。）

## 5. 瓶颈分析与调优建议（M4 后按需）

- **master 侧单线程首帧读取已消除**（每连接独立线程后），当前稳态瓶颈在事件发布（同步 bus 消费者：COW 快照 + JSONL 追加）——`sut.heartbeat-meter` 每 5s 一条，稳态 9928/s 下单 window 事件成本可忽略；若 M4 后要求逐心跳事件，须先把 recorder 改异步批量（现采样策略 D3 已规避）。
- **Windows 端口面**：万档 = 1 万并发回环连接 + 客户端随机端口，远低于动态端口范围（默认 ~16k）上限；更高规模（10 万级）才需考虑端口分片/多监听器。
- **锁步风险**：万档心跳对齐在错峰窗后自然分散（index×1ms 错开相位），无 thundering herd 回归。

## 6. 复现与验收判据核对

- 验收口径（计划 §1）：千档完整跑通注册→心跳→测量闭环 ✔；万档同一场景改 count 即可运行 ✔（scale-10k.yaml 与 1k 仅差 count/采样率）；压测报告落盘 ✔（本文档）。
- 测试门控：常规回归不含压测（`-Dduo.scale` 未设时 skip 2 条），报告数据由压测模式实测产出 ✔。
