// 定价域接口 —— 分类 / 洗涤方式 / 价目表。
//
// 读的三个端点只要"任意有效 token"（价目表是公开信息，顾客看不到价就没法下单）；
// 写（PUT）只认店长 —— 管理员由后端 JwtInterceptor 的角色闸门挡在外面，
// 前端不用再判断角色（真判断了也是错的，见下）。
//
// ⚠️ 别在前端按"是不是管理员"来隐藏改价按钮：JWT 里没有 role 之外的店长信息，
//    而且权限的**唯一**真相在后端。前端做的只是"让店长少点一次"，
//    真拦人靠后端 403。

import { request } from './request'

/** 分类（含一级与叶子，带 parentId —— 前端自己组树） */
export function listCategories(persona) {
  return request('/api/categories', { persona })
}

/** 洗涤方式（固定 3 种：普洗 / 精洗 / 单熨） */
export function listWashTypes(persona) {
  return request('/api/wash-types', { persona })
}

/** 价目表：一行 = 一个（叶子分类 × 洗涤方式）。
 *  `supported: false` 表示这个组合不支持（如羽绒服没有普洗）→ 前端**置灰**，
 *  而不是过滤掉 —— 看不见的选项会让顾客以为"系统坏了"，灰的至少解释得清 */
export function listPrices(persona) {
  return request('/api/prices', { persona })
}

/**
 * 设置某分类的价格（仅店长）。
 * body：`{prices: [{washTypeId, price}]}`
 *
 * 精洗价由后端**派生**（普洗 + 20）：传普洗会自动把精洗一起算好；
 * 传精洗只有在"该分类没有普洗"时才允许（羽绒服那个逃生舱），否则 400。
 * 所以前端改价表单里，精洗一栏在读不到普洗时才是可编辑的。
 */
export function savePrices(categoryId, prices) {
  return request(`/api/prices/${categoryId}`, {
    method: 'PUT',
    body: { prices },
    persona: 'staff',
  })
}
