<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { navigation } from '../../app/navigation';
import { canManageSystem, session } from '../../app/session';
import ErrorNotice from './ErrorNotice.vue';
const router = useRouter();
const principal = computed(() => session.state.principal);
const visibleNavigation = computed(() => navigation.filter(item => !item.systemManager || canManageSystem()));
const exiting = ref(false);
const error = ref<unknown>(null);
async function exit(): Promise<void> {
  if (exiting.value) return;
  exiting.value = true; error.value = null;
  try { await session.logout(); await router.replace({ name: 'g5-login', query: { reason: 'logout' } }); }
  catch (failure) {
    error.value = failure;
    if (session.state.phase === 'anonymous') await router.replace({ name: 'g5-login', query: { reason: session.state.reason } });
  }
  finally { exiting.value = false; }
}
</script>
<template>
  <div class="application-shell">
    <a class="skip-link" href="#main">跳到主要内容</a>
    <header class="main-header"><div class="header-inner">
      <RouterLink class="brand" :to="{ name: 'g6-home' }" aria-label="校园资源首页"><span class="brand-mark" aria-hidden="true">校</span><span>校园资源<small>CAMPUS RESOURCES</small></span></RouterLink>
      <nav class="main-navigation" aria-label="主导航"><RouterLink v-for="item in visibleNavigation" :key="item.to" :to="item.to" :class="{ active: $route.path === item.to }">{{ item.label }}</RouterLink></nav>
      <div class="account-controls"><RouterLink class="account-link" :to="{ name: 'g5-profile' }"><span class="avatar" aria-hidden="true">{{ principal?.displayName.slice(0, 1) }}</span><span class="account-name">{{ principal?.displayName }}</span></RouterLink><button class="text-button logout-button" type="button" @click="exit" :disabled="exiting">{{ exiting ? '正在退出…' : '退出' }}</button></div>
    </div></header>
    <main id="main" class="content-shell" tabindex="-1"><ErrorNotice :error="error" /><slot /></main>
    <footer class="main-footer"><span>校园资源服务平台</span><span>共享校园空间，让每一份资源各尽其用。</span></footer>
  </div>
</template>
