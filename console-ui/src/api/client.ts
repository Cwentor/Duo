import { session } from '../stores/session'

// ---- 契约类型（与计划一 REST 响应字段一一对应；签名冻结，Java 侧测试钉住）----
export interface ScenarioEntry {
  id: string
  name: string
  template: boolean
  valid: boolean
  nodeCount: number
  lastModifiedMillis: number
}

export interface CapabilityRow {
  contract: string
  tier: string
  impl: string
  default: boolean
  supportedFaults: string[]
  endpointShape: string
  interfaceDirect: boolean
  instanceControl: boolean
}

export interface FaultAction {
  type: string
  target: { componentId: { value: string }; instanceIndex?: number | null }
  params: Record<string, unknown>
}

export interface ScenarioEvent {
  seq: number
  type: string
  sourceId: string
  timestamp: string
  payload: Record<string, unknown>
}

export interface EventsPage {
  since: number
  next: number
  dropped: number
  events: ScenarioEvent[]
}

export interface StatusInfo {
  state: 'IDLE' | 'RUNNING' | 'FINISHED' | 'FAILED'
  scenario: string | null
  injectionFailures?: number
  assertions?: { name: string; passed: boolean; detail: string }[]
  passed?: boolean
  warnings?: string[]
  error?: string
  events?: number
}

export interface TopologyNode {
  id: string
  contract: string
  tier: string
  sut: boolean
  count: number
  hosted: boolean
  healthy?: boolean | null
  endpoints: string[]
}

export interface DiagnoseReport {
  chains: {
    injectedIndex: number
    target: string
    action: string
    failedReason: string | null
    cleared: boolean
    componentReactions: { type: string; sourceId: string; timestamp: string }[]
    sutFacts: { type: string; count: number; firstAt: string; lastAt: string }[]
    sutFactTotal: number
    windowEnd: number
  }[]
  assertions: Record<string, unknown>[]
  eventsTotal: number
  gaps: string[]
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

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
  health: () => request('/health').then((b) => b?.ok === true),
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
