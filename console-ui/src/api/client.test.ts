import { describe, expect, it, vi, beforeEach } from 'vitest'
import { api } from '../api/client'
import { session } from '../stores/session'

describe('api client', () => {
  beforeEach(() => {
    sessionStorage.clear()
    session.login('tok')
    vi.unstubAllGlobals()
  })

  it('携带 Authorization 头并解析 JSON', async () => {
    const fetchMock = vi.fn(async (_url: string | URL | Request, _init?: RequestInit) =>
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }))
    vi.stubGlobal('fetch', fetchMock)
    const r = await api.health()
    expect(r).toBe(true)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/health')
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer tok')
  })

  it('401 ⇒ 清会话并跳转 #/login', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"error":"x"}', { status: 401 })))
    await expect(api.listScenarios()).rejects.toThrow()
    expect(session.token).toBeNull()
    expect(window.location.hash).toBe('#/login')
  })

  it('startScenario 返回 body 原样（含 RUNNING）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ state: 'RUNNING' }), { status: 200 })))
    expect((await api.startScenario('name: x')).state).toBe('RUNNING')
  })
})
