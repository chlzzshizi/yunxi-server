<script setup>
// 顾客订单详情 —— 待支付时能付款（唯一写操作），其它时候只看进度。
//
// 付款走 online-pay：**没有 amount 参数**，金额由后端从订单上取（已是折后应付）。
// 顾客传不了金额，也就篡改不了 —— 这是这条链路唯一的安全边界。
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { personaPaths } from '../../router'
import { getOrder, onlinePay } from '../../api/order'
import { loadSession, clearSession } from '../../utils/auth'
import { fmtTime, fmtMoney, ORDER_STATUS_BADGE, SOURCE_TEXT,
         PAY_METHOD_TEXT, ONLINE_PAY_OPTIONS } from '../../utils/format'

const route = useRoute()
const router = useRouter()
const orderId = Number(route.params.id)
const session = loadSession('customer')
const who = session ? (session.claims.sub || '') : ''

const order = ref(null)
const loading = ref(false)
const error = ref('')
const ok = ref('')
const busy = ref(false)
const payMethod = ref('wechat')

const badge = (status) => ORDER_STATUS_BADGE[status] || { cls: 'text-bg-light', text: status }

/** 进度条：门店单与网单在 4 之后分叉，所以"第几步"不能只看码值 */
const steps = [
  { code: 1, text: '待支付' }, { code: 2, text: '已支付' }, { code: 3, text: '洗涤中' },
  { code: 4, text: '待出厂' }, { code: 6, text: '派送中' }, { code: 7, text: '已完成' },
]
const storeSteps = [
  { code: 1, text: '待支付' }, { code: 2, text: '已支付' }, { code: 3, text: '洗涤中' },
  { code: 4, text: '待出厂' }, { code: 5, text: '待取件' }, { code: 7, text: '已完成' },
]
const currentSteps = () => (order.value?.source === 'ONLINE' ? steps : storeSteps)

/** 当前步骤的序号（1 起）；已完成的单落在最后一步 */
function stepIndex() {
  if (!order.value) return 0
  const cur = badge(order.value.status).code
  const i = currentSteps().findIndex((s) => s.code === cur)
  return i < 0 ? 0 : i + 1
}

async function load() {
  loading.value = true
  try {
    order.value = await getOrder('customer', orderId)
    error.value = ''
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function onPay() {
  busy.value = true
  error.value = ''
  ok.value = ''
  try {
    await onlinePay(orderId, payMethod.value)
    await load()   // 成功、失败都重读：状态是后端说了算的
    ok.value = '支付成功'
  } catch (e) {
    // 409 = 状态被改过（比如在另一台设备上已付）→ 重读，让页面显示真实状态
    if (e.code === 409 || e.code === 400) await load()
    error.value = e.message
  } finally {
    busy.value = false
  }
}

function onLogout() {
  clearSession('customer')
  router.push(personaPaths.customer.login)
}

onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h4 class="mb-0">订单详情</h4>
      <div class="d-flex gap-2 align-items-center">
        <span class="text-body-secondary small">{{ who }}</span>
        <router-link to="/customer/orders" class="btn btn-outline-secondary btn-sm">返回列表</router-link>
        <button class="btn btn-outline-danger btn-sm" @click="onLogout">退出</button>
      </div>
    </div>

    <div v-if="error" class="alert alert-danger py-2 small" role="alert">{{ error }}</div>
    <div v-if="ok" class="alert alert-success py-2 small" role="alert">{{ ok }}</div>
    <div v-if="!order" class="text-body-secondary">{{ loading ? '加载中…' : '没有数据' }}</div>

    <template v-else>
      <!-- 进度 -->
      <div class="card shadow-sm mb-3">
        <div class="card-body p-4">
          <div class="d-flex align-items-center gap-2 mb-3">
            <span class="badge fs-6" :class="badge(order.status).cls">{{ badge(order.status).text }}</span>
            <span class="badge text-bg-light">{{ SOURCE_TEXT[order.source] || order.source }}</span>
            <span class="font-monospace small">{{ order.orderNo }}</span>
          </div>

          <div class="d-flex gap-1">
            <div v-for="(s, i) in currentSteps()" :key="s.code" class="flex-fill text-center">
              <div class="rounded" :class="i + 1 <= stepIndex() ? 'bg-primary' : 'bg-body-secondary'"
                   style="height: 6px"></div>
              <div class="small mt-1"
                   :class="i + 1 <= stepIndex() ? 'text-primary' : 'text-body-secondary'">
                {{ s.text }}
              </div>
            </div>
          </div>
          <div v-if="order.source === 'ONLINE' && order.expressNo" class="form-text mt-2">
            快递单号 {{ order.expressNo }}
          </div>
          <div v-if="order.source === 'STORE'" class="form-text mt-2">
            门店单：洗好后到店取件（下单页选的那家门店）
          </div>
        </div>
      </div>

      <!-- 付款：只在待支付时出现 -->
      <div v-if="order.status === 'PENDING_PAY'" class="card shadow-sm mb-3">
        <div class="card-body p-4">
          <h5 class="card-title">去支付</h5>
          <div class="d-flex flex-wrap align-items-end gap-2">
            <div>
              <label class="form-label small mb-1">支付方式</label>
              <select v-model="payMethod" class="form-select form-select-sm" style="width: 10rem">
                <option v-for="m in ONLINE_PAY_OPTIONS" :key="m.code" :value="m.code">{{ m.text }}</option>
              </select>
            </div>
            <button class="btn btn-primary" :disabled="busy" @click="onPay">
              {{ busy ? '支付中…' : `支付 ${fmtMoney(order.totalAmount)}` }}
            </button>
            <span class="form-text">
              金额由系统算好，不需要（也没法）自己填；现金请到店支付
            </span>
          </div>
        </div>
      </div>

      <!-- 明细与金额 -->
      <div class="card shadow-sm mb-3">
        <div class="card-body p-4">
          <h5 class="card-title">衣物明细</h5>
          <table class="table align-middle mb-3">
            <thead>
              <tr><th>分类</th><th>洗涤方式</th><th class="text-end">单价</th>
                  <th class="text-end">数量</th><th class="text-end">小计</th></tr>
            </thead>
            <tbody>
              <tr v-for="it in order.items" :key="it.id">
                <td>#{{ it.categoryId }}</td>
                <td>#{{ it.washTypeId }}</td>
                <td class="text-end">{{ fmtMoney(it.unitPrice) }}</td>
                <td class="text-end">{{ it.quantity }}</td>
                <td class="text-end">{{ fmtMoney(it.unitPrice * it.quantity) }}</td>
              </tr>
              <tr v-if="!order.items || !order.items.length">
                <td colspan="5" class="text-center text-body-secondary py-3">没有明细</td>
              </tr>
            </tbody>
          </table>
          <div class="text-end">
            <div v-if="Number(order.discountAmount) > 0" class="small text-success">
              券已抵扣 −{{ fmtMoney(order.discountAmount) }}
            </div>
            <div class="fs-5">应付 <strong>{{ fmtMoney(order.totalAmount) }}</strong></div>
            <div class="small text-body-secondary">已付 {{ fmtMoney(order.paidAmount) }}</div>
          </div>
        </div>
      </div>

      <!-- 其它 -->
      <div class="card shadow-sm">
        <div class="card-body p-4">
          <h5 class="card-title">其它信息</h5>
          <dl class="row mb-0 small">
            <dt class="col-sm-3 text-body-secondary">下单时间</dt><dd class="col-sm-9">{{ fmtTime(order.createTime) }}</dd>
            <dt class="col-sm-3 text-body-secondary">完成时间</dt><dd class="col-sm-9">{{ fmtTime(order.finishTime) }}</dd>
            <dt class="col-sm-3 text-body-secondary">预约时间</dt><dd class="col-sm-9">{{ fmtTime(order.appointmentTime) }}</dd>
            <dt class="col-sm-3 text-body-secondary">配送地址</dt><dd class="col-sm-9">{{ order.deliveryAddress || '-' }}</dd>
            <dt class="col-sm-3 text-body-secondary">快递单号</dt><dd class="col-sm-9">{{ order.expressNo || '-' }}</dd>
            <dt class="col-sm-3 text-body-secondary">支付方式</dt>
            <dd class="col-sm-9">
              {{ order.payMethod ? (PAY_METHOD_TEXT[order.payMethod] || order.payMethod) : '-' }}
              <span v-if="order.finalPayMethod">
                · 结账 {{ PAY_METHOD_TEXT[order.finalPayMethod] || order.finalPayMethod }}
              </span>
            </dd>
            <dt class="col-sm-3 text-body-secondary">备注</dt><dd class="col-sm-9">{{ order.remark || '-' }}</dd>
          </dl>
        </div>
      </div>
    </template>
  </div>
</template>
