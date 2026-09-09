// 展示格式化工具：后端给的是 ISO local 字符串 / 数值折扣，页面用

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

/** 状态码 → Bootstrap 徽章样式 + 中文文案（1 未开始 2 进行中 3 已结束） */
export const STATUS_BADGE = {
  1: { cls: 'text-bg-warning', text: '未开始' },
  2: { cls: 'text-bg-success', text: '进行中' },
  3: { cls: 'text-bg-secondary', text: '已结束' },
}
