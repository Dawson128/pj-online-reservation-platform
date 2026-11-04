import { reactive, readonly } from 'vue';
import { ApiError, errorMessage, requestForG5 as request } from './http';
import { principalFrom } from './types';
import type { LoginResult, Principal } from './types';

const CSRF_KEY = 'pj.csrf.v1';
function readCsrf(): string {
  try { return sessionStorage.getItem(CSRF_KEY) ?? ''; } catch { return ''; }
}
const state = reactive({
  phase: 'unknown' as 'unknown' | 'loading' | 'authenticated' | 'anonymous' | 'unavailable',
  principal: null as Principal | null, csrf: readCsrf(), error: '', revision: 0, reason: 'expired',
});
let restoring: Promise<void> | undefined;
function csrf(value: string): void {
  state.csrf = value;
  try { if (value) sessionStorage.setItem(CSRF_KEY, value); else sessionStorage.removeItem(CSRF_KEY); } catch { /* memory-only fallback */ }
}
function clear(reason = 'expired'): void {
  state.revision++;
  state.principal = null;
  state.phase = 'anonymous';
  state.error = '';
  state.reason = reason;
  csrf('');
}

async function restore(force = false): Promise<void> {
  if (restoring) return restoring;
  if (!force && state.phase !== 'unknown' && state.phase !== 'unavailable') return;
  const revision = state.revision;
  state.phase = 'loading';
  restoring = (async () => {
    try {
      const principal = principalFrom(await request<unknown>('/api/m2/auth/me'));
      if (revision !== state.revision) return;
      state.principal = principal;
      state.phase = 'authenticated';
      state.error = '';
    } catch (error) {
      if (revision !== state.revision) return;
      if (error instanceof ApiError && error.status === 401) clear();
      else { state.principal = null; state.phase = 'unavailable'; state.error = errorMessage(error); }
    }
  })().finally(() => { restoring = undefined; });
  return restoring;
}

async function login(account: string, password: string): Promise<void> {
  const result = await request<LoginResult>('/api/m2/auth/login', { body: { account, password } });
  const principal = principalFrom(result.principal);
  if (typeof result.csrfToken !== 'string' || result.csrfToken.length < 32 || result.csrfToken.length > 128) {
    throw new Error('安全校验响应不完整，请重新登录。');
  }
  state.revision++;
  csrf(result.csrfToken);
  state.principal = principal;
  state.phase = 'authenticated';
  state.error = '';
}

async function logout(): Promise<void> {
  try {
    await request('/api/m2/auth/logout', { body: {}, csrf: state.csrf });
    clear('logout');
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.code === 'FORBIDDEN')) clear(error.code === 'FORBIDDEN' ? 'security' : 'expired');
    throw error;
  }
}

async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  if (!state.principal || !state.csrf) throw new ApiError(401, 'UNAUTHENTICATED', '请重新登录后修改密码。');
  try {
    await request('/api/m2/auth/change-password', { body: {
      currentPassword, newPassword, expectedVersion: state.principal.accountVersion,
    }, csrf: state.csrf });
    clear('changed');
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.code === 'FORBIDDEN')) clear(error.code === 'FORBIDDEN' ? 'security' : 'expired');
    if (error instanceof ApiError && error.status === 409) await restore(true);
    throw error;
  }
}

export const session = { state: readonly(state), restore, login, logout, changePassword, clear };
// Main's shared HTTP contract continues to use these accessors; there is one identity source.
export const csrfToken = (): string | null => state.csrf || null;
export function setCsrfToken(value: string | null): void { csrf(value ?? ''); }
export function clearSession(): void { clear('expired'); }
export function hasAction(action: string): boolean {
  return state.phase === 'authenticated'
    && (state.principal?.grants.some(grant => grant.action === action) ?? false);
}
export function canManageSystem(): boolean {
  return state.phase === 'authenticated' && (state.principal?.grants.some(grant => grant.action === 'system:manage' && grant.scope.kind === 'SYSTEM') ?? false);
}
