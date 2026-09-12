<script setup>
// 顾客下单（网单）：选门店 → 选衣物 → 预约/地址/备注 → 选券（实时显示预计折后价）→ 提交。
//
// 提交里**没有单价**：价格由后端按价目表算。预计折后价是前端自己乘的，
// 只作参考 —— 页面上必须标"以门店结算为准"，否则就成了前端在承诺一个它定不了的价。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { personaPaths } from '../../router'
import { createOrder } from '../../api/order'
import { listCategories, listWashTypes, listPrices } from '../../api/price'
import { listStores } from '../../api/store'
import { listMyCoupons } from '../../api/coupon'
import { loadSession, clearSession } from '../../utils/auth'
import { fmtMoney, fmtDiscount, fmtTime, SOURCE_CODE } from '../../utils/format'

const router = useRouter()
const session = loadSession('customer')
const who = session ? (session.claims.sub || '') : ''

const stores = ref([])
const categories = ref([])
const washTypes = ref([])
const prices = ref([])
const myCoupons = ref([])
const loadError = ref('')

const storeId = ref('')
const deliveryAddress = ref('')
const appointmentTime = ref('')
const remark = ref('')
const couponId = ref('')       // '' = 不用券

// ── 明细行 ──
const items = ref([{ categoryId: '', washTypeId: '', quantity: 1 }])
const addItem = () => items.value.push({ categoryId: '', washTypeId: '', quantity: 1 })
const removeItem = (i) => { if (items.value.length > 1) items.value.splice(i, 1) }

function priceOf(categoryId, washTypeId) {
  if (!categoryId || !washTypeId) return null
  const row = prices.value.find(
    (p) => p.categoryId === Number(categoryId) && p.washTypeId === Number(washTypeId))
  return row && row.supported ? Number(row.price) : null
}

/** 折前合计（前端估算） */
const gross = computed(() =>
  items.value.reduce((sum, it) => {
    const p = priceOf(it.categoryId, it.washTypeId)
    return sum + (p === null ? 0 : p * (Number(it.quantity) || 0))
  }, 0))

/** 选中的券（从我的券里找） */
const pickedCoupon = computed(() =>
  myCoupons.value.find((c) => String(c.couponId) === String(couponId.value)) || null)

/** 预计折后价 = 折前 × 折扣率。
 *  折扣是 decimal(3,2) 的**比率**（0.50 = 5折），不是百分数 —— 乘它就对了。
 *  保留 2 位、HALF_UP，和后端 BigDecimal 的算法保持一致 */
const net = computed(() => {
  if (!pickedCoupon.value) return gross.value
  return Math.round(gross.value * Number(pickedCoupon.value.discount) * 100) / 100
})

const saving = computed(() =>
  pickedCoupon.value ? Math.round((gross.value - net.value) * 100) / 100 : 0)

const canSubmit = computed(() =>
  deliveryAddress.value.trim() && items.value.length > 0
  && items.value.every((it) => it.categoryId && it.washTypeId && Number(it.quantity) > 0
    && priceOf(it.categoryId, it.washTypeId) !== null))

const submitBusy = ref(false)
const submitError = ref('')

async function onSubmit() {
  submitError.value = ''
  if (!deliveryAddress.value.trim()) return (submitError.value = '网单必须填写配送地址')
  if (!canSubmit.value) return (submitError.value = '每条明细都要选分类、洗涤方式，且数量为正（置灰的组合不支持）')
  submitBusy.value = true
  try {
    // 网单：customerId 取自 token（后端拿 token 里的，请求体不传）；
    // deliveryAddress 必填 —— 这是域层规则，不填后端会 400
    const created = await createOrder('customer', {
      source: SOURCE_CODE.ONLINE,   // 数字 2，不是 'ONLINE'
      // 门店**选填**（2026-09-13 口径）：不选就传 null，后端存 NULL。
      // 不传不行 —— 两者对后端没区别，但传 null 把"我确实没选"写明，
      // 而不是让字段缺席去让人猜是没选还是前端漏了
      storeId: storeId.value ? Number(storeId.value) : null,
      deliveryAddress: deliveryAddress.value.trim(),
      items: items.value.map((it) => ({
        categoryId: Number(it.categoryId),
        washTypeId: Number(it.washTypeId),
        quantity: Number(it.quantity),
      })),
      appointmentTime: appointmentTime.value
        ? (appointmentTime.value.length === 16 ? appointmentTime.value + ':00' : appointmentTime.value)
        : null,
      remark: remark.value.trim() || null,
      couponId: couponId.value ? Number(couponId.value) : null,
    })
    router.push(`/customer/orders/${created.id}`)
  } catch (e) {
    submitError.value = e.message
  } finally {
    submitBusy.value = false
  }
}

/** 顾客登出只是清本地会话 —— 后端**没有**顾客登出接口。
 *  员工那边有 /api/auth/staff/logout（把 token 拉黑），因为员工用的是柜台电脑；
 *  顾客的 token 24 小时后自然过期，为它建一张黑名单表不值当。
 *  两端登出长得不一样是设计，不是漏写 —— 别顺手给顾客补一个"对称"的接口 */
function onLogout() {
  clearSession('customer')
  router.push(personaPaths.customer.login)
}

onMounted(async () => {
  try {
    const [storeList, cats, wash, priceList, coupons] = await Promise.all([
      listStores('customer'),
      listCategories('customer'),
      listWashTypes('customer'),
      listPrices('customer'),
      listMyCoupons(),
    ])
    stores.value = storeList
    categories.value = cats.filter((c) => c.leaf)
    washTypes.value = wash
    prices.value = priceList
    myCoupons.value = coupons
  } catch (e) {
    loadError.value = e.message
  }
})
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h4 class="mb-0">在线下单</h4>
      <div class="d-flex gap-2 align-items-center">
        <span class="text-body-secondary small">{{ who }}</span>
        <router-link to="/customer/orders" class="btn btn-outline-primary btn-sm">我的订单</router-link>
        <router-link to="/customer/home" class="btn btn-outline-primary btn-sm">首页</router-link>
        <button class="btn btn-outline-danger btn-sm" @click="onLogout">退出</button>
      </div>
    </div>

    <div v-if="loadError" class="alert alert-danger py-2 small" role="alert">{{ loadError }}</div>

    <!-- ① 门店与配送 -->
    <div class="card shadow-sm mb-3">
      <div class="card-body p-4">
        <h5 class="card-title">① 送到哪</h5>
        <div class="row g-3">
          <!-- 门店**选填**：在线顾客本来就没有门店归属（下单页不该逼他先做选择），
               所以默认就是"不指定"。想要就近送、或者心里有偏好，再挑一家 -->
          <div class="col-md-4">
            <label class="form-label">门店（选填）</label>
            <select v-model="storeId" class="form-select">
              <option value="">不指定</option>
              <option v-for="s in stores" :key="s.id" :value="s.id">{{ s.name }}</option>
            </select>
            <div class="form-text">不选也行，门店只作归属标记、不影响派送</div>
          </div>
          <div class="col-md-8">
            <label class="form-label">配送地址（必填）</label>
            <input v-model="deliveryAddress" class="form-control" placeholder="如：杭州市西湖区文一西路 100 号 3 幢 501" />
          </div>
        </div>
      </div>
    </div>

    <!-- ② 衣物 -->
    <div class="card shadow-sm mb-3">
      <div class="card-body p-4">
        <div class="d-flex justify-content-between align-items-center">
          <h5 class="card-title mb-0">② 要洗什么</h5>
          <button class="btn btn-outline-primary btn-sm" @click="addItem">+ 加一条</button>
        </div>

        <div v-for="(it, i) in items" :key="i" class="row g-2 align-items-end mt-1">
          <div class="col-md-4">
            <label class="form-label small mb-1">分类</label>
            <select v-model="it.categoryId" class="form-select form-select-sm">
              <option value="">请选择</option>
              <option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option>
            </select>
          </div>
          <div class="col-md-4">
            <label class="form-label small mb-1">洗涤方式</label>
            <select v-model="it.washTypeId" class="form-select form-select-sm">
              <option value="">请选择</option>
              <option v-for="w in washTypes" :key="w.id" :value="w.id"
                      :disabled="priceOf(it.categoryId, w.id) === null">
                {{ w.name }}{{ priceOf(it.categoryId, w.id) === null ? '（不支持）' : '' }}
              </option>
            </select>
          </div>
          <div class="col-md-2">
            <label class="form-label small mb-1">数量</label>
            <input v-model.number="it.quantity" type="number" min="1" class="form-control form-control-sm" />
          </div>
          <div class="col-md-2 text-end">
            <div class="small text-body-secondary">
              {{ priceOf(it.categoryId, it.washTypeId) === null
                 ? '—' : fmtMoney(priceOf(it.categoryId, it.washTypeId) * (it.quantity || 0)) }}
            </div>
            <button class="btn btn-outline-danger btn-sm mt-1" :disabled="items.length <= 1"
                    @click="removeItem(i)">删除</button>
          </div>
        </div>
      </div>
    </div>

    <!-- ③ 预约 / 备注 / 券 -->
    <div class="card shadow-sm mb-3">
      <div class="card-body p-4">
        <h5 class="card-title">③ 时间、备注与优惠券</h5>
        <div class="row g-3">
          <div class="col-md-4">
            <label class="form-label">预约时间（选填）</label>
            <input v-model="appointmentTime" type="datetime-local" class="form-control" />
          </div>
          <div class="col-md-4">
            <label class="form-label">优惠券（选填）</label>
            <select v-model="couponId" class="form-select">
              <option value="">不使用</option>
              <!-- 过期的**列出来但禁用**，不让它凭空消失：本轮的券去哪儿了要答得上来 -->
              <option v-for="c in myCoupons" :key="c.grabId" :value="c.couponId" :disabled="c.expired">
                {{ c.name }}（{{ fmtDiscount(c.discount) }}）{{ c.expired ? '· 已过期' : '' }}
              </option>
            </select>
            <div v-if="!myCoupons.length" class="form-text">还没有券，可以去首页抢一张</div>
          </div>
          <div class="col-md-4">
            <label class="form-label">备注（选填）</label>
            <input v-model="remark" class="form-control" placeholder="如：领口有污渍" />
          </div>
        </div>
      </div>
    </div>

    <!-- ④ 金额 -->
    <div class="card shadow-sm mb-3">
      <div class="card-body p-4">
        <div class="d-flex justify-content-between">
          <span>折前</span><span>{{ fmtMoney(gross) }}</span>
        </div>
        <div v-if="saving > 0" class="d-flex justify-content-between text-success">
          <span>券抵扣</span><span>−{{ fmtMoney(saving) }}</span>
        </div>
        <div class="d-flex justify-content-between fs-5 mt-2">
          <strong>预计应付</strong><strong>{{ fmtMoney(net) }}</strong>
        </div>
        <div class="form-text text-end">以门店结算为准 —— 最终金额由后端算</div>
        <div v-if="pickedCoupon" class="form-text text-end">
          券有效期至 {{ fmtTime(pickedCoupon.endTime) }}
        </div>
      </div>
    </div>

    <div v-if="submitError" class="alert alert-danger py-2 small" role="alert">{{ submitError }}</div>

    <button class="btn btn-primary btn-lg w-100" :disabled="submitBusy || !canSubmit" @click="onSubmit">
      {{ submitBusy ? '提交中…' : '提交订单' }}
    </button>
    <div class="form-text text-center mt-2">提交后在订单详情页付款</div>
  </div>
</template>
