<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import definition from '../../../../../../contracts/config/g5/config-values.schema.json';
import { ApiError } from '../../../app/http';
import { displayTime } from '../../../app/types';
import ErrorNotice from '../../../shared/ui/ErrorNotice.vue';
import { useManagement } from '../management';
import '../management.css';

interface Configuration { version: string; effectiveAt: string; values: Record<string, number> }
const api = useManagement();
const { error, busy, uncertain } = api;
const fields = Object.entries(definition.properties).map(([key, rule]) => ({ key, ...rule })).sort((a, b) => a['x-owner-group'].localeCompare(b['x-owner-group']) || a.key.localeCompare(b.key));
const groups = [{ key: 'G1', label: '个人预约', description: '为每一段专注，设置合适的时长与提前窗口。' }, { key: 'G2', label: '整室申请', description: '管理整室申请可以提前安排的时间。' }, { key: 'G6', label: '自动检查与通知', description: '调整检查频率，以及新通知的自动重试上限。' }];
const current = ref<Configuration | null>(null), latest = ref<Configuration | null>(null);
const draft = reactive<Record<string, string | number>>({});
const loading = ref(true), failed = ref(false), conflict = ref(false);
const notice = ref('');
const changed = computed(() => current.value ? fields.filter(field => String(draft[field.key]) !== String(current.value!.values[field.key])).length : 0);
const locked = computed(() => loading.value || failed.value || busy.value || uncertain.value || conflict.value || !current.value);
function fill(config: Configuration): void { for (const field of fields) draft[field.key] = String(config.values[field.key]); }
async function refresh(clearNotice = true): Promise<void> {
  if (busy.value) return;
  loading.value = true; failed.value = false; api.reset(); conflict.value = false; latest.value = null;
  if (clearNotice) notice.value = '';
  try { const data = await api.read<Configuration>('/api/m2/admin/config'); current.value = data; fill(data); }
  catch { failed.value = true; }
  finally { loading.value = false; }
}
function defaults(): void { api.reset(); for (const field of fields) draft[field.key] = String(field.default); notice.value = '已填入默认值，点击保存后才会生效。'; }
async function readLatest(): Promise<void> {
  try { latest.value = await api.read<Configuration>('/api/m2/admin/config'); }
  catch { /* Preserve the draft and conflict controls while the actual read failure is shown. */ }
}
function resolve(keep: boolean): void {
  if (!latest.value) return;
  current.value = latest.value; if (!keep) fill(latest.value);
  latest.value = null; conflict.value = false; api.reset();
}
async function save(): Promise<void> {
  if (!current.value || busy.value) return;
  const values: Record<string, number> = {};
  if (!uncertain.value) {
    for (const field of fields) {
      const raw = String(draft[field.key] ?? '').trim(), number = Number(raw);
      if (!/^-?[0-9]+$/.test(raw) || !Number.isSafeInteger(number) || number < field.minimum || number > field.maximum) {
        error.value = new Error(`${field.title}须为 ${field.minimum}—${field.maximum} 的整数。`); return;
      }
      values[field.key] = number;
    }
  }
  notice.value = '';
  const data = await api.write<Configuration>('/api/m2/admin/config', 'PATCH', { expectedVersion: current.value.version, values });
  if (data) {
    const unchanged = data.version === current.value.version;
    current.value = data; fill(data);
    notice.value = unchanged ? '数值未变化，保留原配置版本。' : '本次保存已确认，以下显示最新配置。已有记录保留原规则。';
    await refresh(false);
  } else if (error.value instanceof ApiError && error.value.code === 'VERSION_CONFLICT') {
    conflict.value = true; await readLatest();
  }
}
onMounted(() => { void refresh(); });
</script>

<template>

    <section class="page-heading"><div><p class="eyebrow">CAMPUS PREFERENCES</p><h1>全局参数</h1><p>让校园服务有章可循，每一次调整都有迹可查。</p></div><button class="button button-secondary" :disabled="loading || busy || uncertain || conflict" @click="refresh()">刷新配置</button></section>
    <ErrorNotice :error="error" />
    <div v-if="notice" class="notice notice-info" role="status">{{ notice }}</div>
    <div v-if="uncertain" class="notice notice-info" role="status">保存结果暂未确认，输入已保留。请重试本次保存，或重新读取配置进行核对。</div>
    <section v-if="conflict" class="management-conflict config-conflict"><h2>配置已有更新</h2><p>你的输入已保留。请比较最新配置，再决定如何继续。</p><div v-if="latest" class="config-comparison"><div class="config-comparison-head"><span>参数</span><span>你的输入</span><span>最新值 · 版本 {{ latest.version }}</span></div><div v-for="field in fields" :key="field.key"><span>{{ field.title }}</span><strong>{{ draft[field.key] === '' ? '未填写' : draft[field.key] }} {{ field['x-unit'] }}</strong><strong>{{ latest.values[field.key] }} {{ field['x-unit'] }}</strong></div></div><div class="management-actions"><button v-if="!latest" class="button button-secondary" @click="readLatest">重新读取最新配置</button><template v-else><button class="button button-secondary" @click="resolve(false)">采用最新配置</button><button class="button button-secondary" @click="resolve(true)">保留我的修改</button></template></div></section>
    <div v-if="loading && !current" class="surface management-empty" role="status">正在读取配置…</div>
    <section v-else-if="!current" class="surface management-empty"><h2>暂时无法读取配置</h2><p>请刷新配置后重试，当前没有采用默认值或旧值替代。</p></section>
    <form v-else class="configuration-layout" novalidate @submit.prevent="save">
      <div class="configuration-main"><fieldset class="configuration-fieldset" :disabled="locked"><section v-for="group in groups" :key="group.key" class="surface configuration-group"><header><span class="section-label">{{ group.label }}</span><p>{{ group.description }}</p></header><div v-for="field in fields.filter(item => item['x-owner-group'] === group.key)" :key="field.key" class="configuration-field"><div><label :for="`config-${field.key}`">{{ field.title }}</label><p :id="`config-help-${field.key}`">{{ field.description }}</p></div><div><div class="configuration-input"><input :id="`config-${field.key}`" v-model="draft[field.key]" type="number" inputmode="numeric" :min="field.minimum" :max="field.maximum" step="1" :aria-describedby="`config-help-${field.key}`" /><span>{{ field['x-unit'] }}</span></div><span class="configuration-range">允许 {{ field.minimum }}—{{ field.maximum }} {{ field['x-unit'] }} · 默认 {{ field.default }}</span></div></div></section></fieldset></div>
      <aside class="surface configuration-summary"><p class="eyebrow">CURRENT SETTINGS</p><h2>每次调整，都有记录。</h2><dl><div><dt>已读取版本</dt><dd>{{ current.version }}</dd></div><div><dt>版本生效时间</dt><dd :title="current.effectiveAt">{{ displayTime(current.effectiveAt) }}<small>北京时间</small></dd></div><div><dt>待保存</dt><dd>{{ changed }} 项调整</dd></div></dl><p>修改参数只影响后续新操作，已有预约、申请和通知保留原先采用的规则。</p><p>签到时点、固定两级审批等规则不在此处调整。</p><button class="button button-primary button-block" type="submit" :disabled="loading || failed || busy || conflict">{{ busy ? '正在保存…' : uncertain ? '重试本次保存' : '保存参数' }}</button><button v-if="uncertain" class="text-button configuration-defaults" type="button" :disabled="busy" @click="refresh()">重新读取配置核对</button><button v-else class="text-button configuration-defaults" type="button" :disabled="locked" @click="defaults">填入默认值</button><span class="configuration-note">填入和编辑不会自动保存。</span></aside>
    </form>

</template>

<style scoped>
.configuration-layout{display:grid;grid-template-columns:minmax(0,1fr) 290px;gap:25px;align-items:start}.configuration-main,.configuration-fieldset{min-width:0}.configuration-fieldset{border:0;padding:0;margin:0;display:grid;gap:22px}.configuration-group header{padding:25px 28px 21px;border-bottom:1px solid var(--line)}.configuration-group header p{font-size:11px;color:var(--sub);line-height:1.9;margin-top:8px}.configuration-field{display:grid;grid-template-columns:minmax(0,1fr) 180px;gap:20px;padding:25px 28px;border-bottom:1px solid var(--line);align-items:center}.configuration-field:last-child{border:0}.configuration-field label{font-size:14px;display:block;margin-bottom:10px}.configuration-field p{font-size:11px;line-height:1.9;color:var(--sub);margin:0;max-width:340px}.configuration-input{display:flex;align-items:center;gap:12px}.configuration-input input{width:100%;min-width:0;flex:1}.configuration-input span{font-size:12px;color:var(--sub);flex:none}.configuration-range{display:block;font-size:10px;line-height:1.8;color:var(--sub);margin-top:7px}.configuration-summary{padding:26px;position:sticky;top:24px}.configuration-summary h2{font-size:20px;line-height:1.6;margin:14px 0 22px}.configuration-summary dl{margin:0 0 22px;font-size:12px}.configuration-summary dl>div{display:flex;justify-content:space-between;align-items:start;margin:17px 0;gap:14px}.configuration-summary dt{color:var(--sub);flex:none}.configuration-summary dd{margin:0;text-align:right;overflow-wrap:anywhere}.configuration-summary small{display:block;font-size:10px;color:var(--sub);margin-top:6px}.configuration-summary>p:not(.eyebrow){font-size:11px;line-height:1.9;color:var(--sub);margin-bottom:15px}.configuration-summary>.button{margin-top:12px}.configuration-defaults{display:block;margin:19px auto 8px}.configuration-note{display:block;text-align:center;font-size:10px;color:var(--sub)}.config-conflict h2{font-size:18px;margin-bottom:12px}.config-comparison{margin:18px 0}.config-comparison>div{display:grid;grid-template-columns:1.3fr 1fr 1fr;gap:12px;padding:12px 0;border-bottom:1px solid #dce5ed;font-size:12px;line-height:1.7}.config-comparison strong{font-weight:500;overflow-wrap:anywhere}.config-comparison-head{font-size:10px!important;color:var(--sub)}@media(max-width:1000px){.configuration-layout{grid-template-columns:1fr}.configuration-summary{position:static}.configuration-summary h2{margin-top:10px}.configuration-summary dl{display:flex;gap:30px;flex-wrap:wrap}.configuration-summary dl>div{display:block;margin:0}.configuration-summary dd{text-align:left;margin-top:10px}.configuration-summary .button{max-width:320px}.configuration-defaults{margin-left:0}.configuration-note{text-align:left}}@media(max-width:540px){.configuration-field{grid-template-columns:1fr;gap:15px;padding:22px}.configuration-group header{padding:22px}.configuration-input input{font-size:18px}.configuration-layout{gap:20px}.configuration-summary{padding:23px}.configuration-summary dl{gap:20px}.configuration-summary .button{max-width:none}.configuration-summary h2{font-size:22px}.config-comparison>div{gap:8px;font-size:11px}}
</style>
