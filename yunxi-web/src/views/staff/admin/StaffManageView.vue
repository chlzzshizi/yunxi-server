<script setup>
// 员工管理（管理员专区）：名册 + 新建 / 编辑 / 重置密码 + 启用停用。
//
// 进得来只有一种人：管理员。路由的 meta.adminOnly 拦一道（店长点进来只会看到一屏 403），
// 真正的闸门是后端 —— /api/staff 整个前缀只认 ADMIN，店长连读都不行。
//
// 三个面板**同一时刻只开一个**：新建与编辑的字段高度重叠，两个并排开着，
// 很容易在 A 面板填完、然后点了 B 面板的提交按钮。
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { personaPaths } from '../../../router'
import {
  listStaff, createStaff, updateStaff, resetStaffPassword, updateStaffStatus,
} from '../../../api/staff'
import { listAllStores } from '../../../api/store'
import { staffLogout } from '../../../api/auth'
import { loadSession, clearSession } from '../../../utils/auth'
import { STAFF_ROLE_BADGE, STAFF_ROLE_OPTIONS, STAFF_STATUS_BADGE } from '../../../utils/format'
import {
  utf8Bytes, tooLong, passwordTooLongMessage,
  PASSWORD_MAX_BYTES, USERNAME_MAX, STAFF_NAME_MAX, STAFF_PHONE_MAX,
} from '../../../utils/validate'

const router = useRouter()
const session = loadSession('staff')
const who = session ? session.claims.username : ''

// ── 名册 ──
const list = ref([])
const listError = ref('')

async function loadList() {
  try {
    list.value = await listStaff()
    listError.value = ''
  } catch (e) {
    listError.value = e.message // 401 由 request.js 清会话并跳登录；403/网络就地提示
  }
}

// ── 门店下拉：用 /all（**含停业**）──
// 用 /api/stores 的话，一个已停业门店的员工在编辑面板里就没有可选项，
// 一保存就把他的归属门店抹掉了 —— 而那正是他最需要被改的时候
const stores = ref([])

async function loadStores() {
  try {
    stores.value = await listAllStores('staff')
  } catch (e) {
    listError.value = e.message
  }
}

// ── 面板：'' | 'create' | 'edit' | 'reset' ──
const panel = ref('')
const target = ref(null) // 编辑 / 重置密码的对象（名册里那一行的快照）
const formError = ref('')
const formOk = ref('')
const busy = ref(false)

// 三个面板共用一个表单对象：字段是包含关系（编辑比新建少用户名/密码），
// 各开一份就得在三处之间同步同一批值
const form = ref(blankForm())

function blankForm() {
  return { username: '', password: '', name: '', role: 1, storeId: '', phone: '' }
}

function openCreate() {
  form.value = blankForm()
  target.value = null
  formError.value = ''
  formOk.value = ''
  panel.value = 'create'
}

function openEdit(row) {
  form.value = {
    username: row.username, // 只读展示用
    password: '',
    name: row.name || '',
    // 返回体里的 role 是**名字**（'ADMIN'/'MANAGER'），下拉要的是**数字** ——
    // 同一个概念两种形态，写反了不报错，只会静悄悄把角色改成另一个
    role: row.role === 'ADMIN' ? 0 : 1,
    storeId: row.storeId == null ? '' : row.storeId,
    phone: row.phone || '',
  }
  target.value = row
  formError.value = ''
  formOk.value = ''
  panel.value = 'edit'
}

function openReset(row) {
  form.value = { ...blankForm(), username: row.username }
  target.value = row
  formError.value = ''
  formOk.value = ''
  panel.value = 'reset'
}

function closePanel() {
  panel.value = ''
  target.value = null
  formError.value = ''
  formOk.value = ''
}

// ── 校验：前端粗拦是为了少打一次注定失败的后端；后端才是权威 ──
// 文案**逐字照抄后端**（Staff.java / StaffAdminAppService），不在前端另造句子 ——
// 两句话一旦不一样，用户就看到了两个口径

/** 长度：用**码点**数（一个 emoji 算 1 个字），和后端 codePointCount 同一把尺子 */
function lengthError() {
  const f = form.value
  if (tooLong(f.username.trim(), USERNAME_MAX)) return `用户名不能超过 ${USERNAME_MAX} 个字`
  if (tooLong(f.name.trim(), STAFF_NAME_MAX)) return `姓名不能超过 ${STAFF_NAME_MAX} 个字`
  // 抬头是「手机号」不是「电话」—— 门店那边才叫电话，两边文案不同
  if (tooLong(f.phone.trim(), STAFF_PHONE_MAX)) return `手机号不能超过 ${STAFF_PHONE_MAX} 个字`
  return ''
}

/** 密码用**另一把尺子**：UTF-8 字节（BCrypt 的输入上限），不是字数 */
function passwordError() {
  if (!form.value.password) return '密码不能为空'
  if (utf8Bytes(form.value.password) > PASSWORD_MAX_BYTES) return passwordTooLongMessage()
  return ''
}

/** 提交时的门店：管理员 → null（后端也会强制忽略，不留一个会误导人的值）；
 *  下拉的 '' 哨兵 → null（**不归店是合法状态**，管理员必然如此、店长也可以）。
 *  注意不能把 '' 直接发出去，也不能 Number('') —— 那是 0，会被当成门店 id=0 */
function storeIdForSubmit() {
  if (form.value.role === 0) return null
  return form.value.storeId === '' ? null : Number(form.value.storeId)
}

async function onCreate() {
  formError.value = ''
  formOk.value = ''
  const f = form.value
  if (!f.username.trim()) return (formError.value = '用户名不能为空')
  const pwdErr = passwordError()
  if (pwdErr) return (formError.value = pwdErr)
  if (!f.name.trim()) return (formError.value = '姓名不能为空')
  const lenErr = lengthError()
  if (lenErr) return (formError.value = lenErr)

  busy.value = true
  try {
    await createStaff({
      username: f.username.trim(),
      password: f.password,
      name: f.name.trim(),
      role: f.role, // 数字：0=管理员 1=店长（请求参数口径）
      storeId: storeIdForSubmit(),
      phone: f.phone.trim() || null, // 空白存 NULL，不存空串
    })
    formOk.value = `已创建「${f.name.trim()}」（新建一律是启用状态）`
    form.value = blankForm() // 清空，方便接着建下一个
    loadList()
  } catch (e) {
    formError.value = e.message // 400 用户名已存在 / 门店不存在，403 越权，0 网络
  } finally {
    busy.value = false
  }
}

async function onUpdate() {
  formError.value = ''
  formOk.value = ''
  const f = form.value
  if (!f.name.trim()) return (formError.value = '姓名不能为空')
  const lenErr = lengthError()
  if (lenErr) return (formError.value = lenErr)

  const roleName = f.role === 0 ? 'ADMIN' : 'MANAGER'
  const storeId = storeIdForSubmit()
  // 后端只在 role / storeId **真变了**时才作废 token（改个错别字不踢人）。
  // 这里先算一遍，好在成功提示里说清"他被下线了" —— 不说的话，
  // 用户发现同事突然登不上，会以为是这次改动改坏了
  const kicked = target.value.role !== roleName || (target.value.storeId ?? null) !== storeId

  busy.value = true
  try {
    await updateStaff(target.value.id, {
      name: f.name.trim(),
      role: f.role,
      storeId,
      phone: f.phone.trim() || null,
    })
    formOk.value = kicked
      ? '已保存；角色或门店变了，他手上的登录已作废（需重新登录）'
      : '已保存'
    loadList()
  } catch (e) {
    formError.value = e.message
  } finally {
    busy.value = false
  }
}

async function onResetPassword() {
  formError.value = ''
  formOk.value = ''
  const pwdErr = passwordError()
  if (pwdErr) return (formError.value = pwdErr)

  busy.value = true
  try {
    await resetStaffPassword(target.value.id, form.value.password)
    formOk.value = '密码已重置；他手上的登录已作废（需用新密码重新登录）'
    form.value.password = ''
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
    await updateStaffStatus(row.id, next)
    formOk.value = next === 0
      ? `已停用「${row.name}」—— 他手上的登录立刻失效`
      : `已启用「${row.name}」—— 停用期间签出的票不会复活，他要用新登录换一张`
    loadList()
  } catch (e) {
    formError.value = e.message
  } finally {
    busy.value = false
  }
}

async function onLogout() {
  await staffLogout() // 先让后端把 token 拉黑，再清本地（顺序反了会留下一个没拉黑的票）
  clearSession('staff')
  router.push(personaPaths.staff.login)
}

onMounted(() => {
  loadList()
  loadStores()
})
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h4 class="mb-0">员工管理</h4>
      <div class="d-flex gap-2 align-items-center">
        <span class="text-body-secondary small">欢迎，{{ who }}</span>
        <router-link to="/staff/admin/stores" class="btn btn-outline-primary btn-sm">门店管理</router-link>
        <router-link to="/" class="btn btn-outline-primary btn-sm">首页</router-link>
        <button class="btn btn-outline-danger btn-sm" @click="onLogout">退出</button>
      </div>
    </div>

    <!-- 页面级提示：动作来自两个地方（面板里的表单、名册里的启停按钮），
         所以放在页面这一层。放进面板里的话，名册上点"停用"的结果就没人看得见 -->
    <div v-if="formOk" class="alert alert-success py-2 small" role="alert">{{ formOk }}</div>
    <div v-if="formError" class="alert alert-danger py-2 small" role="alert">{{ formError }}</div>

    <!-- 表单面板：新建 / 编辑 / 重置密码，同一时刻只开一个 -->
    <div v-if="panel" class="card shadow-sm mb-4">
      <div class="card-body p-4">
        <div class="d-flex justify-content-between align-items-center mb-3">
          <h5 class="card-title mb-0">
            <template v-if="panel === 'create'">新建员工</template>
            <template v-else-if="panel === 'edit'">编辑员工 · {{ target.name }}</template>
            <template v-else>重置密码 · {{ target.name }}（{{ target.username }}）</template>
          </h5>
          <button class="btn btn-outline-secondary btn-sm" @click="closePanel">收起</button>
        </div>

        <!-- ── 新建 ── -->
        <template v-if="panel === 'create'">
          <div class="row g-3">
            <div class="col-md-3">
              <label class="form-label">用户名</label>
              <input v-model="form.username" class="form-control" placeholder="登录用的账号" />
              <div class="form-text">建了就不能改</div>
            </div>
            <div class="col-md-3">
              <label class="form-label">密码</label>
              <input v-model="form.password" type="password" class="form-control"
                     autocomplete="new-password" />
              <div class="form-text">最多 72 字节，一个汉字算 3 字节</div>
            </div>
            <div class="col-md-2">
              <label class="form-label">姓名</label>
              <input v-model="form.name" class="form-control" />
            </div>
            <div class="col-md-2">
              <label class="form-label">角色</label>
              <select v-model="form.role" class="form-select">
                <option v-for="o in STAFF_ROLE_OPTIONS" :key="o.value" :value="o.value">{{ o.text }}</option>
              </select>
              <div class="form-text">管理员只管人与店</div>
            </div>
            <div class="col-md-2">
              <label class="form-label">归属门店</label>
              <select v-model="form.storeId" class="form-select" :disabled="form.role === 0">
                <option value="">（不归店）</option>
                <option v-for="s in stores" :key="s.id" :value="s.id">
                  {{ s.name }}{{ s.status === 0 ? '（停业）' : '' }}
                </option>
              </select>
              <div class="form-text">管理员必为空</div>
            </div>
          </div>
          <div class="row g-3 mt-0">
            <div class="col-md-3">
              <label class="form-label">手机号</label>
              <input v-model="form.phone" class="form-control" placeholder="可留空" />
            </div>
          </div>
          <div class="mt-3">
            <button class="btn btn-primary" :disabled="busy" @click="onCreate">
              {{ busy ? '提交中…' : '创建' }}
            </button>
            <span class="form-text ms-2">新建一律是启用状态</span>
          </div>
        </template>

        <!-- ── 编辑 ── -->
        <template v-else-if="panel === 'edit'">
          <div class="row g-3">
            <div class="col-md-3">
              <label class="form-label">用户名</label>
              <input :value="form.username" class="form-control" disabled />
              <div class="form-text">用户名建了就不能改</div>
            </div>
            <div class="col-md-3">
              <label class="form-label">姓名</label>
              <input v-model="form.name" class="form-control" />
            </div>
            <div class="col-md-2">
              <label class="form-label">角色</label>
              <select v-model="form.role" class="form-select">
                <option v-for="o in STAFF_ROLE_OPTIONS" :key="o.value" :value="o.value">{{ o.text }}</option>
              </select>
            </div>
            <div class="col-md-2">
              <label class="form-label">归属门店</label>
              <select v-model="form.storeId" class="form-select" :disabled="form.role === 0">
                <option value="">（不归店）</option>
                <option v-for="s in stores" :key="s.id" :value="s.id">
                  {{ s.name }}{{ s.status === 0 ? '（停业）' : '' }}
                </option>
              </select>
            </div>
            <div class="col-md-2">
              <label class="form-label">手机号</label>
              <input v-model="form.phone" class="form-control" placeholder="可留空" />
            </div>
          </div>
          <div class="mt-3 d-flex align-items-center gap-2">
            <button class="btn btn-primary" :disabled="busy" @click="onUpdate">
              {{ busy ? '提交中…' : '保存' }}
            </button>
            <span class="form-text">改角色或门店 → 他手上的登录立刻作废；改姓名/手机号不会</span>
          </div>
        </template>

        <!-- ── 重置密码 ── -->
        <template v-else>
          <div class="row g-3">
            <div class="col-md-4">
              <label class="form-label">新密码</label>
              <input v-model="form.password" type="password" class="form-control"
                     autocomplete="new-password" />
              <div class="form-text">
                最多 72 字节，一个汉字算 3 字节；保存后他手上的登录立刻作废
              </div>
            </div>
          </div>
          <div class="mt-3">
            <button class="btn btn-primary" :disabled="busy" @click="onResetPassword">
              {{ busy ? '提交中…' : '重置密码' }}
            </button>
          </div>
        </template>
      </div>
    </div>

    <!-- 名册 -->
    <div class="card shadow-sm">
      <div class="card-body p-4">
        <div class="d-flex justify-content-between align-items-center mb-2">
          <h5 class="card-title mb-0">员工名册（含已停用）</h5>
          <div class="d-flex gap-2">
            <button class="btn btn-primary btn-sm" @click="openCreate">＋ 新建员工</button>
            <button class="btn btn-outline-secondary btn-sm" @click="loadList">立即刷新</button>
          </div>
        </div>
        <div v-if="listError" class="alert alert-danger py-2 small" role="alert">{{ listError }}</div>

        <div class="table-responsive">
          <table class="table table-hover align-middle mb-0">
            <thead>
              <tr>
                <th>ID</th><th>用户名</th><th>姓名</th><th>角色</th>
                <th>归属门店</th><th>手机号</th><th>状态</th>
                <th class="text-end">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in list" :key="s.id">
                <td class="text-body-secondary">{{ s.id }}</td>
                <td>{{ s.username }}</td>
                <td>{{ s.name }}</td>
                <td>
                  <span class="badge" :class="STAFF_ROLE_BADGE[s.role]?.cls">
                    {{ STAFF_ROLE_BADGE[s.role]?.text || s.role }}
                  </span>
                </td>
                <!-- 门店名由后端拼好；拼不到时节 id —— 门店被删(#3) 和没归店(—) 是两回事 -->
                <td>{{ s.storeId == null ? '—' : (s.storeName || '#' + s.storeId) }}</td>
                <td>{{ s.phone || '—' }}</td>
                <td>
                  <span class="badge" :class="STAFF_STATUS_BADGE[s.status]?.cls">
                    {{ STAFF_STATUS_BADGE[s.status]?.text || s.status }}
                  </span>
                </td>
                <td class="text-end">
                  <button class="btn btn-outline-primary btn-sm me-1" @click="openEdit(s)">编辑</button>
                  <button class="btn btn-outline-secondary btn-sm me-1" @click="openReset(s)">重置密码</button>
                  <button class="btn btn-sm"
                          :class="s.status === 1 ? 'btn-outline-danger' : 'btn-success'"
                          :disabled="busy" @click="onToggleStatus(s)">
                    {{ s.status === 1 ? '停用' : '启用' }}
                  </button>
                </td>
              </tr>
              <tr v-if="!list.length && !listError">
                <td colspan="8" class="text-center text-body-secondary py-4">没有员工数据</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="form-text mt-2">
          提示：后端有意<b>不拦自锁</b>（2026-09-18 拍板）—— 可以停用或降级自己，
          真把最后一个管理员锁死了，只能直连数据库改回来。
        </div>
      </div>
    </div>
  </div>
</template>
