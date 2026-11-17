// 交易状态与业务类型展示映射
// 状态口径：接口契约 2.3 节，七态状态机
// RECEIVED / REJECTED / PROCESSING / PENDING / SUCCEEDED / RECOVERING / FAILED

export const TRANSACTION_STATUS = {
  RECEIVED: { label: '已受理', type: 'info' },
  REJECTED: { label: '已拒绝', type: 'danger' },
  PROCESSING: { label: '处理中', type: 'warning' },
  PENDING: { label: '待确认', type: 'warning' },
  SUCCEEDED: { label: '已成功', type: 'success' },
  RECOVERING: { label: '恢复中', type: 'warning' },
  FAILED: { label: '已失败', type: 'danger' }
}

// 非终态：这些状态允许（也应当）继续查询收敛
export const OPEN_STATUSES = ['RECEIVED', 'PROCESSING', 'PENDING', 'RECOVERING']

export function statusLabel(status) {
  return TRANSACTION_STATUS[status]?.label ?? status
}

export function statusTagType(status) {
  return TRANSACTION_STATUS[status]?.type ?? 'info'
}

export function isFinalStatus(status) {
  return ['SUCCEEDED', 'FAILED', 'REJECTED'].includes(status)
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

// 账户状态（枚举基线：NORMAL / FROZEN / CLOSED）
export const ACCOUNT_STATUS = {
  NORMAL: { label: '正常', type: 'success' },
  FROZEN: { label: '已冻结', type: 'warning' },
  CLOSED: { label: '已注销', type: 'info' }
}

export function accountStatusLabel(status) {
  return ACCOUNT_STATUS[status]?.label ?? status
}

export function accountStatusTagType(status) {
  return ACCOUNT_STATUS[status]?.type ?? 'info'
}
