// 顾客接口 —— 员工代客建档（/api/customers/lookup-or-create）
//
// 为什么是 POST 而不是 GET：它会写库。GET 是"安全方法"的承诺，而浏览器预取、
// 网关重试、用户狂点刷新 —— 任何一个都会凭空多出一堆顾客。

import { request } from './request'

/**
 * 手机号查顾客，没有就建档 —— 员工建门店单前先拿到 customerId。
 * @returns {customerId, name, phone, created}
 *   created=true 表示这次真的建了档（可以提示"已为顾客建档"），
 *   false 表示找到的是老顾客。**老顾客不会被改名**：柜台顺手打个错别字
 *   不该悄悄改档案（改名字是"顾客管理"的事，且只在档案里没名字时才补全）。
 *
 * 建档店取自员工 token，请求体里没有 storeId —— 别加，那是给
 * "把顾客挂到别的店名下"开口子。
 */
export function lookupOrCreateCustomer(phone, name) {
  return request('/api/customers/lookup-or-create', {
    method: 'POST',
    body: { phone, name },
    persona: 'staff',
  })
}

/** 顾客看自己的档案（个人中心）。登录只回一个 token，里面没有姓名，
 *  要显示"你好，张三"只能再查一次 */
export function getMyProfile() {
  return request('/api/customers/me', { persona: 'customer' })
}

/** 顾客改自己的姓名。customerId 只从 token 取 —— 请求体里压根没这个字段 */
export function updateMyProfile(name) {
  return request('/api/customers/me', {
    method: 'PUT',
    body: { name },
    persona: 'customer',
  })
}
