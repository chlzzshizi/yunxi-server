// 订单接口 —— 员工侧（店长 token）与顾客侧（顾客 token，限本人）共用一个前缀
//
// 三条容易写错的约定，每条都在下面的函数上再标一次：
//   1. 支付参数在 **query**，不在 body（`?payMethod=cash&amount=30.00`）
//   2. `source` 请求体里是**数字** 1/2，返回体里是**枚举名** 'STORE'/'ONLINE'
//   3. `status` 筛选参数是**数字**，返回体里是**枚举名**
// 支付参数还要 encodeURIComponent —— 现在的值（cash/wechat/alipay）都是纯 ASCII
// 不会有问题，但金额将来若出现 "1,000.00" 这类字符，不编码就会把 query 拆断

import { request } from './request'

/** 订单列表（分页 + 可选状态筛选）
 *  @param persona 'staff' | 'customer' —— 员工看全部、顾客只看自己的（后端按 token 定归属）
 *  @param status  数字码 1~7，或 null 表示不筛选
 *  例：GET /api/orders?status=2&page=1&pageSize=20 */
export function listOrders(persona, { status = null, page = 1, pageSize = 10 } = {}) {
  const qs = new URLSearchParams({ page, pageSize })
  if (status != null) qs.set('status', status) // 数字，不是枚举名
  return request(`/api/orders?${qs}`, { persona })
}

/** 订单详情（带归属校验：顾客只能看自己的，越权 403、不存在 404） */
export function getOrder(persona, id) {
  return request(`/api/orders/${id}`, { persona })
}

/**
 * 建单（两种来源同一个端点，字段按来源分流）。
 *
 * 门店单（source=1）：店长 token + `customerId`（先走 lookup-or-create 拿）
 *   —— 不需要 storeId，后端取 token 里的（门店单落在店长自己那家店）
 * 网单（source=2）：顾客 token + `deliveryAddress`（**必填**，域层校验）
 *   + `storeId`（**选填**，2026-09-13 口径：不选就传 null，后端存 NULL）
 *
 * 两种来源的 items 都不传单价：**价格由后端算**（请求体里根本没这个字段）。
 * 可选的 couponId 由调用方保证"是这张单顾客的券"，否则后端 400。
 */
export function createOrder(persona, body) {
  return request('/api/orders', { method: 'POST', body, persona })
}

/**
 * 员工收款（先付传全额，洗后付传 0）。
 * **amount 和 payMethod 都在 query 里**，body 是空的 —— 后端是 @RequestParam。
 * 顾客侧请用 onlinePay：那个没有 amount 参数，金额由后端从订单取。
 */
export function payOrder(id, payMethodCode, amount) {
  const qs = new URLSearchParams({
    payMethod: payMethodCode,
    amount: String(amount),
  })
  return request(`/api/orders/${id}/pay?${qs}`, { method: 'POST', persona: 'staff' })
}

/** 顾客在线支付（仅顾客、仅本人）。**没有 amount** —— 金额由后端从订单上取，
 *  顾客传不了也就篡改不了；只认 wechat / alipay */
export function onlinePay(id, payMethodCode) {
  const qs = new URLSearchParams({ payMethod: payMethodCode })
  return request(`/api/orders/${id}/online-pay?${qs}`, { method: 'POST', persona: 'customer' })
}

/** 状态推进一格（走 CAS，冲突 409）。目标状态由后端按当前状态 + 来源推出，传不了 */
export function advanceOrder(id) {
  return request(`/api/orders/${id}/next`, { method: 'POST', persona: 'staff' })
}

/** 洗后付结账，直达终态 7（限门店单 status=5 / 网单 status=6） */
export function finalPayOrder(id, payMethodCode) {
  const qs = new URLSearchParams({ payMethod: payMethodCode })
  return request(`/api/orders/${id}/final-pay?${qs}`, { method: 'POST', persona: 'staff' })
}

/** 录入快递单号（仅网单、仅 status=6 派送中，走同一条 CAS，**不推进状态**） */
export function fillExpressNo(id, expressNo) {
  const qs = new URLSearchParams({ expressNo })
  return request(`/api/orders/${id}/express?${qs}`, { method: 'POST', persona: 'staff' })
}
