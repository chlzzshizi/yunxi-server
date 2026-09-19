# yunxi-web — 云洗助手前端

Vue 3 `<script setup>` + Vue Router + Bootstrap 5（CDN），员工端与顾客端两套人格。

## 运行

```bash
npm install          # 依赖
npm run dev          # 开发服务器 :5173，/api 代理到后端 8081
```

后端 yunxi-server 需在 8081 运行（含 MySQL/Redis）。

> 用 `http://localhost:5173`，**不要用 `127.0.0.1:5173`** —— dev server 只绑了 `[::1]`。

## 页面

| persona | 路由 | 页面 |
|---|---|---|
| 公共 | `/` | 身份选择（员工端 / 顾客端入口） |
| 员工 | `/staff/login` | 登录（**店长与管理员同一个入口**，靠 JWT 里的 role 分流落地页） |
| 员工 | `/staff/orders` | 订单列表（状态筛选 + 分页） |
| 员工 | `/staff/orders/new` | 建门店单（手机号查/建档 → 明细 → 单价自动带出） |
| 员工 | `/staff/orders/:id` | 订单详情 —— **所有写操作的唯一入口**（推进 / 收款 / 结账 / 快递单号） |
| 员工 | `/staff/prices` | 定价管理（改普洗价自动派生精洗） |
| 员工 | `/staff/coupons` | 发券台（发券 / 券列表，30s 轮询捕捉 cron 推进） |
| 员工 | `/staff/admin/staff` | **管理员专区**：员工管理（名册 / 新建 / 编辑 / 重置密码 / 启停用） |
| 员工 | `/staff/admin/stores` | **管理员专区**：门店管理（建店 / 改店 / 营业停业） |
| 顾客 | `/customer/auth` | 注册（即登录，**只填手机号+密码**）/ 登录 |
| 顾客 | `/customer/orders/new` | 下单（选门店**可不选** / 明细 / 预约 / 地址 / 选券看折后价） |
| 顾客 | `/customer/orders` | 我的订单 |
| 顾客 | `/customer/orders/:id` | 订单详情 + 在线支付 |
| 顾客 | `/customer/home` | 领券中心 |
| 顾客 | `/customer/coupons` | 我的券（未用掉的；**过期的置灰保留**不隐藏） |
| 顾客 | `/customer/profile` | 个人中心（看档案 / **补姓名**，手机号只读） |

> 两条 `/staff/admin/*` 是**唯一带角色判断的两页**（`meta.adminOnly`，2026-09-19 补）：
> 它们只给管理员用 —— 后端 `/api/staff` 整个前缀只认 ADMIN，店长进去只会看到一屏 403。
> 其余页面**一律不按 role 藏按钮**：管理员点订单页会看到 403，那是后端的权限口径，前端如实呈现。

## 约定（改代码前先看这几条）

- `<script setup>`、**无 `<style>` 块**、2 空格、无分号、单引号、**中文注释解释"为什么"**
- **后端 HTTP 恒 200**，一切分支看 `body.code`；`request()` 返回信封里的 `data`，失败抛 `ApiError(code, message)`，视图只看 `e.message`
- **409 是"别人改过了"→ 重新读取**，不是重试
- 双人格 token 槽：`yunxi_token_staff` / `yunxi_token_customer`（localStorage，互不挤占）；401 分流集中在 `main.js`
- 路由按 `meta.persona` 守卫；**登录页路径取 `personaPaths`，而"登录后落到哪一页"一律问 `router` 导出的 `homeFor(persona, session)`** —— 它读 JWT 里的 role（管理员进 `/staff/admin/staff`，店长进 `/staff/orders`）。别在任何页面里写死落地页：守卫、登录页各有一份的话，改分流时必漏一处（漏掉的表现是"登录后落在别人的首页上"，看着不像路由问题）
- 枚举在返回体里是**名字**（`"PENDING_PAY"`），查询参数是**数字**（`?status=2`）；支付方式参数是**小写码**（`?payMethod=cash`）
- 无 pinia / 无 axios / 无 jwt-decode —— 依赖最小化

## 构建

```bash
npm run build        # 冒烟用
```

> 构建全绿只证明"语法和 import 能解析"，**不证明任何按钮能用** ——
> 详见 `docs/bug-record.md` 的 Bug 28。

详见设计文档《云洗助手重构设计文档》§8。
