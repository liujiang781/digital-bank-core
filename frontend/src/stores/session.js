// 会话 store：令牌 + 客户基本信息（Pinia）
// 令牌以 localStorage 为权威来源，刷新页面不丢登录态。
import { defineStore } from 'pinia'
import { getToken, setToken } from '../api/request'

export const useSessionStore = defineStore('session', {
  state: () => ({
    token: getToken(),
    customer: JSON.parse(localStorage.getItem('dbank_customer') || 'null')
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
      localStorage.setItem('dbank_customer', JSON.stringify(customer || null))
    },
    clear() {
      this.token = ''
      this.customer = null
      setToken('')
      localStorage.removeItem('dbank_customer')
    }
  }
})
