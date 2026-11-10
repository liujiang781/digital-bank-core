// axios 统一请求层（接口契约第 2 节：统一响应体 / 错误码 / 401 处理）
//
// 约定：
// 1. 后端所有接口（含错误）都返回统一响应体 { code, message, data, traceId }。
// 2. 本层把"后端可达"的响应一律 resolve 成统一响应体对象，
//    页面根据 code 与 message 自行展示成功、待确认或错误（不硬编码"操作失败"）。
// 3. 401 / UNAUTHENTICATED：清除本地会话并跳转登录页。
// 4. 网络错误（超时、断连）：自动重试一次——所有写操作都带幂等 requestId，
//    重试复用同一个 requestId，后端不会重复入账（BR-11/12）。

import axios from 'axios'
import router from '../router'

const TOKEN_KEY = 'dbank_token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' }
})

// 请求拦截：注入会话令牌
http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 未登录处理：清会话，回登录页（带上来源，登录后跳回）
function handleUnauthenticated() {
  setToken('')
  if (router.currentRoute.value.name !== 'login') {
    router.push({
      name: 'login',
      query: { redirect: router.currentRoute.value.fullPath }
    })
  }
}

// 响应拦截
http.interceptors.response.use(
  (response) => {
    const body = response.data
    // 网关或服务在 HTTP 200 里也可能给出会话失效
    if (body && body.code === 'UNAUTHENTICATED') {
      handleUnauthenticated()
    }
    return body
  },
  (error) => {
    // 有后端响应体（4xx / 5xx，含业务错误码）：透传给页面
    if (error.response) {
      const body = error.response.data
      if (error.response.status === 401 || body?.code === 'UNAUTHENTICATED') {
        handleUnauthenticated()
      }
      if (body && typeof body.code === 'string') {
        return Promise.resolve(body)
      }
      // 非 JSON 响应（如网关直接 502/504）
      return Promise.reject(new Error(`网关异常（HTTP ${error.response.status}）`))
    }

    // 网络错误 / 超时：自动重试一次（写请求幂等，重试安全）
    if (
      !error.config.__retried &&
      (error.code === 'ECONNABORTED' || error.message.includes('Network Error'))
    ) {
      error.config.__retried = true
      return http.request(error.config)
    }
    return Promise.reject(new Error('网络异常，请稍后重试'))
  }
)

export default http
