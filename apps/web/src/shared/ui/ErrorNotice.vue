<script setup lang="ts">
import { computed } from 'vue';
import { ApiError, errorMessage } from '../../app/http';
const props = defineProps<{ error: unknown }>();
const message = computed(() => props.error ? errorMessage(props.error) : '');
const trace = computed(() => props.error instanceof ApiError ? props.error.traceId : undefined);
</script>
<template>
  <div v-if="message" class="notice notice-error" role="alert">
    <p>{{ message }}</p>
    <details v-if="trace"><summary>查看问题编号</summary><span class="trace-id">{{ trace }}</span></details>
  </div>
</template>
