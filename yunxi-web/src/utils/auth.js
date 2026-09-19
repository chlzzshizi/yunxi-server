// 会话工具：双人格 token 槽 + 本地 JWT 解码。
// 后端没有 /me 接口，前端要显示"我是谁"只能解 JWT payload（无害：token 只含公开 claims）。

// 两个槽位互不挤占：员工登录不会顶掉顾客登录
const KEY = {
  staff: 'yunxi_token_staff',
  customer: 'yunxi_token_customer',
}

/** base64url → UTF-8 字符串（payload 可能含中文 username，不能只用 atob）
 *  JWT 的 base64url 把 + → -、/ → _，且没有补齐用的 = */
function decodeBase64Url(str) {
  const b64 = str.replace(/-/g, '+').replace(/_/g, '/')
    + '='.repeat((4 - (str.length % 4)) % 4)
  const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
  return new TextDecoder().decode(bytes)
}

/** 解 JWT 的 payload 段（header.payload.signature 的第二段）；解不开就抛错 */
export function decodeToken(token) {
  const parts = token.split('.')
  if (parts.length !== 3) throw new Error('不是合法的 JWT')
  return JSON.parse(decodeBase64Url(parts[1]))
}

/** 登录成功后保存会话：token + 解码后的 claims 快照（快照避免每次读都解一遍）。
 *  **返回**存进去的那份会话 —— 登录页要拿 claims 去问"该落到哪一页"（见 router 的 homeFor），
 *  让它自己再解一次 token 就多了一份会跟这里走散的副本 */
export function saveSession(persona, token) {
  const claims = decodeToken(token) // 解不开说明后端签了坏 token，尽早暴露
  const session = { token, claims }
  localStorage.setItem(KEY[persona], JSON.stringify(session))
  return session
}

/** 读会话；校验 ①claims.type 与槽位一致 ②未过期（exp 单位是秒）。
 *  校验不过就当不存在并清掉（拿垃圾 token 也会被守卫拦在门外） */
export function loadSession(persona) {
  try {
    const raw = localStorage.getItem(KEY[persona])
    if (!raw) return null
    const { token, claims } = JSON.parse(raw)
    if (!claims || claims.type !== persona) { clearSession(persona); return null }
    if (claims.exp && claims.exp * 1000 <= Date.now()) { clearSession(persona); return null }
    return { token, claims }
  } catch {
    clearSession(persona)
    return null
  }
}

export function clearSession(persona) {
  localStorage.removeItem(KEY[persona])
}

// ── 员工角色 ──
// 码与后端 StaffRole 同一个表（0=ADMIN 管理员 / 1=MANAGER 店长），**只读不写死判断**。
// 前端读 role 只为一件事：登录后落到哪一页（router.homeFor）。
// **不用它藏按钮** —— 真拦人靠后端（JwtInterceptor.checkRoleGate），
// 前端藏了也只是"少点一次"，而一旦口径对不上就会变成"店长看不到自己的功能"。
export const STAFF_ROLE_ADMIN = 0

/** 这个会话是不是管理员？null / 字段缺失一律当"不是"（失败侧保守：
 *  宁可把管理员当店长，也别把店长当管理员放进管理页） */
export function isAdminSession(session) {
  return session?.claims?.role === STAFF_ROLE_ADMIN
}
