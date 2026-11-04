<script setup lang="ts">
import { onMounted, ref, useId, watch } from 'vue';
const props = defineProps<{ open: boolean; title: string; busy: boolean }>();
const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>();
const titleId = useId();
function synchronize(): void {
  if (props.open && dialog.value && !dialog.value.open) dialog.value.showModal();
  else if (!props.open && dialog.value?.open) dialog.value.close();
}
onMounted(synchronize);
watch(() => props.open, synchronize, { flush: 'post' });
</script>
<template>
  <dialog ref="dialog" class="management-dialog" :aria-labelledby="titleId" @cancel.prevent="!busy && emit('close')">
    <header class="management-dialog-heading"><div><p class="eyebrow">CAMPUS DIRECTORY</p><h2 :id="titleId">{{ title }}</h2></div><button type="button" class="management-close" aria-label="关闭编辑窗口" :disabled="busy" @click="emit('close')">×</button></header>
    <div class="management-dialog-body"><slot /></div>
  </dialog>
</template>
