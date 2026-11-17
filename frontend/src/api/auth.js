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
 * 查询本人实名信息（脱敏）
 * GET /api/v1/customers/me
 */
export function getMe() {
  return http.get('/customers/me')
}

/**
 * 维护个人资料（UC-ID-03，P1）
 * PUT /api/v1/customers/me
 */
export function updateMe(payload) {
  return http.put('/customers/me', payload)
}
