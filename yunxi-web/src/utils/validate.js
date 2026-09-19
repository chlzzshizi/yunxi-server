// 长度与密码的前端粗校验 —— **尺子必须和后端一致**。
//
// 后端有两把尺子（Staff.java / Store.java / StaffAdminAppService）：
//   · 名字类：**码点**（codePointCount，一个 emoji = 1 个字）
//   · 密码：  **UTF-8 字节**（PASSWORD_MAX_BYTES = 72，BCrypt 的输入上限）
//
// 数错了不会报错，只会两边不一致：用 length() 数 emoji 名字会"前端说超长、后端说没事"；
// 用字数去卡密码则会"前端放行、后端 400"。
//
// 前端拦一遍只是为了**少打一次注定失败的后端**，后端才是权威 —— 两边都拦，谁也不替谁。

/** UTF-8 字节数：一个汉字 3 字节、一个 emoji 4 字节，和 MySQL / BCrypt 数的一样。
 *  用 TextEncoder 而不是 encodeURIComponent 那套技巧：后者碰上不成对的代理对会抛 */
export function utf8Bytes(s) {
  return new TextEncoder().encode(s ?? '').length
}

/** "字数" = 码点数。`[...s].length` 结果一样，写成 Array.from 更好读 */
export function charCount(s) {
  return Array.from(s ?? '').length
}

/** 超长了没？只回布尔 —— 文案由调用方直接用**后端那句**，
 *  不在前端另造一份（两句话一旦不一样，用户就会看到两个口径） */
export function tooLong(s, max) {
  return charCount(s) > max
}

// ── 后端那几个数字与句子，前端照抄一份 ──
// 抄的是**值**，不是判断逻辑：后端改了上限，这里跟着改一处即可。

export const PASSWORD_MAX_BYTES = 72
export const USERNAME_MAX = 30 // Staff.USERNAME_MAX_LENGTH
export const STAFF_NAME_MAX = 20 // Staff.NAME_MAX_LENGTH
export const STAFF_PHONE_MAX = 20 // Staff.PHONE_MAX_LENGTH（抬头是"手机号"）
export const STORE_NAME_MAX = 50 // Store.NAME_MAX_LENGTH
export const STORE_ADDRESS_MAX = 200 // Store.ADDRESS_MAX_LENGTH
export const STORE_PHONE_MAX = 20 // Store.PHONE_MAX_LENGTH（抬头是"电话"）
export const CUSTOMER_NAME_MAX = 20 // Customer.NAME_MAX_LENGTH

/** 与后端逐字相同（StaffAdminAppService 里那段拼接） */
export function passwordTooLongMessage() {
  return `密码过长（最多 ${PASSWORD_MAX_BYTES} 字节，一个汉字算 3 字节）`
}
