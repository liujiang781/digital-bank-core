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
    displayName: (state) => state.customer?.realName || state.customer?.identityId || '客户'
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
