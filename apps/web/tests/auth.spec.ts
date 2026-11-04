import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { spawn, spawnSync } from 'node:child_process';
import type { ChildProcess } from 'node:child_process';
import { closeSync, mkdirSync, openSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import { setTimeout as wait } from 'node:timers/promises';

const root = resolve(process.cwd(), '../..');
const database = process.env.WEB_AUTH_DB_URL ?? process.env.DB_URL ?? '';
if (!/^jdbc:postgresql:\/\/[^/]+\/pj_g5_auth_it_[a-zA-Z0-9_]+$/.test(database)) {
  throw new Error('Browser tests require a dedicated pj_g5_auth_it_* database; use scripts/Test-WebAuth.ps1.');
}
const databaseName = database.slice(database.lastIndexOf('/') + 1);
const account = 'browser-admin';
const initialPassword = randomBytes(18).toString('base64');
let password = initialPassword;
let backend: ChildProcess | undefined;
let startCount = 0;
const output = resolve(root, '.local/web-e2e');
mkdirSync(output, { recursive: true });

function sql(statement: string): void {
  const executable = process.env.PSQL_BIN ?? 'F:/PostgreSQL/17/bin/psql.exe';
  const result = spawnSync(executable, ['-X', '--no-password', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1',
    '-p', '5432', '-U', process.env.DB_MIGRATION_USERNAME!, '-d', databaseName, '-c', statement], {
    env: { ...process.env, PGPASSWORD: process.env.DB_MIGRATION_PASSWORD }, windowsHide: true, encoding: 'utf8',
  });
  if (result.status !== 0) throw new Error('Isolated database fixture operation failed.');
}

async function startBackend(): Promise<void> {
  const java = resolve(process.env.JAVA_HOME!, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
  const jar = resolve(root, 'services/approval/target/approval-service-0.1.0-SNAPSHOT.jar');
  const log = openSync(resolve(output, `backend-${++startCount}.log`), 'w');
  backend = spawn(java, ['-jar', jar], {
    cwd: root, windowsHide: true, stdio: ['ignore', log, log],
    env: { ...process.env, DB_URL: database, DB_MIGRATION_URL: database, APP_PORT: '18092', MANAGEMENT_PORT: '18093', APP_BIND_ADDRESS: '127.0.0.1',
      G5_BOOTSTRAP_ENABLED: 'true', G5_BOOTSTRAP_ACCOUNT: account, G5_BOOTSTRAP_PASSWORD: initialPassword },
  });
  closeSync(log);
  backend.on('error', () => {});
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    if (backend.exitCode !== null) throw new Error('Backend exited; inspect the ignored browser-test log.');
    try {
      const response = await fetch('http://127.0.0.1:18093/actuator/health/readiness');
      if (response.ok) return;
    } catch { /* readiness is polled without credentials */ }
    await wait(200);
  }
  throw new Error('Backend did not become ready.');
}
async function stopBackend(): Promise<void> {
  const stopping = backend;
  backend = undefined;
  if (stopping && stopping.exitCode === null) {
    const ended = new Promise<void>(resolve => stopping.once('exit', () => resolve()));
    const timeout = setTimeout(() => { if (stopping.exitCode === null) stopping.kill('SIGKILL'); }, 15000);
    try { stopping.kill(); await ended; }
    finally { clearTimeout(timeout); }
  }
}
async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('校内账号', { exact: true }).fill(account);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录校园空间', exact: true }).click();
  await expect(page).toHaveURL('https://127.0.0.1:5174/');
}
function noOverflow(page: Page): Promise<boolean> {
  return page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
}

test.describe('真实 HTTPS 认证接入', () => {
  test.describe.configure({ mode: 'serial' });
  test.beforeAll(async () => { test.setTimeout(90000); await startBackend(); });
  test.beforeEach(() => sql('DELETE FROM m2.g5_auth_throttle'));
  test.afterAll(async () => { await stopBackend(); });

  test('首次登录直达首页，8—24 字符主动改密、重登和退出均使用真实后端', async ({ page, playwright }) => {
    await page.goto('/profile');
    await expect(page).toHaveURL(/\/login/);
    await page.screenshot({ path: resolve(output, 'login-desktop.png'), fullPage: true });
    await page.getByLabel('校内账号', { exact: true }).fill(account);
    await page.getByLabel('密码', { exact: true }).fill('incorrect-fixture-password');
    await page.getByRole('button', { name: '登录校园空间', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('登录凭据无效');
    await page.getByLabel('密码', { exact: true }).fill(initialPassword);
    await page.getByRole('button', { name: '登录校园空间', exact: true }).click();
    await expect(page).toHaveURL('https://127.0.0.1:5174/');
    await expect(page.getByRole('heading', { name: '设置你的新密码' })).toHaveCount(0);
    await page.goto('/profile');
    await expect(page).toHaveURL(/\/profile$/);
    await expect(page.getByText('账户、角色与系统参数管理', { exact: true })).toBeVisible();
    await page.getByRole('link', { name: '修改密码', exact: true }).click();
    await expect(page).toHaveURL(/\/change-password$/);
    const cookies = await page.context().cookies();
    const sessionCookie = cookies.find(cookie => cookie.name === 'PJ_SESSION');
    expect(Boolean(sessionCookie?.secure)).toBe(true);
    expect(Boolean(sessionCookie?.httpOnly)).toBe(true);
    expect(sessionCookie?.sameSite).toBe('Lax');
    const identity = await page.request.get('/api/m2/auth/me');
    expect(identity.status()).toBe(200);
    const principal = (await identity.json()).data;
    expect(principal.mustChangePassword).toBe(false);
    expect(principal.grants.some((grant: { action: string }) => grant.action === 'system:manage')).toBe(true);
    const noCsrf = await page.request.post('/api/m2/auth/change-password', {
      data: { currentPassword: initialPassword, newPassword: 'unused-new-password', expectedVersion: '1' },
    });
    expect(noCsrf.status()).toBe(403);
    const replacement = randomBytes(6).toString('base64');
    await page.getByLabel('当前密码', { exact: true }).fill(initialPassword);
    for (const invalid of ['a'.repeat(7), 'a'.repeat(25)]) {
      await page.getByLabel('新密码', { exact: false }).first().fill(invalid);
      await page.getByLabel('确认新密码', { exact: true }).fill(invalid);
      await page.getByRole('button', { name: '更新密码并重新登录' }).click();
      await expect(page.getByRole('alert')).toContainText('8—24 个字符');
      await expect(page).toHaveURL(/\/change-password$/);
    }
    await page.getByLabel('新密码', { exact: false }).first().fill(replacement);
    await page.getByLabel('确认新密码', { exact: true }).fill('mismatching-password');
    await page.getByRole('button', { name: '更新密码并重新登录' }).click();
    await expect(page.getByRole('alert')).toContainText('两次输入的新密码不一致');
    await page.getByLabel('确认新密码', { exact: true }).fill(replacement);
    await page.getByRole('button', { name: '更新密码并重新登录' }).click();
    await expect(page).toHaveURL(/\/login\?reason=changed/);
    await expect(page.getByRole('status')).toContainText('密码已更新');
    const previous = await playwright.request.newContext({ baseURL: 'https://127.0.0.1:5174', ignoreHTTPSErrors: true,
      extraHTTPHeaders: { Cookie: `PJ_SESSION=${sessionCookie!.value}` } });
    expect((await previous.get('/api/m2/auth/me')).status()).toBe(401);
    await previous.dispose();
    password = replacement;
    await login(page);
    await expect(page.locator('[data-state="not-connected"]')).toContainText('座位查询与预约暂未开放');
    await expect(page.getByText('18 席可约', { exact: true })).toHaveCount(0);
    await page.screenshot({ path: resolve(output, 'home-desktop.png'), fullPage: true });
    await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '个人身份' }).click();
    await expect(page).toHaveURL(/\/profile$/);
    await expect(page.getByRole('heading', { name: '初始管理员', exact: true })).toBeVisible();
    await page.reload();
    await expect(page).toHaveURL(/\/profile$/);
    await expect(page.getByText('账户、角色与系统参数管理', { exact: true })).toBeVisible();
    await page.screenshot({ path: resolve(output, 'profile-desktop.png'), fullPage: true });
    await page.getByRole('button', { name: '退出', exact: true }).click();
    await expect(page).toHaveURL(/\/login/);
    expect((await page.request.get('/api/m2/auth/me')).status()).toBe(401);
    await page.goBack();
    await expect(page).toHaveURL(/\/login/);
  });

  test('新标签页缺少 CSRF 时要求重新登录，不在脚本存储会话或密码', async ({ page, context }) => {
    await login(page);
    expect(await page.evaluate(() => document.cookie.includes('PJ_SESSION'))).toBe(false);
    expect(await page.evaluate(() => Object.keys(localStorage).length)).toBe(0);
    expect(await page.evaluate(() => Object.keys(sessionStorage))).toEqual(['pj.csrf.v1']);
    const second = await context.newPage();
    await second.goto('/profile');
    await expect(second).toHaveURL(/\/login\?reason=security/);
    await expect(second.getByRole('status')).toContainText('更新当前页面的安全校验');
    await second.close();
  });

  test('版本冲突保留表单并重新读取版本，再次提交可成功', async ({ page }) => {
    await login(page);
    await page.goto('/change-password');
    const replacement = randomBytes(18).toString('base64');
    await page.getByLabel('当前密码', { exact: true }).fill(password);
    await page.getByLabel('新密码', { exact: false }).first().fill(replacement);
    await page.getByLabel('确认新密码', { exact: true }).fill(replacement);
    sql("UPDATE m2.account SET version=version+1 WHERE user_code='browser-admin'; UPDATE m2.session SET account_version=account_version+1 WHERE account_id=(SELECT id FROM m2.account WHERE user_code='browser-admin') AND revoked_at IS NULL;");
    await page.getByRole('button', { name: '更新密码并重新登录' }).click();
    await expect(page.getByRole('alert')).toContainText('账户已发生变化');
    expect((await page.getByLabel('新密码', { exact: false }).first().inputValue()) === replacement).toBe(true);
    await page.getByRole('button', { name: '更新密码并重新登录' }).click();
    await expect(page).toHaveURL(/\/login\?reason=changed/);
    password = replacement;
    await login(page);
  });

  test('账户停用和会话过期后页面立即回到登录', async ({ page }) => {
    await login(page);
    await page.goto('/profile');
    await expect(page.getByRole('button', { name: '刷新身份', exact: true })).toBeVisible();
    sql("UPDATE m2.account SET enabled=false WHERE user_code='browser-admin'");
    await page.getByRole('button', { name: '刷新身份', exact: true }).click();
    await expect(page).toHaveURL(/\/login/);
    sql("UPDATE m2.account SET enabled=true WHERE user_code='browser-admin'");
    await login(page);
    sql("UPDATE m2.session SET expires_at=created_at+interval '1 millisecond' WHERE account_id=(SELECT id FROM m2.account WHERE user_code='browser-admin')");
    await page.reload();
    await expect(page).toHaveURL(/\/login/);
  });

  test('登录限速显示明确等待提示，非公开接口不能通过前端访问', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('校内账号', { exact: true }).fill(account);
    await page.getByLabel('密码', { exact: true }).fill('incorrect-for-limit');
    for (let attempt = 0; attempt < 10; attempt++) {
      const response = page.waitForResponse(result => result.url().endsWith('/api/m2/auth/login') && result.request().method() === 'POST');
      await page.getByRole('button', { name: '登录校园空间', exact: true }).click();
      expect((await response).status()).toBe(401);
      await expect(page.getByRole('button', { name: '登录校园空间', exact: true })).toBeEnabled();
    }
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录校园空间', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('尝试次数过多');
    expect((await page.request.get('/internal/auth/introspect')).status()).toBe(404);
    for (const path of [
      '/internal/authorization/check', '/internal/authorization/eligibility',
      '/internal/maintenance/assignee-candidates', '/internal/maintenance/assignee-eligibility',
      '/internal/notification-recipients/query', '/internal/notification-recipients/validate',
    ]) {
      expect((await page.request.post(path, { data: {} })).status()).toBe(404);
    }
    expect((await page.request.get('/internal/departments/00000000-0000-4000-8000-000000000090')).status()).toBe(404);
    expect((await page.request.post('/internal/accounts/qualification-facts', { data: { purpose: 'RESOURCE_IMPACT', roomId: '00000000-0000-4000-8000-000000000010', userIds: ['00000000-0000-4000-8000-000000000001'], traceId: '00000000-0000-4000-8000-000000000099' } })).status()).toBe(404);
    expect((await page.request.get('/actuator/health')).status()).toBe(404);
    expect((await page.request.get('/api/m2/admin/config')).status()).toBe(401);
    expect((await page.request.get('/api/m2/admin/accounts?unknown=true')).status()).toBe(401);
    expect((await page.request.get('/api/m2/departments')).status()).toBe(401);
  });

  test('后端故障时保留输入，不能把未知身份当成已登录，恢复后可继续', async ({ page }) => {
    test.setTimeout(90000);
    await page.goto('/login');
    await page.getByLabel('校内账号', { exact: true }).fill(account);
    await page.getByLabel('密码', { exact: true }).fill(password);
    await stopBackend();
    await page.getByRole('button', { name: '登录校园空间', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('暂不可用');
    expect((await page.getByLabel('校内账号', { exact: true }).inputValue()) === account).toBe(true);
    await startBackend();
    await page.getByRole('button', { name: '登录校园空间', exact: true }).click();
    await expect(page).toHaveURL('https://127.0.0.1:5174/');
    await page.goto('/profile');
    await stopBackend();
    await page.reload();
    await expect(page.getByRole('heading', { name: '暂时无法核实登录状态' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '初始管理员', exact: true })).toHaveCount(0);
    await startBackend();
    await page.getByRole('button', { name: '重新连接' }).click();
    await expect(page.getByRole('heading', { name: '初始管理员', exact: true })).toBeVisible();
  });

  test('390px 手机宽度下登录、改密和身份页面均可操作且无横向遮挡', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/login');
    expect(await noOverflow(page)).toBe(true);
    await page.screenshot({ path: resolve(output, 'login-mobile.png'), fullPage: true });
    await login(page);
    expect(await noOverflow(page)).toBe(true);
    await page.goto('/profile');
    expect(await noOverflow(page)).toBe(true);
    await page.screenshot({ path: resolve(output, 'profile-mobile.png'), fullPage: true });
    await page.getByRole('link', { name: '修改密码', exact: true }).click();
    expect(await noOverflow(page)).toBe(true);
    await expect(page.getByRole('button', { name: '更新密码并重新登录' })).toBeVisible();
    await page.screenshot({ path: resolve(output, 'password-mobile.png'), fullPage: true });
  });
});
