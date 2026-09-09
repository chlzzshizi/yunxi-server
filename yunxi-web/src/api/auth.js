// 认证接口（/api/auth/** 免鉴权）—— 401/400 都是业务错误，auth:false 就地展示

import { request } from './request'
import { loadSession } from '../utils/auth'

/** 员工登录：{username, password} → token */
export async function staffLogin(username, password) {
  const data = await request('/api/auth/staff/login', {
    method: 'POST',
    body: { username, password },
    auth: false,
  })
  return data.token
}

/** 员工登出：接口在免鉴权放行区（拦截器不管它），但后端要求带 Authorization 头
 *  （controller 用 @RequestHeader 读）。所以直连 fetch 手动带头 ——
 *  不走 request() 封装，避免 auth 语义打架；失败只记日志，不阻塞本地清理 */
export async function staffLogout() {
  const session = loadSession('staff')
  if (!session) return // 没会话就不用调后端了
  try {
    await fetch('/api/auth/staff/logout', {
      method: 'POST',
      headers: { Authorization: `Bearer ${session.token}` },
    })
  } catch {
    // 黑名单清不掉最多让 token 多活 24 小时，本地已登出，可接受
  }
}

/** 顾客注册（注册即登录）：{name, phone, password} → token */
export async function customerRegister({ name, phone, password }) {
  const data = await request('/api/auth/customer/register', {
    method: 'POST',
    body: { name, phone, password },
    auth: false,
  })
  return data.token
}

/** 顾客登录：{phone, password} → token */
export async function customerLogin(phone, password) {
  const data = await request('/api/auth/customer/login', {
    method: 'POST',
    body: { phone, password },
    auth: false,
  })
  return data.token
}
