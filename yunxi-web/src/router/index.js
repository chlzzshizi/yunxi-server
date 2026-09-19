import { createRouter, createWebHistory } from 'vue-router'
import { loadSession, isAdminSession } from '../utils/auth'

// 路由规则：
//   guest    → 登录/注册页：已登录访问 → 弹回主页
//   persona  → 受保护主页：未登录访问 → 踢回对应登录页
// 守卫只做"本地会话在不在"的粗判断；token 是否真有效由后端 401 说了算
// （request.js 收到 401 会清会话并再跳一次登录页）

// 角色不进路由：管理员能登录，但后端按 URL 前缀把整个 /api/orders 都 403 了。
// 前端不按 role 藏按钮 —— 藏了也只是"少点一次"，真拦人靠后端；而且 JWT 里
// 的 role 一旦和服务端口径不一致，藏按钮反而会变成"店长看不到自己的功能"
//
// 2026-09-19 补管理员端时加了**两处例外**：/staff/admin/*（meta.adminOnly）。
// 它拦的是"**店长**进管理页"，不是"按角色藏功能" —— 那两个页面天生只给管理员用
// （后端 /api/staff 整段只认 ADMIN），店长进去只会看到一屏 403，拦住他才是
// 上面那条"藏按钮"结论的**反面**：不让用户走到一个必然失败的页面上。
// 闸门是**单向**的：管理员进订单/定价页不拦 —— 那是后端口径（他确实会拿到 403），
// 前端如实呈现，不替他决定"你不该点这里"。上面两行的结论在别处一字不变。

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
  // ── 管理员专区（2026-09-19 补）──
  // 挂在 /staff/ 下是为了保住"路径前缀 = persona"：这两页的 token 仍是员工槽位的，
  // 只是 role 必须是 ADMIN。admin 段把"谁的地盘"写在路径上，不用点进去才知道
  {
    path: '/staff/admin/staff',
    name: 'adminStaff',
    component: () => import('../views/staff/admin/StaffManageView.vue'),
    meta: { persona: 'staff', adminOnly: true },
  },
  {
    path: '/staff/admin/stores',
    name: 'adminStores',
    component: () => import('../views/staff/admin/StoreManageView.vue'),
    meta: { persona: 'staff', adminOnly: true },
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
  // ── 2026-09-19 补的两页 ──
  // 我的券：消费 listMyCoupons；个人中心：消费 getMyProfile / updateMyProfile
  // ——那两个函数在此之前是死的（有接口、没页面）
  {
    path: '/customer/coupons',
    name: 'customerCoupons',
    component: () => import('../views/customer/CustomerCouponView.vue'),
    meta: { persona: 'customer' },
  },
  {
    path: '/customer/profile',
    name: 'customerProfile',
    component: () => import('../views/customer/CustomerProfileView.vue'),
    meta: { persona: 'customer' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

// persona → 登录页 / 主页 路径。
// 员工主页从 /staff/coupons 改成 /staff/orders：订单是店长的**日常**，
// 发券台是偶尔用一次的活动页。登录后落到"每天都要开的那一页"
// adminHome 是**管理员**的"每天都要开的那一页"：他碰不到订单（后端口径），
// 日常就是人与店管理
export const personaPaths = {
  staff: { login: '/staff/login', home: '/staff/orders', adminHome: '/staff/admin/staff' },
  customer: { login: '/customer/auth', home: '/customer/orders' },
}

/**
 * "登录后 / 被弹回时该去哪一页" —— 这个问题**只有这一个家**。
 *
 * 三个调用点（守卫两处 + 登录页一处）都走它：任何一处自己写死，
 * 改角色分流时就会漏掉，而漏掉的那处表现为"登录后落在别人的首页上"，
 * 看起来完全不像路由问题。
 */
export function homeFor(persona, session) {
  if (persona === 'staff' && isAdminSession(session)) return personaPaths.staff.adminHome
  return personaPaths[persona].home
}

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const persona = to.meta.persona
  if (!persona) return true // 首页等公共页
  const session = loadSession(persona)
  // 已登录还去登录页 → 进主页。这里必须问 homeFor 而不是 personaPaths.home：
  // 管理员点"员工登录"会被弹回满屏 403 的订单页，正是这次要消灭的那个体验
  if (to.meta.guest && session) return homeFor(persona, session)
  if (!to.meta.guest && !session) return personaPaths[persona].login // 未登录闯主页 → 踢去登录
  // 管理员专区：非管理员别进（进去只有一屏 403）。单向 —— 反方向不拦
  if (to.meta.adminOnly && !isAdminSession(session)) return homeFor(persona, session)
  return true
})

export default router
