<script setup>
// 定价管理：按分类列出现有价格，改价提交。
//
// 前端**不复制**"精洗 = 普洗 + 20"这条规则 —— 它只体现在一个地方：
// 有普洗时精洗那一栏只读（后端会派生，手填会被 400 顶回来）；没有普洗时
// 才让填（羽绒服那个逃生舱：60 ≠ 0 + 20）。规则的家在 domain 的 PricePolicy。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listCategories, listWashTypes, listPrices, savePrices } from '../../api/price'
import { fmtMoney } from '../../utils/format'

const router = useRouter()

const categories = ref([])   // 只留叶子
const washTypes = ref([])
const prices = ref([])
const loading = ref(false)
const error = ref('')
const ok = ref('')

// 编辑中的值：{ [categoryId]: { [washTypeId]: '15.00' } }
// 用字符串存（输入框给的就是字符串），提交前再转数字 —— 直接存数字会让
// 输入过程中删到只剩 "1." 这类中间态被 v-model.number 吞成 1
const draft = ref({})

const priceRow = (categoryId, washTypeId, all) =>
  all.find((p) => p.categoryId === categoryId && p.washTypeId === washTypeId)

/** 某分类是否有"支持的普洗价"（>0）—— 有则精洗由后端派生，前端不让填 */
function hasBasePrice(categoryId) {
  const row = priceRow(categoryId, 1, prices.value)
  return !!row && row.supported && Number(row.price) > 0
}

function resetDraft() {
  const d = {}
  for (const c of categories.value) {
    d[c.id] = {}
    for (const w of washTypes.value) {
      const row = priceRow(c.id, w.id, prices.value)
      d[c.id][w.id] = row && row.supported ? String(row.price) : ''
    }
  }
  draft.value = d
}

async function load() {
  loading.value = true
  try {
    const [cats, wash, list] = await Promise.all([
      listCategories('staff'),
      listWashTypes('staff'),
      listPrices('staff'),
    ])
    categories.value = cats.filter((c) => c.leaf)
    washTypes.value = wash
    prices.value = list
    resetDraft()
    error.value = ''
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

/** 只提交有值的那些洗涤方式。
 *  有普洗时**连精洗一起提交**：后端本来就会派生，一起提交等于把这个分类的价格
 *  一次性对齐（留空的那栏不会被删，后端是 upsert 不是 replace） */
function payloadOf(categoryId) {
  const pricesPayload = []
  for (const w of washTypes.value) {
    const raw = draft.value[categoryId]?.[w.id]
    if (raw === '' || raw === null || raw === undefined) continue
    pricesPayload.push({ washTypeId: w.id, price: Number(raw) })
  }
  return pricesPayload
}

const saving = ref(null)   // 正在保存的 categoryId

async function onSave(categoryId) {
  error.value = ''
  ok.value = ''
  const payload = payloadOf(categoryId)
  if (!payload.length) return (error.value = '这个分类至少要填一个价格')
  if (payload.some((p) => !Number.isFinite(p.price) || p.price < 0)) {
    return (error.value = '价格必须是非负数')
  }
  saving.value = categoryId
  try {
    await savePrices(categoryId, payload)
    await load()   // 重读：精洗可能刚被后端派生出来，本地猜不如直接问
    const name = categories.value.find((c) => c.id === categoryId)?.name || categoryId
    ok.value = `「${name}」价格已保存`
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = null
  }
}

const totalRows = computed(() => categories.value.length)

onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h4 class="mb-0">定价管理</h4>
      <div class="d-flex gap-2 align-items-center">
        <router-link to="/staff/orders" class="btn btn-outline-primary btn-sm">订单</router-link>
        <router-link to="/staff/coupons" class="btn btn-outline-primary btn-sm">发券台</router-link>
        <router-link to="/" class="btn btn-outline-primary btn-sm">首页</router-link>
      </div>
    </div>

    <div class="alert alert-secondary py-2 small">
      价格是**全局**的（不打门店）—— 所有门店共用同一张价目表。
      改普洗价会自动重算该分类的精洗价（= 普洗 + 20），所以有普洗时精洗那一栏是灰的。
    </div>

    <div v-if="error" class="alert alert-danger py-2 small" role="alert">{{ error }}</div>
    <div v-if="ok" class="alert alert-success py-2 small" role="alert">{{ ok }}</div>

    <div class="card shadow-sm">
      <div class="card-body p-4">
        <h5 class="card-title">价目表（{{ totalRows }} 个分类）</h5>

        <div v-if="loading" class="text-body-secondary">加载中…</div>

        <div v-for="c in categories" :key="c.id" class="border rounded p-3 mb-2">
          <div class="d-flex justify-content-between align-items-center mb-2">
            <div>
              <strong>{{ c.name }}</strong>
              <span class="text-body-secondary small ms-2">
                （{{ c.icon || '无图标' }} · #{{ c.id }}）
              </span>
            </div>
            <button class="btn btn-primary btn-sm" :disabled="saving === c.id" @click="onSave(c.id)">
              {{ saving === c.id ? '保存中…' : '保存' }}
            </button>
          </div>

          <div class="row g-2">
            <div v-for="w in washTypes" :key="w.id" class="col-md-4">
              <label class="form-label small mb-1">
                {{ w.name }}
                <span v-if="w.id === 2 && hasBasePrice(c.id)" class="text-body-secondary">
                  （由普洗自动 +20）
                </span>
              </label>
              <input v-model="draft[c.id][w.id]" type="number" step="0.01" min="0"
                     class="form-control form-control-sm"
                     :disabled="w.id === 2 && hasBasePrice(c.id)"
                     :placeholder="w.id === 2 && hasBasePrice(c.id) ? '自动派生' : '不支持' " />
            </div>
          </div>

          <div class="form-text mt-2">
            留空 = 这个分类不支持该洗涤方式（前端下单时那一项会置灰）
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
