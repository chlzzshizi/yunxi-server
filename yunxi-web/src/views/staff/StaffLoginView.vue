<script setup>
// 员工登录页 —— 成功即存 staff 槽位会话并进发券台
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { staffLogin } from '../../api/auth'
import { saveSession } from '../../utils/auth'

const router = useRouter()
const username = ref('admin')
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
    saveSession('staff', token) // 登录成功才算数：token 存进 staff 槽
    router.push('/staff/coupons')
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
          <p class="text-body-secondary small mb-3">店长 / 店员 · 演示账号 admin / admin123</p>

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
