import { reactive } from 'vue'
import { api, EventsPage, ScenarioEvent, StatusInfo, TopologyNode } from '../api/client'

/** 轮询退避：正常 1s，连续失败 1s→2s→5s 封顶（规格 §10）。 */
export function backoffMs(failures: number): number {
  return failures <= 0 ? 1000 : failures === 1 ? 2000 : 5000
}

/**
 * 实例级故障从事件事实推导（/topology 只有组件级 healthy——规格 §6.2 修订口径）。
 * 取证（计划二 Task 8）：实例事件的 sourceId 形态 `<nodeId>-<index>`（如 `workers-3`），
 * payload 只含 mode；类型 `sim.worker-instance-crashed` / `sim.worker-instance-restarted`。
 */
export function deriveInstanceFaults(events: ScenarioEvent[],
                                     nodeIds: string[]): Record<string, number[]> {
  const faults: Record<string, number[]> = {}
  for (const e of events) {
    const nodeId = nodeIds.find((id) => e.sourceId.startsWith(id + '-')
      && /^-\d+$/.test(e.sourceId.slice(id.length)))
    if (!nodeId) continue
    const inst = Number(e.sourceId.slice(nodeId.length + 1))
    const list = faults[nodeId] ?? (faults[nodeId] = [])
    const i = list.indexOf(inst)
    if (e.type === 'sim.worker-instance-crashed') {
      if (i < 0) list.push(inst)
    } else if (e.type === 'sim.worker-instance-restarted') {
      if (i >= 0) list.splice(i, 1)
    }
  }
  return faults
}

interface RuntimeStore {
  state: StatusInfo['state'] | null
  status: StatusInfo | null
  nodes: TopologyNode[]
  events: ScenarioEvent[]
  next: number
  dropped: number
  connected: boolean
  start(yamlText: string): Promise<void>
  stop(): Promise<void>
  beginPolling(): void
  endPolling(): void
  reset(): void
}

let timer: ReturnType<typeof setTimeout> | null = null
let failures = 0

export const runtime = reactive<RuntimeStore>({
  state: null,
  status: null,
  nodes: [],
  events: [],
  next: 0,
  dropped: 0,
  connected: false,

  async start(yamlText) {
    const r = await api.startScenario(yamlText)
    this.state = (r as any)?.state ?? 'RUNNING'
    failures = 0
    this.beginPolling()
  },

  async stop() {
    await api.stopScenario()
    this.endPolling()
    this.reset()
  },

  beginPolling() {
    this.endPolling()
    const tick = async () => {
      try {
        // 三路合并：状态/拓扑全量刷，事件增量按游标。
        // status 在 IDLE/未启动时 409——容忍为 null（状态不可读≠轮询故障）。
        const [status, topology, page] = await Promise.all([
          api.status().catch(() => null),
          api.topology().catch(() => null),
          api.eventsSince(this.next).catch(() => null),
        ])
        this.connected = status !== null || page !== null
        if (status) { this.status = status; this.state = status.state }
        if (topology) this.nodes = topology.nodes
        if (page) {
          this.events.push(...page.events)
          this.next = page.next
          this.dropped = page.dropped
        }
        failures = 0
      } catch {
        this.connected = false
        failures += 1
      }
      timer = setTimeout(tick, backoffMs(failures))
    }
    timer = setTimeout(tick, 0)
  },

  endPolling() {
    if (timer) { clearTimeout(timer); timer = null }
  },

  reset() {
    this.state = null
    this.status = null
    this.nodes = []
    this.events = []
    this.next = 0
    this.dropped = 0
    this.connected = false
  },
})
