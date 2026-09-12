<script setup>
// 员工订单列表：按状态筛选 + 分页 + 进详情。所有店长都能看所有门店的订单（§4.2），
// 所以这里没有"门店"筛选 —— 门店只是地理信息，不是权限边界也不是数据隔离
import { onMounted, ref } from 'vue'
import { personaPaths } from '../../router'
import { listOrders } from '../../api/order'
import { loadSession, clearSession } from '../../utils/auth'
import { staffLogout } from '../../api/auth'
import { useRouter } from 'vue-router'
import { fmtTime, fmtMoney, ORDER_STATUS_BADGE, ORDER_STATUS_OPTIONS, SOURCE_TEXT } from '../../utils/format'

const router = useRouter()
const session = loadSession('staff')
const who = session ? session.claims.username : ''

// ── 筛选与分页 ──
// status 用**数字码**（后端 `?status=2` 要的是数字），和返回体里的枚举名不是一回事
const statusFilter = ref('')   // '' = 全部
const page = ref(1)
const pageSize = 10

const orders = ref([])
const total = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  try {
    const data = await listOrders('staff', {
      status: statusFilter.value === '' ? null : Number(statusFilter.value),
      page: page.value,
      pageSize,
    })
    // 后端的 list 是 PageResult 的组件，字段名就叫 list（不是 items/records）
    orders.value = data.list || []
    total.value = data.total
    totalPages.value = data.totalPages
    error.value = ''
  } catch (e) {
    // 401 由 request.js 清会话并跳登录页，这里只管其它错误就地提示
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  page.value = 1   // 换了筛选条件必须回第一页，否则会停在一个不存在的页码上看到空列表
  load()
}

function goPage(p) {
  if (p < 1 || p > totalPages.value || p === page.value) return
  page.value = p
  load()
}

/** 订单状态 → 徽章。**键是枚举名**（"PENDING_PAY"），不是数字码 */
const badge = (status) => ORDER_STATUS_BADGE[status] || { cls: 'text-bg-light', text: status }

async function onLogout() {
  await staffLogout()
  clearSession('staff')
  router.push(personaPaths.staff.login)
}

onMounted(load)
</script>

<template>
  <div>
    <!-- 顶部：导航 + 当前身份 -->
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h4 class="mb-0">订单管理</h4>
      <div class="d-flex gap-2 align-items-center">
        <span class="text-body-secondary small">欢迎，{{ who }}</span>
        <router-link to="/staff/orders" class="btn btn-outline-primary btn-sm">订单</router-link>
        <router-link to="/staff/prices" class="btn btn-outline-primary btn-sm">定价</router-link>
        <router-link to="/staff/coupons" class="btn btn-outline-primary btn-sm">发券台</router-link>
        <router-link to="/" class="btn btn-outline-primary btn-sm">首页</router-link>
        <button class="btn btn-outline-danger btn-sm" @click="onLogout">退出</button>
      </div>
    </div>

    <div class="card shadow-sm">
      <div class="card-body p-4">
        <!-- 工具条：筛选 + 建单入口 -->
        <div class="d-flex flex-wrap gap-2 align-items-center mb-3">
          <label class="form-label mb-0">状态</label>
          <select v-model="statusFilter" class="form-select form-select-sm" style="width: 12rem"
                  @change="onFilterChange">
            <option value="">全部</option>
            <option v-for="o in ORDER_STATUS_OPTIONS" :key="o.value" :value="o.code">
              {{ o.text }}
            </option>
          </select>
          <button class="btn btn-outline-secondary btn-sm" :disabled="loading" @click="load">
            {{ loading ? '加载中…' : '刷新' }}
          </button>
          <span class="text-body-secondary small">共 {{ total }} 单</span>
          <div class="ms-auto">
            <router-link to="/staff/orders/new" class="btn btn-primary btn-sm">+ 建门店单</router-link>
          </div>
        </div>

        <div v-if="error" class="alert alert-danger py-2 small" role="alert">{{ error }}</div>

        <div class="table-responsive">
          <table class="table table-hover align-middle mb-0">
            <thead>
              <tr>
                <th>订单号</th><th>来源</th><th>顾客</th><th>门店</th>
                <th class="text-end">应付</th><th class="text-end">已付</th>
                <th>状态</th><th>下单时间</th><th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="o in orders" :key="o.id">
                <td class="small font-monospace">{{ o.orderNo }}</td>
                <td>
                  <span class="badge"
                        :class="o.source === 'ONLINE' ? 'text-bg-info' : 'text-bg-light'">
                    {{ SOURCE_TEXT[o.source] || o.source }}
                  </span>
                </td>
                <!-- 顾客/门店只显示 ID：后端没有"按 id 查顾客"的端点（建档那个是查手机的），
                     为一个列表去建它不划算。员工排障时看 ID 够用，真要查人走建档页 -->
                <td class="text-body-secondary small">#{{ o.customerId }}</td>
                <!-- 网单的门店可选，NULL = 顾客没指定 → 显示 '-'。
                     不兜底的话 Vue 会把 null 渲染成空串，那一格只剩一个孤零零的 '#' -->
                <td class="text-body-secondary small">{{ o.storeId ? '#' + o.storeId : '-' }}</td>
                <td class="text-end">{{ fmtMoney(o.totalAmount) }}</td>
                <td class="text-end text-body-secondary">{{ fmtMoney(o.paidAmount) }}</td>
                <td>
                  <span class="badge" :class="badge(o.status).cls">{{ badge(o.status).text }}</span>
                </td>
                <td class="small text-body-secondary">{{ fmtTime(o.createTime) }}</td>
                <td class="text-end">
                  <router-link :to="`/staff/orders/${o.id}`" class="btn btn-outline-primary btn-sm">
                    详情
                  </router-link>
                </td>
              </tr>
              <tr v-if="!orders.length && !loading && !error">
                <td colspan="9" class="text-center text-body-secondary py-4">这一页没有订单</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 分页 -->
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
