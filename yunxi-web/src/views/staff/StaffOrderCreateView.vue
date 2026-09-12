<script setup>
// 员工建门店单：手机号查/建顾客 → 明细行（分类 → 洗涤方式 → **单价自动带出**）→ 提交。
//
// 单价不由人输：后端按（分类 × 洗涤方式）查价目表算钱，请求体里根本没有 unitPrice
// 这个字段 —— 老版本前端能传 1 元洗羽绒服，就是这个口子。这里"自动带出"只是
// 给员工一个预期，真正算钱的是后端；两边不一致时以后端为准。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createOrder } from '../../api/order'
import { lookupOrCreateCustomer } from '../../api/customer'
import { listCategories, listWashTypes, listPrices } from '../../api/price'
import { listStores } from '../../api/store'
import { fmtMoney, SOURCE_CODE } from '../../utils/format'

const router = useRouter()

const categories = ref([])   // 只留叶子（能定价的那一层）
const washTypes = ref([])
const prices = ref([])
const stores = ref([])
const loadError = ref('')

// ── 顾客：手机号查/建档 ──
const phone = ref('')
const custName = ref('')
const customer = ref(null)     // {customerId, name, phone, created}
const custBusy = ref(false)
const custError = ref('')

async function onLookup() {
  custError.value = ''
  customer.value = null
  if (!/^\d{6,20}$/.test(phone.value.trim())) {
    return (custError.value = '请输入手机号（6~20 位数字）')
  }
  custBusy.value = true
  try {
    customer.value = await lookupOrCreateCustomer(phone.value.trim(), custName.value.trim() || null)
  } catch (e) {
    custError.value = e.message
  } finally {
    custBusy.value = false
  }
}

// ── 明细行 ──
const items = ref([newItem()])
function newItem() {
  return { categoryId: '', washTypeId: '', quantity: 1 }
}
const addItem = () => items.value.push(newItem())
const removeItem = (i) => {
  if (items.value.length > 1) items.value.splice(i, 1)
}

/** 查一行价目：命中且 supported 才给价。找不到 = 这个组合不支持 */
function priceOf(categoryId, washTypeId) {
  if (!categoryId || !washTypeId) return null
  const row = prices.value.find(
    (p) => p.categoryId === Number(categoryId) && p.washTypeId === Number(washTypeId))
  return row && row.supported ? Number(row.price) : null
}

/** 前端自己算的合计，只作展示 —— 提交时不带这个数，后端会重算 */
const estimate = computed(() =>
  items.value.reduce((sum, it) => {
    const p = priceOf(it.categoryId, it.washTypeId)
    return sum + (p === null ? 0 : p * (Number(it.quantity) || 0))
  }, 0))

const canSubmit = computed(() =>
  !!customer.value && items.value.length > 0 && items.value.every((it) =>
    it.categoryId && it.washTypeId && Number(it.quantity) > 0
    && priceOf(it.categoryId, it.washTypeId) !== null))

// ── 其它字段 ──
const appointmentTime = ref('')
const remark = ref('')
const submitBusy = ref(false)
const submitError = ref('')

async function onSubmit() {
  submitError.value = ''
  if (!customer.value) return (submitError.value = '请先查/建顾客')
  if (!canSubmit.value) return (submitError.value = '每条明细都要选分类、洗涤方式，且数量为正（置灰的组合不支持）')
  submitBusy.value = true
  try {
    // storeId 不传：门店单的归属门店取自员工 token（落在店长自己那家店）
    const created = await createOrder('staff', {
      source: SOURCE_CODE.STORE,          // 数字 1，不是 'STORE'
      customerId: customer.value.customerId,
      items: items.value.map((it) => ({
        categoryId: Number(it.categoryId),
        washTypeId: Number(it.washTypeId),
        quantity: Number(it.quantity),
      })),
      appointmentTime: appointmentTime.value
        ? (appointmentTime.value.length === 16 ? appointmentTime.value + ':00' : appointmentTime.value)
        : null,
      remark: remark.value.trim() || null,
    })
    router.push(`/staff/orders/${created.id}`)
  } catch (e) {
    submitError.value = e.message
  } finally {
    submitBusy.value = false
  }
}

onMounted(async () => {
  try {
    // 并发拉四个只读表：它们之间没有依赖，串行等三轮纯属浪费
    const [cats, wash, priceList, storeList] = await Promise.all([
      listCategories('staff'),
      listWashTypes('staff'),
      listPrices('staff'),
      listStores('staff'),
    ])
    // 种子里 1~4 是父分类、11+ 是叶子。价格只挂在叶子上，所以下拉里只放叶子
    categories.value = cats.filter((c) => c.leaf)
    washTypes.value = wash
    prices.value = priceList
    stores.value = storeList
  } catch (e) {
    loadError.value = e.message
  }
})
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h4 class="mb-0">建门店单</h4>
      <div class="d-flex gap-2">
        <router-link to="/staff/orders" class="btn btn-outline-secondary btn-sm">返回列表</router-link>
      </div>
    </div>

    <div v-if="loadError" class="alert alert-danger py-2 small" role="alert">{{ loadError }}</div>

    <!-- 第一步：顾客 -->
    <div class="card shadow-sm mb-3">
      <div class="card-body p-4">
        <h5 class="card-title">① 顾客</h5>
        <div class="row g-2 align-items-end">
          <div class="col-auto">
            <label class="form-label small mb-1">手机号</label>
            <input v-model="phone" class="form-control form-control-sm" style="width: 12rem"
                   placeholder="13700000001" @keyup.enter="onLookup" />
          </div>
          <div class="col-auto">
            <label class="form-label small mb-1">姓名（选填）</label>
            <input v-model="custName" class="form-control form-control-sm" style="width: 10rem"
                   placeholder="只在建档时用" />
          </div>
          <div class="col-auto">
            <button class="btn btn-primary btn-sm" :disabled="custBusy" @click="onLookup">
              {{ custBusy ? '查询中…' : '查询 / 建档' }}
            </button>
          </div>
        </div>

        <div v-if="custError" class="alert alert-danger py-2 small mt-3 mb-0" role="alert">{{ custError }}</div>

        <!-- 查到了人 —— 这里必须显式显示 customerId：它是后面建单要传的值，
             显示出来才看得出"点错了行/查错了人" -->
        <div v-if="customer" class="alert py-2 small mt-3 mb-0"
             :class="customer.created ? 'alert-success' : 'alert-secondary'" role="alert">
          <span v-if="customer.created">已为顾客建档：</span>
          <span v-else>找到老顾客：</span>
          <strong>{{ customer.name || '(无姓名)' }}</strong>
          · {{ customer.phone }} · 顾客 ID <strong>{{ customer.customerId }}</strong>
        </div>
      </div>
    </div>

    <!-- 第二步：明细 -->
    <div class="card shadow-sm mb-3">
      <div class="card-body p-4">
        <div class="d-flex justify-content-between align-items-center">
          <h5 class="card-title mb-0">② 衣物明细</h5>
          <button class="btn btn-outline-primary btn-sm" @click="addItem">+ 加一条</button>
        </div>

        <div v-for="(it, i) in items" :key="i" class="row g-2 align-items-end mt-1">
          <div class="col-md-3">
            <label class="form-label small mb-1">分类</label>
            <select v-model="it.categoryId" class="form-select form-select-sm">
              <option value="">请选择</option>
              <option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option>
            </select>
          </div>
          <div class="col-md-3">
            <label class="form-label small mb-1">洗涤方式</label>
            <select v-model="it.washTypeId" class="form-select form-select-sm">
              <option value="">请选择</option>
              <!-- supported=false 的组合**置灰而不是隐藏**：看不见的选项会让员工
                   以为"系统坏了"，灰的至少解释得清"这个分类就是没有普洗" -->
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
          <div class="col-md-2">
            <label class="form-label small mb-1">单价（后端算）</label>
            <input class="form-control form-control-sm" disabled
                   :value="priceOf(it.categoryId, it.washTypeId) === null
                           ? '—' : fmtMoney(priceOf(it.categoryId, it.washTypeId))" />
          </div>
          <div class="col-md-2">
            <button class="btn btn-outline-danger btn-sm" :disabled="items.length <= 1"
                    @click="removeItem(i)">删除</button>
          </div>
        </div>

        <div class="text-end mt-3 fs-5">
          预计合计 <strong>{{ fmtMoney(estimate) }}</strong>
          <div class="form-text">提交后由后端重新计算，以订单详情为准</div>
        </div>
      </div>
    </div>

    <!-- 第三步：其它 -->
    <div class="card shadow-sm mb-3">
      <div class="card-body p-4">
        <h5 class="card-title">③ 其它</h5>
        <div class="row g-3">
          <div class="col-md-4">
            <label class="form-label">预约时间（选填）</label>
            <input v-model="appointmentTime" type="datetime-local" class="form-control" />
          </div>
          <div class="col-md-8">
            <label class="form-label">备注（选填）</label>
            <input v-model="remark" class="form-control" placeholder="如：袖口有污渍" />
          </div>
        </div>
      </div>
    </div>

    <div v-if="submitError" class="alert alert-danger py-2 small" role="alert">{{ submitError }}</div>

    <div class="d-flex gap-2">
      <button class="btn btn-primary" :disabled="submitBusy || !canSubmit" @click="onSubmit">
        {{ submitBusy ? '提交中…' : '创建订单' }}
      </button>
      <span class="form-text align-self-center">
        门店单落在**你自己那家店**（门店取自登录 token，页面不传）
      </span>
    </div>
  </div>
</template>
