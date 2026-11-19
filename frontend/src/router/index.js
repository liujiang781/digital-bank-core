import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'

// 后台员工账号没有个人银行业务，登录后的落地页按身份决定。
function homeRoute(session) {
  if (session.isOpsRisk) return { name: 'risk' }
  if (session.isOpsAdmin) return { name: 'ops' }
  if (session.isBackOffice) return { name: 'transactions' }
  return { name: 'accounts' }
}

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('../views/RegisterView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('../layouts/MainLayout.vue'),
    children: [
      { path: '', redirect: () => homeRoute(useSessionStore()) },
      { path: 'accounts', name: 'accounts', component: () => import('../views/DashboardView.vue'), meta: { customerOnly: true } },
      { path: 'accounts/:accountId', name: 'account-detail', component: () => import('../views/AccountDetailView.vue'), props: true, meta: { customerOnly: true } },
      { path: 'deposit', name: 'deposit', component: () => import('../views/DepositView.vue'), meta: { customerOnly: true } },
      { path: 'withdraw', name: 'withdraw', component: () => import('../views/WithdrawalView.vue'), meta: { customerOnly: true } },
      { path: 'transfer', name: 'transfer', component: () => import('../views/TransferView.vue'), meta: { customerOnly: true } },
      { path: 'transactions', name: 'transactions', component: () => import('../views/TransactionsView.vue') },
      { path: 'profile', name: 'profile', component: () => import('../views/ProfileView.vue') },
      { path: 'ops', name: 'ops', component: () => import('../views/OpsView.vue'), meta: { opsOnly: true } },
      { path: 'risk', name: 'risk', component: () => import('../views/RiskView.vue'), meta: { riskOnly: true } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: () => homeRoute(useSessionStore()) }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫：未登录只能访问登录/注册页
router.beforeEach((to) => {
  const session = useSessionStore()
  if (!to.meta.public && !session.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && session.isLoggedIn) {
    return { path: '/' }
  }
  // 后台页面按角色拦截。前端拦截只是体验优化，真正权限由服务端接口校验。
  if (to.meta.opsOnly && !session.isOpsAdmin) {
    return homeRoute(session)
  }
  if (to.meta.riskOnly && !session.isOpsRisk) {
    return homeRoute(session)
  }
  // 员工账号没有个人银行业务：我的账户/存取款/转账一律回到自己的落地页。
  if (to.meta.customerOnly && session.isBackOffice) {
    return homeRoute(session)
  }
})

export default router
