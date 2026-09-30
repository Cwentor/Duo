import { describe, expect, it } from 'vitest'
import { buildFaultAction, COMPONENT_SCOPED } from './inject-logic'

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
