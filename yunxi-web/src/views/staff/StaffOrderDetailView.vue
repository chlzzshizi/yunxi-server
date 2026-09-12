<script setup>
// 员工订单详情 —— **所有订单写操作的唯一入口**。
//
// 为什么不把"推进/收款"做成列表上的快捷按钮：状态推进是 CAS 保护的写操作，
// 冲突要 409，而列表页是"扫一眼"的地方 —— 在那儿放写按钮，员工点错了连自己
// 点的是哪一单都说不清。详情页一次只面对一单，出错了也看得见。
//
// 按钮只做"让员工少点一次"：该不该显示某按钮，前端按状态粗判；**真正的规则
// 全在后端**（领域状态机 + CAS）。所以按钮显示得不精确不是 bug —— 点了不该点的
// 会拿到一句 400/409，页面把它显示出来就是了。
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getOrder, advanceOrder, payOrder, finalPayOrder, fillExpressNo } from '../../api/order'
import { fmtTime, fmtMoney, ORDER_STATUS_BADGE, SOURCE_TEXT,
         PAY_METHOD_TEXT, STAFF_PAY_OPTIONS } from '../../utils/format'

const route = useRoute()
const router = useRouter()
const orderId = Number(route.params.id)

const order = ref(null)
const loading = ref(false)
const error = ref('')
const ok = ref('')
const busy = ref(false)

// ── 收款表单（先付传全额 / 洗后付传 0）──
const payMethod = ref('cash')
const payAmount = ref('')
// ── 洗后付结账 ──
const finalMethod = ref('cash')
// ── 快递单号 ──
const expressNo = ref('')

const badge = (status) => ORDER_STATUS_BADGE[status] || { cls: 'text-bg-light', text: status }

/** 状态是否在候选中（字段是枚举名，不是数字） */
const is = (...statuses) => order.value && statuses.includes(order.value.status)

async function load() {
  loading.value = true
  try {
    order.value = await getOrder('staff', orderId)
    // 金额默认填应付额 —— 先付就是付这个数。洗后付那边传 0，另有一个按钮
    payAmount.value = order.value.totalAmount
    error.value = ''
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

/** 统一的写操作包装：清提示 → 跑 → 成功重读 → 失败就地显示。
 *  **成功之后必须重读**：OrderView 是后端算出来的快照，本地改一个字段很容易
 *  和后端不一致（比如 6→7 会写 finishTime、收全额会改 paidAmount），重读最省心 */
async function run(fn, okText) {
  busy.value = true
  error.value = ''
  ok.value = ''
  try {
    await fn()
    await load()
    ok.value = okText
  } catch (e) {
    // 409 = 别人刚改过 → 也要重读，让页面显示"现在到底是什么状态"
    if (e.code === 409) await load()
    error.value = e.message
  } finally {
    busy.value = false
  }
}

const onAdvance = () => run(() => advanceOrder(orderId), '已推进到下一步')
// payMethod/finalMethod 的值**就是**要发出去的参数值（'cash'/'wechat'/'alipay'），直接传
const onPay = () => run(() => payOrder(orderId, payMethod.value, Number(payAmount.value)),
                       '收款已记录')
const onFinalPay = () => run(() => finalPayOrder(orderId, finalMethod.value),
                            '结账完成，订单已终结')
const onExpress = () => run(() => fillExpressNo(orderId, expressNo.value.trim()), '快递单号已录入')

onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h4 class="mb-0">订单详情</h4>
      <div class="d-flex gap-2">
        <router-link to="/staff/orders" class="btn btn-outline-secondary btn-sm">返回列表</router-link>
        <router-link to="/staff/prices" class="btn btn-outline-primary btn-sm">定价</router-link>
      </div>
    </div>

    <div v-if="error" class="alert alert-danger py-2 small" role="alert">{{ error }}</div>
    <div v-if="ok" class="alert alert-success py-2 small" role="alert">{{ ok }}</div>

    <div v-if="!order" class="text-body-secondary">{{ loading ? '加载中…' : '没有数据' }}</div>

    <template v-else>
      <!-- 状态条 + 操作 -->
      <div class="card shadow-sm mb-3">
        <div class="card-body p-4">
          <div class="d-flex flex-wrap align-items-center gap-3">
            <span class="badge fs-6" :class="badge(order.status).cls">{{ badge(order.status).text }}</span>
            <span class="font-monospace">{{ order.orderNo }}</span>
            <span class="badge" :class="order.source === 'ONLINE' ? 'text-bg-info' : 'text-bg-light'">
              {{ SOURCE_TEXT[order.source] || order.source }}
            </span>
            <span class="text-body-secondary small">下单 {{ fmtTime(order.createTime) }}</span>
            <span v-if="order.finishTime" class="text-body-secondary small">
              完成 {{ fmtTime(order.finishTime) }}
            </span>
          </div>

          <hr />

          <!-- 推进：目标状态由后端按"当前状态 + 来源"推出，这里只说"下一步" -->
          <div class="d-flex flex-wrap align-items-end gap-2 mb-3">
            <button class="btn btn-primary" :disabled="busy || is('COMPLETED')" @click="onAdvance">
              推进到下一步
            </button>
            <span class="form-text">
              门店单 1→2→3→4→5→7、网单 1→2→3→4→6→7；4 是分叉点，之后由「来源」决定走哪条
            </span>
          </div>

          <!-- 收款：1 态付全额；3/4 态补一笔洗后付占位（金额 0） -->
          <div v-if="is('PENDING_PAY', 'WASHING', 'PENDING_DELIVERY')" class="border rounded p-3 mb-3">
            <div class="fw-semibold mb-2">
              {{ is('PENDING_PAY') ? '收款（先付：收全额）' : '补占位支付（洗后付：金额 0）' }}
            </div>
            <div class="d-flex flex-wrap align-items-end gap-2">
              <div>
                <label class="form-label small mb-1">方式</label>
                <select v-model="payMethod" class="form-select form-select-sm" style="width: 9rem">
                  <option v-for="m in STAFF_PAY_OPTIONS" :key="m.code" :value="m.code">{{ m.text }}</option>
                </select>
              </div>
              <div v-if="is('PENDING_PAY')">
                <label class="form-label small mb-1">金额</label>
                <input v-model="payAmount" type="number" step="0.01" class="form-control form-control-sm"
                       style="width: 9rem" />
              </div>
              <button class="btn btn-success btn-sm" :disabled="busy" @click="onPay">确认收款</button>
              <span class="form-text">
                后端只认两种金额：**全额**（先付）或 **0**（洗后付占位），付一半会被拒
              </span>
            </div>
          </div>

          <!-- 洗后付结账：门店单 5 态 / 网单 6 态 -->
          <div v-if="is('PENDING_PICKUP', 'DELIVERING')" class="border rounded p-3 mb-3">
            <div class="fw-semibold mb-2">洗后付结账（直接到终态「已完成」）</div>
            <div class="d-flex flex-wrap align-items-end gap-2">
              <div>
                <label class="form-label small mb-1">方式</label>
                <select v-model="finalMethod" class="form-select form-select-sm" style="width: 9rem">
                  <option v-for="m in STAFF_PAY_OPTIONS" :key="m.code" :value="m.code">{{ m.text }}</option>
                </select>
              </div>
              <button class="btn btn-success btn-sm" :disabled="busy" @click="onFinalPay">结账</button>
              <span class="form-text">应收 {{ fmtMoney(order.totalAmount) }}，已付 {{ fmtMoney(order.paidAmount) }}</span>
            </div>
          </div>

          <!-- 快递单号：仅网单、仅派送中 -->
          <div v-if="is('DELIVERING') && order.source === 'ONLINE'" class="border rounded p-3">
            <div class="fw-semibold mb-2">录入快递单号</div>
            <div class="d-flex flex-wrap align-items-end gap-2">
              <div>
                <label class="form-label small mb-1">单号（≤50 字）</label>
                <input v-model="expressNo" class="form-control form-control-sm" style="width: 16rem"
                       placeholder="如 SF1234567890" />
              </div>
              <button class="btn btn-primary btn-sm" :disabled="busy || !expressNo.trim()" @click="onExpress">
                录入
              </button>
              <span class="form-text">录入**不推进状态**；一张单要送到顾客手上，先录单号再完成</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 明细与金额 -->
      <div class="card shadow-sm mb-3">
        <div class="card-body p-4">
          <h5 class="card-title">衣物明细</h5>
          <div class="table-responsive">
            <table class="table align-middle mb-2">
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
                  <!-- 小计前端自己乘：OrderItemView 里**没有** subtotal 字段 -->
                  <td class="text-end">{{ fmtMoney(it.unitPrice * it.quantity) }}</td>
                </tr>
                <tr v-if="!order.items || !order.items.length">
                  <td colspan="5" class="text-center text-body-secondary py-3">没有明细</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="text-end">
            <div v-if="Number(order.discountAmount) > 0" class="small text-body-secondary">
              券抵扣 −{{ fmtMoney(order.discountAmount) }}
              <span v-if="order.couponId" class="ms-1">（券 #{{ order.couponId }}）</span>
            </div>
            <div class="fs-5">应付 <strong>{{ fmtMoney(order.totalAmount) }}</strong></div>
            <div class="small text-body-secondary">
              已付 {{ fmtMoney(order.paidAmount) }}
              <span v-if="order.payMethod">· 方式 {{ PAY_METHOD_TEXT[order.payMethod] || order.payMethod }}</span>
              <span v-if="order.finalPayMethod">
                · 结账方式 {{ PAY_METHOD_TEXT[order.finalPayMethod] || order.finalPayMethod }}
              </span>
            </div>
            <!-- 折后价 = 应付，不是"再减一次折扣"。这里只是把"为什么这个数是折后的"说清楚 -->
            <div v-if="Number(order.discountAmount) > 0" class="small text-body-secondary">
              应付已是**折后**金额（后端把折后价存进 total_amount，收款就收这个数）
            </div>
          </div>
        </div>
      </div>

      <!-- 其它信息 -->
      <div class="card shadow-sm">
        <div class="card-body p-4">
          <h5 class="card-title">其它信息</h5>
          <dl class="row mb-0 small">
            <dt class="col-sm-2 text-body-secondary">顾客</dt><dd class="col-sm-4">#{{ order.customerId }}</dd>
            <!-- 网单门店可选，没指定时为 NULL（见建单页注释） -->
            <dt class="col-sm-2 text-body-secondary">门店</dt>
            <dd class="col-sm-4">{{ order.storeId ? '#' + order.storeId : '未指定' }}</dd>
            <dt class="col-sm-2 text-body-secondary">经手员工</dt><dd class="col-sm-4">{{ order.staffId || '-' }}</dd>
            <dt class="col-sm-2 text-body-secondary">预约时间</dt><dd class="col-sm-4">{{ fmtTime(order.appointmentTime) }}</dd>
            <dt class="col-sm-2 text-body-secondary">配送地址</dt><dd class="col-sm-4">{{ order.deliveryAddress || '-' }}</dd>
            <dt class="col-sm-2 text-body-secondary">快递单号</dt><dd class="col-sm-4">{{ order.expressNo || '-' }}</dd>
            <dt class="col-sm-2 text-body-secondary">备注</dt><dd class="col-sm-10">{{ order.remark || '-' }}</dd>
          </dl>
        </div>
      </div>
    </template>
  </div>
</template>
