// 展示格式化工具：后端给的是 ISO local 字符串 / 数值折扣 / **枚举名**，页面用

/** "2026-09-09T10:00:00" → "2026-09-09 10:00"（只做字符串切片，不经过 Date，
 *  避免时区/秒级格式的坑；后端 LocalDateTime 无时区，字符串就是本地时间） */
export function fmtTime(t) {
  if (!t) return '-'
  return t.replace('T', ' ').slice(0, 16)
}

/** 折扣率转中文：0.5 → "5折"，0.85 → "8.5折"（BigDecimal 传过来是数字 0.5） */
export function fmtDiscount(d) {
  const n = Number(d)
  if (!Number.isFinite(n) || n <= 0) return '-'
  return Number((n * 10).toFixed(1)) + '折'
}

/** 金额：后端 BigDecimal 传过来是数字（15.00 可能序列化成 15.0 或 15），
 *  统一成两位小数。判 null/undefined 是为了区分"0 元"和"没这个字段" */
export function fmtMoney(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '-'
  return '¥' + n.toFixed(2)
}

/** 状态码 → Bootstrap 徽章样式 + 中文文案（1 未开始 2 进行中 3 已结束）
 *  —— 这是**券**的状态，别和下面的订单状态混了 */
export const STATUS_BADGE = {
  1: { cls: 'text-bg-warning', text: '未开始' },
  2: { cls: 'text-bg-success', text: '进行中' },
  3: { cls: 'text-bg-secondary', text: '已结束' },
}

// ──────────────── 订单域 ────────────────

/** 订单状态 → 徽章 + 文案。
 *
 *  **键是枚举名字符串，不是数字**：`OrderView.status` 是 `OrderStatus` 枚举，
 *  项目里没有配 ObjectMapper / @JsonValue，所以 Jackson 按默认行为序列化成**名字**
 *  （"PENDING_PAY"）。而同一个状态的**请求参数**（`?status=2`）走的是
 *  `OrderStatus.fromCode(int)`，要的是**数字**。同一个概念两种形态，写反了不会报错，
 *  只会让徽章全是灰的 —— 这是本项目最容易踩的一个坑。
 *
 *  码值连号 1~7（2026-09-11 口径），门店单 1→2→3→4→5→7、网单 1→2→3→4→6→7 */
export const ORDER_STATUS_BADGE = {
  PENDING_PAY: { cls: 'text-bg-warning', text: '待支付', code: 1 },
  PAID: { cls: 'text-bg-info', text: '已支付', code: 2 },
  WASHING: { cls: 'text-bg-primary', text: '洗涤中', code: 3 },
  PENDING_DELIVERY: { cls: 'text-bg-secondary', text: '待出厂', code: 4 },
  PENDING_PICKUP: { cls: 'text-bg-secondary', text: '待取件', code: 5 },
  DELIVERING: { cls: 'text-bg-secondary', text: '派送中', code: 6 },
  COMPLETED: { cls: 'text-bg-success', text: '已完成', code: 7 },
}

/** 状态筛选下拉用的有序列表（按码值排，不是按对象字面量的书写顺序——
 *  JS 对象虽然保序，但依赖它太脆；显式排一次） */
export const ORDER_STATUS_OPTIONS = Object.entries(ORDER_STATUS_BADGE)
  .map(([value, v]) => ({ value, code: v.code, text: v.text }))
  .sort((a, b) => a.code - b.code)

/** 订单来源 → 中文（枚举名 → 文案；请求体里要的是数字 1/2，见 SOURCE_CODE） */
export const SOURCE_TEXT = { STORE: '门店单', ONLINE: '网单' }

/** 来源在**请求体**里的形态：数字。建单时用，别把 'STORE' 传进去 */
export const SOURCE_CODE = { STORE: 1, ONLINE: 2 }

/** 支付方式 → 中文。**枚举名 → 文案**，只用于显示返回值（`order.payMethod` 是 "CASH" 这种名字）。
 *  要发请求请看 STAFF_PAY_OPTIONS / ONLINE_PAY_OPTIONS —— 那张表的 code 本身就是参数值 */
export const PAY_METHOD_TEXT = {
  CASH: '现金',
  WECHAT: '微信',
  ALIPAY: '支付宝',
  BALANCE: '余额',
}

/** 柜台能选的支付方式。`code` **直接就是要发出去的参数值**（`?payMethod=cash`），
 *  中间**不设**"把枚举名翻成小写码"的查表 —— 那种表的键一旦写错（用枚举名 CASH
 *  去查小写码 'cash'），查不到只会得到 undefined，然后静悄悄发一个
 *  `?payMethod=undefined` 出去：前端一路绿灯，到后端才 400 */
export const STAFF_PAY_OPTIONS = [
  { code: 'cash', text: '现金' },
  { code: 'wechat', text: '微信' },
  { code: 'alipay', text: '支付宝' },
]

/** 顾客在线支付能选的方式（后端只认这两个；现金/余额是柜台动作） */
export const ONLINE_PAY_OPTIONS = [
  { code: 'wechat', text: '微信支付' },
  { code: 'alipay', text: '支付宝' },
]
