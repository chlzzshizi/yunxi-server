// 券接口 —— 列表全量可见（无 grab 标记，抢没抢过由前端按"抢成功/被拒"记忆），
// 发券要员工 token，抢券要顾客 token

import { request } from './request'

/** 券列表（任何登录态都能看；返回 CouponPO 数组） */
export function listCoupons(persona) {
  return request('/api/coupons', { persona })
}

/** 店长发券：{name, discount, totalStock, startTime, endTime} */
export function createCoupon(body) {
  return request('/api/coupons', {
    method: 'POST',
    body,
    persona: 'staff',
  })
}

/** 顾客抢券：成功 data 是后端文案，如 "抢到了！折扣：0.50" */
export function grabCoupon(couponId) {
  return request(`/api/coupons/${couponId}/grab`, {
    method: 'POST',
    persona: 'customer',
  })
}

/**
 * 我的券 —— 顾客侧下单页选券用。
 * @returns [{grabId, couponId, name, discount, startTime, endTime, grabTime, expired}]
 *
 * 只含**未使用**的券；过期的也返回，带 `expired: true` —— 前端**置灰**而不是
 * 让它凭空消失。"我抢的券去哪了"比"这里本来就没有东西"好回答得多。
 */
export function listMyCoupons() {
  return request('/api/coupons/mine', { persona: 'customer' })
}
