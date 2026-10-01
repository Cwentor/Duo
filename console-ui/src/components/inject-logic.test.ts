import { describe, expect, it } from 'vitest'
import {
  availableActions, buildFaultAction, COMPONENT_SCOPED, LIFECYCLE_ACTIONS, needsInstanceIndex,
} from './inject-logic'

/** 取证形状＝`GET /api/capabilities` 的 `providers` 子集（计划一实测，2026-10-01）。 */
const CAPS = [
  { contract: 'worker', tier: 'virtual', supportedFaults: ['freeze', 'task-kill', 'resource-exhaust', 'slow'], instanceControl: true },
  { contract: 'worker', tier: 'real', supportedFaults: [], instanceControl: true },
  { contract: 'scheduler', tier: 'virtual', supportedFaults: ['freeze'], instanceControl: false },
  { contract: 'registry', tier: 'virtual', supportedFaults: ['registry-flap'], instanceControl: false },
  { contract: 'registry', tier: 'container', supportedFaults: [], instanceControl: false },
]

describe('buildFaultAction', () => {
  it('组件级动作（freeze/slow/resource-exhaust）不带 instanceIndex', () => {
    const f = buildFaultAction('freeze', 'workers', 3)
    expect(f.target).toEqual({ componentId: { value: 'workers' } })
    expect(f.type).toBe('freeze')
  })
  it('实例级动作（crash/restart/task-kill）带 instanceIndex', () => {
    const f = buildFaultAction('crash', 'workers', 3)
    expect(f.target.instanceIndex).toBe(3)
  })
  it('COMPONENT_SCOPED 清单与计划一实测一致', () => {
    expect([...COMPONENT_SCOPED].sort()).toEqual(['freeze', 'resource-exhaust', 'slow'])
  })
})

// ---- 终审发现（2026-10-01 实测取证，详见 ledger Ruling）----
describe('availableActions', () => {
  it('worker/virtual 节点：生命周期动作 + 该档位 supportedFaults', () => {
    expect(availableActions({ contract: 'worker', tier: 'virtual' }, CAPS))
      .toEqual(['crash', 'restart', 'freeze', 'task-kill', 'resource-exhaust', 'slow'])
  })

  it('crash/restart 是生命周期动作，不依赖 supportedFaults 声明（G-W2 冒烟第 5 步的唯一通路）', () => {
    // 取证：worker/real 的 supportedFaults=∅，但内核 dispatch 对 crash/restart 走
    // stop/restart 生命周期分支（ScenarioRuntime.dispatch），实测注入成功。
    expect(availableActions({ contract: 'worker', tier: 'real' }, CAPS)).toEqual(['crash', 'restart'])
  })

  it('按 (contract,tier) 精确匹配——worker/real 不得继承 worker/virtual 的动作', () => {
    const a = availableActions({ contract: 'worker', tier: 'real' }, CAPS)
    expect(a).not.toContain('freeze')
    expect(a).not.toContain('task-kill')
    expect(a).not.toContain('slow')
  })

  it('SUT 节点不提供任何注入动作（§7.2 target 不得为 SUT）', () => {
    expect(availableActions({ contract: 'scheduler', tier: 'virtual', sut: true }, CAPS)).toEqual([])
  })

  it('registry/container（supportedFaults=∅）只剩生命周期动作', () => {
    expect(availableActions({ contract: 'registry', tier: 'container' }, CAPS)).toEqual(['crash', 'restart'])
  })

  it('未知 (contract,tier) 不返回任何档位动作，不误给别的档位', () => {
    expect(availableActions({ contract: 'filestore', tier: 'virtual' }, CAPS)).toEqual(['crash', 'restart'])
  })

  it('LIFECYCLE_ACTIONS 即 crash/restart', () => {
    expect(LIFECYCLE_ACTIONS).toEqual(['crash', 'restart'])
  })
})

describe('needsInstanceIndex', () => {
  it('instanceControl=true 且非组件级动作 ⇒ 需要实例号', () => {
    expect(needsInstanceIndex('crash', true)).toBe(true)
    expect(needsInstanceIndex('task-kill', true)).toBe(true)
  })
  it('组件级动作永不问实例号', () => {
    expect(needsInstanceIndex('freeze', true)).toBe(false)
  })
  it('无 instanceControl 的档位不问实例号（问了必然失败）', () => {
    expect(needsInstanceIndex('registry-flap', false)).toBe(false)
    expect(needsInstanceIndex('crash', false)).toBe(false)
  })
})
