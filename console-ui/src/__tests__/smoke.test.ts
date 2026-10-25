import { describe, expect, it } from 'vitest'

describe('工程冒烟', () => {
  it('vitest 可运行且 TS 编译无误', () => {
    expect(1 + 1).toBe(2)
  })
})
