export type Scope = { kind: 'SCHOOL' | 'SYSTEM' } | { kind: 'DEPARTMENT'; departmentId: string } | { kind: 'ROOM'; roomId: string };
export interface Grant { action: string; scope: Scope }
export interface Principal {
  userId: string;
  displayName: string;
  deptId: string;
  accountVersion: string;
  permissionVersion: string;
  grants: Grant[];
  mustChangePassword: boolean;
  expiresAt: string;
}
export interface LoginResult { principal: Principal; csrfToken: string }

export function principalFrom(value: unknown): Principal {
  const candidate = value as Partial<Principal> | null;
  if (!candidate || typeof candidate.userId !== 'string' || typeof candidate.displayName !== 'string'
      || typeof candidate.deptId !== 'string' || typeof candidate.mustChangePassword !== 'boolean'
      || typeof candidate.accountVersion !== 'string' || !/^[1-9][0-9]*$/.test(candidate.accountVersion)
      || typeof candidate.permissionVersion !== 'string' || !/^[1-9][0-9]*$/.test(candidate.permissionVersion)
      || typeof candidate.expiresAt !== 'string' || Number.isNaN(Date.parse(candidate.expiresAt))
      || !Array.isArray(candidate.grants) || candidate.grants.some(grant =>
        typeof grant?.action !== 'string' || !['SCHOOL', 'SYSTEM', 'DEPARTMENT', 'ROOM'].includes(grant.scope?.kind))) {
    throw new Error('身份响应格式不正确，请稍后重试。');
  }
  return candidate as Principal;
}

export function displayTime(time: string, date = true): string {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', ...(date ? { month: 'long', day: 'numeric' } : {}),
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(new Date(time));
}
