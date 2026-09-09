<script setup>
// 顾客登录 | 注册 —— 注册即登录（后端直接返回 token），两模式共用一个卡片
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { customerLogin, customerRegister } from '../../api/auth'
import { saveSession } from '../../utils/auth'

const router = useRouter()
const mode = ref('login') // 'login' | 'register'
const name = ref('')
const phone = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

function switchMode(m) {
  mode.value = m
  error.value = ''
}

async function onSubmit() {
  error.value = ''
  // 前后端都校验：前端拦空值/位数是为了少打一次后端（后端才是权威）
  if (!phone.value.trim()) return (error.value = '请输入手机号')
  if (!/^1\d{10}$/.test(phone.value.trim())) return (error.value = '手机号格式不对（11 位，1 开头）')
  if (!password.value) return (error.value = '请输入密码')
  if (mode.value === 'register' && !name.value.trim()) return (error.value = '请输入姓名')

  loading.value = true
  try {
    const token = mode.value === 'register'
      ? await customerRegister({ name: name.value.trim(), phone: phone.value.trim(), password: password.value })
      : await customerLogin(phone.value.trim(), password.value)
    saveSession('customer', token)
    router.push('/customer/home')
  } catch (e) {
    // 401 密码错 / 400 已注册 / 0 网络 —— 就地展示，绝不跳转
    error.value = e.message
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
          <!-- 模式切换（Bootstrap 风格的分段按钮） -->
          <div class="btn-group w-100 mb-3" role="group">
            <button class="btn" :class="mode === 'login' ? 'btn-success' : 'btn-outline-success'"
                    @click="switchMode('login')">登录</button>
            <button class="btn" :class="mode === 'register' ? 'btn-success' : 'btn-outline-success'"
                    @click="switchMode('register')">注册</button>
          </div>

          <h4 class="card-title mb-3">{{ mode === 'register' ? '顾客注册' : '顾客登录' }}</h4>

          <div v-if="error" class="alert alert-danger py-2 small" role="alert">{{ error }}</div>

          <form @submit.prevent="onSubmit">
            <div v-if="mode === 'register'" class="mb-3">
              <label class="form-label">姓名</label>
              <input v-model="name" class="form-control" placeholder="例如：张三" />
            </div>
            <div class="mb-3">
              <label class="form-label">手机号</label>
              <input v-model="phone" class="form-control" placeholder="11 位手机号"
                     inputmode="numeric" autocomplete="tel" />
            </div>
            <div class="mb-3">
              <label class="form-label">密码</label>
              <input v-model="password" type="password" class="form-control"
                     :placeholder="mode === 'register' ? '设置登录密码' : ''"
                     autocomplete="current-password" />
            </div>
            <button class="btn btn-success w-100" :disabled="loading">
              {{ loading ? '请稍候…' : mode === 'register' ? '注册并登录' : '登录' }}
            </button>
          </form>

          <router-link to="/" class="d-block text-center small mt-3">← 返回身份选择</router-link>
        </div>
      </div>
    </div>
  </div>
</template>
