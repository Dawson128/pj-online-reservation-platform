<script setup lang="ts">
const services = [
  { no: '01', title: '自习座位', text: '找到适合自己的学习空间，按时预约与签到。', tag: '专注学习' },
  { no: '02', title: '教室申请', text: '为课堂之外的交流与活动，安排合适的空间。', tag: '有序共享' },
  { no: '03', title: '设施报修', text: '反馈空间中的设施问题，让校园环境更舒适。', tag: '共同维护' },
]
</script>

<template>
  <section class="hero" aria-labelledby="hero-title">
    <div class="hero-copy"><p class="eyebrow">学习 · 交流 · 共建</p><h1 id="hero-title">让校园空间，<br><em>连接更多可能。</em></h1><p class="intro">从一张安静的自习座位，到一次面对面的交流。<br class="desktop-break">在这里，让空间与需要相遇。</p><p class="preparing"><span aria-hidden="true"></span>平台正在建设中，服务开放时间待公布。</p></div>
    <div class="space-art" aria-hidden="true"><div class="window"><i></i><i></i><i></i><i></i></div><div class="sun"></div><div class="desk"></div><div class="book book-one"></div><div class="book book-two"></div><div class="plant"><span></span><i></i><b></b></div><div class="chair"></div><p>留一处空间，给新的想法。</p></div>
  </section>
  <section class="services" aria-labelledby="services-title"><div class="section-title"><p class="eyebrow">校园生活的日常所需</p><h2 id="services-title">一个入口，三份便利</h2></div><div class="service-grid"><article v-for="service in services" :key="service.no"><span class="service-no">{{ service.no }}</span><span class="service-tag">{{ service.tag }}</span><h3>{{ service.title }}</h3><p>{{ service.text }}</p></article></div></section>
</template>
