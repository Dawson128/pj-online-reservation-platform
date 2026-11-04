<script setup lang="ts">
import CampusScene from './CampusScene.vue';
defineProps<{ eyebrow: string; headline: string; description: string }>();
</script>

<template>
  <div class="auth-page">
    <header class="auth-header">
      <div class="brand" aria-label="校园资源"><span class="brand-mark" aria-hidden="true">校</span><span>校园资源<small>CAMPUS RESOURCES</small></span></div>
      <span class="header-caption">让每一段时间，都有好去处。</span>
    </header>
    <main class="auth-layout">
      <section class="auth-intro">
        <p class="eyebrow">{{ eyebrow }}</p>
        <h1>{{ headline }}</h1>
        <p class="intro-description">{{ description }}</p>
        <CampusScene class="auth-scene" />
        <p class="intro-foot">学习 · 相遇 · 从容安排</p>
      </section>
      <section class="auth-card"><slot /></section>
    </main>
    <footer class="auth-footer"><span>校园资源服务平台</span><span>共享校园空间，让每一份资源各尽其用。</span></footer>
  </div>
</template>
