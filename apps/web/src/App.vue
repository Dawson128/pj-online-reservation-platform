<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { canManageSystem, session } from './app/session';
import AppShell from './shared/ui/AppShell.vue';
const route = useRoute();
const router = useRouter();
const initiallyLoading = computed(() => (session.state.phase === 'unknown' || session.state.phase === 'loading') && !session.state.principal);
let lastFocus = 0;
async function retry(): Promise<void> {
  await session.restore(true);
  if (session.state.phase === 'anonymous') await router.replace({ name: 'g5-login', query: { reason: session.state.reason } });
  else if (session.state.phase === 'authenticated') {
    if (!session.state.csrf) await router.replace({ name: 'g5-login', query: { reason: 'security' } });
    else if (route.meta.systemManager && !canManageSystem()) await router.replace({ name: 'g5-access-denied' });
    else if (route.name === 'g5-login') await router.replace({ name: 'g6-home' });
  }
}
async function onFocus(): Promise<void> {
  if (document.hidden || Date.now() - lastFocus < 1500) return;
  if (!['authenticated', 'unavailable'].includes(session.state.phase)) return;
  lastFocus = Date.now();
  await retry();
}
onMounted(() => { window.addEventListener('focus', onFocus); document.addEventListener('visibilitychange', onFocus); window.addEventListener('online', onFocus); });
onBeforeUnmount(() => { window.removeEventListener('focus', onFocus); document.removeEventListener('visibilitychange', onFocus); window.removeEventListener('online', onFocus); });
</script>
<template>
  <main v-if="initiallyLoading" class="connection-page" aria-live="polite"><span class="brand-mark" aria-hidden="true">校</span><p>正在确认你的校园身份…</p></main>
  <main v-else-if="session.state.phase === 'unavailable'" class="connection-page"><span class="brand-mark" aria-hidden="true">校</span><div class="surface connection-card" role="alert"><p class="eyebrow">稍候片刻</p><h1>暂时无法核实登录状态</h1><p>{{ session.state.error }}</p><button class="button button-primary" @click="retry">重新连接 →</button><p class="fine-print">确认身份后，即可继续使用。</p></div></main>
  <RouterView v-else v-slot="{ Component, route: currentRoute }">
    <component :is="Component" v-if="currentRoute.meta.public || currentRoute.meta.layout === 'auth'" />
    <AppShell v-else><component :is="Component" /></AppShell>
  </RouterView>
</template>
