import { createApp } from 'vue'
import App from './App.vue'
import router, { personaPaths } from './router'
import { setUnauthorizedHandler } from './api/request'

// 401 处理注册（放这里而不是 router/index.js 里 import api → 避免循环依赖）：
// 会话失效 → 清会话（request.js 已做）→ 跳该人格的登录页
setUnauthorizedHandler((persona) => {
  const path = personaPaths[persona]?.login
  if (path && router.currentRoute.value.path !== path) {
    router.push(path)
  }
})

createApp(App).use(router).mount('#app')
