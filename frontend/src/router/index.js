import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'

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
      { path: '', redirect: '/accounts' },
      { path: 'accounts', name: 'accounts', component: () => import('../views/DashboardView.vue') },
      { path: 'accounts/:accountId', name: 'account-detail', component: () => import('../views/AccountDetailView.vue'), props: true },
      { path: 'deposit', name: 'deposit', component: () => import('../views/DepositView.vue') },
      { path: 'withdraw', name: 'withdraw', component: () => import('../views/WithdrawalView.vue') },
      { path: 'transfer', name: 'transfer', component: () => import('../views/TransferView.vue') },
      { path: 'transactions', name: 'transactions', component: () => import('../views/TransactionsView.vue') }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/accounts' }
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
})

export default router
