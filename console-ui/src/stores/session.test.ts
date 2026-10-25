import { describe, expect, it, beforeEach } from 'vitest'
import { session } from '../stores/session'

describe('session store', () => {
  beforeEach(() => sessionStorage.clear())

  it('login 持久化到 sessionStorage，logout 清除', () => {
    session.login('t-abc')
    expect(session.token).toBe('t-abc')
    expect(sessionStorage.getItem('duo-console-token')).toBe('t-abc')
    session.logout()
    expect(session.token).toBeNull()
    expect(sessionStorage.getItem('duo-console-token')).toBeNull()
  })

  it('tokenInSession 反映 sessionStorage 现值（刷新恢复依据）', () => {
    sessionStorage.setItem('duo-console-token', 't-xyz')
    expect(session.tokenInSession()).toBe('t-xyz')
  })
})
