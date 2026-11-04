<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import ErrorNotice from '../../../shared/ui/ErrorNotice.vue';
import { session, hasAction } from '../../../app/session';
import { displayTime } from '../../../app/types';
const principal = computed(() => session.state.principal);
const router = useRouter();
const refreshing = ref(false);
const labels: Record<string, string> = {
  'system:manage': '账户、角色与系统参数管理', 'audit:read': '查看操作记录', 'operation:read': '查看异常事项',
  'operation:recover': '恢复异常事项', 'notification:read': '查看通知投递', 'notification:retry': '重试通知投递',
  'runtime:read': '查看系统运行信息', 'room:read': '查看开放空间', 'reservation:create': '预约个人座位',
  'reservation:read-own': '查看本人预约', 'reservation:cancel': '取消本人预约', 'reservation:checkin': '预约签到',
  'violation:read-own': '查看本人违约', 'violation:appeal': '提交违约申诉', 'violation:read': '查看违约记录',
  'violation:review': '处理违约申诉', 'violation:correct': '更正违约记录', 'statistics:read': '查看运营统计',
  'room:apply': '申请整间教室', 'room:manage': '管理教室与座位', 'room:display': '查看教室签到屏幕',
  'application:read-own': '查看本人整室申请', 'application:withdraw': '撤销本人整室申请',
  'application:read': '查看授权范围内整室申请', 'application:terminate': '终止整室申请',
  'approval:level1': '进行一级审批', 'approval:level2': '进行二级审批', 'resource:trace': '追溯空间占用',
  'workorder:report': '上报空间故障', 'workorder:read-own': '查看本人报修', 'workorder:cancel': '撤销本人报修',
  'workorder:evaluate': '评价本人报修', 'workorder:read': '查看授权范围内工单', 'workorder:assign': '分配维修工单',
  'workorder:accept': '验收维修结果', 'workorder:escalation-receive': '接收工单升级提醒',
  'workorder:read-assigned': '查看本人维修工单', 'workorder:repair': '处理维修工单',
};
async function refresh(): Promise<void> {
  if (refreshing.value) return;
  refreshing.value = true;
  try {
    await session.restore(true);
    if (session.state.phase === 'anonymous') await router.replace({ name: 'g5-login', query: { reason: session.state.reason } });
  } finally { refreshing.value = false; }
}
</script>
<template>

    <section class="page-heading"><div><p class="eyebrow">YOUR CAMPUS IDENTITY</p><h1>你的身份，一目了然。</h1><p>查看当前账户与已获授权，安心使用校园服务。</p></div><button class="button button-secondary" @click="refresh" :disabled="refreshing">{{ refreshing ? '正在核实…' : '刷新身份' }}</button></section>
    <ErrorNotice :error="session.state.error || null" />
    <div v-if="principal" class="profile-grid">
      <section class="surface identity-card"><div class="identity-heading"><span class="large-avatar">{{ principal.displayName.slice(0, 1) }}</span><div><h2>{{ principal.displayName }}</h2><span class="status-label"><i aria-hidden="true"></i>账户已验证</span></div></div><dl class="identity-facts"><div><dt>登录状态</dt><dd>正常使用</dd></div><div><dt>安全设置</dt><dd>已完成密码设置</dd></div><div><dt>登录有效至</dt><dd>{{ displayTime(principal.expiresAt) }}<small>北京时间</small></dd></div><div><dt>使用范围</dt><dd>{{ hasAction('system:manage') ? '已获系统管理授权' : '按已授予权限使用' }}</dd></div></dl><RouterLink class="button button-primary button-block" :to="{ name: 'g5-change-password' }">修改密码 <span aria-hidden="true">→</span></RouterLink><p class="fine-print">修改密码后，所有原登录状态都会失效。</p></section>
      <section class="surface permissions-card"><div class="section-heading"><h2>当前授权</h2><span class="quiet-label">以当前账户状态为准</span></div><p class="section-description">管理权限与业务权限分别授予，系统管理身份不会自动获得预约、审批或维修权限。</p><ul v-if="principal.grants.length" class="permission-list"><li v-for="(grant, index) in principal.grants" :key="`${grant.action}-${index}`"><span class="permission-marker" aria-hidden="true">✓</span><span>{{ labels[grant.action] ?? grant.action }}</span><span class="scope-label">{{ grant.scope.kind === 'SYSTEM' ? '系统范围' : grant.scope.kind === 'SCHOOL' ? '全校范围' : grant.scope.kind === 'DEPARTMENT' ? '授权院系' : '授权空间' }}</span></li></ul><div v-else class="compact-empty"><p>暂未分配业务权限</p><span>如需使用其他功能，请联系管理员授权。</span></div></section>
    </div>

</template>
