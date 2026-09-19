// 员工管理接口 —— 六个端点都在 /api/staff 前缀下。
//
// 这个前缀被 JwtInterceptor **整段**把守：只有管理员（role=ADMIN）能过，
// 店长连读都不行。这里 persona 一律 'staff' 说的是"用员工槽位的 token"，
// 能不能过由后端按 JWT 里的 role 判 —— 前端不预先猜。
//
// 全是管理员的动作，没有一个是店长日常，所以这些函数只在 /staff/admin/staff 用。

import { request } from './request'

/** 员工名册（**含停用**的）。返回 StaffView[]：
 *  { id, username, name, role, storeId, storeName, phone, status }
 *  · role 是**名字**（'ADMIN' / 'MANAGER'）不是数字 —— 返回体口径，前端不用回去翻表
 *  · storeName 是后端拼好的：列表要显示的"张店长 · 云洗中央门店"不用前端自己映射
 *    （员工没归店或门店已删时为 null，渲染要兜底） */
export function listStaff() {
  return request('/api/staff', { persona: 'staff' })
}

/** 单个员工 —— 编辑面板回显用 */
export function getStaff(id) {
  return request(`/api/staff/${id}`, { persona: 'staff' })
}

/** 新建：{ username, password, name, role, storeId, phone }
 *  · role 传**数字**（请求参数口径）：0=管理员 / 1=店长
 *  · role=0 时 storeId 必须是 null（后端强制，StaffAdminAppService:130）
 *  · **不传 status**：新建恒为启用（后端写死 1，没有"建出来就是停用的员工"） */
export function createStaff(body) {
  return request('/api/staff', { method: 'POST', body, persona: 'staff' })
}

/** 改资料：{ name, role, storeId, phone } —— **改不了用户名**
 *  （后端 UpdateStaffRequest 里压根没有这个字段）。
 *  ⚠️ role 或 storeId 真变了 → 后端**作废该员工手上所有 token**（他被踢下线，
 *  下次请求 401）—— 页面要提示，否则会被当成"改个资料把系统改坏了" */
export function updateStaff(id, body) {
  return request(`/api/staff/${id}`, { method: 'PUT', body, persona: 'staff' })
}

/** 重置密码：{ password } —— 是 **PUT** 不是 POST（`PUT /api/staff/{id}/password`）。
 *  同样会作废他手上的 token */
export function resetStaffPassword(id, password) {
  return request(`/api/staff/${id}/password`, {
    method: 'PUT',
    body: { password },
    persona: 'staff',
  })
}

/** 启用(1) / 停用(0)。停用会作废他的 token —— 下一刻他就进不来了 */
export function updateStaffStatus(id, status) {
  return request(`/api/staff/${id}/status`, {
    method: 'PUT',
    body: { status },
    persona: 'staff',
  })
}
