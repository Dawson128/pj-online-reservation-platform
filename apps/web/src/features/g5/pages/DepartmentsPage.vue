<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ApiError } from '../../../app/http';
import { displayTime } from '../../../app/types';
import ErrorNotice from '../../../shared/ui/ErrorNotice.vue';
import ManagementDialog from '../components/ManagementDialog.vue';
import { queryPath, useManagement } from '../management';
import type { Department, Page } from '../management';
import '../management.css';
const api = useManagement();
const { error, busy, uncertain } = api;
const items = ref<Department[]>([]);
const filters = reactive({ keyword: '', enabled: 'true' });
let applied = { ...filters };
const page = ref(1);
const total = ref(0);
const pageSize = 20;
const pages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));
const loading = ref(true);
const opening = ref(false);
const editor = ref<'' | 'create' | 'edit'>('');
const selected = ref<Department | null>(null);
const latest = ref<Department | null>(null);
const statusTarget = ref<Department | null>(null);
const notice = ref('');
const form = reactive({ code: '', name: '' });
let generation = 0;
async function load(next = page.value): Promise<void> {
  const current = ++generation;
  loading.value = true; items.value = []; error.value = null;
  try {
    const data = await api.read<Page<Department>>(queryPath('/api/m2/departments', { ...applied, page: next, pageSize }));
    if (current !== generation) return;
    const lastPage = Math.max(1, Math.ceil(data.total / pageSize));
    if (data.page > lastPage) { await load(lastPage); return; }
    items.value = data.items; page.value = data.page; total.value = data.total;
  } catch { /* The visible error distinguishes a failed read from an empty directory. */ }
  finally { if (current === generation) loading.value = false; }
}
function search(): void { applied = { ...filters, keyword: filters.keyword.trim() }; void load(1); }
function clearFilters(): void { Object.assign(filters, { keyword: '', enabled: 'true' }); search(); }
async function currentDepartment(department: Department): Promise<Department> {
  const [enabled, disabled] = await Promise.all([
    api.all<Department>('/api/m2/departments', { keyword: department.code, enabled: 'true' }),
    api.all<Department>('/api/m2/departments', { keyword: department.code, enabled: 'false' }),
  ]);
  const current = [...enabled, ...disabled].find(item => item.id === department.id);
  if (!current) throw new Error('院系记录已变化，请刷新列表后重试。');
  return current;
}
async function open(department?: Department): Promise<void> {
  if (opening.value) return;
  opening.value = true; api.reset(); notice.value = ''; latest.value = null;
  try {
    selected.value = department ? await currentDepartment(department) : null;
    Object.assign(form, { code: selected.value?.code ?? '', name: selected.value?.name ?? '' });
    editor.value = department ? 'edit' : 'create';
  } catch (failure) { error.value = failure; }
  finally { opening.value = false; }
}
function closeEditor(): void {
  if (busy.value) return;
  const refresh = uncertain.value || Boolean(error.value);
  editor.value = ''; selected.value = null; latest.value = null; Object.assign(form, { code: '', name: '' }); api.reset();
  if (refresh) void load();
}
async function save(): Promise<void> {
  if (!uncertain.value && (!form.code.trim() || !form.name.trim())) { error.value = new Error('请填写院系编号和名称。'); return; }
  const create = editor.value === 'create';
  const body = create ? { code: form.code, name: form.name } : { expectedVersion: selected.value!.version, name: form.name };
  const data = await api.write<Department>(create ? '/api/m2/admin/departments' : `/api/m2/admin/departments/${selected.value!.id}`, create ? 'POST' : 'PATCH', body);
  if (data) { closeEditor(); notice.value = create ? '院系已新增，可以为账户选择该院系。' : '院系名称已更新。'; await load(create ? 1 : page.value); }
  else if (error.value instanceof ApiError && error.value.code === 'VERSION_CONFLICT' && selected.value) {
    try { latest.value = await currentDepartment(selected.value); } catch (failure) { error.value = failure; }
  }
}
function resolveConflict(keepDraft: boolean): void {
  if (!latest.value) return;
  selected.value = latest.value;
  if (!keepDraft) Object.assign(form, { code: latest.value.code, name: latest.value.name });
  latest.value = null; api.reset();
}
function askStatus(department: Department): void { api.reset(); notice.value = ''; statusTarget.value = department; }
function closeStatus(): void { if (busy.value) return; const refresh = uncertain.value || Boolean(error.value); statusTarget.value = null; api.reset(); if (refresh) void load(); }
async function changeStatus(): Promise<void> {
  if (!statusTarget.value) return;
  const data = await api.write<Department>(`/api/m2/admin/departments/${statusTarget.value.id}`, 'PATCH', { expectedVersion: statusTarget.value.version, enabled: !statusTarget.value.enabled });
  if (data) { closeStatus(); notice.value = data.enabled ? '院系已启用，可用于新的账户绑定。' : '院系已停用，已有账户与历史记录保留。'; await load(); }
}
onMounted(() => { void load(1); });
</script>
<template>

    <section class="page-heading"><div><p class="eyebrow">CAMPUS ORGANIZATION</p><h1>院系管理</h1><p>让校园组织清晰有序，为账户维护准确的归属。</p></div><div class="management-actions"><button class="button button-secondary" :disabled="loading || opening" @click="load()">刷新列表</button><button class="button button-primary" :disabled="loading || opening" @click="open()">新增院系 <span aria-hidden="true">＋</span></button></div></section>
    <div v-if="notice" class="notice notice-info" role="status">{{ notice }}</div>
    <ErrorNotice v-if="!editor && !statusTarget" :error="error" />
    <form class="surface management-filters management-filters--departments" aria-label="院系筛选" @submit.prevent="search"><div class="management-filter-field"><label for="department-keyword-filter">搜索院系</label><input id="department-keyword-filter" v-model="filters.keyword" placeholder="院系编号或名称" /></div><div class="management-filter-field"><label for="department-state-filter">院系状态</label><select id="department-state-filter" v-model="filters.enabled"><option value="true">启用院系</option><option value="false">停用院系</option></select></div><div class="management-filter-buttons"><button class="button button-primary" type="submit" :disabled="loading">查询</button><button class="text-button" type="button" @click="clearFilters">重置</button></div></form>
    <section class="surface management-directory" aria-label="院系目录" :aria-busy="loading"><div class="management-directory-heading"><div><span class="section-label">校园组织目录</span><p>{{ loading ? '正在读取最新院系…' : `共 ${total} 个匹配院系` }}</p></div><span class="management-caption">停用保留历史，启用承接新的绑定</span></div><div v-if="loading" class="management-empty" role="status">正在读取院系…</div><div v-else-if="!items.length" class="management-empty"><h2>{{ error ? '暂时无法读取院系' : '没有匹配的院系' }}</h2><p>{{ error ? '请刷新列表后重试。' : '试试其他搜索条件，或新增一个院系。' }}</p></div><table v-else class="management-table management-table--departments"><caption class="management-sr-only">院系列表</caption><thead><tr><th>院系</th><th>状态</th><th>最近更新</th><th>操作</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td data-label="院系"><strong>{{ item.name }}</strong><span class="management-subline">{{ item.code }}</span></td><td data-label="状态"><span class="management-state" :class="{ 'is-disabled': !item.enabled }"><i></i>{{ item.enabled ? '启用' : '停用' }}</span></td><td data-label="最近更新">{{ displayTime(item.updatedAt) }}</td><td data-label="操作"><div class="management-row-actions"><button class="text-button" :disabled="opening" @click="open(item)">编辑</button><button class="text-button" :class="{ 'management-danger': item.enabled }" @click="askStatus(item)">{{ item.enabled ? '停用' : '启用' }}</button></div></td></tr></tbody></table><div class="management-pagination"><span>第 {{ page }} / {{ pages }} 页 · 每页 {{ pageSize }} 条</span><div><button class="button button-secondary" :disabled="loading || page <= 1" @click="load(page - 1)">上一页</button><button class="button button-secondary" :disabled="loading || page >= pages" @click="load(page + 1)">下一页</button></div></div></section>
    <ManagementDialog :open="Boolean(editor)" :title="editor === 'create' ? '新增院系' : '编辑院系'" :busy="busy" @close="closeEditor">
      <ErrorNotice :error="error" /><p v-if="uncertain" class="notice notice-info" role="status">提交结果暂未确认。请重试本次操作，或返回列表核实。</p><section v-if="latest" class="management-conflict"><h3>这条记录已有更新</h3><p>你的输入已保留。最新名称为「{{ latest.name }}」，当前{{ latest.enabled ? '启用' : '停用' }}。</p><div class="management-actions"><button class="button button-secondary" @click="resolveConflict(false)">采用最新资料</button><button class="button button-secondary" @click="resolveConflict(true)">保留我的修改</button></div></section>
      <form novalidate @submit.prevent="save"><fieldset class="management-form-grid" :disabled="busy || uncertain || Boolean(latest)"><div class="form-field management-full"><label for="department-code">院系编号</label><input id="department-code" v-model="form.code" :readonly="editor !== 'create'" autocomplete="off" placeholder="例如 CS" /></div><div class="form-field management-full"><label for="department-name">院系名称</label><input id="department-name" v-model="form.name" autocomplete="off" placeholder="例如计算机学院" /></div></fieldset><p class="management-form-note">院系编号创建后保持不变。院系停用不会删除既有账户或历史。</p><div class="management-dialog-actions"><button class="button button-secondary" type="button" :disabled="busy" @click="closeEditor">{{ uncertain ? '返回列表核实' : '取消' }}</button><button class="button button-primary" type="submit" :disabled="busy || Boolean(latest)">{{ busy ? '正在保存…' : uncertain ? '重试本次操作' : editor === 'create' ? '创建院系' : '保存院系' }}</button></div></form>
    </ManagementDialog>
    <ManagementDialog :open="Boolean(statusTarget)" :title="statusTarget?.enabled ? '停用院系' : '启用院系'" :busy="busy" @close="closeStatus"><ErrorNotice :error="error" /><p class="management-confirm-copy">{{ statusTarget?.enabled ? '停用' : '启用' }}「{{ statusTarget?.name }}」？</p><p class="management-form-note">{{ statusTarget?.enabled ? '停用后不能为账户新选此院系；已有绑定和历史记录保留。' : '启用后，可以为账户选择此院系。' }}</p><p v-if="uncertain" class="notice notice-info" role="status">结果尚未确认，请重试或刷新列表核实。</p><div class="management-dialog-actions"><button class="button button-secondary" :disabled="busy" @click="closeStatus">{{ error ? '返回列表核实' : '取消' }}</button><button class="button button-primary" :disabled="busy || (error instanceof ApiError && error.code === 'VERSION_CONFLICT')" @click="changeStatus">{{ busy ? '正在处理…' : uncertain ? '重试本次操作' : statusTarget?.enabled ? '确认停用' : '确认启用' }}</button></div></ManagementDialog>

</template>
