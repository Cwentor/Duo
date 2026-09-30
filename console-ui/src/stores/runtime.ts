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
let generation = 0          // 轮询代次：endPolling/重启后，在途响应作废（终审 I-2）
let consecutiveFailures = 0 // 连续「三路全空」次数，驱动退避（终审 I-1）

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
    this.beginPolling()
  },

  async stop() {
    await api.stopScenario()
    this.endPolling()
    this.reset()
  },

  beginPolling() {
    this.endPolling()
    const gen = ++generation
    const tick = async () => {
      if (gen !== generation) return // 代次已换：本轮是僵尸，不得续命
      try {
        // 三路合并：状态/拓扑全量刷，事件增量按游标。
        // status 在 IDLE/未启动时 409——容忍为 null（状态不可读≠轮询故障）；
        // 三路**全**拿不到才算一次失败（退避针对的是 serve 不可达，不是单端点空态）。
        const [status, topology, page] = await Promise.all([
          api.status().catch(() => null),
          api.topology().catch(() => null),
          api.eventsSince(this.next).catch(() => null),
        ])
        if (gen !== generation) return // 响应期间被停止/重启：丢弃，不写状态不续命
        const ok = status !== null || topology !== null || page !== null
        this.connected = ok
        consecutiveFailures = ok ? 0 : consecutiveFailures + 1
        if (status) { this.status = status; this.state = status.state }
        if (topology) this.nodes = topology.nodes
        if (page) {
          this.events.push(...page.events)
          this.next = page.next
          this.dropped = page.dropped
        }
      } catch {
        consecutiveFailures += 1
        this.connected = false
      }
      if (gen !== generation) return
      timer = setTimeout(tick, backoffMs(consecutiveFailures))
    }
    timer = setTimeout(tick, 0)
  },

  endPolling() {
    generation += 1 // 在途 tick 返回后代次不匹配，自动终止
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
