# Duo 场景 DSL 参考

- 适用版本：`0.1.0-SNAPSHOT`（HEAD `5b72753`）
- 实现：`ScenarioLoader`（结构解析）→ `ScenarioValidator`（语义校验规则 1–8）→ `ScenarioEngine`（编排执行）
- 依据：设计文档 v1.0 §8（`§n` 引用均指该文档）

场景文件是一份 YAML，描述**一次仿真运行**：拓扑 + 行为剧本 + 时间线 + 断言。
顶层结构：

```yaml
name: <场景名>            # 必填（用于录制目录 build/scenarios/<name>/）
topology: [ ... ]        # 必填，非空
behaviors: { ... }       # 可选
timeline: [ ... ]        # 可选
assertions: [ ... ]      # 可选
```

---

## 1. `topology[]` 节点字段

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| `id` | ✅ | string | 节点 id，**全场景唯一**；也是事件 `sourceId` 与注入 `target` 的组件名 |
| `contract` | ✅ | string | `registry`/`store`/`worker`/`engine`/`scheduler`/`resource`/`message`/`filestore`（大小写不敏感） |
| `tier` | ✅ | string | `virtual`/`embedded`/`container`/`real` |
| `sut` | — | bool | 标为被测对象；**全场景恰好一个**（规则 5） |
| `count` | — | int | 实例数（>1 时展开为逻辑实例，寻址 `id[N]`，**N 从 1 开始**）；缺省 1 |
| `impl` | — | string | 显式指定实现名（同 `(contract,tier)` 下有多个实现时）；缺省取 `default: true` 的那个 |
| `config` | — | map | 自由键值，原样注入组件 `ComponentContext.config()`（值统一转字符串） |
| `capacity` | — | map | 资源容量；展开为 `capacity.<key>` 注入 config（如 `capacity.slots`） |
| `exposes` | — | list | 对外暴露的契约端点声明，见 §1.1 |
| `wiring` | — | map | 依赖槽声明，见 §1.2 |
| `launch` | — | map | 启动方式（SUT/external 节点），见 §1.3 |

### 1.1 `exposes[]`

```yaml
exposes:
  - contract: scheduler     # 暴露哪个契约
    port: 0                 # 0 = 内核分配空闲端口；external 节点必须显式非 0
    addr: 127.0.0.1         # 可选，缺省 127.0.0.1
```

- 非 0 固定端口在**启动前**做占用检查（规则 8，只查 `127.0.0.1`）；端口**可达性**属启动后 ready 阶段。
- `endpoints()` 返回的是运行时实际绑定地址（`ExposedEndpoint`，带 `tcp`/`fs` 类型）。

### 1.2 `wiring`

```yaml
wiring:
  registry: zk                                             # 简写：仅当槽名 == 已注册契约名
  registry: { node: zk, contract: registry }               # 显式
  registry: { node: zk, path: wire }                       # 显式连接路径
  registry: { node: zk, contract: registry, path: direct }
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `node` | ✅ | 目标节点 id |
| `contract` | 简写时可省 | 槽期望契约；必须与目标节点 `contract` 一致（规则 2）。简写形态下槽名即契约名 |
| `path` | — | `wire` 或 `direct`；缺省按目标端点形态推断（见 §4.2） |

**设计意图提示**：拓扑里「没有某个槽」通常是有意的（如 master 无 `workers` 槽——worker 经 registry 发现 master，
故障转移路径正依赖该发现机制），不要当成遗漏。

### 1.3 `launch`

```yaml
# in-process SUT（内核在同一 JVM 内以独立线程调 run(SutContext)）
launch: { mode: in-process, main: io.duo.sim.examples.scheduler.DemoScheduler }

# external SUT（M6 已实现）：内核代起子进程 + 端点告知 + ready 探针
launch:
  mode: external
  command: '${java} -jar third-party-sut.jar --duo.config'   # 内核代起（可省＝attach 形态）
  configOut: build/sut.properties     # 内核生成端点配置文件（主途径）
  ready: { type: tcp, port: 8123, timeout: 30s }   # 探针声明（只认 launch.ready 下的键）
```

| 字段 | 说明 |
| --- | --- |
| `mode` | `in-process`（缺省）或 `external` |
| `main` | `in-process` 必填：`SutMain` 实现类的全限定名（反射实例化，需无参构造器） |
| `command` | `external` 可选：内核代起的外部进程命令行（M6 决策 D7）。按空白切分、支持引号包裹；`${java}` / `${java.home}` 展开为**当前 JVM** 的 java 可执行文件/JDK 家目录，使场景文件不写死本机路径。**省略 `command` ＝ attach 形态**：进程由用户自行启动，内核只写端点配置 + 探针就绪（此时进程退出不可观测） |
| `configOut` | `external` 必填：端点配置文件输出路径（§7.3 主途径；校验规则 4） |
| `ready` | `external` 必填的探针声明；`ScenarioLoader` 把 `launch.ready.*` 展平为 `config["ready.<k>"]`，见 §1.4 |

### 1.4 `launch.ready`（就绪探针，M6）

| 键 | 取值 | 说明 |
| --- | --- | --- |
| `type` | `tcp` / `http` | `external` 必填；其他值**启动前**拒绝（规则 4） |
| `port` | int | 探针端口；省略时取该节点 `exposes` 的首个非 0 端口 |
| `host` | string | 缺省 `127.0.0.1` |
| `path` | string | `http` 用，缺省 `/` |
| `timeout` | 带单位时长（`ms`/`s`/`m`） | 缺省 `60s`；到期仍不可达归**启动失败路径**（§12，不静默） |

- 探针只做**可达性**判定：`tcp` 能建立连接即就绪；`http` 收到状态码 < 500 的应答即就绪。
- 轮询间隔 200ms；**进程在 ready 前退出优先报退出根因**（`exited before ready`），不把根因拖成超时。
- `ready.*` 属内核侧声明，**不写入端点配置文件**（SUT 拿到的配置里不会出现 `ready.*` 键）。
- in-process SUT 亦可用 `ready: { timeout: 90s }` 覆盖 `ctx.ready()` 回调超时（M6 起全链路消费，见 §8）。

### 1.5 external SUT 的端点告知与生命周期（M6）

| 方向 | 途径 | 落点 |
| --- | --- | --- |
| 内核 → SUT | **主途径**：端点配置文件 | `launch.configOut`；写入 `duo.endpoint.<contract>=<endpoint>` 与节点 `config` 键值；路径另经环境变量 `duo.config` 告知子进程 |
| SUT → 内核 | **兜底途径**：stdout | 行首为 `duo.endpoint.<contract>=<endpoint>` 的行（容忍首尾空白）→ `sim.external-endpoint` 事件（`source=stdout`） |

- **行格式是严格契约**：前缀必须在**行首**。SUT 若把内核写入的配置行原样回显到 stdout，会被当作
  自身端点宣告——宣告行须由 SUT 显式打印。
- **生命周期归用户**（§7.3）：场景结束**只拆接线、不杀进程**，发 `sim.external-process-left-running`
  事件 + 终态警告提示用户自行终止；句柄经 `ScenarioEngine.externalSut()` 暴露（`process()` 可自行终止）。
- **启动失败即销毁**（决策 D9）：ready 超时或 ready 前退出时内核销毁子进程（未就绪的进程从未成为 SUT，
  留着必然泄漏）——与「场景结束不杀」不冲突。
- **attach 形态**（省略 `command`）：进程由用户自行启动，内核只写端点配置 + 探针就绪；此时**进程退出
  不可观测**（无 `sut.exited`/`sut.crashed`），需要退出事实请用 `command` 代起形态。

---

## 2. `behaviors` 行为剧本

```yaml
behaviors:
  profiles:
    default:   { duration: 3s, jitter: 0.1, successRate: 0.9 }
    spark-etl: { duration: 5s, failAt: 60 }
  bindings:
    - node: workers
      profile: default
    - node: workers
      match: { taskName: "spark-*" }     # 通配
      profile: spark-etl
    - node: workers
      match: { label: critical }         # 标签
      profile: spark-etl
```

### 2.1 profile 字段（TaskStub 行为模型，§9）

| 字段 | 取值 | 语义 | 缺省 |
| --- | --- | --- | --- |
| `duration` | 带单位 `ms`/`s`/`m`，或无单位（＝毫秒） | 执行时长 | `1000` |
| `jitter` | `[0,1]` 比率 | 时长抖动幅度：`actual = duration × (1 + jitter × U(-1,1))` | `0.0` |
| `successRate` | `[0,1]` | 成功率（在 `exception`/`failAt` 未触发时按概率判成败） | `1.0` |
| `failAt` | `0`–`100` 整数 | 进度到达该百分比时**确定性失败**（执行在失败点截断） | 无 |
| `exception` | 异常类名字符串 | 每次尝试必抛（模拟报错） | 无 |
| `neverReport` | bool | 领取后**永不回报终态**（僵尸任务；槽位照常释放） | `false` |
| `progress` | `off` / `periodic` | `periodic` 时执行期间每 25% 进度回调一次（worker 转成 `sim.worker-task-progress`） | `off` |
| `logLines` | — | ⚠️ **声明在 `BehaviorProfile` 但未接入 DSL**（`BehaviorResolver` 固定传空列表），见 §8 |

> ⚠️ **与设计文档 §8 示例的差异**：设计示例写 `jitter: 20%`、`failAt: 60%`，**实现只接受数值**
> （`0.1` / `60`）。写成百分号会抛 `NumberFormatException`。

### 2.2 匹配优先级（四级）

**精确任务名 > 标签 > 通配 pattern > default**（`BehaviorResolver.resolve`）。

`ScenarioEngine.mergeBehaviors` 把绑定展平成 config 键：

| 绑定形态 | 展平前缀 |
| --- | --- |
| `profile: default` 或 `match` 全空 | `behaviors.default.` |
| `match: { label: L }` | `behaviors.by-label.L.` |
| `match: { taskName: X }` | `behaviors.named.X.`（X 含 `*` 时归通配表） |

通配只支持**单个 `*`**（前缀/后缀/中缀），不支持多星或正则。

---

## 3. `timeline` 时间线故障注入

```yaml
timeline:
  - at: 10s                    # 相对场景启动（t0 = 全部组件启动完成时刻）
    action: crash
    target: workers[3]         # 实例下标从 1 开始；不写下标＝整组
  - at: 20s
    action: registry-flap
    target: zk
    duration: 5s               # 到期自动 clear（仅 FaultInjectable 类动作有效）
  - at: 25s
    action: custom-hook
    target: master             # 允许指向 SUT（§7.2 豁免）
    params: { hook: quiesce }
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `at` | ✅ | 相对延时（带单位 `ms`/`s`/`m`）；解析失败记 warning 并跳过该条 |
| `action` | ✅ | 动作类型，见下表 |
| `target` | ✅ | `componentId` 或 `componentId[N]` |
| `duration` | — | 持续窗口；到期自动 `clear()`。**crash/restart 带 duration 会被警告并忽略**（无可清除之物） |
| `params` | — | 动作参数（`custom-hook` 用 `{hook: <名>}`，其余透传给 `FaultAction.params`） |

### 3.1 动作类型与实现状态

| 动作 | 类别 | 实现状态 | 支持的实现 |
| --- | --- | --- | --- |
| `crash` | 生命周期 | ✅ 已落地 | 任意可 `stop(CRASH)` 的组件 |
| `restart` | 生命周期 | ✅ 已落地 | 任意可 `restart()` 的组件（容器档 registry **显式拒绝**） |
| `registry-flap` | FaultInjectable | ✅ 已落地 | `VirtualRegistry`（持续窗口）、`CuratorRegistry`（瞬时整服重启） |
| `task-kill` | FaultInjectable | ✅ 已落地 | `VirtualWorker`（实例级） |
| `custom-hook` | 用户钩子 | 🟡 通路已实现，**引擎未暴露注册入口**（见 §8） | `HookRegistry` |
| `freeze` | FaultInjectable | ❌ 仅有常量声明 | — |
| `slow` | FaultInjectable | ❌ 仅有常量声明 | — |
| `resource-exhaust` | FaultInjectable | ❌ 仅有常量声明 | — |

### 3.2 实例寻址与无降级

- 寻址记法 `componentId[index]`，**索引从 1 开始**：`workers[3]` 即实例 `workers-3`。
- 携带下标时，目标实现必须声明 `instanceControl` 能力，否则**校验期直接失败**（规则 6）——
  不存在「实例级请求被悄悄改写成整组生效」的降级。
- `target` **不得为 SUT**（in-process 无法安全强杀；external 生命周期归用户）；**唯一豁免是 `custom-hook`**。

### 3.3 两档 `registry-flap` 语义不对称

| 档位 | 语义 | `duration` |
| --- | --- | --- |
| `virtual`（`VirtualRegistry`） | 内存会话闪断，**持续窗口** | ✅ 有效（窗口结束自动恢复） |
| `embedded`（`CuratorRegistry`） | `TestingServer.restart` **瞬时整服重启** | ❌ 被忽略——校验器给**显式警告**，不静默 |

---

## 4. 校验规则（规则 1–8）

启动前快速失败；除规则 8 的端口占用外，全部只读契约注册表的**静态能力元数据**（§6/§7.5）。

| # | 规则 | 失败表现 |
| --- | --- | --- |
| 1 | 契约名必须已注册；`(contract, tier)` 必须能解析出实现（`impl` 指定则必须存在）；**交互型契约声明 embedded/container 档被拒绝** | `node X: unknown contract / no implementation registered / interactive contract ... has no embedded implementation form` |
| 2 | wiring 槽的期望契约必须与目标节点契约一致；简写形态要求「槽名 == 契约名」 | `slot 'x' expects contract ... but target ... is ...` |
| 3 | 连接路径校验：`wire` 要求目标端点形态 ≠ NONE；`direct` 要求**消费方 in-process 且目标 `interfaceDirect: true`**；缺省推断：端点形态 ≠ NONE → wire，否则 direct | `targets endpoint-less ...; upgrade target tier or use direct path` / `is external: slot ... cannot use interface-direct` / `does not support interface-direct` |
| 4 | external 节点：被引用的契约槽必须在 `exposes` 显式声明非 0 端口；必须声明 ready 探针 | `must declare an explicit port` / `exposes no port for it` / `must declare a ready probe` |
| 5 | `sut: true` 恰好一个；节点 id 唯一且非空 | `exactly one node must be marked sut: true` / `duplicate node id` |
| 6 | 时间线：`target` 可解析；**不得为 SUT**（`custom-hook` 豁免）；下标 ∈ `[1,count]`；动作须在 `supportedFaults` 或属 `crash`/`restart`；带下标须有 `instanceControl` | `is SUT (§7.2; custom-hook excepted)` / `index out of [1,count]` / `unsupported by ... (§7.2 无降级)` / `requires instanceControl capability` |
| 7 | 每个**具名** profile 至少一个绑定；绑定不得引用未知 profile（`default` 免绑定） | `behavior profile 'x' has no binding (rule 7)` |
| 8 | `exposes` 声明的固定端口（非 0）未被占用 | `fixed port N is already in use` |

额外校验：`assertions` 节在**校验期**即解析，未知断言名/形态错误＝启动前失败（不静默忽略）。

**警告（不阻断启动）**：embedded registry 上误写 `registry-flap` 的 `duration`。

---

## 5. 完整示例

见 `duo-sim-examples`：

| 场景 | 演示要点 |
| --- | --- |
| `src/main/resources/scenarios/m0-acceptance-virtual-workers.yaml` | M0 档位切换（virtual workers） |
| `src/main/resources/scenarios/m0-acceptance-real-workers.yaml` | M0 档位切换（real workers，仅 `tier` 一行不同） |
| `src/main/resources/scenarios/m1-failover-acceptance.yaml` | M1 金标准：4 并行长任务 + crash/registry-flap/restart 三动作 + 4 断言 |
| `src/main/resources/scenarios/m3-inject-demo.yaml` | M3 热注入（`timeline: []`，故障由 CLI 运行中注入） |
| `src/test/resources/scenarios/m2-reelection-acceptance.yaml` | M2 金标准：embedded ZK 双路径 wiring（SUT `wire` / 框架组件 `direct`）+ 重选主断言 |
| `src/test/resources/scenarios/junit-extension-smoke.yaml` | `@VirtualCluster` 冒烟 |
| `src/test/resources/scenarios/scale-1k.yaml` / `scale-10k.yaml` | M4 压测（`-Dduo.scale=true` 触发） |

---

## 6. 断言 `assertions`

YAML 内置评估在场景结束（`ScenarioEngine.stop()`）执行，结果写入 `ScenarioResult`；
`duo assert` / `GET /assertions` 的退出码/`passed` 即结论。JUnit 侧等价 API 见 `DuoAssertions`。

| 断言 | 形态 | 语义 |
| --- | --- | --- |
| `failoverWithin` | `{ seconds: 30 }`（`seconds` 缺省 30） | 起点＝`sim.fault-injected` 且 `payload.action=crash`；成功＝受影响任务在新实例上**首次重派发**且随后到达 `SUCCESS`，均在窗口内。「新实例」判定：不得为 crash 时刻的实例，除非重派发发生在该实例 `sim.worker-instance-restarted` 之后 |
| `noTaskLost` | `- noTaskLost` 或 `{ requireAllSuccess: true }`（缺省 true） | 所有 `sut.task-dispatched` 过的任务都有终态；`requireAllSuccess=true` 时终态必须为 `SUCCESS`（`FAILED`/`SKIPPED` 判不通过） |
| `masterReelectedWithin` | `{ seconds: 30 }` | 起点＝`sim.fault-injected` 且 `payload.action=registry-flap`；成功＝窗口内出现 `sut.leader-elected`（闪断前的首次选主不算） |
| `eventSequence` | `[typeA, typeB, ...]` | 给定事件类型子序列按序出现（不要求相邻） |
| `affectedTasksAtLeast` | `{ min: 1 }` | crash 时归属该实例的在途任务数 ≥ min——**防空真守护**（防止「crash 时根本没任务受影响」也亮绿灯） |

> **建议**：`failoverWithin` 一律与 `affectedTasksAtLeast` 搭配使用，否则「没任务可转移」也会通过。

---

## 7. 内置组件 config 键参考

### 7.1 `VirtualWorker`（worker / virtual）

| 键 | 缺省 | 说明 |
| --- | --- | --- |
| `count` | `1` | 实例数（由节点 `count` 注入） |
| `capacity.cpu` | `4` | 每实例 CPU |
| `capacity.memGB` | `8` | 每实例内存 |
| `capacity.slots` | ＝`capacity.cpu` | 每实例任务槽位（`slots: 1` 常用于逼出重派发路径） |
| `heartbeat.interval.ms` | 内置缺省 | 心跳周期（压测用 `1000`） |
| `behaviors.*` | — | 行为剧本展平键（§2.2） |

### 7.2 `DemoScheduler`（scheduler / real，参考 SUT）

| 键 | 缺省 | 说明 |
| --- | --- | --- |
| `dag.tasks` | 内置示例 | 逗号分隔任务名 |
| `dag.deps.<task>` | — | 该任务的依赖（逗号分隔）；不写＝无依赖 |
| `heartbeat.eventSampleRate` | `1` | 每 N 条心跳发 1 条 `sut.heartbeat`（吞吐以 `sut.heartbeat-meter` 为准） |
| `registry.mode` | 有端点则 `zk`，否则 `direct` | `direct`＝同进程门面；`zk`＝真实 Curator 客户端（`wire` 路径） |
| `registry.connectString` | 取 `endpointByContract().get("registry")` | ZK 连接串 |

### 7.3 embedded/container 档

| 实现 | 关键 config | 备注 |
| --- | --- | --- |
| `CuratorRegistry` | — | `endpoints()` 暴露真实 ZK 端口；同进程门面供 direct |
| `ZookeeperContainerRegistry` | 容器相关键 | 需 Docker；**不支持 `registry-flap`/`restart`** |
| `H2Store` | `jdbcUrl`（可自定义） | 端点形态 THIRD_PARTY（JDBC URL），**无 interface-direct** |
| `Fabric8K8sMock` | — | 真实 K8s REST 协议；端点 THIRD_PARTY |

---

## 8. 现状与设计偏差（务必先读）

以下为**实现与设计文档/直觉不一致**的地方，均为已知项，已登记进 [`ROADMAP.md`](ROADMAP.md)：

| # | 偏差 | 影响 | 对应路线图 |
| --- | --- | --- | --- |
| 1 | `jitter` 只接受 `[0,1]` 比率，不接受设计示例的 `20%` | 照抄设计 §8 示例会抛异常 | G7 |
| 2 | `failAt` 只接受 `0–100` 整数，不接受 `60%` | 同上 | G7 |
| 3 | `logLines` 在 `BehaviorProfile` 中声明，但 `BehaviorResolver` 固定传空列表 | DSL 写了也不生效 | G7 |
| 4 | ~~`launch.mode=external` 能通过校验，但引擎抛 `M0 engine only supports in-process SUT launch`~~ | **已闭合（M6）**：external 代起/attach 两形态、端点告知双途径、ready 探针、退出/崩溃事实事件全部落地并有端到端验收 | G2 / M6 ✅ |
| 5 | ~~`launch.ready.timeout` 被解析进 config 但引擎未消费~~ | **已闭合（M6）**：in-process 与 external 同口径消费 `ready.timeout`（缺省 60s） | G2 ✅ |
| 6 | ~~`ScenarioValidator` 的 ready 报错文案写 `config: {ready.type: ...}`~~ | **已闭合（M6）**：文案改为 `launch.ready`，且探针 type/端口/时长在**启动前**校验（`ReadyProbe.spec`），写 `config.ready.type` 的后门随之关闭 | G7 部分 ✅ |
| 7 | `custom-hook` 的 `HookRegistry` 无法从 `ScenarioEngine` 注入（引擎内部 `new HookRegistry()`） | YAML 时间线里的 `custom-hook` 必然「no hook registered」失败 | G5 |
| 8 | `freeze`/`slow`/`resource-exhaust` 仅有常量声明，无实现声明 `supportedFaults` | 写了会被校验期拒绝（当前行为正确，属功能未实现） | G5 |
| 9 | `launch.command` / `${java}` 占位符是 **M6 新增的 DSL 字段**（设计文档未定义） | 设计 §7.3 只说「用户自行启动」；实现补了「内核代起并观测退出」的形态，否则验收要求的 `sut.exited`/`sut.crashed` 无法产出（决策 D7） | DECISIONS D7 |

> **M6 修正的另一处实现缺陷（不在 DSL 面，但影响断言写法）**：`sim.fault-injected` 原先在
> `dispatch` **之后**才落流，导致组件在同一注入调用内发布的反应事件（如 embedded
> `sim.registry-flap-started/cleared`）排在「因」之前，使
> `eventSequence: [sim.fault-injected, <反应事件>]` 恒不可满足、以 `sim.fault-injected`
> 为窗口起点的断言变脆。现已改为**先因后果**（`ScenarioRuntime.inject/clear` 先落流再派发），
> 派发失败另记 `sim.fault-inject-failed`。

---

## 9. 常见报错速查

| 报错片段 | 含义 | 处置 |
| --- | --- | --- |
| `exactly one node must be marked sut: true, found 0/2` | SUT 标记缺失或重复 | 恰好标一个 `sut: true` |
| `no implementation registered for registry/container` | 该档位实现不在 classpath | 加对应模块依赖，或换档位 |
| `interactive contract 'worker' has no embedded implementation form` | 交互型契约无 embedded 档 | 用 `virtual`（Duo 协议）或 `real` |
| `targets endpoint-less 'zk'; upgrade target tier or use direct path` | 对无端点目标显式写了 `path: wire` | 改 `direct` 或升档到 embedded |
| `targets 'zk' which does not support interface-direct` | 目标无同进程适配（如 `H2Store`） | 改 `wire` |
| `is external: slot 'x' cannot use interface-direct` | external 消费方不能走 direct | 目标需有真实端点 |
| `external node 'm' must declare launch.configOut` | external 缺端点配置文件路径（§7.3 主途径） | 补 `launch.configOut` |
| `unsupported ready.type 'udp' (expected tcp\|http)` | 探针类型非法 | 改 `tcp` 或 `http` |
| `ready probe needs a port` | 既无 `ready.port` 也无非 0 `exposes` | 补其一 |
| `timeline action 'freeze' unsupported by 'workers'` | 动作未实现 | 换 `crash`/`restart`/`registry-flap`/`task-kill` |
| `timeline target 'workers' is SUT` | 对 SUT 注入 | 改用 `custom-hook`，或改注入替身节点 |
| `requires instanceControl capability` | 目标不支持实例级操作 | 去掉下标（整组）或换实现 |
| `SUT ready timeout (60000ms)` | SUT 未在超时内调 `ctx.ready()` | 检查 SUT 启动路径；超时可用 `ready: { timeout: 90s }` 覆盖 |
| `external SUT ready timeout (30000ms)` | external 探针到期仍不可达 | 检查进程是否监听声明的端口；或调大 `ready.timeout` |
| `external SUT exited before ready (exit code N)` | 子进程在就绪前退出 | 看子进程自身日志（stdout 已并入内核流）；内核会销毁该进程（D9） |
