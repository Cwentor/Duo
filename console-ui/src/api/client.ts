import { session } from '../stores/session'

// ---- 契约类型（与计划一 REST 响应字段一一对应；签名冻结，Java 侧测试钉住）----

/** 场景库条目（GET /api/scenarios 行；镜像 Java ScenarioLibrary.Entry）。 */
export interface ScenarioEntry {
  /** 场景 id（文件名主干；模板 id 来自 classpath index）。 */
  id: string
  /** 场景名（YAML 的 name 字段；解析不过时回落为 id）。 */
  name: string
  /** 是否内置模板（只读——可作 fork 源，不可写/删）。 */
  template: boolean
  /** 解析 + 校验是否通过（不过的条目照常列出，展示为「校验不过」）。 */
  valid: boolean
  /** 节点数（解析失败时 0）。 */
  nodeCount: number
  /** 文件最后修改时间（毫秒时间戳；模板为 0）。 */
  lastModifiedMillis: number
}

/** 能力元数据行（GET /api/capabilities；镜像 Java CapabilityMetadata）。 */
export interface CapabilityRow {
  /** 角色契约（设计 §5 八种之一）。 */
  contract: string
  /** 实现档位（virtual < embedded < container < real，§6）。 */
  tier: string
  /** 实现名（场景 impl 键可指定非缺省实现）。 */
  impl: string
  /** 是否该 (contract, tier) 的缺省实现（唯一性由注册表校验）。 */
  default: boolean
  /** 声明的故障动作集（注入校验依据，§7.2 无降级）。 */
  supportedFaults: string[]
  /** 端点形态（DUO_PORT / THIRD_PARTY / FS_PATH / NONE，§7.5）。 */
  endpointShape: string
  /** 是否提供同进程契约门面（wiring direct 路径的前提）。 */
  interfaceDirect: boolean
  /** 是否支持实例级操作（带下标注入的前提）。 */
  instanceControl: boolean
}

/** 故障注入动作（POST /inject 与 /api/inject/clear 的 body；对应 kernel FaultAction）。 */
export interface FaultAction {
  /** 动作类型（crash/restart 生命周期类，或 freeze/slow 等 FaultInjectable 类）。 */
  type: string
  /** 目标：组件 id + 可选实例下标（1 起；缺省＝整组）。 */
  target: { componentId: { value: string }; instanceIndex?: number | null }
  /** 动作参数（如 slow 的 factor、custom-hook 的 hook）。 */
  params: Record<string, unknown>
}

/** 单条事件（GET /events 行；字段序与 Java EventProjections 一致）。 */
export interface ScenarioEvent {
  /** 1-based 单调序号（增量游标依据，D3）。 */
  seq: number
  /** 事件类型（sim.＝框架事件 / sut.＝SUT 事实，两个命名空间）。 */
  type: string
  /** 发布方标识（组件 id，实例级带 -N 后缀；SUT 事实统一为 "sut"）。 */
  sourceId: string
  /** 发生时刻（ISO-8601）。 */
  timestamp: string
  /** 载荷键值（null 落为空对象）。 */
  payload: Record<string, unknown>
}

/** 事件增量页（GET /events?since= 的响应）。 */
export interface EventsPage {
  /** 本次请求的 since（回显）。 */
  since: number
  /** 新游标（下次增量传它）。 */
  next: number
  /** 服务端累计丢弃数（内存流有界；非 0 必须可见，§12）。 */
  dropped: number
  events: ScenarioEvent[]
}

/** 运行状态（GET /scenario/status；数据源 ScenarioHost.status()）。 */
export interface StatusInfo {
  /** 宿主状态机：IDLE（未启动）/ RUNNING / FINISHED / FAILED。 */
  state: 'IDLE' | 'RUNNING' | 'FINISHED' | 'FAILED'
  /** 当前场景名（未启动为 null）。 */
  scenario: string | null
  /** 注入失败累计数（§12：计入场景判定）。 */
  injectionFailures?: number
  /** 断言评估明细（场景收摊后写入）。 */
  assertions?: { name: string; passed: boolean; detail: string }[]
  /** 场景判定（无注入失败且断言全过）。 */
  passed?: boolean
  /** 警告（校验期 + 收摊期；不判成败）。 */
  warnings?: string[]
  /** 最近一次错误（如启动失败根因）。 */
  error?: string
  /** 事件总数（eventCount 自省口径）。 */
  events?: number
}

/** 拓扑节点行（GET /topology）。 */
export interface TopologyNode {
  /** 拓扑节点 id（即组件 id）。 */
  id: string
  /** 角色契约。 */
  contract: string
  /** 实现档位。 */
  tier: string
  /** 是否 SUT。 */
  sut: boolean
  /** 声明实例数。 */
  count: number
  /** 是否内核托管（external 进程不在 components() 表，生命周期归用户）。 */
  hosted: boolean
  /** 组件级健康（SUT 为 null——由 SutLauncher 管理，不在组件表内）。 */
  healthy?: boolean | null
  /** 对外暴露的端点列表。 */
  endpoints: string[]
}

/** 诊断链（GET /diagnose；镜像 Java FaultDiagnostics.Report 的四段结构）。 */
export interface DiagnoseReport {
  chains: {
    /** 注入事件序号（负值＝没找到注入事件）。 */
    injectedIndex: number
    /** 目标组件（实例级带下标后缀）。 */
    target: string
    /** 动作名。 */
    action: string
    /** 注入失败原因（null＝已下达）。 */
    failedReason: string | null
    /** 是否已清除（带 duration 的动作到期自动 clear）。 */
    cleared: boolean
    /** 窗口内组件反应（sim.* 事实）。 */
    componentReactions: { type: string; sourceId: string; timestamp: string }[]
    /** SUT 事实按类型归并（心跳类高频事件折成计数——逐条铺开等于没输出）。 */
    sutFacts: { type: string; count: number; firstAt: string; lastAt: string }[]
    /** SUT 事实总数（0＝断链，报告 MISSING）。 */
    sutFactTotal: number
    /** 观测窗口收尾事件号。 */
    windowEnd: number
  }[]
  /** 断言明细快照。 */
  assertions: Record<string, unknown>[]
  /** 事件总数。 */
  eventsTotal: number
  /** 断链原因清单（非空＝诊断不完整）。 */
  gaps: string[]
}

/** REST 调用错误（携带 HTTP 状态；401 已由 request 统一处理并跳登录，不再以异常业务形态出现）。 */
export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

/**
 * 统一请求入口：附 Bearer 令牌；401 统一清会话回登录（草稿在 localStorage 不随会话丢失，规格 §10）；
 * 非 2xx 抛 ApiError，消息取 body.error / body.errors 拼接 / HTTP n（与 Java 侧错误掩码同源）。
 */
async function request(path: string, init?: RequestInit): Promise<any> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (session.token) headers['Authorization'] = `Bearer ${session.token}`
  const resp = await fetch(path, { ...init, headers })
  if (resp.status === 401) {
    // 令牌失效：清会话回登录页（草稿在 localStorage，不随会话丢失——规格 §10）
    session.logout()
    window.location.hash = '#/login'
    throw new ApiError(401, 'unauthorized')
  }
  const text = await resp.text()
  const body = text ? JSON.parse(text) : null
  if (!resp.ok) {
    const msg = body?.error ?? (Array.isArray(body?.errors) ? body.errors.join('; ') : null)
      ?? `HTTP ${resp.status}`
    throw new ApiError(resp.status, msg)
  }
  return body
}

export const api = {
  /** 存活探测（免令牌端点；登录页先探连接、再验令牌）。 */
  health: () => request('/health').then((b) => b?.ok === true),
  // ---- 控制台专属面 /api/**（M10：场景库 CRUD/fork/校验、能力元数据）----
  meta: () => request('/api/meta'),
  listScenarios: () => request('/api/scenarios'),
  getScenario: (id: string) => request(`/api/scenarios/${encodeURIComponent(id)}`),
  putScenario: (id: string, yaml: string) =>
    request(`/api/scenarios/${encodeURIComponent(id)}`, { method: 'PUT', body: yaml }),
  deleteScenario: (id: string) =>
    request(`/api/scenarios/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  forkScenario: (id: string, body?: { id?: string }) =>
    request(`/api/scenarios/${encodeURIComponent(id)}/fork`,
      { method: 'POST', body: JSON.stringify(body ?? {}) }),
  validateYaml: (yamlText: string) =>
    request('/api/scenarios/validate', { method: 'POST', body: yamlText }),
  capabilities: () => request('/api/capabilities'),
  // ---- 核心面（CLI 依赖，签名冻结；/scenario 族＝运行生命周期，/events＝增量观测）----
  startScenario: (yamlText: string) => request('/scenario', { method: 'POST', body: yamlText }),
  stopScenario: () => request('/scenario', { method: 'DELETE' }),
  status: () => request('/scenario/status'),
  topology: () => request('/topology'),
  eventsSince: (since: number) => request(`/events?since=${since}`),
  inject: (fault: FaultAction) =>
    request('/inject', { method: 'POST', body: JSON.stringify(fault) }),
  clearInject: (fault: FaultAction) =>
    request('/api/inject/clear', { method: 'POST', body: JSON.stringify(fault) }),
  diagnose: () => request('/diagnose'),
}
