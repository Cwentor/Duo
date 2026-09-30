import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { runtime, deriveInstanceFaults, backoffMs } from './runtime'
import type { ScenarioEvent } from '../api/client'

function ev(seq: number, type: string, sourceId: string): ScenarioEvent {
  return { seq, type, sourceId, timestamp: '', payload: {} }
}

describe('runtime store 逻辑', () => {
  beforeEach(() => { vi.useFakeTimers(); runtime.endPolling() })
  afterEach(() => { vi.useRealTimers() })

  it('backoff 序列 1s→2s→5s 封顶', () => {
    expect(backoffMs(0)).toBe(1000)
    expect(backoffMs(1)).toBe(2000)
    expect(backoffMs(2)).toBe(5000)
    expect(backoffMs(9)).toBe(5000)
  })

  it('instanceFaults 从事件推导（sourceId 为 nodeId-实例号；crash 记账、restarted 消账）', () => {
    const faults = deriveInstanceFaults([
      ev(1, 'sim.worker-instance-crashed', 'workers-3'),
      ev(2, 'sim.worker-instance-crashed', 'workers-2'),
      ev(3, 'sim.worker-instance-restarted', 'workers-2'),
    ], ['workers', 'master'])
    expect(faults['workers']).toEqual([3])
  })

  it('非实例形态的 sourceId 不误报', () => {
    const faults = deriveInstanceFaults([
      ev(1, 'sim.fault-injected', 'workers'),
    ], ['workers'])
    expect(faults['workers'] ?? []).toEqual([])
  })

  it('轮询推进 next 且不重不漏（伪造两页事件）', async () => {
    const pages = [
      { since: 0, next: 2, dropped: 0, events: [
        ev(1, 'a', 'x'), ev(2, 'b', 'x')] },
      { since: 2, next: 3, dropped: 0, events: [ev(3, 'c', 'x')] },
    ]
    let call = 0
    vi.stubGlobal('fetch', vi.fn(async (url: any) => {
      const u = String(url)
      // 三路轮询各走各的端点：只有 /events 消耗页序列（夹具按 URL 分发，防止 status/topology 吃页）
      if (u.includes('/events')) {
        // 页序耗尽后返回空页（真服务端 since=next 即空，不重不漏）
        const page = call < pages.length ? pages[call++] : { since: 3, next: 3, dropped: 0, events: [] }
        return new Response(JSON.stringify(page), { status: 200 })
      }
      if (u.includes('/scenario/status')) {
        return new Response(JSON.stringify({ state: 'RUNNING', scenario: 'x' }), { status: 200 })
      }
      return new Response(JSON.stringify({ nodes: [] }), { status: 200 })
    }))
    runtime.beginPolling()
    await vi.advanceTimersByTimeAsync(1100)
    await vi.advanceTimersByTimeAsync(1100)
    runtime.endPolling()
    expect(runtime.next).toBe(3)
    expect(runtime.events.map((e) => e.seq)).toEqual([1, 2, 3])
    expect(runtime.connected).toBe(true)
  })

  it('轮询失败退避且 connected=false', async () => {
    const fetchMock = vi.fn(async (url: any) => { throw new Error(String(url)) })
    vi.stubGlobal('fetch', fetchMock)
    const eventCalls = () => fetchMock.mock.calls.filter((c) => String(c[0]).includes('/events')).length
    runtime.beginPolling()
    await vi.advanceTimersByTimeAsync(50)
    expect(runtime.connected).toBe(false)
    // 退避递增（按 /events 一路计数；每拍三路各一次 fetch）：首拍后 failures=1 → 下一拍 2s
    const afterFirst = eventCalls()
    await vi.advanceTimersByTimeAsync(1000)
    expect(eventCalls()).toBe(afterFirst, '1s 处不得重试（应等 2s）')
    await vi.advanceTimersByTimeAsync(1200)
    expect(eventCalls()).toBe(afterFirst + 1)
    runtime.endPolling()
  })

  it('endPolling 后在途响应不续命（僵尸轮询防护，终审 I-2）', async () => {
    const resolvers: ((r: Response) => void)[] = []
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Promise<Response>((resolve) => { resolvers.push(resolve) })))
    runtime.beginPolling()
    await vi.advanceTimersByTimeAsync(50)
    const callsAtPending = resolvers.length
    runtime.endPolling()
    for (const r of resolvers) {
      r(new Response('{"since":0,"next":0,"dropped":0,"events":[]}', { status: 200 }))
    }
    await vi.advanceTimersByTimeAsync(10000)
    expect(resolvers.length).toBe(callsAtPending, '停止后不得再有新请求')
  })
})
