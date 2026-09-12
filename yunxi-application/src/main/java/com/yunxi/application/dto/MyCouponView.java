package com.yunxi.application.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 「我的券」出参 —— `GET /api/coupons/mine` 里的一行。
 *
 * 字段是**顾客视角**的：券名、折扣、什么时候到期。库存、券批次状态（status）
 * 这些运营字段不在这里 —— "我的券"要回答的是"我能拿它省多少钱"，
 * 不是"这个批次发得怎么样"。
 *
 * @param grabId    抢券记录 id。**只给列表渲染当 key 用**，下单不要传它
 * @param couponId  **下单时传这个**（POST /api/orders 的 couponId 字段）。
 *                  一个人在一个批次里只能抢到一张（uk_coupon_customer），
 *                  所以"券 + 顾客"就能唯一确定他手里那一张，不需要 grabId
 * @param expired   是否已过期。过期的券由前端置灰，而不是让它从列表里消失 ——
 *                  "我抢的券去哪了"比"这里本来就没有东西"好回答得多
 *
 * 为什么这个类上没有 from(po) 静态工厂（OrderView 是有的）：那个 po 是
 * infrastructure 的类型，而 `application.dto` 引用 infrastructure 会多一条
 * 依赖箭头。券模块"没有 domain 层"是**记在案的主动简化例外**，但例外的范围是
 * `CouponAppService` 这一个类 —— 映射写在它里面，例外就还留在原地，不会顺手
 * 扩大到整个 dto 包。
 */
public record MyCouponView(
        Long grabId,
        Long couponId,
        String name,
        BigDecimal discount,
        LocalDateTime startTime,
        LocalDateTime endTime,
        LocalDateTime grabTime,
        boolean expired
) {}
