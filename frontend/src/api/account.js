// 账户服务接口（account-service，接口契约 3.2）
// 金额一律字符串两位小数（BR-01）；写操作一律带 requestId（BR-11/12）
import http from './request'

/**
 * 开户（UC-AC-01，P0）
 * POST /api/v1/accounts
 */
export function openAccount({ currency = 'CNY', requestId }) {
  return http.post('/accounts', { currency, requestId })
}

/**
 * 账户列表（UC-AC-02，P0）
 * GET /api/v1/accounts?page=&pageSize=
 * @returns data.items：accountId、currency、status、可用余额 A
 */
export function listAccounts(params = {}) {
  return http.get('/accounts', { params })
}

/**
 * 账户详情（UC-AC-03，P0）
 * GET /api/v1/accounts/{accountId}
 */
export function getAccount(accountId) {
  return http.get(`/accounts/${accountId}`)
}

/**
 * 余额查询（UC-AC-04，P0）
 * GET /api/v1/accounts/{accountId}/balance
 * @returns data：bookBalance(B)、reservedAmount(R)、availableBalance(A)、currency、status
 *         同一快照读取，A = B − R（BR-04）
 */
export function getBalance(accountId) {
  return http.get(`/accounts/${accountId}/balance`)
}

/**
 * 冻结账户（UC-AC-05，P0，运营/风控授权）
 * POST /api/v1/accounts/{accountId}/freeze
 */
export function freezeAccount(accountId, { reason, requestId }) {
  return http.post(`/accounts/${accountId}/freeze`, { reason, requestId })
}

/**
 * 解冻账户（UC-AC-06，P0，运营授权）
 * POST /api/v1/accounts/{accountId}/unfreeze
 */
export function unfreezeAccount(accountId, { basis, requestId }) {
  return http.post(`/accounts/${accountId}/unfreeze`, { basis, requestId })
}

/**
 * 设置个人交易限额（UC-AC-08，P1）
 * POST /api/v1/accounts/{accountId}/limits
 */
export function setLimits(accountId, { personalSingleLimit, personalDailyLimit, requestId }) {
  return http.post(`/accounts/${accountId}/limits`, {
    personalSingleLimit,
    personalDailyLimit,
    requestId
  })
}

/**
 * 注销账户（UC-AC-07，P0）
 * POST /api/v1/accounts/{accountId}/close
 */
export function closeAccount(accountId, { requestId }) {
  return http.post(`/accounts/${accountId}/close`, { requestId })
}

/**
 * 账户操作记录（UC-AC-09，P1）
 * GET /api/v1/accounts/{accountId}/operations
 */
export function getOperations(accountId, params = {}) {
  return http.get(`/accounts/${accountId}/operations`, { params })
}

/**
 * 资金流水查询（UC-TR-06，P0，BR-19：只含实际资金事件）
 * GET /api/v1/accounts/{accountId}/fund-entries
 * @returns data.items：entryId、tradeNo、entryRole、direction、amount、bookBalanceAfter、occurredAt
 */
export function getFundEntries(accountId, params = {}) {
  return http.get(`/accounts/${accountId}/fund-entries`, { params })
}
