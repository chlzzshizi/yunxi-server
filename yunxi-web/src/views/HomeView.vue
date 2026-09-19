<script setup>
// 首页：身份选择 —— 员工端（店长：订单/定价/发券 · 管理员：员工与门店） / 顾客端（注册抢券）
</script>

<template>
  <div class="row justify-content-center">
    <div class="col-lg-8">
      <div class="card shadow-sm text-center">
        <div class="card-body p-5">
          <h2 class="card-title mb-3">欢迎来到云洗助手</h2>
          <p class="text-body-secondary mb-4">请选择你的身份进入演示</p>

          <div class="row g-3 justify-content-center">
            <div class="col-sm-6">
              <div class="card h-100 border-primary-subtle">
                <div class="card-body">
                  <div class="display-6 mb-2">🛠️</div>
                  <h5>员工端</h5>
                  <p class="text-body-secondary small mb-3">店长：订单 / 定价 / 发券 · 管理员：员工与门店</p>
                  <router-link to="/staff/login" class="btn btn-primary w-100">
                    员工入口
                  </router-link>
                </div>
              </div>
            </div>
            <div class="col-sm-6">
              <div class="card h-100 border-success-subtle">
                <div class="card-body">
                  <div class="display-6 mb-2">🧺</div>
                  <h5>顾客端</h5>
                  <p class="text-body-secondary small mb-3">注册 / 登录 · 抢折扣券</p>
                  <router-link to="/customer/auth" class="btn btn-success w-100">
                    顾客入口
                  </router-link>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
