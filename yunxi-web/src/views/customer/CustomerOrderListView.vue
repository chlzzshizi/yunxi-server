<script setup>
// 顾客订单列表：只看自己的（后端按 token 定归属，前端传不了也改不了）、可进详情付款
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { personaPaths } from '../../router'
import { listOrders } from '../../api/order'
import { loadSession, clearSession } from '../../utils/auth'
import { fmtTime, fmtMoney, ORDER_STATUS_BADGE, ORDER_STATUS_OPTIONS, SOURCE_TEXT } from '../../utils/format'

const router = useRouter()
const session = loadSession('customer')
const who = session ? (session.claims.sub || '') : ''

const statusFilter = ref('')
const page = ref(1)
const pageSize = 10

const orders = ref([])
const total = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const error = ref('')

const badge = (status) => ORDER_STATUS_BADGE[status] || { cls: 'text-bg-light', text: status }

async function load() {
  loading.value = true
  try {
    const data = await listOrders('customer', {
      status: statusFilter.value === '' ? null : Number(statusFilter.value),
      page: page.value,
      pageSize,
    })
    orders.value = data.list || []
    total.value = data.total
    totalPages.value = data.totalPages
    error.value = ''
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  page.value = 1
  load()
}

function goPage(p) {
  if (p < 1 || p > totalPages.value || p === page.value) return
  page.value = p
  load()
}

function onLogout() {
  clearSession('customer')   // 顾客没有后端登出接口，见下单页的说明
  router.push(personaPaths.customer.login)
}

onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h4 class="mb-0">我的订单</h4>
      <div class="d-flex gap-2 align-items-center">
        <span class="text-body-secondary small">{{ who }}</span>
        <router-link to="/customer/orders/new" class="btn btn-primary btn-sm">+ 下单</router-link>
        <router-link to="/customer/home" class="btn btn-outline-primary btn-sm">首页</router-link>
        <button class="btn btn-outline-danger btn-sm" @click="onLogout">退出</button>
      </div>
    </div>

    <div class="card shadow-sm">
      <div class="card-body p-4">
        <div class="d-flex flex-wrap gap-2 align-items-center mb-3">
          <label class="form-label mb-0">状态</label>
          <select v-model="statusFilter" class="form-select form-select-sm" style="width: 12rem"
                  @change="onFilterChange">
            <option value="">全部</option>
            <option v-for="o in ORDER_STATUS_OPTIONS" :key="o.value" :value="o.code">{{ o.text }}</option>
          </select>
          <button class="btn btn-outline-secondary btn-sm" :disabled="loading" @click="load">
            {{ loading ? '加载中…' : '刷新' }}
          </button>
          <span class="text-body-secondary small">共 {{ total }} 单</span>
        </div>

        <div v-if="error" class="alert alert-danger py-2 small" role="alert">{{ error }}</div>

        <div v-for="o in orders" :key="o.id" class="border rounded p-3 mb-2">
          <div class="d-flex justify-content-between align-items-center">
            <div>
              <span class="badge" :class="badge(o.status).cls">{{ badge(o.status).text }}</span>
              <span class="badge text-bg-light ms-1">{{ SOURCE_TEXT[o.source] || o.source }}</span>
              <span class="font-monospace small ms-2">{{ o.orderNo }}</span>
            </div>
            <div class="text-end">
              <div class="fs-5">{{ fmtMoney(o.totalAmount) }}</div>
              <div class="small text-body-secondary">{{ fmtTime(o.createTime) }}</div>
            </div>
          </div>
          <div class="d-flex justify-content-between align-items-center mt-2">
            <span class="small text-body-secondary">
              <span v-if="o.expressNo">快递 {{ o.expressNo }}</span>
              <span v-else-if="o.deliveryAddress">送至 {{ o.deliveryAddress }}</span>
            </span>
            <router-link :to="`/customer/orders/${o.id}`" class="btn btn-outline-primary btn-sm">
              {{ o.status === 'PENDING_PAY' ? '去支付' : '查看' }}
            </router-link>
          </div>
        </div>

        <div v-if="!orders.length && !loading && !error" class="text-center text-body-secondary py-4">
          还没有订单，去下一单吧
        </div>

        <div class="d-flex align-items-center gap-2 mt-3">
          <button class="btn btn-outline-secondary btn-sm" :disabled="page <= 1" @click="goPage(page - 1)">
            上一页
          </button>
          <span class="small text-body-secondary">第 {{ page }} / {{ totalPages || 1 }} 页</span>
          <button class="btn btn-outline-secondary btn-sm"
                  :disabled="page >= totalPages" @click="goPage(page + 1)">
            下一页
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
