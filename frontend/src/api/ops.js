// 运营与风控后台 API（UC-OP-01 / UC-AU-02 / UC-AC-05/06 / UC-RK-01—04）
// 所有接口的角色与数据范围由服务端会话校验，前端只负责展示与提交。
import http from './request'

// ---- 客户与授权（UC-OP-01 / UC-AU-02）----
export function listCustomers(params) {
  return http.get('/customers', { params })
}

export function getAuthorization(identityId) {
  return http.get('/roles', { params: { identityId } })
}

export function updateAuthorization(roleId, payload) {
  return http.put(`/roles/${encodeURIComponent(roleId)}`, payload)
}

// ---- 后台按授权范围查账户（独立接口：必须带查询原因，服务端写审计）----
// 与「我的账户」/accounts 分开：这里只返回会话授权范围内的账户。
export function listScopedAccounts(params = {}) {
  return http.get('/accounts/scoped', { params })
}

// ---- 账户冻结解冻（UC-AC-05 / UC-AC-06）----
export function freezeAccount(accountId, { reason, requestId }) {
  return http.post(`/accounts/${encodeURIComponent(accountId)}/freeze`, { reason, requestId })
}

export function unfreezeAccount(accountId, payload) {
  return http.post(`/accounts/${encodeURIComponent(accountId)}/unfreeze`, payload)
}

// ---- 风控解除冻结审批（UC-AC-06 前置，S3 新增）----
// 风控对授权范围内的一条 RISK 冻结给出肯定解除结论，运营解冻时才能覆盖该限制。
export function approveFreezeRelease(accountId, restrictionId, { basis }) {
  return http.post(
    `/risk/accounts/${encodeURIComponent(accountId)}/restrictions/${encodeURIComponent(restrictionId)}/release-approval`,
    { basis }
  )
}

// ---- 风控规则（UC-RK-01）----
export function listRules() {
  return http.get('/risk/rules')
}

export function submitRuleChange(ruleId, payload) {
  return http.put(`/risk/rules/${encodeURIComponent(ruleId)}`, payload)
}

export function listChanges(params = {}) {
  return http.get('/risk/changes', { params })
}

export function approveChange(changeId, payload) {
  return http.post(`/risk/changes/${encodeURIComponent(changeId)}/approve`, payload)
}

// ---- 风险名单（UC-RK-02）----
export function listRiskEntries(params = {}) {
  return http.get('/risk/lists', { params })
}

export function submitListChange(payload) {
  return http.post('/risk/lists/changes', payload)
}

// ---- 告警处置（UC-RK-03）----
export function disposeAlert(eventId, payload) {
  return http.post(`/risk/alerts/${encodeURIComponent(eventId)}/dispose`, payload)
}

// ---- 风险记录查询（UC-RK-04）----
export function listRiskEvents(params = {}) {
  return http.get('/risk/events', { params })
}
