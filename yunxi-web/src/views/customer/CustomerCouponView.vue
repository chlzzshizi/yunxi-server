<script setup>
// 我的券：顾客手里**还没用掉**的券（过期的也在，带 expired 标记）。
//
// 后端 /api/coupons/mine 的口径是"过期的也返回、但打上标记" —— 这一页就照它来：
// 过期券**置灰显示**，不让它消失。"我抢的券去哪了"比"这里本来就没有东西"
// 好回答得多（那句话是接口注释里的原话）。
//
// 下单时用的是 couponId 不是 grabId：一个人在一个批次里只能抢到一张，
// "券 + 顾客"就唯一确定了他手里那一张，所以 grabId 只当渲染用的 key。
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { personaPaths } from '../../router'
import { listMyCoupons } from '../../api/coupon'
import { loadSession, clearSession } from '../../utils/auth'
import { fmtTime, fmtDiscount } from '../../utils/format'

const router = useRouter()
const session = loadSession('customer')
const phone = session.claims.sub // 顾客 token 的 subject 是手机号

const list = ref([])
const listError = ref('')

async function loadList() {
  try {
    list.value = await listMyCoupons()
    listError.value = ''
  } catch (e) {
    listError.value = e.message // 401 由 request.js 清会话并跳登录；网络异常就地提示
  }
}

function onLogout() {
  clearSession('customer') // 顾客没有后端登出接口，只能清本地（token 到期自然失效）
  router.push(personaPaths.customer.login)
}

onMounted(loadList)
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-center mb-3">
      <h4 class="mb-0">我的券</h4>
      <div class="d-flex gap-2 align-items-center">
        <span class="text-body-secondary small">手机尾号 {{ phone.slice(-4) }} 的顾客</span>
        <router-link to="/customer/orders" class="btn btn-outline-primary btn-sm">我的订单</router-link>
        <router-link to="/customer/home" class="btn btn-outline-primary btn-sm">首页</router-link>
        <button class="btn btn-outline-danger btn-sm" @click="onLogout">退出</button>
      </div>
    </div>

    <div v-if="listError" class="alert alert-danger py-2 small" role="alert">{{ listError }}</div>

    <div class="row g-3">
      <div v-for="c in list" :key="c.grabId" class="col-md-6 col-lg-4">
        <div class="card h-100 shadow-sm">
          <div class="card-body d-flex flex-column">
            <div class="d-flex justify-content-between align-items-start mb-2">
              <h5 class="card-title mb-0" :class="c.expired ? 'text-body-secondary' : ''">{{ c.name }}</h5>
              <span class="badge" :class="c.expired ? 'text-bg-secondary' : 'text-bg-success'">
                {{ c.expired ? '已过期' : '可用' }}
              </span>
            </div>
            <div class="display-6 fw-bold mb-2"
                 :class="c.expired ? 'text-body-secondary' : 'text-success'">
              {{ fmtDiscount(c.discount) }}
            </div>
            <ul class="list-unstyled small text-body-secondary mb-0">
              <li>有效期 {{ fmtTime(c.startTime) }} ~ {{ fmtTime(c.endTime) }}</li>
              <li>抢到于 {{ fmtTime(c.grabTime) }}</li>
            </ul>
          </div>
        </div>
      </div>
      <div v-if="!list.length && !listError" class="col-12">
        <div class="alert alert-light text-center text-body-secondary">
          还没有券，去领券中心抢一张
        </div>
      </div>
    </div>

    <p class="form-text mt-3">
      这里显示的是还没用掉的券；过期的置灰保留，不会凭空消失。
      下单时在「在线下单」页选用。
    </p>
  </div>
</template>
