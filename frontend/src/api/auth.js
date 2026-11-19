// 用户服务接口（user-service，接口契约 3.1）
import http from './request'

/**
 * 注册与实名认证（UC-ID-01，P0）
 * POST /api/v1/auth/register
 * @param {{ realName: string, identityId: string, password: string, requestId: string }} payload
 */
export function register(payload) {
  return http.post('/auth/register', payload)
}

/**
 * 登录（UC-AU-01，P0）
 * POST /api/v1/auth/login
 * @param {{ identityId: string, password: string }} payload
 * @returns 会话令牌 + customer 基本信息
 */
export function login(payload) {
  return http.post('/auth/login', payload)
}

/**
 * 登出
 * POST /api/v1/auth/logout
 */
export function logout() {
  return http.post('/auth/logout')
}

/**
 * 查询本人会话信息（脱敏）
 * GET /api/v1/auth/me（S1 AuthController）
 */
export function getMe() {
  return http.get('/auth/me')
}

/**
 * 维护个人资料（UC-ID-03，P1）
 * PUT /api/v1/customers/me
 */
export function updateMe(payload) {
  return http.put('/customers/me', payload)
}

/** 本人资料；与 getMe() 的会话身份查询分开。 */
export function getProfile() {
  return http.get('/customers/me')
}

/** 运营客户列表，params 必须包含 reason，范围由服务端会话决定。 */
export function listCustomers(params) {
  return http.get('/customers', { params })
}

/** 管理员查询目标身份的业务角色与账户范围。 */
export function getAuthorization(identityId) {
  return http.get('/roles', { params: { identityId } })
}

/** 管理员替换/撤销授权；payload 包含 identityId、enabled、accountScope、reason、requestId。 */
export function updateAuthorization(roleId, payload) {
  return http.put(`/roles/${encodeURIComponent(roleId)}`, payload)
}
