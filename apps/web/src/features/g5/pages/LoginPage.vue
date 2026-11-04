<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AuthFrame from '../components/AuthFrame.vue';
import ErrorNotice from '../../../shared/ui/ErrorNotice.vue';
import { session } from '../../../app/session';

const route = useRoute();
const router = useRouter();
const account = ref('');
const password = ref('');
const visible = ref(false);
const busy = ref(false);
const error = ref<unknown>(null);
const banner = computed(() => ({
  changed: '密码已更新。请使用新密码重新登录。',
  logout: '你已安全退出。',
  expired: '请登录后继续使用校园空间。',
  security: '请重新登录，以更新当前页面的安全校验。',
  updated: '账户信息或授权已更新，请重新登录。',
} as Record<string, string>)[String(route.query.reason ?? '')]);

async function submit(): Promise<void> {
  if (busy.value) return;
  error.value = null;
  if (!account.value.trim() || Array.from(account.value.trim()).length > 40) {
    error.value = new Error('请输入 1—40 个字符的校内账号。'); return;
  }
  if (!password.value || Array.from(password.value).length > 128) {
    error.value = new Error('请输入有效密码，最多 128 个字符。'); return;
  }
  busy.value = true;
  try {
    await session.login(account.value.trim(), password.value);
    password.value = '';
    await router.replace({ name: 'g6-home' });
  } catch (failure) { error.value = failure; }
  finally { busy.value = false; }
}
</script>

<template>
  <AuthFrame eyebrow="YOUR SPACE, YOUR FOCUS" :headline="'好好学习，\n也好好安排。'" description="座位预约、空间申请与校园服务，在一处，有条不紊。">
    <div class="card-heading"><span class="section-label">校园通行证</span><h2>欢迎回来</h2><p>使用校内账户，进入你的校园空间。</p></div>
    <div v-if="banner" class="notice notice-info" role="status">{{ banner }}</div>
    <ErrorNotice :error="error" />
    <form novalidate @submit.prevent="submit" :aria-busy="busy">
      <div class="form-field"><label for="login-account">校内账号</label><input id="login-account" v-model="account" name="username" autocomplete="username" placeholder="请输入校内账号" :disabled="busy" required /></div>
      <div class="form-field"><label for="login-password">密码</label><div class="password-control"><input id="login-password" v-model="password" name="password" :type="visible ? 'text' : 'password'" autocomplete="current-password" placeholder="请输入密码" :disabled="busy" required /><button type="button" :aria-label="visible ? '隐藏密码' : '显示密码'" :aria-pressed="visible" @click="visible = !visible">{{ visible ? '隐藏' : '显示' }}</button></div></div>
      <button class="button button-primary button-block" type="submit" :disabled="busy"><span>{{ busy ? '正在核验身份…' : '登录校园空间' }}</span><span v-if="!busy" aria-hidden="true">→</span></button>
    </form>
    <div class="auth-hint"><span class="hint-dot"></span><p>账户由管理员统一维护。忘记密码时，请联系学校管理员。</p></div>
  </AuthFrame>
</template>
