<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ApiError } from '../../../app/http';
import { session } from '../../../app/session';
import { displayTime } from '../../../app/types';
import type { Grant, Scope } from '../../../app/types';
import ErrorNotice from '../../../shared/ui/ErrorNotice.vue';
import ManagementDialog from '../components/ManagementDialog.vue';
import { queryPath, useManagement } from '../management';
import type { Account, Department, Page, Role } from '../management';
import '../management.css';

interface Permission { action: string; description: string; scopeKinds: Scope['kind'][]; ownerGroup: string }
interface DraftGrant { id: number; action: string; kind: Scope['kind']; target: string }
const api = useManagement();
const router = useRouter();
const { error, busy, uncertain } = api;
const permissions = ref<Permission[]>([]);
const departments = ref<Department[]>([]);
const items = ref<Role[]>([]);
const filters = reactive({ keyword: '', builtIn: '' });
let applied = { ...filters };
const page = ref(1), total = ref(0);
const pageSize = 20;
const pages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));
const loading = ref(true), opening = ref(false), catalogReady = ref(false);
const editor = ref<'' | 'create' | 'edit'>('');
const selected = ref<Role | null>(null), latest = ref<Role | null>(null);
const selfBound = ref(false), notice = ref('');
const form = reactive({ key: '', name: '', grants: [] as DraftGrant[] });
const scopeNames: Record<Scope['kind'], string> = { SCHOOL: '全校范围', DEPARTMENT: '指定院系', ROOM: '指定房间', SYSTEM: '系统范围' };
const groups: Record<string, string> = { G1: '个人预约', G2: '整室审批', G3: '报修维修', G4: '空间资源', G5: '身份与权限', G6: '通知与运行' };
let serial = 0, generation = 0;
function descriptor(action: string): Permission | undefined { return permissions.value.find(item => item.action === action); }
function grantLabel(grant: Grant): string {
  const scope = grant.scope;
  const target = scope.kind === 'DEPARTMENT' ? departments.value.find(item => item.id === scope.departmentId)?.name ?? scope.departmentId : scope.kind === 'ROOM' ? scope.roomId : '';
  return `${descriptor(grant.action)?.description ?? grant.action} · ${scopeNames[scope.kind]}${target ? `：${target}` : ''}`;
}
async function load(next = page.value): Promise<void> {
  const current = ++generation;
  loading.value = true; items.value = []; error.value = null;
  try {
    const data = await api.read<Page<Role>>(queryPath('/api/m2/admin/roles', { ...applied, page: next, pageSize }));
    if (current !== generation) return;
    const last = Math.max(1, Math.ceil(data.total / pageSize));
    if (data.page > last) { await load(last); return; }
    items.value = data.items; page.value = data.page; total.value = data.total;
  } catch { /* useManagement exposes the actual read failure. */ }
  finally { if (current === generation) loading.value = false; }
}
async function refresh(): Promise<void> {
  catalogReady.value = false; loading.value = true; error.value = null;
  try {
    const [catalog, enabled, disabled] = await Promise.all([
      api.read<{ items: Permission[] }>('/api/m2/admin/permissions'),
      api.all<Department>('/api/m2/departments'),
      api.all<Department>('/api/m2/departments', { enabled: 'false' }),
    ]);
    permissions.value = catalog.items; departments.value = [...enabled, ...disabled]; catalogReady.value = true;
    await load();
  } catch { loading.value = false; }
}
function search(): void { applied = { ...filters, keyword: filters.keyword.trim() }; void load(1); }
function assign(role: Role | null): void {
  form.key = role?.key ?? ''; form.name = role?.name ?? '';
  form.grants = role?.grants.map(grant => ({ id: ++serial, action: grant.action, kind: grant.scope.kind, target: grant.scope.kind === 'ROOM' ? grant.scope.roomId : grant.scope.kind === 'DEPARTMENT' ? grant.scope.departmentId : '' })) ?? [];
}
async function open(role?: Role): Promise<void> {
  if (opening.value || !catalogReady.value) return;
  opening.value = true; api.reset(); notice.value = ''; latest.value = null; selfBound.value = false;
  try {
    const current = role ? await api.read<Role>(`/api/m2/admin/roles/${role.id}`) : null;
    if (current && session.state.principal) {
      const self = await api.read<Account>(`/api/m2/admin/accounts/${session.state.principal.userId}`);
      selfBound.value = self.roleIds.includes(current.id);
    }
    selected.value = current; assign(current); editor.value = role ? 'edit' : 'create';
  } catch (failure) { error.value = failure; }
  finally { opening.value = false; }
}
function close(): void {
  if (busy.value) return;
  const refreshList = uncertain.value || Boolean(error.value);
  editor.value = ''; selected.value = null; latest.value = null; assign(null); api.reset();
  if (refreshList) void load();
}
function addGrant(): void {
  const first = permissions.value[0];
  if (first) form.grants.push({ id: ++serial, action: first.action, kind: first.scopeKinds[0], target: '' });
}
function changeAction(grant: DraftGrant): void {
  const kinds = descriptor(grant.action)?.scopeKinds ?? [];
  if (!kinds.includes(grant.kind)) { grant.kind = kinds[0]; grant.target = ''; }
}
function grants(): Grant[] {
  return form.grants.map(grant => ({ action: grant.action, scope: grant.kind === 'DEPARTMENT' ? { kind: grant.kind, departmentId: grant.target } : grant.kind === 'ROOM' ? { kind: grant.kind, roomId: grant.target.trim() } : { kind: grant.kind } }));
}
async function save(): Promise<void> {
  if (!uncertain.value && (!form.key.trim() || !form.name.trim() || form.grants.some(grant => ['ROOM', 'DEPARTMENT'].includes(grant.kind) && !grant.target.trim()))) {
    error.value = new Error('请填写角色编号、名称和每条授权的具体范围。'); return;
  }
  const create = editor.value === 'create';
  const data = await api.write<Role>(create ? '/api/m2/admin/roles' : `/api/m2/admin/roles/${selected.value!.id}`, create ? 'POST' : 'PATCH', { ...(create ? { key: form.key } : { expectedVersion: selected.value!.version }), name: form.name, grants: grants() });
  if (data) {
    if (selfBound.value) { session.clear('updated'); await router.replace({ name: 'g5-login', query: { reason: 'updated' } }); return; }
    // Creation replay may contain the original cached snapshot: always reload current lists/details.
    close(); notice.value = create ? '角色已创建，可在账户管理中分配给账户。' : '角色已更新，绑定账户的旧登录已失效，请使用新权限重新登录。'; await load(create ? 1 : page.value);
  } else if (error.value instanceof ApiError && error.value.code === 'VERSION_CONFLICT' && selected.value) {
    try { latest.value = await api.read<Role>(`/api/m2/admin/roles/${selected.value.id}`); } catch (failure) { error.value = failure; }
  }
}
function resolveConflict(keep: boolean): void {
  if (!latest.value) return;
  selected.value = latest.value; if (!keep) assign(latest.value);
  latest.value = null; api.reset();
}
onMounted(() => { void refresh(); });
</script>

<template>

    <section class="page-heading"><div><p class="eyebrow">CAMPUS ACCESS</p><h1>角色与权限</h1><p>把合适的权限，交给合适的人。每一份授权都有明确范围。</p></div><div class="management-actions"><button class="button button-secondary" :disabled="loading || opening" @click="refresh">刷新列表</button><button class="button button-primary" :disabled="loading || opening || !catalogReady" @click="open()">新增角色 <span aria-hidden="true">＋</span></button></div></section>
    <div v-if="notice" class="notice notice-info" role="status">{{ notice }}</div>
    <ErrorNotice v-if="!editor" :error="error" />
    <form class="surface management-filters management-filters--departments" aria-label="角色筛选" @submit.prevent="search"><div class="management-filter-field"><label for="role-keyword-filter">搜索角色</label><input id="role-keyword-filter" v-model="filters.keyword" placeholder="角色编号或名称" /></div><div class="management-filter-field"><label for="role-type-filter">角色类型</label><select id="role-type-filter" v-model="filters.builtIn"><option value="">全部类型</option><option value="true">内置角色</option><option value="false">自定义角色</option></select></div><div class="management-filter-buttons"><button class="button button-primary" type="submit" :disabled="loading">查询</button><button class="text-button" type="button" @click="Object.assign(filters, { keyword: '', builtIn: '' }); search()">重置</button></div></form>
    <section class="surface management-directory" aria-label="角色目录" :aria-busy="loading"><div class="management-directory-heading"><div><span class="section-label">校园授权目录</span><p>{{ loading ? '正在读取角色…' : `共 ${total} 个匹配角色` }}</p></div><span class="management-caption">多角色权限合并，系统管理不自动获得业务权限</span></div><div v-if="loading" class="management-empty" role="status">正在读取角色…</div><div v-else-if="!items.length" class="management-empty"><h2>{{ error ? '暂时无法读取角色' : '没有匹配的角色' }}</h2><p>调整搜索条件，或刷新列表后重试。</p></div><table v-else class="management-table management-table--roles"><caption class="management-sr-only">角色列表</caption><thead><tr><th>角色</th><th>类型</th><th>授权内容</th><th>最近更新</th><th>操作</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td data-label="角色"><strong>{{ item.name }}</strong><span class="management-subline">{{ item.key }}</span></td><td data-label="类型"><span class="role-kind">{{ item.builtIn ? '内置角色' : '自定义角色' }}</span></td><td data-label="授权内容"><ul class="role-summary"><li v-for="(grant, index) in item.grants.slice(0, 2)" :key="index">{{ grantLabel(grant) }}</li></ul><span class="management-subline">{{ item.grants.length ? `共 ${item.grants.length} 条授权` : '暂未授予操作权限' }}</span></td><td data-label="最近更新">{{ displayTime(item.updatedAt) }}</td><td data-label="操作"><button class="text-button" :disabled="opening || !catalogReady" @click="open(item)">查看与编辑</button></td></tr></tbody></table><div class="management-pagination"><span>第 {{ page }} / {{ pages }} 页 · 每页 {{ pageSize }} 条</span><div><button class="button button-secondary" :disabled="loading || page <= 1" @click="load(page - 1)">上一页</button><button class="button button-secondary" :disabled="loading || page >= pages" @click="load(page + 1)">下一页</button></div></div></section>
    <ManagementDialog :open="Boolean(editor)" :title="editor === 'create' ? '新增角色' : '编辑角色权限'" :busy="busy" @close="close">
      <ErrorNotice :error="error" />
      <p v-if="uncertain" class="notice notice-info" role="status">提交结果暂未确认。输入已保留，请重试本次操作，或返回列表核实。</p>
      <section v-if="latest" class="management-conflict"><h3>这个角色已有更新</h3><p>你的草稿已保留。最新名称为「{{ latest.name }}」，共 {{ latest.grants.length }} 条授权。</p><details><summary>查看最新授权</summary><ul class="role-summary"><li v-for="(grant, index) in latest.grants" :key="index">{{ grantLabel(grant) }}</li></ul></details><div class="management-actions"><button class="button button-secondary" @click="resolveConflict(false)">采用最新资料</button><button class="button button-secondary" @click="resolveConflict(true)">保留我的修改</button></div></section>
      <form novalidate @submit.prevent="save"><fieldset class="role-fieldset" :disabled="busy || uncertain || Boolean(latest)"><div class="management-form-grid"><div class="form-field"><label for="role-key">角色编号</label><input id="role-key" v-model="form.key" :readonly="editor !== 'create'" autocomplete="off" placeholder="例如 CAMPUS_VIEWER" /></div><div class="form-field"><label for="role-name">角色名称</label><input id="role-name" v-model="form.name" autocomplete="off" placeholder="例如校园空间查看人员" /></div></div><p class="management-form-note">{{ selected?.builtIn ? '内置角色可以修改名称与授权，内置标识和编号保持不变。' : '角色编号创建后保持不变。角色不包含权限时仍可登录并管理本人的登录安全。' }}</p><div class="role-grants-heading"><h3>操作与范围 <span>{{ form.grants.length }} / 1000</span></h3><button class="text-button" type="button" :disabled="form.grants.length >= 1000" @click="addGrant">添加授权 ＋</button></div><p v-if="!form.grants.length" class="role-empty">还没有添加授权。选择需要的操作，再明确它可以用于哪些范围。</p><section v-for="(grant, index) in form.grants" :key="grant.id" class="role-grant" :aria-label="`第 ${index + 1} 条授权`"><div class="role-grant-heading"><span>授权 {{ String(index + 1).padStart(2, '0') }}</span><button class="text-button management-danger" type="button" :aria-label="`移除第 ${index + 1} 条授权`" @click="form.grants.splice(index, 1)">移除</button></div><div class="form-field"><label :for="`grant-action-${grant.id}`">操作权限 {{ index + 1 }}</label><select :id="`grant-action-${grant.id}`" v-model="grant.action" @change="changeAction(grant)"><optgroup v-for="(label, group) in groups" :key="group" :label="label"><option v-for="permission in permissions.filter(item => item.ownerGroup === group)" :key="permission.action" :value="permission.action">{{ permission.description }}</option></optgroup></select><span class="management-form-note role-action-code">{{ grant.action }}</span></div><div class="form-field"><label :for="`grant-scope-${grant.id}`">授权范围 {{ index + 1 }}</label><select :id="`grant-scope-${grant.id}`" v-model="grant.kind" @change="grant.target = ''"><option v-for="kind in descriptor(grant.action)?.scopeKinds ?? []" :key="kind" :value="kind">{{ scopeNames[kind] }}</option></select></div><div v-if="grant.kind === 'DEPARTMENT'" class="form-field"><label :for="`grant-dept-${grant.id}`">院系 {{ index + 1 }}</label><select :id="`grant-dept-${grant.id}`" v-model="grant.target"><option value="">请选择院系</option><option v-for="department in departments" :key="department.id" :value="department.id">{{ department.name }}{{ department.enabled ? '' : '（已停用）' }}</option></select></div><div v-if="grant.kind === 'ROOM'" class="form-field"><label :for="`grant-room-${grant.id}`">房间编号 ID {{ index + 1 }}</label><input :id="`grant-room-${grant.id}`" v-model="grant.target" placeholder="填写已登记房间的 ID" autocomplete="off" /><p class="management-form-note">保存时核验房间是否存在；空间服务不可用时无法保存此范围。</p></div></section></fieldset><p class="role-impact">{{ editor === 'edit' ? '保存后，所有绑定该角色的账户都需重新登录，新的授权随即生效。' : '自定义角色可组合已有操作，不会自动开放尚未开发的业务。' }}{{ selfBound ? '这个角色也绑定了你本人，保存成功后将返回登录页。' : '' }}</p><div class="management-dialog-actions"><button class="button button-secondary" type="button" :disabled="busy" @click="close">{{ uncertain ? '返回列表核实' : '取消' }}</button><button class="button button-primary" type="submit" :disabled="busy || Boolean(latest)">{{ busy ? '正在保存…' : uncertain ? '重试本次操作' : editor === 'create' ? '创建角色' : '保存权限' }}</button></div></form>
    </ManagementDialog>

</template>

<style scoped>
.role-kind{font-size:11px;color:var(--sub);white-space:nowrap}.role-summary{list-style:none;margin:0;padding:0;font-size:11px;line-height:1.9;overflow-wrap:anywhere}.role-fieldset{border:0;padding:0;margin:0;min-width:0}.role-grants-heading{display:flex;align-items:center;justify-content:space-between;margin:26px 0 15px;gap:10px}.role-grants-heading h3{font-size:14px}.role-grants-heading h3 span{font-size:10px;font-weight:400;color:var(--sub);margin-left:8px}.role-grant{border:1px solid var(--line);border-radius:10px;padding:18px;margin-bottom:13px;background:#f8fafb;display:grid;gap:14px;min-width:0}.role-grant-heading{display:flex;justify-content:space-between;align-items:center;font-size:10px;color:var(--sub);letter-spacing:1px}.role-grant .form-field{margin:0;min-width:0}.role-grant select,.role-grant input{width:100%;min-width:0}.role-action-code{margin:0;font-family:monospace;overflow-wrap:anywhere}.role-empty{font-size:12px;color:var(--sub);line-height:1.9;padding:20px;border:1px dashed var(--line);border-radius:10px}.role-impact{font-size:11px;line-height:1.9;color:var(--sub);margin-top:19px}.management-table--roles th:nth-child(3){width:32%}.management-table--roles th:nth-child(5){width:14%}.management-conflict details{margin-top:12px;font-size:11px;color:var(--sub)}@media(max-width:760px){.management-table--roles td:nth-child(3){grid-column:1/-1}}@media(max-width:480px){.role-grant{padding:14px}.role-grant select,.role-grant input{font-size:16px}}
</style>
