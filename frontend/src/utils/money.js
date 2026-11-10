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
