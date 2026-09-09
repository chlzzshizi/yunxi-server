import { createRouter, createWebHistory } from 'vue-router'
import { loadSession } from '../utils/auth'

// 路由规则：
//   guest    → 登录/注册页：已登录访问 → 弹回主页
//   persona  → 受保护主页：未登录访问 → 踢回对应登录页
// 守卫只做"本地会话在不在"的粗判断；token 是否真有效由后端 401 说了算
// （request.js 收到 401 会清会话并再跳一次登录页）

const routes = [
  { path: '/', name: 'home', component: () => import('../views/HomeView.vue') },
  {
    path: '/staff/login',
    name: 'staffLogin',
    component: () => import('../views/staff/StaffLoginView.vue'),
    meta: { persona: 'staff', guest: true },
  },
  {
    path: '/staff/coupons',
    name: 'staffCoupons',
    component: () => import('../views/staff/StaffCouponView.vue'),
    meta: { persona: 'staff' },
  },
  {
    path: '/customer/auth',
    name: 'customerAuth',
    component: () => import('../views/customer/CustomerAuthView.vue'),
    meta: { persona: 'customer', guest: true },
  },
  {
    path: '/customer/home',
    name: 'customerHome',
    component: () => import('../views/customer/CustomerHomeView.vue'),
    meta: { persona: 'customer' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

// persona → 登录页 / 主页 路径
export const personaPaths = {
  staff: { login: '/staff/login', home: '/staff/coupons' },
  customer: { login: '/customer/auth', home: '/customer/home' },
}

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const persona = to.meta.persona
  if (!persona) return true // 首页等公共页
  const loggedIn = !!loadSession(persona)
  if (to.meta.guest && loggedIn) return personaPaths[persona].home // 已登录还去登录页 → 进主页
  if (!to.meta.guest && !loggedIn) return personaPaths[persona].login // 未登录闯主页 → 踢去登录
  return true
})

export default router
