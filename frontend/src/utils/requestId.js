// requestId 幂等工具（BR-11/12，接口契约 2.4 节）
//
// 约定：前端为"每一次用户操作"生成一个 UUID。
// 网络失败后的重试、以及用户点击"重试提交"，都复用同一个 requestId，
// 后端按 (identityId, businessType, requestId) 三元组幂等认领，不会重复入账。
//
// 用法：表单提交时先 form.requestId = newRequestId()；
//       重试时不要重新生成，直接用 form.requestId 再提交。

/**
 * 生成一个新的 requestId（UUID v4）。
 * 优先使用浏览器原生 crypto.randomUUID。
 * @returns {string}
 */
export function newRequestId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // 兜底：手工拼 UUID v4
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}
