import { test, expect } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';
import { spawn, spawnSync } from 'node:child_process';
import type { ChildProcess } from 'node:child_process';
import { openSync, closeSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomBytes, randomUUID } from 'node:crypto';
import { setTimeout as wait } from 'node:timers/promises';

const root = resolve(process.cwd(), '../..');
const database = process.env.WEB_MANAGEMENT_DB_URL ?? '';
if (!/^jdbc:postgresql:\/\/[^/]+\/pj_g5_auth_it_[a-zA-Z0-9_]+$/.test(database) || database === process.env.DB_URL) {
  throw new Error('Use scripts/Test-WebAuth.ps1 with distinct authentication and management browser databases.');
}
const databaseName = database.slice(database.lastIndexOf('/') + 1);
const baseURL = 'https://127.0.0.1:5174';
const output = resolve(root, '.local/web-management-e2e');
mkdirSync(output, { recursive: true });
const originalPassword = randomBytes(18).toString('base64');
const studentPassword = randomBytes(18).toString('base64');
const backupPassword = randomBytes(18).toString('base64');
let managerCode = 'web-manager';
let managerPassword = originalPassword;
let backend: ChildProcess | undefined;
function sql(statement: string): string {
  const result = spawnSync(process.env.PSQL_BIN ?? 'F:/PostgreSQL/17/bin/psql.exe', ['-X', '--no-password', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1', '-p', '5432', '-U', process.env.DB_MIGRATION_USERNAME!, '-d', databaseName, '-f', '-'], {
    env: { ...process.env, PGPASSWORD: process.env.DB_MIGRATION_PASSWORD, PGCLIENTENCODING: 'UTF8' }, windowsHide: true, encoding: 'utf8', input: statement + ';\n',
  });
  if (result.status !== 0) throw new Error('Isolated management fixture failed.');
  return result.stdout.trim();
}
async function startBackend(): Promise<void> {
  const log = openSync(resolve(output, 'backend.log'), 'w');
  backend = spawn(resolve(process.env.JAVA_HOME!, 'bin', process.platform === 'win32' ? 'java.exe' : 'java'), ['-jar', resolve(root, 'services/approval/target/approval-service-0.1.0-SNAPSHOT.jar')], {
    cwd: root, windowsHide: true, stdio: ['ignore', log, log],
    env: { ...process.env, DB_URL: database, DB_MIGRATION_URL: database, APP_PORT: '18092', MANAGEMENT_PORT: '18093', APP_BIND_ADDRESS: '127.0.0.1', G5_BOOTSTRAP_ENABLED: 'true', G5_BOOTSTRAP_ACCOUNT: managerCode, G5_BOOTSTRAP_PASSWORD: originalPassword, G5_IDEMPOTENCY_HMAC_KEY: randomBytes(48).toString('base64') },
  });
  closeSync(log); backend.on('error', () => {});
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    if (backend.exitCode !== null) throw new Error('Management browser backend exited; inspect ignored log.');
    try { if ((await fetch('http://127.0.0.1:18093/actuator/health/readiness')).ok) return; } catch { /* startup */ }
    await wait(200);
  }
  throw new Error('Management browser backend did not become ready.');
}
async function stopBackend(): Promise<void> {
  const stopping = backend; backend = undefined;
  if (!stopping || stopping.exitCode !== null) return;
  const ended = new Promise<void>(resolve => stopping.once('exit', () => resolve()));
  const timeout = setTimeout(() => { if (stopping.exitCode === null) stopping.kill('SIGKILL'); }, 15000);
  try { stopping.kill(); await ended; } finally { clearTimeout(timeout); }
}
async function login(page: Page, code = managerCode, password = managerPassword): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('校内账号', { exact: true }).fill(code);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录校园空间', exact: true }).click();
  await expect(page).toHaveURL(baseURL + '/');
}
async function accounts(page: Page): Promise<void> {
  await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '账户管理', exact: true }).click();
  await expect(page.getByRole('button', { name: '新增账户', exact: false })).toBeEnabled();
}
function row(page: Page, code: string): Locator { return page.locator('.management-table tbody tr').filter({ hasText: code }); }
async function searchAccount(page: Page, code: string): Promise<void> {
  await page.getByLabel('搜索账户', { exact: true }).fill(code);
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(row(page, code)).toHaveCount(1);
}
async function newAccount(page: Page, code: string, password: string, role: string, department = '浏览器验证院系'): Promise<void> {
  await page.getByRole('button', { name: '新增账户', exact: false }).click();
  const dialog = page.getByRole('dialog', { name: '新增账户', exact: true });
  await dialog.getByLabel('用户编号', { exact: true }).fill(code);
  await dialog.getByLabel('姓名', { exact: true }).fill(code === 'web-student' ? '页面验证学生' : code);
  await dialog.getByLabel('所属院系', { exact: true }).selectOption({ label: department });
  await dialog.getByLabel('联系邮箱', { exact: true }).fill(code + '@example.invalid');
  await dialog.getByLabel('初始密码', { exact: false }).first().fill(password);
  await dialog.getByRole('checkbox', { name: role, exact: false }).check();
  await dialog.getByRole('button', { name: '创建账户', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('status').filter({ hasText: '账户已创建' })).toBeVisible();
}
async function csrf(page: Page): Promise<string> { return await page.evaluate(() => sessionStorage.getItem('pj.csrf.v1') ?? ''); }
async function noOverflow(page: Page): Promise<void> { expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true); }

test.describe('真实 HTTPS 院系和账户管理页面', () => {
  test.describe.configure({ mode: 'serial' });
  test.beforeAll(async () => { test.setTimeout(90000); await startBackend(); });
  test.beforeEach(() => sql('DELETE FROM m2.g5_auth_throttle'));
  test.afterAll(stopBackend);

  test('院系与账户从页面创建，角色变更撤销旧登录，停用后拒绝登录', async ({ page, browser }) => {
    await login(page);
    await page.getByRole('navigation').getByRole('link', { name: '院系管理', exact: true }).click();
    await page.getByRole('button', { name: '新增院系', exact: false }).click();
    const departmentDialog = page.getByRole('dialog', { name: '新增院系', exact: true });
    await departmentDialog.getByLabel('院系编号', { exact: true }).fill('WEB_DEPT');
    await departmentDialog.getByLabel('院系名称', { exact: true }).fill('浏览器验证院系');
    await departmentDialog.getByRole('button', { name: '创建院系', exact: true }).click();
    await expect(departmentDialog).not.toBeVisible();
    await expect(row(page, 'WEB_DEPT')).toContainText('浏览器验证院系');
    await accounts(page);
    await page.screenshot({ path: resolve(output, 'accounts-desktop.png'), fullPage: true });
    await newAccount(page, 'web-student', studentPassword, '学生');
    await searchAccount(page, 'web-student');
    await expect(row(page, 'web-student')).toContainText('学生');
    const studentContext = await browser.newContext({ baseURL, ignoreHTTPSErrors: true });
    const student = await studentContext.newPage();
    try {
      await login(student, 'web-student', studentPassword);
      await expect(student.getByRole('navigation').getByRole('link', { name: '账户管理' })).toHaveCount(0);
      await expect(student.getByRole('navigation').getByRole('link', { name: '院系管理' })).toHaveCount(0);
      await student.goto('/admin/accounts');
      await expect(student.getByRole('heading', { name: '你没有管理权限' })).toBeVisible();
      expect((await student.request.get('/api/m2/admin/accounts')).status()).toBe(403);
      await row(page, 'web-student').getByRole('button', { name: '分配角色', exact: true }).click();
      const roles = page.getByRole('dialog', { name: '分配角色', exact: true });
      await roles.getByRole('checkbox', { name: '学生', exact: false }).uncheck();
      await roles.getByRole('checkbox', { name: '整室申请人', exact: false }).check();
      await roles.getByRole('button', { name: '保存角色', exact: true }).click();
      await expect(roles).not.toBeVisible();
      await student.reload(); await expect(student).toHaveURL(/\/login/);
      await login(student, 'web-student', studentPassword);
      await student.goto('/profile');
      await expect(student.getByText('申请整间教室', { exact: true })).toBeVisible();
      await row(page, 'web-student').getByRole('button', { name: '停用', exact: true }).click();
      await page.getByRole('dialog', { name: '停用账户', exact: true }).getByRole('button', { name: '确认停用', exact: true }).click();
      await expect(page.getByRole('dialog', { name: '停用账户', exact: true })).not.toBeVisible();
      await student.reload(); await expect(student).toHaveURL(/\/login/);
      await student.getByLabel('校内账号', { exact: true }).fill('web-student');
      await student.getByLabel('密码', { exact: true }).fill(studentPassword);
      await student.getByRole('button', { name: '登录校园空间', exact: true }).click();
      await expect(student.getByRole('alert')).toContainText('登录凭据无效');
    } finally { await studentContext.close(); }
  });

  test('重复院系编号与最后管理员保护提供明确提示', async ({ page }) => {
    await login(page); await page.goto('/admin/departments');
    await page.getByRole('button', { name: '新增院系', exact: false }).click();
    const dialog = page.getByRole('dialog', { name: '新增院系', exact: true });
    await dialog.getByLabel('院系编号', { exact: true }).fill('WEB_DEPT');
    await dialog.getByLabel('院系名称', { exact: true }).fill('重复编号测试');
    await dialog.getByRole('button', { name: '创建院系', exact: true }).click();
    await expect(dialog.getByRole('alert')).toContainText('已经存在');
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    await accounts(page); await searchAccount(page, managerCode);
    await row(page, managerCode).getByRole('button', { name: '停用', exact: true }).click();
    const confirmation = page.getByRole('dialog', { name: '停用账户', exact: true });
    await confirmation.getByRole('button', { name: '确认停用', exact: true }).click();
    await expect(confirmation.getByRole('alert')).toContainText('至少一个有效系统管理员');
    await confirmation.getByRole('button', { name: '返回列表核实', exact: true }).click();
    await expect(row(page, managerCode)).toContainText('启用');
  });

  test('真实版本冲突保留草稿，比较最新资料后显式选择再保存', async ({ page }) => {
    await login(page); await accounts(page); await searchAccount(page, 'web-student');
    await row(page, 'web-student').getByRole('button', { name: '编辑', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '编辑账户', exact: true });
    await dialog.getByLabel('姓名', { exact: true }).fill('保留的页面草稿');
    const list = await (await page.request.get('/api/m2/admin/accounts?keyword=web-student')).json();
    const account = list.data.items[0];
    const change = await page.request.patch(`/api/m2/admin/accounts/${account.id}`, { headers: { 'X-CSRF-Token': await csrf(page), 'Idempotency-Key': randomUUID() }, data: { expectedVersion: account.version, email: 'latest@example.invalid' } });
    expect(change.status()).toBe(200);
    await dialog.getByRole('button', { name: '保存资料', exact: true }).click();
    await expect(dialog.getByRole('alert')).toContainText('记录已发生变化');
    await expect(dialog.getByLabel('姓名', { exact: true })).toHaveValue('保留的页面草稿');
    await expect(dialog.getByText('latest@example.invalid', { exact: true })).toBeVisible();
    await dialog.getByRole('button', { name: '保留我的修改', exact: true }).click();
    await dialog.getByRole('button', { name: '保存资料', exact: true }).click();
    await expect(dialog).not.toBeVisible(); await expect(row(page, 'web-student')).toContainText('保留的页面草稿');
  });

  test('真实提交成功后丢失响应，手动重试沿用原请求键且只创建一次', async ({ page }) => {
    await login(page); await accounts(page);
    let dropped = false; const keys: string[] = [];
    await page.route('**/api/m2/admin/accounts', async route => {
      if (route.request().method() !== 'POST') { await route.continue(); return; }
      keys.push((await route.request().headerValue('Idempotency-Key')) ?? '');
      if (!dropped) { dropped = true; const committed = await route.fetch(); expect(committed.status()).toBe(201); await route.abort('failed'); }
      else await route.continue();
    });
    await page.getByRole('button', { name: '新增账户', exact: false }).click();
    const dialog = page.getByRole('dialog', { name: '新增账户', exact: true });
    await dialog.getByLabel('用户编号', { exact: true }).fill('retry-ui');
    await dialog.getByLabel('姓名', { exact: true }).fill('响应丢失验证');
    await dialog.getByLabel('所属院系', { exact: true }).selectOption({ label: '浏览器验证院系' });
    await dialog.getByLabel('联系邮箱', { exact: true }).fill('retry@example.invalid');
    await dialog.getByLabel('初始密码', { exact: false }).first().fill(randomBytes(18).toString('base64'));
    await dialog.getByRole('checkbox', { name: '学生', exact: false }).check();
    await dialog.getByRole('button', { name: '创建账户', exact: true }).click();
    await expect(dialog.getByRole('status')).toContainText('提交结果暂未确认');
    await expect(dialog.getByLabel('用户编号', { exact: true })).toBeDisabled();
    await dialog.getByRole('button', { name: '重试本次操作', exact: true }).click();
    await expect(dialog).not.toBeVisible(); expect(keys.length).toBe(2); expect(keys[0] === keys[1] && keys[0].length > 0).toBe(true);
    expect(sql("SELECT count(*) FROM m2.account WHERE user_code='retry-ui'")).toBe('1');
    await page.unroute('**/api/m2/admin/accounts');
  });

  test('本人资料与角色变更后立即重登，撤销管理角色后菜单和直达入口均受限', async ({ page }) => {
    await login(page); await accounts(page);
    await newAccount(page, 'web-backup', backupPassword, '系统管理员');
    await searchAccount(page, managerCode);
    await row(page, managerCode).getByRole('button', { name: '编辑', exact: true }).click();
    let dialog = page.getByRole('dialog', { name: '编辑账户', exact: true });
    await dialog.getByLabel('姓名', { exact: true }).fill('修改后的本人姓名');
    await dialog.getByRole('button', { name: '保存资料', exact: true }).click();
    await expect(page).toHaveURL(/\/login\?reason=updated/); await expect(page.getByRole('status')).toContainText('账户信息或授权已更新');
    await login(page); await accounts(page); await searchAccount(page, managerCode);
    await row(page, managerCode).getByRole('button', { name: '分配角色', exact: true }).click();
    dialog = page.getByRole('dialog', { name: '分配角色', exact: true });
    await dialog.getByRole('checkbox', { name: '系统管理员', exact: false }).uncheck();
    await dialog.getByRole('checkbox', { name: '学生', exact: false }).check();
    await dialog.getByRole('button', { name: '保存角色', exact: true }).click();
    await expect(page).toHaveURL(/\/login\?reason=updated/);
    await login(page); await expect(page.getByRole('navigation').getByRole('link', { name: '账户管理' })).toHaveCount(0);
    await page.goto('/admin/departments'); await expect(page.getByRole('heading', { name: '你没有管理权限' })).toBeVisible();
    expect((await page.request.get('/api/m2/admin/accounts')).status()).toBe(403);
    await page.getByRole('button', { name: '退出', exact: true }).click(); await expect(page).toHaveURL(/\/login/);
    managerCode = 'web-backup'; managerPassword = backupPassword;
  });

  test('院系启停、已有停用院系绑定保留与390px管理页面可操作', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await login(page); await page.goto('/admin/departments');
    await expect(row(page, 'WEB_DEPT')).toBeVisible(); await noOverflow(page);
    await row(page, 'WEB_DEPT').getByRole('button', { name: '编辑', exact: true }).click();
    let dialog = page.getByRole('dialog', { name: '编辑院系', exact: true });
    await dialog.getByLabel('院系名称', { exact: true }).fill('改名后的浏览器院系');
    await dialog.getByRole('button', { name: '保存院系', exact: true }).click();
    await expect(dialog).not.toBeVisible(); await expect(row(page, 'WEB_DEPT')).toContainText('改名后的浏览器院系');
    await page.screenshot({ path: resolve(output, 'departments-mobile.png'), fullPage: true });
    await row(page, 'WEB_DEPT').getByRole('button', { name: '停用', exact: true }).click();
    await page.getByRole('dialog', { name: '停用院系', exact: true }).getByRole('button', { name: '确认停用', exact: true }).click();
    await expect(page.getByRole('dialog', { name: '停用院系', exact: true })).not.toBeVisible();
    await page.getByLabel('院系状态', { exact: true }).selectOption('false'); await page.getByRole('button', { name: '查询', exact: true }).click();
    await expect(row(page, 'WEB_DEPT')).toContainText('停用'); await accounts(page); await noOverflow(page);
    await page.screenshot({ path: resolve(output, 'accounts-mobile.png'), fullPage: true });
    await searchAccount(page, 'web-student'); await row(page, 'web-student').getByRole('button', { name: '编辑', exact: true }).click();
    dialog = page.getByRole('dialog', { name: '编辑账户', exact: true });
    await expect(dialog.getByLabel('所属院系', { exact: true }).locator('option:checked')).toHaveText('改名后的浏览器院系（停用）');
    await expect(dialog.getByLabel('所属院系', { exact: true }).locator('option:checked')).toBeEnabled();
    await noOverflow(page); await page.screenshot({ path: resolve(output, 'edit-account-mobile.png'), fullPage: true });
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    await page.getByRole('button', { name: '新增账户', exact: false }).click(); dialog = page.getByRole('dialog', { name: '新增账户', exact: true });
    await expect(dialog.getByLabel('所属院系', { exact: true }).locator('option').filter({ hasText: '改名后的浏览器院系（停用）' })).toBeDisabled();
    await noOverflow(page); await dialog.getByRole('button', { name: '取消', exact: true }).click();
  });

  test('账户分页与超过100项的院系选择完整读取', async ({ page }) => {
    test.setTimeout(90000);
    await login(page); await accounts(page);
    sql("INSERT INTO m2.department(id,code,name) SELECT gen_random_uuid(),'BULK_'||n::text,'批量院系'||n::text FROM generate_series(1,105) n");
    const system = sql("SELECT id FROM m2.department WHERE code='SYSTEM'");
    const role = sql("SELECT id FROM m2.role WHERE key='STUDENT'");
    const headers = { 'X-CSRF-Token': await csrf(page) };
    for (let index = 1; index <= 25; index++) {
      const code = `pager-${index}`;
      const result = await page.request.post('/api/m2/admin/accounts', { headers: { ...headers, 'Idempotency-Key': randomUUID() }, data: { userCode: code, displayName: `分页验证${index}`, departmentId: system, email: `${code}@example.invalid`, initialPassword: randomBytes(18).toString('base64'), roleIds: [role] } });
      expect(result.status()).toBe(201);
    }
    await page.getByLabel('搜索账户', { exact: true }).fill('pager-'); await page.getByRole('button', { name: '查询', exact: true }).click();
    await expect(page.locator('.management-table tbody tr')).toHaveCount(20);
    await page.getByRole('button', { name: '下一页', exact: true }).click(); await expect(page.locator('.management-table tbody tr')).toHaveCount(5);
    await expect(page.getByText('第 2 / 2 页 · 每页 20 条', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '新增账户', exact: false }).click();
    const dialog = page.getByRole('dialog', { name: '新增账户', exact: true });
    const count = Number(sql('SELECT count(*) FROM m2.department'));
    await expect(dialog.getByLabel('所属院系', { exact: true }).locator('option')).toHaveCount(count + 1);
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    expect(await page.evaluate(() => Object.keys(localStorage))).toEqual([]);
    expect(await page.evaluate(() => Object.keys(sessionStorage))).toEqual(['pj.csrf.v1']);
  });

  test('角色页面创建范围授权，分配账户后登录，再修改角色撤销旧会话', async ({ page, browser }) => {
    await login(page); await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '角色与权限', exact: true }).click();
    await page.getByRole('button', { name: '新增角色', exact: false }).click();
    const dialog = page.getByRole('dialog', { name: '新增角色', exact: true });
    await dialog.getByLabel('角色编号', { exact: true }).fill('BROWSER_ROLE');
    await dialog.getByLabel('角色名称', { exact: true }).fill('浏览器角色验证');
    await dialog.getByRole('button', { name: '添加授权', exact: false }).click();
    await dialog.getByLabel('操作权限 1', { exact: true }).selectOption('room:apply');
    await dialog.getByLabel('授权范围 1', { exact: true }).selectOption('SCHOOL');
    await dialog.getByRole('button', { name: '添加授权', exact: false }).click();
    await dialog.getByLabel('操作权限 2', { exact: true }).selectOption('room:read');
    await dialog.getByLabel('授权范围 2', { exact: true }).selectOption('DEPARTMENT');
    const count = Number(sql('SELECT count(*) FROM m2.department'));
    await expect(dialog.getByLabel('院系 2', { exact: true }).locator('option')).toHaveCount(count + 1);
    await dialog.getByLabel('院系 2', { exact: true }).selectOption({ label: '系统管理' });
    await dialog.getByRole('button', { name: '创建角色', exact: true }).click();await expect(dialog).not.toBeVisible();
    await expect(row(page, 'BROWSER_ROLE')).toContainText('共 2 条授权');
    await page.screenshot({ path: resolve(output, 'roles-desktop.png'), fullPage: true });
    await accounts(page); const password = randomBytes(18).toString('base64');
    await newAccount(page, 'role-browser-user', password, '浏览器角色验证', '系统管理');
    const context = await browser.newContext({ baseURL, ignoreHTTPSErrors: true }); const user = await context.newPage();
    try {
      await login(user, 'role-browser-user', password); await user.goto('/profile');
      await expect(user.getByText('申请整间教室', { exact: true })).toBeVisible();
      await expect(user.getByRole('navigation').getByRole('link', { name: '角色与权限', exact: true })).toHaveCount(0);
      await user.goto('/admin/roles'); await expect(user.getByRole('heading', { name: '你没有管理权限' })).toBeVisible();
      expect((await user.request.get('/api/m2/admin/permissions')).status()).toBe(403);
      await page.goto('/admin/roles');await row(page, 'BROWSER_ROLE').getByRole('button', { name: '查看与编辑', exact: true }).click();
      const edit = page.getByRole('dialog', { name: '编辑角色权限', exact: true });
      await edit.getByRole('button', { name: '移除第 1 条授权', exact: true }).click();
      await edit.getByRole('button', { name: '保存权限', exact: true }).click();await expect(edit).not.toBeVisible();
      await user.reload(); await expect(user).toHaveURL(/\/login/);
      await login(user, 'role-browser-user', password); await user.goto('/profile');
      await expect(user.getByText('申请整间教室', { exact: true })).toHaveCount(0);
      await expect(user.getByText('查看开放空间', { exact: true })).toBeVisible();
    } finally { await context.close(); }
  });

  test('角色版本冲突保留草稿并显示最新授权，显式合并后保存', async ({ page }) => {
    await login(page); await page.goto('/admin/roles'); await row(page, 'BROWSER_ROLE').getByRole('button', { name: '查看与编辑', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '编辑角色权限', exact: true });
    await expect(dialog.getByLabel('角色编号', { exact: true })).toHaveAttribute('readonly', '');
    await dialog.getByLabel('角色名称', { exact: true }).fill('保留的角色草稿');
    const role = (await (await page.request.get('/api/m2/admin/roles?keyword=BROWSER_ROLE')).json()).data.items[0];
    const changed = await page.request.patch(`/api/m2/admin/roles/${role.id}`, { headers: { 'X-CSRF-Token': await csrf(page), 'Idempotency-Key': randomUUID() }, data: { expectedVersion: role.version, name: '另一位管理员的新名称', grants: role.grants } });expect(changed.status()).toBe(200);
    await dialog.getByRole('button', { name: '保存权限', exact: true }).click();
    await expect(dialog.getByRole('heading', { name: '这个角色已有更新' })).toBeVisible();
    await expect(dialog.getByLabel('角色名称', { exact: true })).toHaveValue('保留的角色草稿');
    await dialog.getByText('查看最新授权', { exact: true }).click(); await expect(dialog.locator('.management-conflict details')).toContainText('查看开放空间');
    await dialog.getByRole('button', { name: '保留我的修改', exact: true }).click();
    await dialog.getByRole('button', { name: '保存权限', exact: true }).click();await expect(dialog).not.toBeVisible();
    await expect(row(page, 'BROWSER_ROLE')).toContainText('保留的角色草稿');
  });

  test('角色创建丢失真实响应后同键重试，数据库只有一个角色', async ({ page }) => {
    await login(page); await page.goto('/admin/roles');let dropped = false; const keys: string[] = [];
    await page.route('**/api/m2/admin/roles', async route => {
      if (route.request().method() !== 'POST') { await route.continue(); return; }
      keys.push((await route.request().headerValue('Idempotency-Key')) ?? '');
      if (!dropped) { dropped = true; expect((await route.fetch()).status()).toBe(201); await route.abort('failed'); } else await route.continue();
    });
    await page.getByRole('button', { name: '新增角色', exact: false }).click();const dialog = page.getByRole('dialog', { name: '新增角色', exact: true });
    await dialog.getByLabel('角色编号', { exact: true }).fill('ROLE_RETRY');await dialog.getByLabel('角色名称', { exact: true }).fill('角色响应丢失验证');
    await dialog.getByRole('button', { name: '创建角色', exact: true }).click();await expect(dialog.getByRole('status')).toContainText('提交结果暂未确认');
    await expect(dialog.getByLabel('角色名称', { exact: true })).toBeDisabled();
    await dialog.getByRole('button', { name: '重试本次操作', exact: true }).click();await expect(dialog).not.toBeVisible();expect(keys).toHaveLength(2);expect(keys[0]).toBe(keys[1]);
    expect(sql("SELECT count(*) FROM m2.role WHERE key='ROLE_RETRY'")).toBe('1');await page.unroute('**/api/m2/admin/roles');
  });

  test('重复角色、系统范围选项和最后管理员保护，修改本人角色后重登', async ({ page }) => {
    await login(page);await page.goto('/admin/roles');await page.getByRole('button', { name: '新增角色', exact: false }).click();
    let dialog = page.getByRole('dialog', { name: '新增角色', exact: true });await dialog.getByLabel('角色编号', { exact: true }).fill('ROLE_RETRY');await dialog.getByLabel('角色名称', { exact: true }).fill('重复角色');
    await dialog.getByRole('button', { name: '创建角色', exact: true }).click();await expect(dialog.getByRole('alert')).toContainText('已经存在');await dialog.getByRole('button', { name: '取消', exact: true }).click();
    await row(page, 'SYSTEM_ADMIN').getByRole('button', { name: '查看与编辑', exact: true }).click();dialog = page.getByRole('dialog', { name: '编辑角色权限', exact: true });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('button', { name: /^移除第/ })).toHaveCount(7);
    while (await dialog.getByRole('button', { name: /^移除第/ }).count()) await dialog.getByRole('button', { name: /^移除第/ }).first().click();
    await dialog.getByRole('button', { name: '保存权限', exact: true }).click();await expect(dialog.getByRole('alert')).toContainText('至少一个有效系统管理员');await dialog.getByRole('button', { name: '取消', exact: true }).click();
    await row(page, 'SYSTEM_ADMIN').getByRole('button', { name: '查看与编辑', exact: true }).click();dialog = page.getByRole('dialog', { name: '编辑角色权限', exact: true });
    await dialog.getByRole('button', { name: '添加授权', exact: false }).click();const last = await dialog.getByRole('button', { name: /^移除第/ }).count();
    await dialog.getByLabel(`操作权限 ${last}`, { exact: true }).selectOption('system:manage');await expect(dialog.getByLabel(`授权范围 ${last}`, { exact: true }).locator('option')).toHaveCount(1);
    await expect(dialog.getByLabel(`授权范围 ${last}`, { exact: true })).toHaveValue('SYSTEM');
    await dialog.getByRole('button', { name: `移除第 ${last} 条授权`, exact: true }).click();await dialog.getByLabel('角色名称', { exact: true }).fill('系统维护角色');
    await dialog.getByRole('button', { name: '保存权限', exact: true }).click();await expect(page).toHaveURL(/\/login\?reason=updated/);await login(page);
  });

  test('房间核验不可用时明确拒绝，390px角色编辑和分页可操作', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });await login(page);await page.goto('/admin/roles');await noOverflow(page);
    await page.screenshot({ path: resolve(output, 'roles-mobile.png'), fullPage: true });await page.getByRole('button', { name: '新增角色', exact: false }).click();
    const dialog = page.getByRole('dialog', { name: '新增角色', exact: true });await dialog.getByLabel('角色编号', { exact: true }).fill('ROOM_UNAVAILABLE');await dialog.getByLabel('角色名称', { exact: true }).fill('房间服务不可用验证');
    await dialog.getByRole('button', { name: '添加授权', exact: false }).click();await dialog.getByLabel('授权范围 1', { exact: true }).selectOption('ROOM');await dialog.getByLabel('房间编号 ID 1', { exact: true }).fill(randomUUID());await noOverflow(page);
    await page.screenshot({ path: resolve(output, 'role-editor-mobile.png'), fullPage: true });
    await dialog.getByRole('button', { name: '创建角色', exact: true }).click();await expect(dialog.getByRole('alert')).toContainText('暂时无法核验房间');expect(sql("SELECT count(*) FROM m2.role WHERE key='ROOM_UNAVAILABLE'")).toBe('0');
    await dialog.getByRole('button', { name: '返回列表核实', exact: true }).click();
    for (let i = 1; i <= 25; i++) { const result = await page.request.post('/api/m2/admin/roles', { headers: { 'X-CSRF-Token': await csrf(page), 'Idempotency-Key': randomUUID() }, data: { key: `ROLE_PAGE_${i}`, name: `分页角色${i}`, grants: [] } });expect(result.status()).toBe(201); }
    await page.getByLabel('搜索角色', { exact: true }).fill('ROLE_PAGE_');await page.getByRole('button', { name: '查询', exact: true }).click();await expect(page.locator('.management-table tbody tr')).toHaveCount(20);
    await page.getByRole('button', { name: '下一页', exact: true }).click();await expect(page.locator('.management-table tbody tr')).toHaveCount(5);await noOverflow(page);
    expect(await page.evaluate(() => Object.keys(localStorage))).toEqual([]);expect(await page.evaluate(() => Object.keys(sessionStorage))).toEqual(['pj.csrf.v1']);
  });

  test('配置默认值、合法保存、版本生效时间与普通用户隔离', async ({ page, browser }) => {
    await login(page);await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '全局参数', exact: true }).click();
    await expect(page.getByRole('button', { name: '保存参数', exact: true })).toBeEnabled();
    for (const [label, value] of [['个人预约时长上限', '4'], ['个人预约提前天数', '7'], ['整室申请提前天数', '14'], ['自动检查间隔', '10'], ['通知自动重试次数', '5']]) await expect(page.getByLabel(label, { exact: true })).toHaveValue(value);
    await expect(page.getByText('允许 0—5 次 · 默认 5', { exact: true })).toBeVisible();
    await page.screenshot({ path: resolve(output, 'configuration-desktop.png'), fullPage: true });
    await page.getByLabel('个人预约时长上限', { exact: true }).fill('3');await page.getByLabel('通知自动重试次数', { exact: true }).fill('0');
    await page.getByRole('button', { name: '保存参数', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: '本次保存已确认' })).toBeVisible();await expect(page.getByRole('button', { name: '保存参数', exact: true })).toBeEnabled();
    const current = (await (await page.request.get('/api/m2/admin/config')).json()).data;expect(current.version).toBe('2');expect(current.values.reservationMaxHours).toBe(3);expect(current.values.notificationRetryLimit).toBe(0);
    await page.reload();await expect(page.getByLabel('个人预约时长上限', { exact: true })).toHaveValue('3');await expect(page.getByLabel('通知自动重试次数', { exact: true })).toHaveValue('0');
    const context = await browser.newContext({ baseURL, ignoreHTTPSErrors: true });const ordinary = await context.newPage();
    try { await login(ordinary, 'web-manager', originalPassword);await expect(ordinary.getByRole('navigation').getByRole('link', { name: '全局参数', exact: true })).toHaveCount(0);await ordinary.goto('/admin/config');await expect(ordinary.getByRole('heading', { name: '你没有管理权限' })).toBeVisible();expect((await ordinary.request.get('/api/m2/admin/config')).status()).toBe(403);expect((await ordinary.request.get('/internal/config')).status()).toBe(404); }
    finally { await context.close(); }
  });

  test('配置表单拒绝越界和空值，填入默认不会自动保存', async ({ page }) => {
    await login(page);await page.goto('/admin/config');await expect(page.getByRole('button', { name: '保存参数', exact: true })).toBeEnabled();
    const before = (await (await page.request.get('/api/m2/admin/config')).json()).data;
    await page.getByLabel('个人预约时长上限', { exact: true }).fill('9');await page.getByRole('button', { name: '保存参数', exact: true }).click();await expect(page.getByRole('alert')).toContainText('1—8');
    await page.getByLabel('个人预约时长上限', { exact: true }).fill('');await page.getByRole('button', { name: '保存参数', exact: true }).click();await expect(page.getByRole('alert')).toContainText('整数');
    await page.getByRole('button', { name: '填入默认值', exact: true }).click();await expect(page.getByLabel('个人预约时长上限', { exact: true })).toHaveValue('4');await expect(page.getByRole('status').filter({ hasText: '点击保存后才会生效' })).toBeVisible();
    expect((await (await page.request.get('/api/m2/admin/config')).json()).data).toEqual(before);
    await page.getByRole('button', { name: '刷新配置', exact: true }).click();await expect(page.getByLabel('个人预约时长上限', { exact: true })).toHaveValue('3');
  });

  test('配置真实版本冲突显示最新五项值并保留草稿供显式选择', async ({ page }) => {
    await login(page);await page.goto('/admin/config');await expect(page.getByRole('button', { name: '保存参数', exact: true })).toBeEnabled();
    await page.getByLabel('个人预约时长上限', { exact: true }).fill('2');
    const before = (await (await page.request.get('/api/m2/admin/config')).json()).data;
    const update = await page.request.patch('/api/m2/admin/config', { headers: { 'X-CSRF-Token': await csrf(page), 'Idempotency-Key': randomUUID() }, data: { expectedVersion: before.version, values: { ...before.values, applicationAdvanceDays: 10 } } });expect(update.status()).toBe(200);
    await page.getByRole('button', { name: '保存参数', exact: true }).click();await expect(page.getByRole('heading', { name: '配置已有更新', exact: true })).toBeVisible();await expect(page.getByLabel('个人预约时长上限', { exact: true })).toHaveValue('2');await expect(page.locator('.config-comparison')).toContainText('10 天');
    await page.getByRole('button', { name: '保留我的修改', exact: true }).click();await page.getByRole('button', { name: '保存参数', exact: true }).click();await expect(page.getByRole('status').filter({ hasText: '本次保存已确认' })).toBeVisible();
    const current = (await (await page.request.get('/api/m2/admin/config')).json()).data;expect(current.version).toBe('4');expect(current.values.reservationMaxHours).toBe(2);
  });

  test('配置已提交但响应丢失时冻结原输入，同键重试不重复增加版本', async ({ page }) => {
    await login(page);await page.goto('/admin/config');await expect(page.getByRole('button', { name: '保存参数', exact: true })).toBeEnabled();
    let dropped = false;const keys: string[] = [];const bodies: unknown[] = [];
    await page.route('**/api/m2/admin/config', async route => {
      if (route.request().method() !== 'PATCH') { await route.continue();return; }
      keys.push((await route.request().headerValue('Idempotency-Key')) ?? '');bodies.push(route.request().postDataJSON());
      if (!dropped) { dropped = true;expect((await route.fetch()).status()).toBe(200);await route.abort('failed'); } else await route.continue();
    });
    await page.getByLabel('个人预约时长上限', { exact: true }).fill('5');await page.getByRole('button', { name: '保存参数', exact: true }).click();await expect(page.getByRole('status').filter({ hasText: '保存结果暂未确认' })).toBeVisible();await expect(page.getByLabel('个人预约时长上限', { exact: true })).toBeDisabled();
    await page.getByRole('button', { name: '重试本次保存', exact: true }).click();await expect(page.getByRole('status').filter({ hasText: '本次保存已确认' })).toBeVisible();await expect(page.getByRole('button', { name: '保存参数', exact: true })).toBeEnabled();
    expect(keys).toHaveLength(2);expect(keys[0]).toBe(keys[1]);expect(bodies[0]).toEqual(bodies[1]);
    expect((await (await page.request.get('/api/m2/admin/config')).json()).data.version).toBe('5');expect(sql('SELECT count(*) FROM m2.g5_config_history')).toBe('5');
    await page.unroute('**/api/m2/admin/config');
  });

  test('390px配置页面完整可操作，相同值保存不产生新版本或事件', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });await login(page);await page.goto('/admin/config');await expect(page.getByRole('button', { name: '保存参数', exact: true })).toBeEnabled();await noOverflow(page);
    await page.screenshot({ path: resolve(output, 'configuration-mobile.png'), fullPage: true });
    const events = sql("SELECT count(*) FROM m2.g5_audit_event WHERE event->>'eventType'='ConfigChanged'");
    await page.getByRole('button', { name: '保存参数', exact: true }).click();await expect(page.getByRole('status').filter({ hasText: '数值未变化' })).toBeVisible();
    expect((await (await page.request.get('/api/m2/admin/config')).json()).data.version).toBe('5');expect(sql("SELECT count(*) FROM m2.g5_audit_event WHERE event->>'eventType'='ConfigChanged'")).toBe(events);
    expect(await page.evaluate(() => Object.keys(localStorage))).toEqual([]);expect(await page.evaluate(() => Object.keys(sessionStorage))).toEqual(['pj.csrf.v1']);
  });
});
