<script setup>
// 个人中心：看自己的档案 + 改姓名。
//
// 顾客注册**不填姓名**（2026-09-12 后端口径），名字就在这一页补 —— 所以这页的
// "姓名"不是可有可无的摆设，它是那个被砍掉的注册字段的新家。
//
// 改谁的名字由 token 决定：请求体里压根没有 customerId 这个字段（后端
// UpdateProfileRequest 就没有），所以这一页不存在"改错人"的口子。
// 手机号是账号本身（改它等于换账号），这一页只读。
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { personaPaths } from '../../router'
import { getMyProfile, updateMyProfile } from '../../api/customer'
import { loadSession, clearSession } from '../../utils/auth'
import { tooLong, CUSTOMER_NAME_MAX } from '../../utils/validate'

const router = useRouter()
const session = loadSession('customer')
const phone = session.claims.sub // 顾客 token 的 subject 是手机号

// ── 档案 ──
const profile = ref(null)
const listError = ref('')

// 姓名进了输入框（**改它**是这一页的主要动作）；手机号不回填输入框，只做展示
const name = ref('')

async function loadProfile() {
  try {
    profile.value = await getMyProfile()
    name.value = profile.value.name || ''
    listError.value = ''
  } catch (e) {
    listError.value = e.message // 401 由 request.js 清会话并跳登录；网络异常就地提示
  }
}

// ── 改名 ──
const formError = ref('')
const formOk = ref('')
const busy = ref(false)

async function onSave() {
  formError.value = ''
  formOk.value = ''
  const value = name.value.trim()
  // 文案逐字照抄后端（CustomerProfileAppService / Customer.requireValidName）
  if (!value) return (formError.value = '姓名不能为空')
  if (tooLong(value, CUSTOMER_NAME_MAX)) {
    return (formError.value = `姓名不能超过 ${CUSTOMER_NAME_MAX} 个字`)
  }

  busy.value = true
  try {
    profile.value = await updateMyProfile(value)
    name.value = profile.value.name || ''
    formOk.value = '已保存'
  } catch (e) {
    formError.value = e.message
  } finally {
    busy.value = false
  }
}

function onLogout() {
  clearSession('customer') // 顾客没有后端登出接口，只能清本地
  router.push(personaPaths.customer.login)
}

onMounted(loadProfile)
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h4 class="mb-0">个人中心</h4>
      <div class="d-flex gap-2 align-items-center">
        <span class="text-body-secondary small">手机尾号 {{ phone.slice(-4) }} 的顾客</span>
        <router-link to="/customer/coupons" class="btn btn-outline-primary btn-sm">我的券</router-link>
        <router-link to="/customer/orders" class="btn btn-outline-primary btn-sm">我的订单</router-link>
        <router-link to="/customer/home" class="btn btn-outline-primary btn-sm">首页</router-link>
        <button class="btn btn-outline-danger btn-sm" @click="onLogout">退出</button>
      </div>
    </div>

    <div class="row justify-content-center">
      <div class="col-md-6 col-lg-5">
        <div class="card shadow-sm">
          <div class="card-body p-4">
            <h5 class="card-title mb-3">我的档案</h5>

            <div v-if="listError" class="alert alert-danger py-2 small" role="alert">{{ listError }}</div>
            <div v-if="formOk" class="alert alert-success py-2 small" role="alert">{{ formOk }}</div>
            <div v-if="formError" class="alert alert-danger py-2 small" role="alert">{{ formError }}</div>

            <div class="mb-3">
              <label class="form-label">顾客编号</label>
              <input :value="profile ? profile.customerId : ''" class="form-control" disabled />
              <div class="form-text">下单、抢券都挂在这个编号上</div>
            </div>
            <div class="mb-3">
              <label class="form-label">手机号</label>
              <input :value="profile ? profile.phone : ''" class="form-control" disabled />
              <div class="form-text">手机号是账号本身，改它等于换账号 —— 这一页只读</div>
            </div>
            <div class="mb-3">
              <label class="form-label">姓名</label>
              <input v-model="name" class="form-control" placeholder="例如：张三" />
              <div class="form-text">注册时不填姓名，第一次在这里补上</div>
            </div>

            <button class="btn btn-success w-100" :disabled="busy" @click="onSave">
              {{ busy ? '保存中…' : '保存' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
