// fetch 封装（不用 axios）：
// 1. 后端永远 HTTP 200 + 信封 {code, message, data} → 只按 body.code 分支
// 2. 会话 token 自动带上 Authorization: Bearer <token>
// 3. 401 分流：带 token 的请求 401 = 会话失效 → 清会话 + 跳登录页；
//    auth:false 的请求（登录/注册）401 = 密码错 → 抛给页面就地展示
// 4. 后端没起 / 返回乱码 → 统一转成可读错误，不白屏

export class ApiError extends Error {
  constructor(code, message) {
    super(message)
    this.code = code
  }
}

// 由 main.js 注入（避免 request ↔ router 循环 import）
let unauthorizedHandler = null
export function setUnauthorizedHandler(fn) { unauthorizedHandler = fn }

import { loadSession, clearSession } from '../utils/auth'

/**
 * @param path    '/api/...'
 * @param opts    { method, body, persona, auth }
 *   persona: 'staff' | 'customer' —— 取哪个槽位的 token
 *   auth:    false = 登录/注册这类免鉴权接口（401 是业务错误，不清会话不跳转）
 * @returns 成功时返回信封里的 data
 */
export async function request(path, { method = 'GET', body, persona, auth = true } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  let token = null
  if (auth) {
    const session = persona ? loadSession(persona) : null
    token = session ? session.token : null
    if (!session) throw new ApiError(401, '未登录') // 守卫拦过但再兜一层
  }
  if (token) headers.Authorization = `Bearer ${token}`

  let res
  try {
    res = await fetch(path, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    })
  } catch {
    throw new ApiError(0, '网络异常：请确认后端 8081 已启动')
  }

  let payload
  try {
    payload = await res.json()
  } catch {
    throw new ApiError(-1, '服务器返回了无法解析的内容')
  }

  if (payload.code === 401 && auth) {
    // 会话失效/过期/被拉黑：清掉本地会话，跳对应登录页
    if (persona) clearSession(persona)
    if (unauthorizedHandler) unauthorizedHandler(persona, payload.message)
  }
  if (payload.code !== 200) throw new ApiError(payload.code, payload.message)
  return payload.data
}
