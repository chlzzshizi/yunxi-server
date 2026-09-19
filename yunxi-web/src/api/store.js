// 门店接口 —— 一个**混合前缀**：
//   GET  /api/stores       只返回**营业中**的（后端 SQL 带 WHERE status = 1）→ 选店下拉框
//   GET  /api/stores/all   **含停业**的管理列表                              → 管理页
//   POST / PUT             写接口，**只认管理员**（店长 403、顾客 401）
import { request } from './request'

/** 营业中的门店列表 —— 员工建单页与顾客下单页共用的下拉框。
 *  返回 StoreView[]，**没有 status 字段**：能拿到就是在营业，
 *  "哪些店能选"的判断后端 SQL 已经干完了，前端不需要再 `if (status === 1)` */
export function listStores(persona) {
  return request('/api/stores', { persona })
}

/** **含停业**的门店列表 —— 管理页用；员工编辑面板的"所属门店"下拉也用它
 *  （停业门店里的员工不该在名册里变成一个没有门店名的人）。
 *  返回 StoreAdminView[]：比上面多一个 status（0=停业 / 1=营业） */
export function listAllStores(persona) {
  return request('/api/stores/all', { persona })
}

/** 建店 { name, address, phone } —— 新建一律**营业**
 *  （没有"建出来就是停业的店"这种需求，要停再调一次 status） */
export function createStore(body) {
  return request('/api/stores', { method: 'POST', body, persona: 'staff' })
}

/** 改店资料 { name, address, phone } —— 改不了营业状态，那是下面那个 */
export function updateStore(id, body) {
  return request(`/api/stores/${id}`, { method: 'PUT', body, persona: 'staff' })
}

/** 营业(1) / 停业(0)。停业**立刻生效**：顾客的选店列表里马上看不到这家店。
 *  但店里员工的 token **不受影响**（不踢人 —— 和改员工不一样，那边一改就作废） */
export function updateStoreStatus(id, status) {
  return request(`/api/stores/${id}/status`, {
    method: 'PUT',
    body: { status },
    persona: 'staff',
  })
}
