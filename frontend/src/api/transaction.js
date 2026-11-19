// 交易服务接口（transaction-service，接口契约 3.3）
// 受理类接口返回 data：tradeNo + 当前 status（七态）
import http from './request'

/**
 * 存款（UC-TR-01，P0）
 * POST /api/v1/deposits
 * 重复请求（同 requestId）：返回原 tradeNo 与当前状态
 */
export function deposit({ accountId, amount, currency = 'CNY', requestId }) {
  return http.post('/deposits', { accountId, amount, currency, requestId })
}

/**
 * 取款（UC-TR-02，P0）
 * POST /api/v1/withdrawals
 */
export function withdraw({ accountId, amount, currency = 'CNY', requestId }) {
  return http.post('/withdrawals', { accountId, amount, currency, requestId })
}

/**
 * 转账：本行（UC-TR-03）与跨行（UC-TR-04）共用一个端点，按字段分派。
 * POST /api/v1/transfers
 * 后端 TransferRequest 新契约：
 *   payerAccountId（付款账户，优先；兼容旧字段 accountId）
 *   本行：payeeAccountId
 *   跨行：externalBankCode + externalPayeeRef（不可同时给本行收款）
 */
export function transfer({
  accountId,
  payerAccountId,
  payeeAccountId = null,
  externalBankCode = null,
  externalPayeeRef = null,
  amount,
  currency = 'CNY',
  purpose = null,
  requestId
}) {
  return http.post('/transfers', {
    accountId,
    payerAccountId,
    payeeAccountId,
    externalBankCode,
    externalPayeeRef,
    amount,
    currency,
    purpose,
    requestId
  })
}

/**
 * 查询交易结果（UC-TR-05，P0）
 * GET /api/v1/transactions/{tradeNo}
 * 客户触发查询还会发起一次渠道核对（帮助 UNKNOWN/RECOVERING 收敛）
 */
export function getTransaction(tradeNo) {
  return http.get(`/transactions/${encodeURIComponent(tradeNo)}`)
}

/**
 * 交易列表（UC-TR-05 列表形态）
 * GET /api/v1/transactions?page=&pageSize=&businessType=&status=
 */
export function listTransactions(params = {}) {
  return http.get('/transactions', { params })
}

/**
 * 下载交易回单（UC-TR-07，P2，仅成功交易）
 * GET /api/v1/transactions/{tradeNo}/receipt
 */
export function getReceipt(tradeNo) {
  return http.get(`/transactions/${encodeURIComponent(tradeNo)}/receipt`)
}

/**
 * 核对待确认交易（UC-TR-08，P1，运营授权）
 * POST /api/v1/transactions/{tradeNo}/reconcile
 */
export function reconcileTrade(tradeNo, { reason, requestId }) {
  return http.post(`/transactions/${encodeURIComponent(tradeNo)}/reconcile`, { reason, requestId })
}

/**
 * 重试资金恢复（UC-TR-09，P1，运营授权）
 * POST /api/v1/transactions/{tradeNo}/recover
 */
export function recoverTrade(tradeNo, { reason, requestId }) {
  return http.post(`/transactions/${encodeURIComponent(tradeNo)}/recover`, { reason, requestId })
}
