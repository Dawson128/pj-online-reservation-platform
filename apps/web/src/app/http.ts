import { clearSession, csrfToken } from './session'

export interface ApiErrorBody {
  code: string; message: string; traceId: string
  details: { fields: { field: string; reason: string }[]; conflicts: unknown[];
    currentVersion: string | null; operationId: string | null; retryAfterSeconds: number | null }
}
export class ApiError extends Error {
  constructor(public status: number, public body: ApiErrorBody | null) {
    super(body?.message ?? `请求失败（${status}）`)
  }
}
export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'
  body?: unknown; idempotencyKey?: string; signal?: AbortSignal
}
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  if (!/^\/api\/m[123]\//.test(path) || /[\\#]/.test(path) || path.split('?')[0]!.split('/').some(p => ['.', '..'].includes(decodeURIComponent(p)))) {
    throw new Error('只允许同源业务 API 路径')
  }
  const method = options.method ?? 'GET'
  const headers = new Headers({ Accept: 'application/json' })
  if (options.body !== undefined) headers.set('Content-Type', 'application/json')
  if (method !== 'GET' && csrfToken()) headers.set('X-CSRF-Token', csrfToken()!)
  if (options.idempotencyKey) headers.set('Idempotency-Key', options.idempotencyKey)
  // Never auto-retry writes or generate a replacement idempotency key.
  const response = await fetch(path, {
    method, headers, credentials: 'same-origin',
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: AbortSignal.any([AbortSignal.timeout(8000), ...(options.signal ? [options.signal] : [])]),
    redirect: 'error',
  })
  const body: unknown = await response.json().catch(() => null)
  if (!response.ok) {
    if (response.status === 401) {
      clearSession()
      window.dispatchEvent(new Event('session-expired'))
    }
    throw new ApiError(response.status, body as ApiErrorBody | null)
  }
  if (!body || typeof body !== 'object' || !('data' in body) || !('traceId' in body)) {
    throw new Error('服务器响应不符合接口契约')
  }
  return (body as { data: T }).data
}
