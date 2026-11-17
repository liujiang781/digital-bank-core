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
 * 转账（UC-TR-03 本行 P0）
 * POST /api/v1/transfers
 * 后端 TransferRequest 字段：accountId（付款账户）、payeeAccountId、amount、currency、purpose、requestId。
 * 本轮 S3 只实现本行转账；跨行（UC-TR-04）按交接文档第 7.1 节需另立契约版本，暂不提交。
 */
export function transfer({
  accountId,
  payeeAccountId,
  amount,
  currency = 'CNY',
  purpose = null,
  requestId
}) {
  return http.post('/transfers', {
    accountId,
    payeeAccountId,
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
