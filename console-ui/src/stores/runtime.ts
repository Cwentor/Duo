import { reactive } from 'vue'
import { api, EventsPage, ScenarioEvent, StatusInfo, TopologyNode } from '../api/client'

/**
 * 事件流内存封顶（评审 A3，2026-10-30）：服务端事件流有界（MAX_BUFFERED_EVENTS，
 * 丢弃经 dropped 可见），前端 store 此前无界累积——长跑场景的高频事件让内存与
 * deriveInstanceFaults 的全量扫描线性蠕升。截断数计入 truncatedLocal 并随 dropped
 * 一并展示（§12：丢弃必须可见，本机截断也不例外）。
 */
export const MAX_BUFFERED_EVENTS = 5000

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

/** 运行时 store：三路合并视图（状态/拓扑/事件）+ 轮询生命周期。单例（模块级 reactive）。 */
interface RuntimeStore {
  /** 宿主状态机（null＝尚未连上）。 */
  state: StatusInfo['state'] | null
  /** 最新状态快照。 */
  status: StatusInfo | null
  /** 最新全量拓扑。 */
  nodes: TopologyNode[]
  /** 增量累积的事件流（时间序；UI 自行截尾展示）。 */
  events: ScenarioEvent[]
  /** 事件游标（/events 的 next；随 reset 归零）。 */
  next: number
  /** 服务端累计丢弃数。 */
  dropped: number
  /** 本机内存截断数（超过 MAX_BUFFERED_EVENTS 的事件条数；reset 归零）。 */
  truncatedLocal: number
  /** serve 是否可达（三路全空才算断，见 tick）。 */
  connected: boolean
  /** POST /scenario 启动并立即开轮询。 */
  start(yamlText: string): Promise<void>
  /** DELETE /scenario 停止，收轮询并清空视图。 */
  stop(): Promise<void>
  /** 开轮询（代次 +1 作废旧在途轮；幂等——先收再开）。 */
  beginPolling(): void
  /** 收轮询（代次 +1：在途响应返回时自判僵尸）。 */
  endPolling(): void
  /** 视图归零（连接态一并复位）。 */
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
  truncatedLocal: 0,
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
          if (this.events.length > MAX_BUFFERED_EVENTS) {
            this.truncatedLocal += this.events.length - MAX_BUFFERED_EVENTS
            this.events.splice(0, this.events.length - MAX_BUFFERED_EVENTS)
          }
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
    this.truncatedLocal = 0
    this.connected = false
  },
})
