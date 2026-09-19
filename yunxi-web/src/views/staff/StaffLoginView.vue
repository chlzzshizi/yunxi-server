<script setup>
// 员工登录页 —— 成功即存 staff 槽位会话并进员工主页
//
// 落地页交给 router 的 homeFor 决定（它读 JWT 里的 role：**管理员**进人与店管理、
// **店长**进订单列表）—— **这里不写死任何路径**。写死一份在这里，改分流时就会漏掉
// 这处，而漏掉的表现是"登录后落在别人的首页上"，看起来完全不像路由问题
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { homeFor } from '../../router'
import { staffLogin } from '../../api/auth'
import { saveSession } from '../../utils/auth'

const router = useRouter()
// 默认填**店长**账号：店长是这个系统的主要使用者（订单/定价/发券都归他），
// 而管理员进来只有"人与店"两页 —— 默认值该指向多数人要走的那条路
const username = ref('manager')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function onSubmit() {
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  try {
    const token = await staffLogin(username.value.trim(), password.value)
    // 登录成功才算数：token 存进 staff 槽，再按 role 决定落到哪一页
    const session = saveSession('staff', token)
    router.push(homeFor('staff', session))
  } catch (e) {
    error.value = e.message // 401 密码错 / 403 停用 / 0 网络 —— 全部就地展示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="row justify-content-center">
    <div class="col-md-5 col-lg-4">
      <div class="card shadow-sm">
        <div class="card-body p-4">
          <h4 class="card-title mb-1">员工登录</h4>
          <p class="text-body-secondary small mb-1">
            演示账号：店长 <code>manager</code> / <code>admin123</code>
            · 管理员 <code>admin</code> / <code>admin123</code>
          </p>
          <p class="text-body-secondary small mb-3">
            管理员登录后进"人与店管理"；订单、定价、发券对他是 403 —— 那是后端有意的
            权限口径，不是坏了
          </p>

          <div v-if="error" class="alert alert-danger py-2 small" role="alert">{{ error }}</div>

          <form @submit.prevent="onSubmit">
            <div class="mb-3">
              <label class="form-label">用户名</label>
              <input v-model="username" class="form-control" autocomplete="username" />
            </div>
            <div class="mb-3">
              <label class="form-label">密码</label>
              <input v-model="password" type="password" class="form-control"
                     autocomplete="current-password" />
            </div>
            <button class="btn btn-primary w-100" :disabled="loading">
              {{ loading ? '登录中…' : '登录' }}
            </button>
          </form>

          <router-link to="/" class="d-block text-center small mt-3">← 返回身份选择</router-link>
        </div>
      </div>
    </div>
  </div>
</template>
