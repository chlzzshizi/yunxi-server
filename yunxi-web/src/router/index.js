import { createRouter, createWebHistory } from 'vue-router'
import { loadSession } from '../utils/auth'

// 路由规则：
//   guest    → 登录/注册页：已登录访问 → 弹回主页
//   persona  → 受保护主页：未登录访问 → 踢回对应登录页
// 守卫只做"本地会话在不在"的粗判断；token 是否真有效由后端 401 说了算
// （request.js 收到 401 会清会话并再跳一次登录页）

// 角色不进路由：管理员能登录，但后端按 URL 前缀把整个 /api/orders 都 403 了。
// 前端不按 role 藏按钮 —— 藏了也只是"少点一次"，真拦人靠后端；而且 JWT 里
// 的 role 一旦和服务端口径不一致，藏按钮反而会变成"店长看不到自己的功能"

const routes = [
  { path: '/', name: 'home', component: () => import('../views/HomeView.vue') },
  {
    path: '/staff/login',
    name: 'staffLogin',
    component: () => import('../views/staff/StaffLoginView.vue'),
    meta: { persona: 'staff', guest: true },
  },
  {
    path: '/staff/orders',
    name: 'staffOrders',
    component: () => import('../views/staff/StaffOrderListView.vue'),
    meta: { persona: 'staff' },
  },
  {
    path: '/staff/orders/new',
    name: 'staffOrderCreate',
    component: () => import('../views/staff/StaffOrderCreateView.vue'),
    meta: { persona: 'staff' },
  },
  {
    path: '/staff/orders/:id',
    name: 'staffOrderDetail',
    component: () => import('../views/staff/StaffOrderDetailView.vue'),
    meta: { persona: 'staff' },
  },
  {
    path: '/staff/prices',
    name: 'staffPrices',
    component: () => import('../views/staff/StaffPriceView.vue'),
    meta: { persona: 'staff' },
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
    path: '/customer/orders/new',
    name: 'customerOrderCreate',
    component: () => import('../views/customer/CustomerOrderCreateView.vue'),
    meta: { persona: 'customer' },
  },
  {
    path: '/customer/orders',
    name: 'customerOrders',
    component: () => import('../views/customer/CustomerOrderListView.vue'),
    meta: { persona: 'customer' },
  },
  {
    path: '/customer/orders/:id',
    name: 'customerOrderDetail',
    component: () => import('../views/customer/CustomerOrderDetailView.vue'),
    meta: { persona: 'customer' },
  },
  {
    path: '/customer/home',
    name: 'customerHome',
    component: () => import('../views/customer/CustomerHomeView.vue'),
    meta: { persona: 'customer' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

// persona → 登录页 / 主页 路径。
// 员工主页从 /staff/coupons 改成 /staff/orders：订单是店长的**日常**，
// 发券台是偶尔用一次的活动页。登录后落到"每天都要开的那一页"
export const personaPaths = {
  staff: { login: '/staff/login', home: '/staff/orders' },
  customer: { login: '/customer/auth', home: '/customer/orders' },
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
