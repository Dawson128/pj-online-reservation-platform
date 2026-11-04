<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import AuthFrame from '../components/AuthFrame.vue';
import ErrorNotice from '../../../shared/ui/ErrorNotice.vue';
import { session } from '../../../app/session';
import { ApiError } from '../../../app/http';

const router = useRouter();
const current = ref('');
const replacement = ref('');
const confirm = ref('');
const visible = ref(false);
const busy = ref(false);
const exiting = ref(false);
const error = ref<unknown>(null);
const uncertain = ref(false);
async function submit(): Promise<void> {
  if (busy.value || exiting.value) return;
  error.value = null; uncertain.value = false;
  if (!current.value) { error.value = new Error('请输入当前密码。'); return; }
  const length = Array.from(replacement.value).length;
  if (length < 8 || length > 24 || /[\u0000-\u001f\u007f-\u009f]/u.test(replacement.value)) {
    error.value = new Error('新密码须为 8—24 个字符，且不能包含控制字符。'); return;
  }
  if (replacement.value === current.value) { error.value = new Error('新密码不能与当前密码相同。'); return; }
  if (replacement.value !== confirm.value) { error.value = new Error('两次输入的新密码不一致。'); return; }
  busy.value = true;
  try {
    await session.changePassword(current.value, replacement.value);
    current.value = ''; replacement.value = ''; confirm.value = '';
    await router.replace({ name: 'g5-login', query: { reason: 'changed' } });
  } catch (failure) {
    error.value = failure;
    uncertain.value = failure instanceof ApiError && failure.status === 0;
    if (session.state.phase === 'anonymous') await router.replace({ name: 'g5-login', query: { reason: session.state.reason } });
  } finally { busy.value = false; }
}
async function exit(): Promise<void> {
  if (exiting.value || busy.value) return;
  exiting.value = true; error.value = null;
  try { await session.logout(); await router.replace({ name: 'g5-login', query: { reason: 'logout' } }); }
  catch (failure) {
    error.value = failure;
    if (session.state.phase === 'anonymous') await router.replace({ name: 'g5-login', query: { reason: session.state.reason } });
  }
  finally { exiting.value = false; }
}
function verifyLogin(): void { session.clear('security'); void router.replace({ name: 'g5-login', query: { reason: 'security' } }); }
</script>

<template>
  <AuthFrame eyebrow="A LITTLE CARE, MORE PEACE OF MIND" :headline="'安心使用，\n从容安排。'" description="为自己的校园账户，设置一把只属于你的钥匙。">
    <div class="card-heading"><span class="section-label">账户安全</span><h2>修改密码</h2><p>修改后需要重新登录，其他已登录设备也会退出。</p></div>
    <ErrorNotice :error="error" />
    <div v-if="uncertain" class="notice notice-info" role="status">修改结果尚未确认。请尝试使用新密码重新登录核实。<button type="button" class="text-button" @click="verifyLogin">返回登录核实 →</button></div>
    <form novalidate @submit.prevent="submit" :aria-busy="busy">
      <div class="form-field"><label for="current-password">当前密码</label><input id="current-password" v-model="current" type="password" autocomplete="current-password" placeholder="输入当前使用的密码" :disabled="busy || exiting" required /></div>
      <div class="form-field"><label for="new-password">新密码 <span class="field-caption">8—24 个字符</span></label><div class="password-control"><input id="new-password" v-model="replacement" :type="visible ? 'text' : 'password'" autocomplete="new-password" placeholder="设置一个新的密码" :disabled="busy || exiting" required /><button type="button" :aria-label="visible ? '隐藏新密码' : '显示新密码'" :aria-pressed="visible" @click="visible = !visible">{{ visible ? '隐藏' : '显示' }}</button></div></div>
      <div class="form-field"><label for="confirm-password">确认新密码</label><input id="confirm-password" v-model="confirm" type="password" autocomplete="new-password" placeholder="再次输入新密码" :disabled="busy || exiting" required /></div>
      <button class="button button-primary button-block" type="submit" :disabled="busy || exiting">{{ busy ? '正在更新密码…' : '更新密码并重新登录' }}<span v-if="!busy" aria-hidden="true">→</span></button>
    </form>
    <div class="auth-secondary"><RouterLink :to="{ name: 'g5-profile' }">返回个人身份</RouterLink><button class="text-button" type="button" @click="exit" :disabled="busy || exiting">{{ exiting ? '正在退出…' : '退出当前账户' }}</button></div>
  </AuthFrame>
</template>
