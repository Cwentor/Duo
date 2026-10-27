import type { Edge, Node } from '@xyflow/react'
import { MarkerType } from '@xyflow/react'

// 图谱事实来源：各包 package-info.java 与 docs/ARCHITECTURE.md——节点描述不写这里没有的东西。

export type ModuleKey =
  | 'input'
  | 'scenario'
  | 'kernel'
  | 'contract'
  | 'protocol'
  | 'virtual'
  | 'embedded'
  | 'container'
  | 'real'
  | 'control'
  | 'web'
  | 'obs'
  | 'junit'

export interface ModuleMeta {
  label: string
  bg: string
  border: string
  text: string
}

export const MODULES: Record<ModuleKey, ModuleMeta> = {
  input: { label: '输入', bg: '#f8fafc', border: '#94a3b8', text: '#475569' },
  scenario: { label: 'duo-sim-scenario', bg: '#f0fdfa', border: '#14b8a6', text: '#0f766e' },
  kernel: { label: 'duo-sim-kernel', bg: '#eef2ff', border: '#6366f1', text: '#4338ca' },
  contract: { label: '契约层', bg: '#f5f3ff', border: '#8b5cf6', text: '#6d28d9' },
  protocol: { label: 'duo-sim-protocol', bg: '#f0f9ff', border: '#0ea5e9', text: '#0369a1' },
  virtual: { label: 'virtual 档', bg: '#ecfdf5', border: '#10b981', text: '#047857' },
  embedded: { label: 'embedded 档', bg: '#fffbeb', border: '#f59e0b', text: '#b45309' },
  container: { label: 'container 档', bg: '#fff7ed', border: '#f97316', text: '#c2410c' },
  real: { label: 'real 档 / SUT', bg: '#fff1f2', border: '#f43f5e', text: '#be123c' },
  control: { label: 'duo-sim-control', bg: '#faf5ff', border: '#a855f7', text: '#7e22ce' },
  web: { label: 'console-ui', bg: '#fdf4ff', border: '#d946ef', text: '#a21caf' },
  obs: { label: '观测面', bg: '#ecfeff', border: '#06b6d4', text: '#0e7490' },
  junit: { label: 'duo-sim-junit', bg: '#f7fee7', border: '#84cc16', text: '#4d7c0f' },
}

export interface CardData extends Record<string, unknown> {
  title: string
  module: ModuleKey
  classes: string[]
  desc: string
  doc?: { label: string; url: string }
  sut?: boolean
}

export interface SectionData extends Record<string, unknown> {
  label: string
}

const GH = 'https://github.com/Cwentor/Duo/blob/master/'

// 卡片宽 210、行高基准 120；分组区块（section）不参与连线。
export const initialNodes: Node[] = [
  { id: 'yaml', type: 'card', position: { x: 20, y: 110 }, data: {
    title: '场景 YAML', module: 'input',
    classes: ['name / topology / behaviors', 'timeline / assertions'],
    desc: '唯一事实来源：一份 YAML 描述整条链路（拓扑、行为剧本、故障时间线、断言）。字段全集与校验规则见 DSL 手册。',
    doc: { label: 'SCENARIO-DSL.md', url: GH + 'docs/SCENARIO-DSL.md' },
  } },

  { id: 's-scenario', type: 'section', position: { x: 280, y: -40 }, data: { label: '场景引擎' }, style: { width: 960, height: 380 }, draggable: false, selectable: false },
  { id: 'loader', type: 'card', position: { x: 300, y: 10 }, data: {
    title: '加载 · 校验', module: 'scenario',
    classes: ['ScenarioLoader', 'ScenarioValidator', 'InputTrust'],
    desc: '结构解析 → 规则 1–8 全量校验；外部输入档（REST/控制台上传）加规则 9–11 能力收窄，信任档全链路唯一枚举。',
    doc: { label: 'ARCHITECTURE.md', url: GH + 'docs/ARCHITECTURE.md' },
  } },
  { id: 'engine', type: 'card', position: { x: 300, y: 130 }, data: {
    title: '编排', module: 'scenario',
    classes: ['ScenarioEngine', 'ComponentBootstrapper'],
    desc: '校验 → SUT 启动 → 拓扑启动 → 时间线 → 统一收摊；SUT 双形态（in-process / external）与内核组件共用同一装配核心。',
  } },
  { id: 'timeline', type: 'card', position: { x: 540, y: 130 }, data: {
    title: '时间线', module: 'scenario',
    classes: ['TimelineScheduler', 'HookRegistry'],
    desc: '以启动为 t0 的串行节拍器：按 at 触发注入、带 duration 的动作到期自动 clear；custom-hook 注册表执行 DSL 钩子。',
  } },
  { id: 'journal', type: 'card', position: { x: 780, y: 130 }, data: {
    title: '事件日志', module: 'scenario',
    classes: ['EventJournal', 'EventRecorder'],
    desc: '内存事件流 + events.jsonl 录制 + 世代闸门（同名场景重跑不互相覆盖）；有界缓冲，丢弃显式可见。',
  } },
  { id: 'boot', type: 'card', position: { x: 540, y: 250 }, data: {
    title: '装配', module: 'scenario',
    classes: ['ComponentBootstrapper'],
    desc: 'provider 解析 → 实例化 → config/capacity/count 合并 → behaviors 展平 → exposes → init；SUT 先于内核组件启动。',
  } },

  { id: 's-kernel', type: 'section', position: { x: 1320, y: -40 }, data: { label: '仿真内核' }, style: { width: 1010, height: 380 }, draggable: false, selectable: false },
  { id: 'wiring', type: 'card', position: { x: 1340, y: 10 }, data: {
    title: '接线解析', module: 'kernel',
    classes: ['WiringResolver'],
    desc: '§6.1 判定表唯一权威：wire（真实协议端口）/ direct（同 JVM 注入契约接口）由能力元数据推断或显式指定，校验器消费同一份结论。',
  } },
  { id: 'runtime', type: 'card', position: { x: 1340, y: 130 }, data: {
    title: '注入通路', module: 'kernel',
    classes: ['ScenarioRuntime'],
    desc: 'target 解析 → 三查（越界/声明/实例能力）→ sim.fault-injected 先因后果落流 → 分发生命周期或 FaultInjectable 动作。',
  } },
  { id: 'sutface', type: 'card', position: { x: 1340, y: 250 }, data: {
    title: 'SUT 适配面', module: 'kernel',
    classes: ['SutLauncher', 'ExternalSutLauncher', 'ReadyProbe'],
    desc: 'in-process：独立线程跑 SutMain；external：端点告知（配置文件/stdout 双途径）+ 就绪探针 + 进程退出竞速，生命周期归用户。',
  } },
  { id: 'registry', type: 'card', position: { x: 1580, y: 10 }, data: {
    title: '契约注册表', module: 'kernel',
    classes: ['ContractRegistry', 'CapabilityMetadata'],
    desc: 'ServiceLoader 加载 (contract, tier) 实现；注册期一致性校验（NONE⇒direct 等）+ 缺省唯一性——启动前校验只读静态元数据。',
  } },
  { id: 'manager', type: 'card', position: { x: 1580, y: 130 }, data: {
    title: '组件管理器', module: 'kernel',
    classes: ['ComponentManager'],
    desc: '依赖拓扑排序启动、失败逆序拆除、统一清理；SUT 协作停止并入同一拆除序列。',
  } },
  { id: 'bus', type: 'card', position: { x: 1820, y: 130 }, data: {
    title: '事件总线', module: 'kernel',
    classes: ['SimpleEventBus', 'Event', 'EventProjections'],
    desc: '进程内发布/订阅（订阅在发布线程同步执行）；sim.* 框架事件与 sut.* SUT 事实共用单一事件流——唯一事实来源。',
  } },
  { id: 'spi', type: 'card', position: { x: 1820, y: 10 }, data: {
    title: '组件 SPI', module: 'kernel',
    classes: ['VirtualComponent', 'ComponentProvider', 'ComponentContext'],
    desc: '生命周期 init/start/stop/restart；8 契约接口定义必发事件与档位语义差异（如 flap 后快照重放 vs 会话真实丢失）。',
  } },
  { id: 'assertions', type: 'card', position: { x: 1580, y: 250 }, data: {
    title: '断言内核', module: 'kernel',
    classes: ['AssertionParser', 'Assertions'],
    desc: 'failoverWithin / noTaskLost / eventSequence / affectedTasksAtLeast——起点事件、成功判据、窗口边界语义钉死在实现上。',
  } },

  { id: 'contracts', type: 'card', position: { x: 700, y: 420 }, data: {
    title: '8 角色契约', module: 'contract',
    classes: ['registry · worker · scheduler', 'engine · store · resource', 'message · filestore'],
    desc: '内核唯一认识的抽象——不认识任何具体产品。档位 virtual < embedded < container < real，同一契约位换档测试代码零改动。',
  } },

  { id: 's-tier', type: 'section', position: { x: 280, y: 560 }, data: { label: '契约实现：四档 × 线协议' }, style: { width: 2050, height: 210 }, draggable: false, selectable: false },
  { id: 'virtual', type: 'card', position: { x: 300, y: 620 }, data: {
    title: 'virtual 档', module: 'virtual',
    classes: ['VirtualWorker / Registry / Scheduler', 'VirtualEngine / Filestore', 'MessageBroker / ResourceManager'],
    desc: '进程内状态机与行为模型（BehaviorProfile 剧本）；故障动作 freeze/slow/resource-exhaust/task-kill/registry-flap 全支持。',
    doc: { label: 'components README', url: GH + 'duo-sim-components/README.md' },
  } },
  { id: 'embedded', type: 'card', position: { x: 540, y: 620 }, data: {
    title: 'embedded 档', module: 'embedded',
    classes: ['CuratorRegistry（真 ZK 端口）', 'H2Store（真 JDBC）', 'Fabric8K8sMock（K8s REST）'],
    desc: 'JVM 内跑真实协议的内存化替身——SUT 连的是真端口、真驱动，不需要 Docker。',
    doc: { label: 'embedded README', url: GH + 'duo-sim-embedded/README.md' },
  } },
  { id: 'container', type: 'card', position: { x: 780, y: 620 }, data: {
    title: 'container 档', module: 'container',
    classes: ['ZookeeperContainerRegistry', 'PostgresContainerStore'],
    desc: 'Testcontainers 按需拉起真 ZK / 真 PostgreSQL（CI container job 断言 skip=0）；不支持 flap/restart，校验期显式拒绝。',
  } },
  { id: 'real', type: 'card', position: { x: 1020, y: 620 }, data: {
    title: 'real 档 · SUT', module: 'real', sut: true,
    classes: ['DemoScheduler（master 侧）', 'RealWorkerSut（worker 侧）', 'DemoRealWorker'],
    desc: '被测的唯一真实实现：拓扑标 sut: true。两档 scheduler 共用状态机与连接面，换「谁是 SUT」不改 YAML 结构。',
    doc: { label: 'examples README', url: GH + 'duo-sim-examples/README.md' },
  } },
  { id: 'protocol', type: 'card', position: { x: 1330, y: 620 }, data: {
    title: 'Duo 线协议', module: 'protocol',
    classes: ['FrameCodec / FrameConnection', 'DuoCodec / 8 类报文'],
    desc: 'worker↔master 的自定义 TCP 帧（帧边界即协议边界）；第三方适配器只需依赖此工件。',
  } },

  { id: 's-control', type: 'section', position: { x: 20, y: 840 }, data: { label: '控制面（REST · CLI · Web）' }, style: { width: 1520, height: 340 }, draggable: false, selectable: false },
  { id: 'web', type: 'card', position: { x: 40, y: 890 }, data: {
    title: 'Web 控制台', module: 'web',
    classes: ['Vue3 + TS + Vite SPA', '画布编辑⇄运行 / 注入 / 观测'],
    desc: 'M10：场景库、双模式画布、注入面板、观测三 tab；duo serve 同源托管 /console/**，CSP default-src \'self\'。',
    doc: { label: 'console-ui', url: GH + 'console-ui' },
  } },
  { id: 'host', type: 'card', position: { x: 270, y: 890 }, data: {
    title: '内核适配层', module: 'control',
    classes: ['ScenarioHost'],
    desc: '持有引擎生命周期，向 REST/CLI 暴露统一操作面；§10 约束：零内核改动，只用既有公开 API。',
  } },
  { id: 'rest', type: 'card', position: { x: 500, y: 890 }, data: {
    title: 'REST 服务', module: 'control',
    classes: ['RestControlServer / RestRoutes', 'Bearer + 回环校验 + 1MiB 上限'],
    desc: 'JDK HttpServer + 虚拟线程；认证/跨站/上限/错误掩码收敛在端点管道一处执行。除 /health 全端点要求令牌。',
  } },
  { id: 'cli', type: 'card', position: { x: 500, y: 1000 }, data: {
    title: 'CLI', module: 'control',
    classes: ['DuoCli：run / serve / inject', 'status / events / assert / diagnose'],
    desc: '同进程直连与 REST 客户端双模式；退出码即结论，供脚本化验收与 CI 使用。',
  } },
  { id: 'metrics', type: 'card', position: { x: 730, y: 890 }, data: {
    title: '指标', module: 'control',
    classes: ['MetricsCollector（/metrics）'],
    desc: '从事件流与组件状态读数的薄层（19 指标族）；_total 计数器按游标单调推进——不丢、不重。',
    doc: { label: 'METRICS.md', url: GH + 'docs/METRICS.md' },
  } },
  { id: 'diag', type: 'card', position: { x: 960, y: 890 }, data: {
    title: '诊断链', module: 'control',
    classes: ['FaultDiagnostics / FaultLog'],
    desc: '一次注入的完整因果链四段重建：注入 → 组件反应 → SUT 事实 → 断言；断链显式报 MISSING，退出码非 0。',
  } },
  { id: 'library', type: 'card', position: { x: 1190, y: 890 }, data: {
    title: '场景库', module: 'control',
    classes: ['ScenarioLibrary'],
    desc: '内置模板（classpath 只读）+ 用户库（--library-dir）；id 白名单防路径逃逸，上传 YAML 按外部输入档全量校验。',
  } },
  { id: 'junit', type: 'card', position: { x: 40, y: 1000 }, data: {
    title: 'JUnit5 扩展', module: 'junit',
    classes: ['@VirtualCluster', 'DuoAssertions'],
    desc: '场景即测试夹具：扩展管理引擎生命周期并注入参数；编程式断言与 YAML 内置评估共用同一事件事实源。',
    doc: { label: 'junit README', url: GH + 'duo-sim-junit/README.md' },
  } },

  { id: 's-obs', type: 'section', position: { x: 1600, y: 840 }, data: { label: '观测面（三条互补通道）' }, style: { width: 730, height: 340 }, draggable: false, selectable: false },
  { id: 'eventsfile', type: 'card', position: { x: 1620, y: 890 }, data: {
    title: '事件流', module: 'obs',
    classes: ['events.jsonl / duo events'],
    desc: '机器通道，唯一的事实来源——断言、录制、指标都从它取数。',
  } },
  { id: 'logs', type: 'card', position: { x: 1850, y: 890 }, data: {
    title: '日志', module: 'obs',
    classes: ['io.duo.sim.fault'],
    desc: '人读通道：稳定可 grep 的因果链日志，与事件流同源同序。',
  } },
  { id: 'metricsout', type: 'card', position: { x: 1620, y: 1000 }, data: {
    title: '指标', module: 'obs',
    classes: ['GET /metrics'],
    desc: '聚合通道：Prometheus 文本格式，可直接被 scrape。',
  } },
  { id: 'assert', type: 'card', position: { x: 1850, y: 1000 }, data: {
    title: '断言结果', module: 'obs',
    classes: ['duo assert / ScenarioResult'],
    desc: '判定通道：逐条 PASS/FAIL 写入场景结果；CLI 退出码与 CI 结论来源。',
  } },
]

const dep = (id: string, source: string, target: string, label?: string,
  sourceHandle = 'r', targetHandle = 'l'): Edge => ({
  id, source, target, sourceHandle, targetHandle, label,
  type: 'smoothstep',
  style: { stroke: '#94a3b8', strokeWidth: 1.5 },
  markerEnd: { type: MarkerType.ArrowClosed, color: '#94a3b8' },
})

const event = (id: string, source: string, target: string, label?: string,
  sourceHandle = 'r', targetHandle = 'l'): Edge => ({
  id, source, target, sourceHandle, targetHandle, label,
  type: 'smoothstep', animated: true,
  style: { stroke: '#0891b2', strokeWidth: 1.8, strokeDasharray: '6 4' },
  markerEnd: { type: MarkerType.ArrowClosed, color: '#0891b2' },
})

export const initialEdges: Edge[] = [
  // 场景引擎内部流水线
  dep('e1', 'yaml', 'loader', '载入'),
  dep('e2', 'loader', 'validator', '结构→语义', 'b', 't'),
  dep('e3', 'validator', 'engine', '通过', 'b', 't'),
  dep('e4', 'engine', 'boot', '装配', 'b', 't'),
  dep('e5', 'boot', 'wiring', '接线解析', 'r', 'l'),
  dep('e6', 'boot', 'registry', '解析实现', 'r', 'l'),
  dep('e7', 'boot', 'manager', '拓扑启动', 'r', 'l'),
  dep('e8', 'boot', 'sutface', 'SUT 双形态', 'b', 't'),
  dep('e9', 'inputtrust', 'validator', '档位决定规则 9–11', 'b', 'r'),
  dep('e10', 'engine', 'journal', '持有', 'r', 'l'),
  dep('e11', 'timeline', 'runtime', '时间线动作', 'r', 'l'),
  // 内核
  dep('e12', 'spi', 'registry', 'ServiceLoader 注册', 'b', 'r'),
  dep('e13', 'registry', 'manager', 'resolve', 'b', 't'),
  event('e14', 'runtime', 'bus', 'sim.* 事件', 'r', 'l'),
  event('e15', 'bus', 'journal', '单一订阅点', 'b', 'r'),
  // 契约与四档实现
  dep('e16', 'contracts', 'virtual', '实现', 'b', 't'),
  dep('e17', 'contracts', 'embedded', '实现', 'b', 't'),
  dep('e18', 'contracts', 'container', '实现', 'b', 't'),
  dep('e19', 'contracts', 'real', '实现', 'b', 't'),
  dep('e20', 'manager', 'virtual', '拓扑序启动', 'b', 't'),
  dep('e21', 'manager', 'embedded', '拓扑序启动', 'b', 't'),
  dep('e22', 'manager', 'container', '拓扑序启动', 'b', 't'),
  dep('e23', 'sutface', 'real', '启动 SUT', 'b', 't'),
  // 线协议：worker 拨号 ↔ master 接受
  dep('e24', 'virtual', 'protocol', 'worker 拨号', 'r', 'l'),
  dep('e25', 'protocol', 'real', 'accept 注册', 'b', 'r'),
  // SUT 事实回流
  event('e26', 'real', 'bus', 'sut.* 事实', 't', 'b'),
  // 控制面
  dep('e27', 'web', 'rest', 'fetch /api/**', 'r', 'l'),
  dep('e28', 'cli', 'rest', '--url REST 客户端', 't', 'b'),
  dep('e29', 'rest', 'host', '端点管道', 'r', 'l'),
  dep('e30', 'rest', 'web', 'SPA 托管 /console/**', 'b', 'r'),
  dep('e31', 'host', 'engine', '持有 / 操作', 't', 'b'),
  dep('e32', 'host', 'metrics', '读数', 'r', 'l'),
  dep('e33', 'host', 'diag', '因果链重建', 'b', 't'),
  dep('e34', 'junit', 'engine', '@VirtualCluster 驱动', 't', 'r'),
  // 观测面：三条通道都从同一事实源取数
  event('e35', 'journal', 'eventsfile', 'JSONL 落盘', 'b', 't'),
  event('e36', 'journal', 'logs', '人读镜像', 'b', 't'),
  event('e37', 'metrics', 'metricsout', 'Prometheus 文本', 'r', 'l'),
  event('e38', 'journal', 'assert', '事件快照评估', 'b', 't'),
]
