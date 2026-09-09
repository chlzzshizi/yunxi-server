<script setup>
// 员工发券台：发券表单 + 券列表（状态徽章）+ 30s 轮询 + 登出
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listCoupons, createCoupon } from '../../api/coupon'
import { staffLogout } from '../../api/auth'
import { loadSession, clearSession } from '../../utils/auth'
import { fmtTime, fmtDiscount, STATUS_BADGE } from '../../utils/format'

const router = useRouter()

// ── 会话信息 ──
const session = loadSession('staff')
const who = session ? session.claims.username : ''

// ── 发券表单 ──
const form = ref({
  name: '',
  discount: '0.50',
  totalStock: 100,
  startTime: '',
  endTime: '',
})
const formError = ref('')
const formOk = ref('')
const creating = ref(false)

/** 填充演示时间：开始 = 现在-1 分钟（cron 每分钟第 0 秒推进，≤60s 后变"进行中"），
 *  结束 = 现在+1 小时 */
function fillDemoTime() {
  const now = new Date()
  const start = new Date(now.getTime() - 60_000)
  const end = new Date(now.getTime() + 3_600_000)
  form.value.startTime = toLocalInput(start)
  form.value.endTime = toLocalInput(end)
}

/** Date → datetime-local 输入框格式 "YYYY-MM-DDTHH:mm"（浏览器本地时区） */
function toLocalInput(d) {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

async function onCreate() {
  formError.value = ''
  formOk.value = ''
  const f = form.value
  const discount = Number(f.discount)
  // 后端是权威，这里只做前端粗校验（少一次白跑的后端调用）
  if (!f.name.trim()) return (formError.value = '请填写券名称')
  if (!Number.isFinite(discount) || discount <= 0 || discount > 1) {
    return (formError.value = '折扣需在 0~1 之间，如 0.50 = 5折')
  }
  if (!Number.isInteger(f.totalStock) || f.totalStock <= 0) {
    return (formError.value = '库存需为正整数')
  }
  if (!f.startTime || !f.endTime) return (formError.value = '请填写开始/结束时间')
  if (f.endTime <= f.startTime) return (formError.value = '结束时间必须晚于开始时间')

  creating.value = true
  try {
    await createCoupon({
      name: f.name.trim(),
      discount,
      totalStock: f.totalStock,
      // datetime-local 给 "2026-09-09T10:00"（无秒），后端 LocalDateTime 也能收；
      // 补 ":00" 是让后端返回的字符串格式统一（保险起见，见计划"补秒"约定）
      startTime: f.startTime.length === 16 ? f.startTime + ':00' : f.startTime,
      endTime: f.endTime.length === 16 ? f.endTime + ':00' : f.endTime,
    })
    formOk.value = `「${f.name.trim()}」发布成功`
    form.value.name = ''
    fillDemoTime() // 下一条券继续用演示时间，方便连发几张
    loadList()
  } catch (e) {
    formError.value = e.message
  } finally {
    creating.value = false
  }
}

// ── 券列表 ──
const coupons = ref([])
const listError = ref('')
let pollTimer = null

async function loadList() {
  try {
    coupons.value = await listCoupons('staff')
    listError.value = ''
  } catch (e) {
    listError.value = e.message // 401 由 request.js 跳登录页；网络异常就地提示
  }
}

async function onLogout() {
  await staffLogout()      // 先让后端把 token 拉黑（要 Authorization 头，auth.js 已带）
  clearSession('staff')
  router.push('/staff/login')
}

onMounted(() => {
  loadList()
  // 30s 轮询：捕捉定时任务把 1→2→3 的推进（cron 每分钟第 0 秒跑）
  pollTimer = setInterval(loadList, 30_000)
})
onUnmounted(() => clearInterval(pollTimer))
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h4 class="mb-0">发券台</h4>
      <div class="d-flex gap-2 align-items-center">
        <span class="text-body-secondary small">欢迎，{{ who }}</span>
        <router-link to="/" class="btn btn-outline-primary btn-sm">首页</router-link>
        <button class="btn btn-outline-danger btn-sm" @click="onLogout">退出</button>
      </div>
    </div>

    <!-- 发券表单 -->
    <div class="card shadow-sm mb-4">
      <div class="card-body p-4">
        <h5 class="card-title">发布新券</h5>
        <div v-if="formOk" class="alert alert-success py-2 small" role="alert">{{ formOk }}</div>
        <div v-if="formError" class="alert alert-danger py-2 small" role="alert">{{ formError }}</div>

        <div class="row g-3">
          <div class="col-md-4">
            <label class="form-label">券名称</label>
            <input v-model="form.name" class="form-control" placeholder="例如：演示5折券" />
          </div>
          <div class="col-md-2">
            <label class="form-label">折扣（0~1）</label>
            <input v-model="form.discount" type="number" step="0.05" min="0.05" max="1"
                   class="form-control" />
          </div>
          <div class="col-md-2">
            <label class="form-label">库存</label>
            <input v-model.number="form.totalStock" type="number" min="1" class="form-control" />
          </div>
          <div class="col-md-2">
            <label class="form-label">开始时间</label>
            <input v-model="form.startTime" type="datetime-local" class="form-control" />
          </div>
          <div class="col-md-2">
            <label class="form-label">结束时间</label>
            <input v-model="form.endTime" type="datetime-local" class="form-control" />
          </div>
        </div>
        <div class="mt-3 d-flex gap-2">
          <button class="btn btn-primary" :disabled="creating" @click="onCreate">
            {{ creating ? '发布中…' : '发布' }}
          </button>
          <button class="btn btn-outline-secondary" @click="fillDemoTime">填充演示时间</button>
          <span class="form-text align-self-center">
            演示时间 = 开始(现在-1分钟) ~ 结束(现在+1小时)，发布后≤60秒变"进行中"
          </span>
        </div>
      </div>
    </div>

    <!-- 券列表 -->
    <div class="card shadow-sm">
      <div class="card-body p-4">
        <div class="d-flex justify-content-between align-items-center mb-2">
          <h5 class="card-title mb-0">券列表（每 30 秒自动刷新）</h5>
          <button class="btn btn-outline-secondary btn-sm" @click="loadList">立即刷新</button>
        </div>
        <div v-if="listError" class="alert alert-danger py-2 small" role="alert">{{ listError }}</div>

        <div class="table-responsive">
          <table class="table table-hover align-middle mb-0">
            <thead>
              <tr>
                <th>ID</th><th>名称</th><th>折扣</th><th>总库存</th>
                <th>开始</th><th>结束</th><th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in coupons" :key="c.id">
                <td class="text-body-secondary">{{ c.id }}</td>
                <td>{{ c.name }}</td>
                <td>{{ fmtDiscount(c.discount) }}</td>
                <td>{{ c.totalStock }}</td>
                <td class="small">{{ fmtTime(c.startTime) }}</td>
                <td class="small">{{ fmtTime(c.endTime) }}</td>
                <td>
                  <span class="badge" :class="STATUS_BADGE[c.status]?.cls">{{ STATUS_BADGE[c.status]?.text || c.status }}</span>
                </td>
              </tr>
              <tr v-if="!coupons.length && !listError">
                <td colspan="7" class="text-center text-body-secondary py-4">
                  还没有券，发一张试试
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>
