import { describe, expect, it } from 'vitest'
import { ScenarioDoc } from './scenario'

const SAMPLE = `name: worker-crash-failover
topology:
  - id: zk
    contract: registry
    tier: virtual
  - id: master
    contract: scheduler
    tier: real
    sut: true
    launch: { mode: in-process, main: io.duo.sim.examples.scheduler.DemoScheduler }
    config: { dag.tasks: "a,b" }
    exposes: [{ contract: scheduler, port: 0 }]
    wiring:
      registry: { node: zk, contract: registry }
  - id: workers
    contract: worker
    tier: virtual
    count: 4
    capacity: { slots: 1 }
    wiring:
      registry: { node: zk, contract: registry }
behaviors:
  profiles:
    default: { duration: 15s, jitter: 0.1, successRate: 1.0 }
  bindings:
    - node: workers
      profile: default
timeline:
  - at: 10s
    action: crash
    target: workers[3]
assertions:
  - noTaskLost: { requireAllSuccess: true }
`

describe('ScenarioDoc', () => {
  it('parse → view：节点/接线/SUT 完整还原', () => {
    const doc = ScenarioDoc.parse(SAMPLE)
    const v = doc.view()
    expect(v.name).toBe('worker-crash-failover')
    expect(v.nodes.map((n) => n.id)).toEqual(['zk', 'master', 'workers'])
    const master = v.nodes.find((n) => n.id === 'master')!
    expect(master.sut).toBe(true)
    expect(master.tier).toBe('real')
    expect(master.config['dag.tasks']).toBe('a,b')
    expect(master.wiring['registry'].node).toBe('zk')
    expect(v.nodes.find((n) => n.id === 'workers')!.count).toBe(4)
  })

  it('不改 → toString 语义等价（Review Focus #1）', () => {
    const doc = ScenarioDoc.parse(SAMPLE)
    const again = ScenarioDoc.parse(doc.toString()).view()
    const orig = ScenarioDoc.parse(SAMPLE).view()
    expect(again.nodes).toEqual(orig.nodes)
    expect(again.name).toBe(orig.name)
  })

  it('语法错误 → ScenarioParseError 且带问题清单', () => {
    expect(() => ScenarioDoc.parse('name: [broken')).toThrow(/parse/i)
  })

  it('画布加节点 → YAML 出节点；updateNode 改 tier → view 变；未动节点注释保留', () => {
    const doc = ScenarioDoc.parse(SAMPLE)
    doc.addNode({ id: 'store1', contract: 'store', tier: 'virtual' })
    const v = doc.view()
    expect(v.nodes.map((n) => n.id)).toContain('store1')
    doc.updateNode('store1', { tier: 'embedded' })
    expect(doc.view().nodes.find((n) => n.id === 'store1')!.tier).toBe('embedded')
    const text = doc.toString()
    expect(text).toContain('store1')
    // 未改动节点注释保留：加注释后再改别的节点，注释仍在
    const commented = SAMPLE.replace('- id: zk\n', '- id: zk  # keep me\n')
    const doc2 = ScenarioDoc.parse(commented)
    doc2.addNode({ id: 'extra', contract: 'engine', tier: 'virtual' })
    expect(doc2.toString()).toContain('# keep me')
  })

  it('connect/disconnect 维护 wiring', () => {
    const doc = ScenarioDoc.parse(SAMPLE)
    doc.addNode({ id: 'eng1', contract: 'engine', tier: 'virtual' })
    doc.connect('eng1', 'registry', 'zk')
    expect(doc.view().nodes.find((n) => n.id === 'eng1')!.wiring['registry'].node).toBe('zk')
    doc.disconnect('eng1', 'registry')
    expect(doc.view().nodes.find((n) => n.id === 'eng1')!.wiring['registry']).toBeUndefined()
  })

  it('removeNode 同步移除引用它的 wiring（防悬挂引用）', () => {
    const doc = ScenarioDoc.parse(SAMPLE)
    doc.removeNode('zk')
    expect(doc.view().nodes.map((n) => n.id)).not.toContain('zk')
    for (const n of doc.view().nodes) {
      for (const w of Object.values(n.wiring)) expect(w.node).not.toBe('zk')
    }
  })
})
