// 会话 store：令牌 + 客户基本信息（Pinia）
// 存在 sessionStorage：每个标签页独立，支持多标签页登录不同账号；刷新页面不丢登录态。
import { defineStore } from 'pinia'
import { getToken, setToken } from '../api/request'

export const useSessionStore = defineStore('session', {
  state: () => ({
    token: getToken(),
    customer: JSON.parse(sessionStorage.getItem('dbank_customer') || 'null')
  }),
  getters: {
    isLoggedIn: (state) => Boolean(state.token),
    displayName: (state) => state.customer?.realName || state.customer?.identityId || '客户',
    // 管理角色：OPERATIONS 可见运营台，RISK 可见风控台。
    // 角色来自服务端登录响应，前端只做展示控制，真正的权限校验仍在服务端。
    roles: (state) => state.customer?.roles || [],
    isOperations: (state) => (state.customer?.roles || []).includes('OPERATIONS'),
    isRisk: (state) => (state.customer?.roles || []).includes('RISK'),
    isAdmin: (state) => (state.customer?.roles || []).includes('ADMIN'),
    // 风控读权限：RISK / OPERATIONS / AUDIT（与后端 RiskAccess.requireRiskReader/requireRuleReader 一致）
    canReadRisk: (state) => {
      const roles = state.customer?.roles || []
      return !roles.includes('ADMIN') &&
        ['RISK', 'OPERATIONS', 'AUDIT'].some((r) => roles.includes(r))
    },
    // 风控写权限：仅 RISK，且需非空账户范围（与后端 requireRiskWriter 一致）
    canWriteRisk: (state) => {
      const roles = state.customer?.roles || []
      return roles.includes('RISK') && (state.customer?.accountScope || []).length > 0
    }
  },
  actions: {
    loginSuccess({ token, customer }) {
      this.token = token
      this.customer = customer || null
      setToken(token)
      sessionStorage.setItem('dbank_customer', JSON.stringify(customer || null))
    },
    clear() {
      this.token = ''
      this.customer = null
      setToken('')
      sessionStorage.removeItem('dbank_customer')
    }
  }
})
