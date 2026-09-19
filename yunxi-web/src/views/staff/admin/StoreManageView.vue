<script setup>
// 门店管理（管理员专区）：门店表（含停业）+ 建店 / 改店 + 营业停业。
//
// 列表用 /api/stores/all —— **含停业**。用 /api/stores 的话，一家店停业之后就从
// 这张表里消失了，而"把它重新开起来"恰恰是唯一需要在这里做的事。
//
// 停业是**软停业**（改 status，不硬删）：门店被历史订单引用着，删了那些订单的
// store_id 就指向虚空，而库里没有外键，数据库不会替你拦。
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { personaPaths } from '../../../router'
import { listAllStores, createStore, updateStore, updateStoreStatus } from '../../../api/store'
import { staffLogout } from '../../../api/auth'
import { loadSession, clearSession } from '../../../utils/auth'
import { STORE_STATUS_BADGE } from '../../../utils/format'
import {
  tooLong, STORE_NAME_MAX, STORE_ADDRESS_MAX, STORE_PHONE_MAX,
} from '../../../utils/validate'

const router = useRouter()
const session = loadSession('staff')
const who = session ? session.claims.username : ''

// ── 门店表 ──
const list = ref([])
const listError = ref('')

async function loadList() {
  try {
    list.value = await listAllStores('staff')
    listError.value = ''
  } catch (e) {
    listError.value = e.message // 401 由 request.js 清会话并跳登录；403/网络就地提示
  }
}

// ── 面板：'' | 'create' | 'edit' ──
const panel = ref('')
const target = ref(null)
const formError = ref('')
const formOk = ref('')
const busy = ref(false)
const form = ref(blankForm())

function blankForm() {
  return { name: '', address: '', phone: '' }
}

function openCreate() {
  form.value = blankForm()
  target.value = null
  formError.value = ''
  formOk.value = ''
  panel.value = 'create'
}

function openEdit(row) {
  form.value = { name: row.name || '', address: row.address || '', phone: row.phone || '' }
  target.value = row
  formError.value = ''
  formOk.value = ''
  panel.value = 'edit'
}

function closePanel() {
  panel.value = ''
  target.value = null
  formError.value = ''
  formOk.value = ''
}

// ── 校验：文案逐字照抄后端（Store.java / StoreAdminAppService）──
// 门店的电话抬头是「电话」—— 员工那边是「手机号」，同一个 requireWithin，说法不同
function validate() {
  const f = form.value
  if (!f.name.trim()) return '门店名称不能为空'
  if (!f.address.trim()) return '地址不能为空'
  if (tooLong(f.name.trim(), STORE_NAME_MAX)) return `门店名称不能超过 ${STORE_NAME_MAX} 个字`
  if (tooLong(f.address.trim(), STORE_ADDRESS_MAX)) return `地址不能超过 ${STORE_ADDRESS_MAX} 个字`
  if (tooLong(f.phone.trim(), STORE_PHONE_MAX)) return `电话不能超过 ${STORE_PHONE_MAX} 个字`
  return ''
}

function payload() {
  const f = form.value
  return {
    name: f.name.trim(),
    address: f.address.trim(),
    phone: f.phone.trim() || null, // 空白存 NULL，不存空串
  }
}

async function onCreate() {
  formError.value = ''
  formOk.value = ''
  const err = validate()
  if (err) return (formError.value = err)

  busy.value = true
  try {
    await createStore(payload())
    formOk.value = `已建店「${form.value.name.trim()}」（新建一律是营业状态）`
    form.value = blankForm()
    loadList()
  } catch (e) {
    formError.value = e.message
  } finally {
    busy.value = false
  }
}

async function onUpdate() {
  formError.value = ''
  formOk.value = ''
  const err = validate()
  if (err) return (formError.value = err)

  busy.value = true
  try {
    await updateStore(target.value.id, payload())
    formOk.value = '已保存'
    loadList()
  } catch (e) {
    formError.value = e.message
  } finally {
    busy.value = false
  }
}

async function onToggleStatus(row) {
  const next = row.status === 1 ? 0 : 1
  formError.value = ''
  formOk.value = ''
  busy.value = true
  try {
    await updateStoreStatus(row.id, next)
    // 停业**不踢人**：店里员工的 token 不受影响（token 里签的是 storeId，不是营业状态）
    formOk.value = next === 0
      ? `已停业「${row.name}」—— 顾客的选店列表里立刻看不到它；店里员工的登录不受影响`
      : `已营业「${row.name}」—— 顾客可以选它下单了`
    loadList()
  } catch (e) {
    formError.value = e.message
  } finally {
    busy.value = false
  }
}

async function onLogout() {
  await staffLogout()
  clearSession('staff')
  router.push(personaPaths.staff.login)
}

onMounted(loadList)
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h4 class="mb-0">门店管理</h4>
      <div class="d-flex gap-2 align-items-center">
        <span class="text-body-secondary small">欢迎，{{ who }}</span>
        <router-link to="/staff/admin/staff" class="btn btn-outline-primary btn-sm">员工管理</router-link>
        <router-link to="/" class="btn btn-outline-primary btn-sm">首页</router-link>
        <button class="btn btn-outline-danger btn-sm" @click="onLogout">退出</button>
      </div>
    </div>

    <!-- 页面级提示：动作来自面板和名册两处（同员工管理页） -->
    <div v-if="formOk" class="alert alert-success py-2 small" role="alert">{{ formOk }}</div>
    <div v-if="formError" class="alert alert-danger py-2 small" role="alert">{{ formError }}</div>

    <!-- 建店 / 改店 -->
    <div v-if="panel" class="card shadow-sm mb-4">
      <div class="card-body p-4">
        <div class="d-flex justify-content-between align-items-center mb-3">
          <h5 class="card-title mb-0">
            <template v-if="panel === 'create'">新建门店</template>
            <template v-else>编辑门店 · {{ target.name }}</template>
          </h5>
          <button class="btn btn-outline-secondary btn-sm" @click="closePanel">收起</button>
        </div>

        <div class="row g-3">
          <div class="col-md-3">
            <label class="form-label">门店名称</label>
            <input v-model="form.name" class="form-control" placeholder="例如：云洗城南店" />
          </div>
          <div class="col-md-5">
            <label class="form-label">地址</label>
            <input v-model="form.address" class="form-control" placeholder="可含门牌号" />
          </div>
          <div class="col-md-4">
            <label class="form-label">电话</label>
            <input v-model="form.phone" class="form-control" placeholder="可留空" />
          </div>
        </div>
        <div class="mt-3 d-flex align-items-center gap-2">
          <button class="btn btn-primary" :disabled="busy" @click="panel === 'create' ? onCreate() : onUpdate()">
            {{ busy ? '提交中…' : (panel === 'create' ? '创建' : '保存') }}
          </button>
          <span class="form-text">营业状态不在这里改 —— 用名册里的「停业 / 营业」按钮</span>
        </div>
      </div>
    </div>

    <!-- 门店表 -->
    <div class="card shadow-sm">
      <div class="card-body p-4">
        <div class="d-flex justify-content-between align-items-center mb-2">
          <h5 class="card-title mb-0">门店列表（含已停业）</h5>
          <div class="d-flex gap-2">
            <button class="btn btn-primary btn-sm" @click="openCreate">＋ 新建门店</button>
            <button class="btn btn-outline-secondary btn-sm" @click="loadList">立即刷新</button>
          </div>
        </div>
        <div v-if="listError" class="alert alert-danger py-2 small" role="alert">{{ listError }}</div>

        <div class="table-responsive">
          <table class="table table-hover align-middle mb-0">
            <thead>
              <tr>
                <th>ID</th><th>名称</th><th>地址</th><th>电话</th><th>状态</th>
                <th class="text-end">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in list" :key="s.id">
                <td class="text-body-secondary">{{ s.id }}</td>
                <td>{{ s.name }}</td>
                <td>{{ s.address }}</td>
                <td>{{ s.phone || '—' }}</td>
                <td>
                  <span class="badge" :class="STORE_STATUS_BADGE[s.status]?.cls">
                    {{ STORE_STATUS_BADGE[s.status]?.text || s.status }}
                  </span>
                </td>
                <td class="text-end">
                  <button class="btn btn-outline-primary btn-sm me-1" @click="openEdit(s)">编辑</button>
                  <button class="btn btn-sm"
                          :class="s.status === 1 ? 'btn-outline-danger' : 'btn-success'"
                          :disabled="busy" @click="onToggleStatus(s)">
                    {{ s.status === 1 ? '停业' : '营业' }}
                  </button>
                </td>
              </tr>
              <tr v-if="!list.length && !listError">
                <td colspan="6" class="text-center text-body-secondary py-4">还没有门店</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="form-text mt-2">
          停业是软停业：门店被历史订单引用着，删了那些订单的 store_id 就指向虚空。
          停业的店在这里随时能重新开起来。
        </div>
      </div>
    </div>
  </div>
</template>
