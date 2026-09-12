// 门店接口 —— 只返回**营业中**的门店（后端 SQL 带 WHERE status = 1，停业的店不下发）
import { request } from './request'

/** 营业中的门店列表 —— 员工建单页与顾客下单页共用的下拉框 */
export function listStores(persona) {
  return request('/api/stores', { persona })
}
