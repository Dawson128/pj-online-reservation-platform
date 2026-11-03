import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from './http'
import { clearSession, csrfToken, setCsrfToken } from './session'

describe('shared API boundary', () => {
  beforeEach(() => { clearSession(); vi.stubGlobal('window', new EventTarget()) })
  afterEach(() => vi.unstubAllGlobals())
  it('passes the original key and CSRF token without transforming version strings', async () => {
    setCsrfToken('csrf-token')
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: { version: '9223372036854775807' }, traceId: 'trace' })))
    vi.stubGlobal('fetch', fetch)
    const result = await request<{ version: string }>('/api/m3/example', { method: 'POST', body: { expectedVersion: '7' }, idempotencyKey: 'original-key' })
    const options = fetch.mock.calls[0]![1] as RequestInit
    expect(new Headers(options.headers).get('Idempotency-Key')).toBe('original-key')
    expect(new Headers(options.headers).get('X-CSRF-Token')).toBe('csrf-token')
    expect(options.credentials).toBe('same-origin')
    expect(result.version).toBe('9223372036854775807')
  })
  it('does not retry a write when the outcome is unknown', async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError('connection lost'))
    vi.stubGlobal('fetch', fetch)
    await expect(request('/api/m3/example', { method: 'POST', idempotencyKey: 'same-key' })).rejects.toThrow('connection lost')
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('clears CSRF state on session expiry and retains the server error', async () => {
    setCsrfToken('old-token')
    const expired = vi.fn(); window.addEventListener('session-expired', expired)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'UNAUTHENTICATED', message: '请登录', traceId: 'trace' }), { status: 401 })))
    await expect(request('/api/m3/example')).rejects.toBeInstanceOf(ApiError)
    expect(csrfToken()).toBeNull(); expect(expired).toHaveBeenCalledTimes(1)
  })
  it.each(['https://example.com/api/m3/x', '//example.com/api/m3/x', '/api/m3/../internal/events', '/api/m3/%2e%2e/internal/events', '/api/m3/\\example.com'])('rejects an unsafe API path: %s', async path => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    await expect(request(path)).rejects.toThrow()
    expect(fetch).not.toHaveBeenCalled()
  })
  it('does not silently accept an HTML response as business success', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>proxy error</html>')))
    await expect(request('/api/m3/example')).rejects.toThrow('服务器响应不符合接口契约')
  })
})
