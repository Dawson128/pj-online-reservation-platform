<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ApiError } from '../../../app/http';
import { session } from '../../../app/session';
import ErrorNotice from '../../../shared/ui/ErrorNotice.vue';
import ManagementDialog from '../components/ManagementDialog.vue';
import { queryPath, useManagement } from '../management';
import type { Account, Department, Page, Role } from '../management';
import '../management.css';

const api = useManagement();
const { error, busy, uncertain } = api;
const accounts = ref<Account[]>([]);
const departments = ref<Department[]>([]);
const roles = ref<Role[]>([]);
const loading = ref(true);
const opening = ref(false);
const page = ref(1);
const total = ref(0);
const pageSize = 20;
const pages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));
const filters = reactive({ keyword: '', enabled: '', departmentId: '', roleId: '' });
let applied = { ...filters };
let generation = 0;
const notice = ref('');
const editor = ref<'' | 'create' | 'edit' | 'roles'>('');
const selected = ref<Account | null>(null);
const latest = ref<Account | null>(null);
const statusTarget = ref<Account | null>(null);
const revealPassword = ref(false);
const form = reactive({ userCode: '', displayName: '', departmentId: '', email: '', initialPassword: '', roleIds: [] as string[] });
const editorTitle = computed(() => editor.value === 'create' ? '新增账户' : editor.value === 'roles' ? '分配角色' : '编辑账户');
const locked = computed(() => busy.value || uncertain.value);
function departmentName(id: string): string { return departments.value.find(item => item.id === id)?.name ?? '院系信息待刷新'; }
function roleNames(ids: string[]): string[] { return ids.map(id => roles.value.find(item => item.id === id)?.name ?? '角色信息待刷新'); }

async function choices(): Promise<void> {
  const [enabled, disabled, catalog] = await Promise.all([
    api.all<Department>('/api/m2/departments', { enabled: 'true' }),
    api.all<Department>('/api/m2/departments', { enabled: 'false' }),
    api.all<Role>('/api/m2/admin/roles'),
  ]);
  departments.value = [...new Map([...enabled, ...disabled].map(item => [item.id, item])).values()].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
  roles.value = catalog;
}
async function load(next = page.value): Promise<void> {
  const current = ++generation;
  loading.value = true; accounts.value = []; error.value = null;
  try {
    const result = await api.read<Page<Account>>(queryPath('/api/m2/admin/accounts', { ...applied, page: next, pageSize }));
    if (current !== generation) return;
    const lastPage = Math.max(1, Math.ceil(result.total / pageSize));
    if (result.page > lastPage) { await load(lastPage); return; }
    accounts.value = result.items; total.value = result.total; page.value = result.page;
  } catch { /* Error state is displayed; an unavailable list is never presented as an empty success. */ }
  finally { if (current === generation) loading.value = false; }
}
async function initialize(): Promise<void> {
  try { await choices(); await load(1); }
  catch { loading.value = false; }
}
function search(): void { applied = { ...filters, keyword: filters.keyword.trim() }; void load(1); }
function clearFilters(): void { Object.assign(filters, { keyword: '', enabled: '', departmentId: '', roleId: '' }); search(); }
async function open(mode: 'create' | 'edit' | 'roles', account?: Account): Promise<void> {
  if (opening.value) return;
  opening.value = true; api.reset(); notice.value = ''; latest.value = null;
  try {
    await choices();
    selected.value = account ? await api.read<Account>(`/api/m2/admin/accounts/${account.id}`) : null;
    fill(selected.value); editor.value = mode;
  } catch { /* Keep the list available for retry. */ }
  finally { opening.value = false; }
}
function fill(account: Account | null): void {
  Object.assign(form, { userCode: account?.userCode ?? '', displayName: account?.displayName ?? '', departmentId: account?.departmentId ?? '', email: account?.email ?? '', initialPassword: '', roleIds: [...(account?.roleIds ?? [])] });
  revealPassword.value = false;
}
function closeEditor(): void {
  if (busy.value) return;
  const wasUncertain = uncertain.value;
  const shouldRefresh = wasUncertain || Boolean(error.value);
  editor.value = ''; selected.value = null; latest.value = null; fill(null); api.reset();
  if (wasUncertain) { notice.value = '请先查询账户列表，核实刚才的操作结果。'; void load(1); }
  else if (shouldRefresh) void load();
}
function generatePassword(): void {
  form.initialPassword = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(12))));
}
async function save(): Promise<void> {
  if (!uncertain.value && editor.value !== 'roles') {
    if (![form.userCode, form.displayName, form.departmentId, form.email].every(value => value.trim())) { error.value = new Error('请完整填写账户资料。'); return; }
    if (editor.value === 'create' && (Array.from(form.initialPassword).length < 8 || Array.from(form.initialPassword).length > 24)) { error.value = new Error('初始密码须为 8—24 个字符。'); return; }
  }
  let path = '/api/m2/admin/accounts';
  let method: 'POST' | 'PATCH' | 'PUT' = 'POST';
  let body: object;
  if (editor.value === 'create') body = { userCode: form.userCode, displayName: form.displayName, departmentId: form.departmentId, email: form.email, initialPassword: form.initialPassword, roleIds: [...form.roleIds] };
  else if (editor.value === 'roles') { path += `/${selected.value!.id}/roles`; method = 'PUT'; body = { expectedVersion: selected.value!.version, roleIds: [...form.roleIds] }; }
  else { path += `/${selected.value!.id}`; method = 'PATCH'; body = { expectedVersion: selected.value!.version, displayName: form.displayName, departmentId: form.departmentId, email: form.email }; }
  const result = await api.write<Account>(path, method, body);
  if (result) {
    const mode = editor.value;
    closeEditor();
    if (mode !== 'create' && await api.changedSelf(result)) return;
    notice.value = mode === 'create' ? '账户已创建，可使用指定初始密码登录。' : mode === 'roles' ? '角色已更新，该账户原有登录已失效。' : '账户资料已更新，该账户需要重新登录。';
    await load(mode === 'create' ? 1 : page.value);
  } else if (error.value instanceof ApiError && error.value.code === 'VERSION_CONFLICT' && selected.value) {
    try { latest.value = await api.read<Account>(`/api/m2/admin/accounts/${selected.value.id}`); } catch { /* The current draft is retained. */ }
  }
}
function resolveConflict(keepDraft: boolean): void {
  if (!latest.value) return;
  selected.value = latest.value;
  if (!keepDraft) fill(latest.value);
  latest.value = null; api.reset();
}
function askStatus(account: Account): void { api.reset(); notice.value = ''; statusTarget.value = account; }
function closeStatus(): void {
  if (busy.value) return;
  const unknown = uncertain.value;
  const shouldRefresh = unknown || Boolean(error.value);
  statusTarget.value = null; api.reset();
  if (unknown) { notice.value = '请刷新列表核实账户状态。'; void load(); }
  else if (shouldRefresh) void load();
}
async function changeStatus(): Promise<void> {
  if (!statusTarget.value) return;
  const result = await api.write<Account>(`/api/m2/admin/accounts/${statusTarget.value.id}`, 'PATCH', { expectedVersion: statusTarget.value.version, enabled: !statusTarget.value.enabled });
  if (result) {
    closeStatus();
    if (await api.changedSelf(result)) return;
    notice.value = result.enabled ? '账户已启用。' : '账户已停用，原有登录已失效。'; await load();
  }
}
onMounted(() => { void initialize(); });
</script>

<template>

    <section class="page-heading"><div><p class="eyebrow">PEOPLE & ACCESS</p><h1>账户管理</h1><p>维护校园账户，让每个人拥有合适的身份与权限。</p></div><div class="management-actions"><button class="button button-secondary" :disabled="loading || opening" @click="initialize">刷新列表</button><button class="button button-primary" :disabled="loading || opening" @click="open('create')">新增账户 <span aria-hidden="true">＋</span></button></div></section>
    <div v-if="notice" class="notice notice-info" role="status">{{ notice }}</div>
    <ErrorNotice v-if="!editor && !statusTarget" :error="error" />
    <form class="surface management-filters" aria-label="账户筛选" @submit.prevent="search">
      <div class="management-filter-field"><label for="account-keyword-filter">搜索账户</label><input id="account-keyword-filter" v-model="filters.keyword" placeholder="用户编号或姓名" /></div>
      <div class="management-filter-field"><label for="account-state-filter">账户状态</label><select id="account-state-filter" v-model="filters.enabled"><option value="">全部状态</option><option value="true">启用</option><option value="false">停用</option></select></div>
      <div class="management-filter-field"><label for="account-department-filter">筛选院系</label><select id="account-department-filter" v-model="filters.departmentId"><option value="">全部院系</option><option v-for="item in departments" :key="item.id" :value="item.id">{{ item.name }}{{ item.enabled ? '' : '（停用）' }}</option></select></div>
      <div class="management-filter-field"><label for="account-role-filter">筛选角色</label><select id="account-role-filter" v-model="filters.roleId"><option value="">全部角色</option><option v-for="role in roles" :key="role.id" :value="role.id">{{ role.name }}</option></select></div>
      <div class="management-filter-buttons"><button class="button button-primary" type="submit" :disabled="loading">查询</button><button class="text-button" type="button" @click="clearFilters">重置</button></div>
    </form>
    <section class="surface management-directory" aria-label="账户目录" :aria-busy="loading">
      <div class="management-directory-heading"><div><span class="section-label">校园账户目录</span><p>{{ loading ? '正在读取最新账户…' : `共 ${total} 个匹配账户` }}</p></div><span class="management-caption">管理权限与业务权限分别授予</span></div>
      <div v-if="loading" class="management-empty" role="status">正在读取账户…</div>
      <div v-else-if="!accounts.length" class="management-empty"><h2>{{ error ? '暂时无法读取账户' : '没有匹配的账户' }}</h2><p>{{ error ? '请刷新列表后重试。' : '试试其他搜索条件，或创建一个新账户。' }}</p></div>
      <table v-else class="management-table"><caption class="management-sr-only">账户列表</caption><thead><tr><th>账户</th><th>所属院系</th><th>已分配角色</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="account in accounts" :key="account.id"><td data-label="账户"><strong>{{ account.displayName }}</strong><span class="management-subline">{{ account.userCode }}<span v-if="account.id === session.state.principal?.userId" class="management-self">本人</span></span><span class="management-subline">{{ account.email }}</span></td><td data-label="所属院系">{{ departmentName(account.departmentId) }}</td><td data-label="已分配角色"><div class="management-chips"><span v-for="name in roleNames(account.roleIds)" :key="name" class="management-chip">{{ name }}</span><span v-if="!account.roleIds.length" class="quiet-label">暂未分配</span></div></td><td data-label="状态"><span class="management-state" :class="{ 'is-disabled': !account.enabled }"><i></i>{{ account.enabled ? '启用' : '停用' }}</span></td><td data-label="操作"><div class="management-row-actions"><button class="text-button" :disabled="opening" @click="open('edit', account)">编辑</button><button class="text-button" :disabled="opening" @click="open('roles', account)">分配角色</button><button class="text-button" :class="{ 'management-danger': account.enabled }" @click="askStatus(account)">{{ account.enabled ? '停用' : '启用' }}</button></div></td></tr></tbody></table>
      <div class="management-pagination"><span>第 {{ page }} / {{ pages }} 页 · 每页 {{ pageSize }} 条</span><div><button class="button button-secondary" :disabled="loading || page <= 1" @click="load(page - 1)">上一页</button><button class="button button-secondary" :disabled="loading || page >= pages" @click="load(page + 1)">下一页</button></div></div>
    </section>

    <ManagementDialog :open="Boolean(editor)" :title="editorTitle" :busy="busy" @close="closeEditor">
      <ErrorNotice :error="error" />
      <div v-if="uncertain" class="notice notice-info" role="status">提交结果暂未确认。可重试本次操作，或返回列表核实；请勿重复创建账户。</div>
      <section v-if="latest" class="management-conflict" aria-label="最新账户资料"><h3>这条记录已有更新</h3><p>你的输入已保留。请核对最新资料，再决定如何继续。</p><dl><div><dt>姓名</dt><dd>{{ latest.displayName }}</dd></div><div><dt>院系</dt><dd>{{ departmentName(latest.departmentId) }}</dd></div><div><dt>邮箱</dt><dd>{{ latest.email }}</dd></div><div><dt>角色</dt><dd>{{ roleNames(latest.roleIds).join('、') || '暂未分配' }}</dd></div><div><dt>状态</dt><dd>{{ latest.enabled ? '启用' : '停用' }}</dd></div></dl><div class="management-actions"><button class="button button-secondary" @click="resolveConflict(false)">采用最新资料</button><button class="button button-secondary" @click="resolveConflict(true)">保留我的修改</button></div></section>
      <form novalidate @submit.prevent="save">
        <fieldset class="management-form-grid" :disabled="locked || Boolean(latest)">
          <template v-if="editor !== 'roles'">
            <div class="form-field"><label for="account-code">用户编号</label><input id="account-code" v-model="form.userCode" autocomplete="off" :readonly="editor !== 'create'" :placeholder="editor === 'create' ? '例如学号或工号' : ''" /></div>
            <div class="form-field"><label for="account-name">姓名</label><input id="account-name" v-model="form.displayName" autocomplete="off" placeholder="请输入姓名" /></div>
            <div class="form-field"><label for="account-department">所属院系</label><select id="account-department" v-model="form.departmentId"><option value="">请选择院系</option><option v-for="item in departments" :key="item.id" :value="item.id" :disabled="!item.enabled && item.id !== selected?.departmentId">{{ item.name }}{{ item.enabled ? '' : '（停用）' }}</option></select></div>
            <div class="form-field"><label for="account-email">联系邮箱</label><input id="account-email" v-model="form.email" type="email" autocomplete="off" placeholder="name@example.edu" /></div>
          </template>
          <div v-if="editor === 'create'" class="form-field management-full"><label for="account-password">初始密码 <span class="field-caption">8—24 个字符</span></label><div class="password-control"><input id="account-password" v-model="form.initialPassword" :type="revealPassword ? 'text' : 'password'" autocomplete="new-password" placeholder="设置使用者的初始密码" /><button type="button" :aria-label="revealPassword ? '隐藏初始密码' : '显示初始密码'" @click="revealPassword = !revealPassword">{{ revealPassword ? '隐藏' : '显示' }}</button></div><button class="text-button" type="button" @click="generatePassword">生成临时密码</button><p class="fine-print">请受控交付给使用者。关闭窗口后，此处不再保留密码。</p></div>
          <fieldset v-if="editor === 'create' || editor === 'roles'" class="management-role-picker management-full"><legend>{{ editor === 'roles' ? `${selected?.displayName ?? ''} 的角色` : '初始角色' }}</legend><label v-for="role in roles" :key="role.id" class="management-role-option"><input v-model="form.roleIds" type="checkbox" :value="role.id" /><span>{{ role.name }}<small>{{ role.builtIn ? '内置角色' : '已有自定义角色' }}</small></span></label><p class="fine-print">可以选择多个角色。未选择角色的账户可登录，暂无业务权限。</p></fieldset>
        </fieldset>
        <p v-if="editor !== 'create'" class="management-form-note">保存后，该账户的原有登录会失效。{{ selected?.id === session.state.principal?.userId ? '你正在修改本人账户，完成后需要重新登录。' : '' }}</p>
        <div class="management-dialog-actions"><button class="button button-secondary" type="button" :disabled="busy" @click="closeEditor">{{ uncertain ? '返回列表核实' : '取消' }}</button><button class="button button-primary" type="submit" :disabled="busy || Boolean(latest)">{{ busy ? '正在保存…' : uncertain ? '重试本次操作' : editor === 'create' ? '创建账户' : editor === 'roles' ? '保存角色' : '保存资料' }}</button></div>
      </form>
    </ManagementDialog>
    <ManagementDialog :open="Boolean(statusTarget)" :title="statusTarget?.enabled ? '停用账户' : '启用账户'" :busy="busy" @close="closeStatus">
      <ErrorNotice :error="error" /><p class="management-confirm-copy">{{ statusTarget?.enabled ? '停用' : '启用' }}「{{ statusTarget?.displayName }}」（{{ statusTarget?.userCode }}）？</p><p class="management-form-note">{{ statusTarget?.enabled ? '停用后，所有原有登录立即失效，账户资料和记录会保留。' : '启用后，使用者可以使用原密码重新登录。' }}</p>
      <p v-if="uncertain" class="notice notice-info" role="status">操作结果尚未确认，请重试本次操作或刷新列表核实。</p><div class="management-dialog-actions"><button class="button button-secondary" :disabled="busy" @click="closeStatus">{{ error ? '返回列表核实' : '取消' }}</button><button class="button button-primary" :disabled="busy || (error instanceof ApiError && error.code === 'VERSION_CONFLICT')" @click="changeStatus">{{ busy ? '正在处理…' : uncertain ? '重试本次操作' : statusTarget?.enabled ? '确认停用' : '确认启用' }}</button></div>
    </ManagementDialog>

</template>
