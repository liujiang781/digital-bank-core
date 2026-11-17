// 金额工具（BR-01：金额是两位小数的字符串，不用浮点运算）
//
// 后端接口的金额一律传字符串："123.45"。
// 前端展示统一 ¥1,234.56。

/**
 * 校验金额输入：非负、最多两位小数。
 * 合法返回 true，非法返回 false。
 * @param {string} value
 * @returns {boolean}
 */
export function isValidAmount(value) {
  if (typeof value !== 'string') return false
  return /^\d+(\.\d{1,2})?$/.test(value.trim())
}

/**
 * 把用户输入规范化为后端要求的两位小数字符串。
 * 后端正则 [0-9]{1,16}\.[0-9]{2} 强制两位小数，所以 "50" 必须转成 "50.00"。
 * "50" → "50.00"，"50.5" → "50.50"，"50.00" → "50.00"。
 * @param {string} value
 * @returns {string}
 */
export function normalizeAmount(value) {
  const s = String(value).trim()
  if (/^\d{1,16}\.\d{2}$/.test(s)) return s
  if (/^\d{1,16}(\.\d{0,2})?$/.test(s)) {
    const [whole, decimal = ''] = s.split('.')
    return whole + '.' + (decimal + '00').slice(0, 2)
  }
  return s
}

/**
 * 展示格式：¥1,234.56。千位加分隔符，强制两位小数。
 * @param {string|number} amount
 * @returns {string}
 */
export function formatAmount(amount) {
  const n = Number(amount)
  if (!Number.isFinite(n)) return String(amount)
  return '¥' + n.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })
}
