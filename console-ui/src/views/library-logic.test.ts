import { describe, expect, it } from 'vitest'
import { forkTargetId } from './library-logic'

describe('forkTargetId', () => {
  it('缺省 {source}-copy', () => {
    expect(forkTargetId('tpl', [])).toBe('tpl-copy')
  })
  it('撞名追加序号', () => {
    expect(forkTargetId('tpl', ['tpl-copy'])).toBe('tpl-copy-2')
    expect(forkTargetId('tpl', ['tpl-copy', 'tpl-copy-2'])).toBe('tpl-copy-3')
  })
})
