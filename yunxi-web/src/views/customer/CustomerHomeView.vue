<script setup>
// 顾客领券中心：券卡片列表 + 抢券 + 30s 轮询 + 退出（后端无顾客登出接口 → 只清本地）
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { personaPaths } from '../../router'
import { listCoupons, grabCoupon } from '../../api/coupon'
import { loadSession, clearSession } from '../../utils/auth'
import { fmtTime, fmtDiscount, STATUS_BADGE } from '../../utils/format'

const router = useRouter()

// ── 会话与"已抢过"记忆 ──
const session = loadSession('customer')
const customerId = session.claims.customerId
const phone = session.claims.sub // 顾客 token 的 subject 是手机号

// 后端列表接口不含"我抢过没"的标记，前端只能自己记：
// 抢成功 / 后端拒绝"你已经抢过了" → 记进本地（按顾客 id 分桶），
// 权威兜底仍是 DB 唯一键 —— 跨浏览器/清缓存后点抢，后端照样拒绝
const GRABBED_KEY = `yunxi_grabbed_${customerId}`
const grabbedIds = ref(new Set(JSON.parse(localStorage.getItem(GRABBED_KEY) || '[]')))

function rememberGrabbed(id) {
  grabbedIds.value.add(id)
  localStorage.setItem(GRABBED_KEY, JSON.stringify([...grabbedIds.value]))
}

const isGrabbed = (id) => grabbedIds.value.has(id)

// ── 列表 ──
const coupons = ref([])
const listError = ref('')
const loading = ref(false)
let pollTimer = null

const alert = ref(null) // { kind: 'success'|'danger', text } —— 抢券结果提示
let alertTimer = null

function showAlert(kind, text) {
  alert.value = { kind, text }
  clearTimeout(alertTimer)
  alertTimer = setTimeout(() => (alert.value = null), 5000) // 5 秒后自动消失
}

async function loadList() {
  try {
    coupons.value = await listCoupons('customer')
    listError.value = ''
  } catch (e) {
    listError.value = e.message
  }
}

async function onGrab(c) {
  if (isGrabbed(c.id)) return
  loading.value = true
  try {
    const text = await grabCoupon(c.id) // 后端原样文案，如 "抢到了！折扣：0.50"
    rememberGrabbed(c.id)
    showAlert('success', text)
    await loadList()
  } catch (e) {
    showAlert('danger', e.message)
    // 后端以 400"你已经抢过了"拒绝 → 也记进本地，按钮变"已抢到"
    if (/抢过|已抢|重复/.test(e.message)) rememberGrabbed(c.id)
  } finally {
    loading.value = false
  }
}

function onLogout() {
  clearSession('customer') // 只清 token；已抢记忆保留，重新登录同一账号仍是"已抢到"
  router.push(personaPaths.customer.login)
}

// 按钮文案与可用性：状态 2 进行中且没抢过 → 可抢
const grabState = (c) => {
  if (isGrabbed(c.id)) return { disabled: true, cls: 'btn-success', text: '已抢到 ✓' }
  if (c.status !== 2) return { disabled: true, cls: 'btn-outline-secondary', text: '不可抢' }
  return { disabled: false, cls: 'btn-success', text: '立即抢' }
}

onMounted(() => {
  loadList()
  pollTimer = setInterval(loadList, 30_000)
})
onUnmounted(() => {
  clearInterval(pollTimer)
  clearTimeout(alertTimer)
})
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h4 class="mb-0">领券中心</h4>
      <div class="d-flex gap-2 align-items-center">
        <span class="text-body-secondary small">手机尾号 {{ phone.slice(-4) }} 的顾客</span>
        <router-link to="/customer/coupons" class="btn btn-outline-primary btn-sm">我的券</router-link>
        <router-link to="/customer/profile" class="btn btn-outline-primary btn-sm">个人中心</router-link>
        <router-link to="/" class="btn btn-outline-primary btn-sm">首页</router-link>
        <button class="btn btn-outline-danger btn-sm" @click="onLogout">退出</button>
      </div>
    </div>

    <!-- 抢券结果提示 -->
    <div v-if="alert" class="alert" :class="alert.kind === 'success' ? 'alert-success' : 'alert-danger'"
         role="alert">{{ alert.text }}</div>

    <div v-if="listError" class="alert alert-danger py-2 small" role="alert">{{ listError }}</div>

    <div class="row g-3">
      <div v-for="c in coupons" :key="c.id" class="col-md-6 col-lg-4">
        <div class="card h-100 shadow-sm">
          <div class="card-body d-flex flex-column">
            <div class="d-flex justify-content-between align-items-start mb-2">
              <h5 class="card-title mb-0">{{ c.name }}</h5>
              <span class="badge" :class="STATUS_BADGE[c.status]?.cls">
                {{ STATUS_BADGE[c.status]?.text || c.status }}
              </span>
            </div>
            <div class="display-6 text-success fw-bold mb-2">{{ fmtDiscount(c.discount) }}</div>
            <ul class="list-unstyled small text-body-secondary mb-3">
              <li>🕐 {{ fmtTime(c.startTime) }} ~ {{ fmtTime(c.endTime) }}</li>
              <li>📦 总库存 {{ c.totalStock }}</li>
            </ul>
            <button class="btn mt-auto w-100" :class="grabState(c).cls"
                    :disabled="grabState(c).disabled || loading"
                    @click="onGrab(c)">
              {{ grabState(c).text }}
            </button>
          </div>
        </div>
      </div>
      <div v-if="!coupons.length && !listError" class="col-12">
        <div class="alert alert-light text-center text-body-secondary">
          还没有可领的券，请稍后刷新看看
        </div>
      </div>
    </div>
  </div>
</template>
