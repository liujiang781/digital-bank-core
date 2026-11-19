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
    // 角色来自服务端登录响应，前端只做展示控制，真正的权限校验仍在服务端。
    roles: (state) => state.customer?.roles || [],
    // 后台模块按演示账号精确隔离：
    // 运营管理只给 OPS-ADMIN，风险控制只给 OPS-RISK。
    isOpsAdmin: (state) => state.customer?.identityId === 'OPS-ADMIN',
    isOpsRisk: (state) => state.customer?.identityId === 'OPS-RISK',
    // 后台身份：运营或风控员工。员工账号没有个人银行业务，
    // 菜单与路由据此隐藏「我的账户/存款/取款/转账」。
    isBackOffice: (state) => {
      const roles = state.customer?.roles || []
      return roles.includes('OPERATIONS') || roles.includes('RISK')
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
