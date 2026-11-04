import { clearSession, csrfToken } from './session'

export interface ApiErrorBody {
  code: string; message: string; traceId: string
  details: { fields: { field: string; reason: string }[]; conflicts: unknown[];
    currentVersion: string | null; operationId: string | null; retryAfterSeconds: number | null }
}
export class ApiError extends Error {
  readonly status: number
  readonly body: ApiErrorBody | null
  constructor(status: number, body: ApiErrorBody | null)
  constructor(status: number, code: string, message: string, traceId?: string, fields?: { field: string; reason: string }[], retryAfterSeconds?: number, currentVersion?: string)
  constructor(status: number, bodyOrCode: ApiErrorBody | null | string, message?: string, traceId?: string, fields: { field: string; reason: string }[] = [], retryAfterSeconds?: number, currentVersion?: string) {
    const body = typeof bodyOrCode === 'string' ? { code: bodyOrCode, message: message ?? '', traceId: traceId ?? '', details: { fields, conflicts: [], operationId: null, retryAfterSeconds: retryAfterSeconds ?? null, currentVersion: currentVersion ?? null } } : bodyOrCode
    super(body?.message ?? `请求失败（${status}）`)
    this.name = 'ApiError'; this.status = status; this.body = body
  }
  get code(): string { return this.body?.code ?? 'HTTP_ERROR' }
  get traceId(): string | undefined { return this.body?.traceId || undefined }
  get fields(): { field: string; reason: string }[] { return this.body?.details?.fields ?? [] }
  get retryAfterSeconds(): number | undefined { return this.body?.details?.retryAfterSeconds ?? undefined }
  get currentVersion(): string | undefined { return this.body?.details?.currentVersion ?? undefined }
}
export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'
  body?: unknown; idempotencyKey?: string; signal?: AbortSignal; csrf?: string
}
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  if (!/^\/api\/m[123]\//.test(path) || /[\\#]/.test(path) || path.split('?')[0]!.split('/').some(p => ['.', '..'].includes(decodeURIComponent(p)))) {
    throw new Error('只允许同源业务 API 路径')
  }
  const method = options.method ?? 'GET'
  const headers = new Headers({ Accept: 'application/json' })
  if (options.body !== undefined) headers.set('Content-Type', 'application/json')
  const csrf = options.csrf ?? csrfToken()
  if (method !== 'GET' && csrf) headers.set('X-CSRF-Token', csrf)
  if (options.idempotencyKey) headers.set('Idempotency-Key', options.idempotencyKey)
  // Never auto-retry writes or generate a replacement idempotency key.
  const response = await fetch(path, {
    method, headers, credentials: 'same-origin', cache: 'no-store',
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

/** Feature compatibility adapter: uses the one main HTTP client and never retries writes. */
export async function requestForG5<T>(path: string, options: RequestOptions = {}): Promise<T> {
  try { return await request<T>(path, { ...options, method: options.method ?? (options.body === undefined ? 'GET' : 'POST') }) }
  catch (error) {
    if (error instanceof ApiError) throw error
    const timeout = error instanceof DOMException && ['AbortError', 'TimeoutError'].includes(error.name)
    throw new ApiError(0, timeout ? 'REQUEST_TIMEOUT' : 'NETWORK_ERROR', timeout ? '请求超时，结果尚未确认，请稍后核实。' : '暂时无法连接，请检查网络后重试。')
  }
}

export function errorMessage(error: unknown): string {
  if (typeof error === 'string') return error
  if (error instanceof ApiError && error.code === 'RATE_LIMITED') return `尝试次数过多，请约 ${Math.max(1, Math.ceil((error.retryAfterSeconds ?? 300) / 60))} 分钟后重试。`
  return error instanceof Error ? error.message : '操作暂未完成，请稍后重试。'
}
