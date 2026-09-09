<script setup>
// App.vue：全局骨架 —— 品牌栏 + 路由出口，各页面只负责内容卡片
</script>

<template>
  <nav class="navbar navbar-dark bg-primary shadow-sm">
    <div class="container">
      <router-link to="/" class="navbar-brand d-flex align-items-center gap-2">
        <span class="fs-4">🧺</span>
        <span>云洗助手</span>
        <span class="badge text-bg-light ms-1">Yunxi Server</span>
      </router-link>
    </div>
  </nav>

  <main class="container py-4">
    <router-view />
  </main>

  <footer class="text-center text-body-secondary small py-3 border-top">
    云洗助手 · 抢券演示
  </footer>
</template>
