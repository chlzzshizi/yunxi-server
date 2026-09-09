# yunxi-web — 云洗助手前端

Vue 3 `<script setup>` + Vue Router + Bootstrap 5（CDN）的抢券闭环演示。

## 运行

```bash
npm install          # 依赖
npm run dev          # 开发服务器 :5173，/api 代理到后端 8081
```

后端 yunxi-server 需在 8081 运行（含 MySQL/Redis）。

## 结构

- 员工端：登录 → 发券台（发券 / 券列表 / 状态徽章，30s 轮询捕捉 cron 推进）
- 顾客端：注册（即登录）/ 登录 → 领券中心（抢进行中的券）
- 双人格 token 槽：`yunxi_token_staff` / `yunxi_token_customer`（localStorage，互不挤占）
- `request.js` 统一信封解包（后端恒 HTTP 200，按 `body.code` 分支）+ 401 分流
- 无 pinia / 无 axios / 无 jwt-decode —— 依赖最小化

详见设计文档《云洗助手重构设计文档》§8。
