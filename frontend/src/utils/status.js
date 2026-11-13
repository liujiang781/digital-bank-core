// 交易状态与业务类型展示映射
// 口径：实验三 S2-S3 接口交接说明 v1.0（执行版）2.2 节
// 交易状态：ACCEPTED / PROCESSING / UNKNOWN / SUCCESS / RECOVERING / FAILED / REJECTED
// 账户状态：ACTIVE / FROZEN / CLOSED
// 注意：外壳响应 code=SUCCEEDED 只代表请求已处理，业务终态看 data.status（如 SUCCESS）

export const TRANSACTION_STATUS = {
  ACCEPTED: { label: '已受理', type: 'info' },
  REJECTED: { label: '已拒绝', type: 'danger' },
  PROCESSING: { label: '处理中', type: 'warning' },
  UNKNOWN: { label: '待确认', type: 'warning' },
  SUCCESS: { label: '已成功', type: 'success' },
  RECOVERING: { label: '恢复中', type: 'warning' },
  FAILED: { label: '已失败', type: 'danger' }
}

// 非终态：允许（也应当）继续查询收敛
export const OPEN_STATUSES = ['ACCEPTED', 'PROCESSING', 'UNKNOWN', 'RECOVERING']

export function statusLabel(status) {
  return TRANSACTION_STATUS[status]?.label ?? status
}

export function statusTagType(status) {
  return TRANSACTION_STATUS[status]?.type ?? 'info'
}

export function isFinalStatus(status) {
  return ['SUCCESS', 'FAILED', 'REJECTED'].includes(status)
}

export const BUSINESS_TYPE = {
  DEPOSIT: '存款',
  WITHDRAWAL: '取款',
  INTERNAL_TRANSFER: '本行转账',
  EXTERNAL_TRANSFER: '跨行转账',
  ACCOUNT_OPEN: '开户'
}

export function businessTypeLabel(type) {
  return BUSINESS_TYPE[type] ?? type
}

// 账户状态（实验三执行口径：ACTIVE / FROZEN / CLOSED）
export const ACCOUNT_STATUS = {
  ACTIVE: { label: '正常', type: 'success' },
  FROZEN: { label: '已冻结', type: 'warning' },
  CLOSED: { label: '已注销', type: 'info' }
}

export function accountStatusLabel(status) {
  return ACCOUNT_STATUS[status]?.label ?? status
}

export function accountStatusTagType(status) {
  return ACCOUNT_STATUS[status]?.type ?? 'info'
}
