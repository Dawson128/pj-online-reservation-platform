import { onBeforeUnmount, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ApiError, requestForG5 as request } from '../../app/http';
import { canManageSystem, session } from '../../app/session';
import type { Grant } from '../../app/types';

export interface Meta { id: string; version: string; createdAt: string; updatedAt: string }
export interface Account extends Meta { userCode: string; displayName: string; departmentId: string; email: string; enabled: boolean; mustChangePassword: boolean; roleIds: string[]; permissionVersion: string }
export interface Department extends Meta { code: string; name: string; enabled: boolean }
export interface Role extends Meta { key: string; name: string; builtIn: boolean; grants: Grant[] }
export interface Page<T> { items: T[]; page: number; pageSize: number; total: number; asOf: string }
export function queryPath(path: string, filters: Record<string, string | number>): string {
  const query = new URLSearchParams();
  for (const [name, value] of Object.entries(filters)) if (value !== '') query.set(name, String(value));
  return `${path}?${query}`;
}

/** Feature operations reuse the app HTTP/session layer; write intent lives only in this view's memory. */
export function useManagement() {
  const router = useRouter();
  const error = ref<unknown>(null);
  const busy = ref(false);
  const uncertain = ref(false);
  let pending: { path: string; method: 'POST' | 'PATCH' | 'PUT'; body: object; key: string } | undefined;
  let active = true;
  onBeforeUnmount(() => { active = false; pending = undefined; });
  function reset(): void { pending = undefined; error.value = null; uncertain.value = false; }
  async function handle(failure: unknown): Promise<void> {
    error.value = failure;
    if (failure instanceof ApiError && failure.status === 401) {
      reset(); session.clear('expired'); await router.replace({ name: 'g5-login', query: { reason: 'expired' } });
    } else if (failure instanceof ApiError && failure.status === 403) {
      await session.restore(true);
      if (session.state.phase === 'anonymous') await router.replace({ name: 'g5-login', query: { reason: 'expired' } });
      else if (session.state.phase === 'authenticated' && !canManageSystem()) await router.replace({ name: 'g5-access-denied' });
      else if (session.state.phase === 'authenticated') { session.clear('security'); await router.replace({ name: 'g5-login', query: { reason: 'security' } }); }
    }
  }
  async function read<T>(path: string): Promise<T> {
    try { return await request<T>(path); }
    catch (failure) { await handle(failure); throw failure; }
  }
  async function all<T>(path: string, filters: Record<string, string | number> = {}): Promise<T[]> {
    const result: T[] = [];
    for (let page = 1; page <= 1000000; page++) {
      const data = await read<Page<T>>(queryPath(path, { ...filters, page, pageSize: 100 }));
      result.push(...data.items);
      if (result.length >= data.total) return result;
      if (!data.items.length) throw new Error('目录读取不完整，请刷新后重试。');
    }
    throw new Error('目录超过可读取范围，请联系管理员。');
  }
  async function write<T>(path: string, method: 'POST' | 'PATCH' | 'PUT', body: object): Promise<T | undefined> {
    if (busy.value) return undefined;
    busy.value = true; error.value = null;
    if (!pending || !uncertain.value) pending = { path, method, body: JSON.parse(JSON.stringify(body)) as object, key: crypto.randomUUID() };
    const intent = pending;
    try {
      const data = await request<T>(intent.path, { method: intent.method, body: intent.body, csrf: session.state.csrf, idempotencyKey: intent.key });
      if (!active) return undefined;
      reset(); return data;
    } catch (failure) {
      if (failure instanceof ApiError && (failure.status === 0 || [502, 503, 504].includes(failure.status))) uncertain.value = true;
      else { pending = undefined; uncertain.value = false; }
      await handle(failure); return undefined;
    } finally { busy.value = false; }
  }
  async function changedSelf(account: Account): Promise<boolean> {
    if (account.id !== session.state.principal?.userId) return false;
    session.clear('updated'); await router.replace({ name: 'g5-login', query: { reason: 'updated' } }); return true;
  }
  return { error, busy, uncertain, reset, read, all, write, changedSelf };
}
