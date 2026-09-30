import { describe, expect, it } from 'vitest'
import { layoutKey, draftKey } from './workspace-logic'
import { ScenarioDoc } from '../model/scenario'

describe('workspace-logic', () => {
  it('键按场景 id 隔离', () => {
    expect(layoutKey('a')).toBe('duo-layout-a')
    expect(draftKey('a')).toBe('duo-draft-a')
  })

  it('画布改动投影进 YAML（Review Focus #4 模型半边）', () => {
    const doc = ScenarioDoc.parse(
      'name: x\ntopology:\n  - id: a\n    contract: registry\n    tier: virtual\n')
    doc.addNode({ id: 'b', contract: 'worker', tier: 'virtual' })
    const text = doc.toString()
    expect(text).toContain('id: b')
    expect(text).toContain('contract: worker')
  })
})
