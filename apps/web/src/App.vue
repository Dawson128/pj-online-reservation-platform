<script setup lang="ts">
import { navigation } from './app/navigation'
</script>

<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <header class="site-header">
    <RouterLink to="/" class="brand"><span class="brand-mark" aria-hidden="true">校</span>校园资源<span class="brand-sub">CAMPUS RESOURCES</span></RouterLink>
    <nav aria-label="主导航"><RouterLink v-for="item in navigation" :key="item.to" :to="item.to">{{ item.label }}</RouterLink></nav>
  </header>
  <main id="main" tabindex="-1"><RouterView /></main>
  <footer>共享校园空间，让每一份资源各尽其用。</footer>
</template>
