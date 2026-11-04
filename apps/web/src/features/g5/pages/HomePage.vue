<script setup lang="ts">
import { computed } from 'vue';
import CampusScene from '../components/CampusScene.vue';
import { canManageSystem, hasAction, session } from '../../../app/session';
import { displayTime } from '../../../app/types';
const principal = computed(() => session.state.principal);
</script>
<template>

    <div class="campus-welcome"><section class="page-heading"><div><p class="eyebrow">学习 · 交流 · 共建</p><h1>下一段专注，<br><em>从这里开始。</em></h1><p>欢迎回来，{{ principal?.displayName }}。把校园生活，安排得从容一点。</p></div><span class="page-caption">校园资源服务</span></section><CampusScene class="welcome-scene" /></div>
    <section class="search-bar" aria-label="空间预约状态"><div><span class="field-caption">预约日期</span><p>等待空间服务开放</p></div><span class="vertical-rule" aria-hidden="true"></span><div><span class="field-caption">学习时段</span><p>开放后选择</p></div><button class="button button-primary" type="button" disabled>座位查询尚未开放 <span aria-hidden="true">→</span></button></section>
    <div class="section-heading home-section-heading"><h2>校园空间</h2><span class="quiet-label">服务准备中</span></div>
    <div class="home-grid">
      <section class="surface spaces-empty" data-state="not-connected"><div class="campus-service-grid" aria-label="校园服务建设进度">
        <article><div class="campus-service-top"><span>01</span><span class="campus-service-tag">专注学习</span></div><h3>自习座位</h3><p>找到一处安静的空间，留给下一段专注。</p><span class="campus-service-status">待开放</span></article>
        <article><div class="campus-service-top"><span>02</span><span class="campus-service-tag">有序共享</span></div><h3>教室申请</h3><p>为交流与活动，安排合适的校园空间。</p><span class="campus-service-status">待开放</span></article>
        <article><div class="campus-service-top"><span>03</span><span class="campus-service-tag">共同维护</span></div><h3>设施报修</h3><p>反馈身边的问题，一起维护舒适环境。</p><span class="campus-service-status">待开放</span></article>
      </div><span class="section-label">给专注，留一个位置</span><h2>好空间，即将与你见面。</h2><p>座位查询与预约暂未开放。<br>你现在可以查看个人身份、管理登录安全。</p><RouterLink class="text-button inline-link" :to="{ name: 'g5-profile' }">查看我的身份 <span aria-hidden="true">↗</span></RouterLink></section>
      <aside class="surface account-summary"><div class="section-heading"><h2>我的账户</h2><span class="status-label"><i aria-hidden="true"></i>已验证</span></div><span class="large-avatar summary-avatar">{{ principal?.displayName.slice(0, 1) }}</span><h3>{{ principal?.displayName }}</h3><p>{{ hasAction('system:manage') ? '系统管理已授权' : '按已授予权限使用校园服务' }}</p><div class="summary-divider"></div><span class="field-caption">当前登录有效至</span><p class="expiry-time">{{ principal ? displayTime(principal.expiresAt) : '—' }}</p><RouterLink class="button button-secondary button-block" :to="{ name: 'g5-change-password' }">修改密码</RouterLink><p class="fine-print">保护自己的账户，也守护每一段安排。</p></aside>
    </div>
    <section v-if="canManageSystem()" class="management-note"><div><h2>校园账户与组织</h2><p>维护账户、分配已有角色，管理院系和账户状态。</p></div><div class="management-home-links"><RouterLink class="text-button" :to="{ name: 'g5-accounts' }">管理账户 ↗</RouterLink><RouterLink class="text-button" :to="{ name: 'g5-departments' }">管理院系 ↗</RouterLink></div></section>

</template>
<style scoped>.management-home-links{display:flex;gap:20px;flex-wrap:wrap;flex:none}@media(max-width:580px){.management-note{flex-direction:column}}</style>
