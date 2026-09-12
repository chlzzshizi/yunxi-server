package com.yunxi.infrastructure.persistence.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 「我的券」列表的**联表行** —— coupon_grabs ⋈ coupons。
 *
 * 为什么不是 CouponGrabPO 加几个字段：那是 coupon_grabs 的单表映射，
 * 塞进券名/折扣率之后，往 insert 里用就会带着一堆 null 字段走。
 * 联表读出来的行是**另一种形状**，给它一个自己的类型。
 *
 * 为什么不直接返回 CouponPO：一张券有库存、开始时间这些顾客不需要看的字段，
 * 而且"我的券"的粒度是**抢到的每一张**（coupon_grabs），不是券批次本身。
 */
public class CouponGrabDetailPO {

    private Long grabId;              // coupon_grabs.id —— 券的唯一标识用这个，不是 couponId
    private Long couponId;
    private String name;              // 券名，如"5折洗护券"
    private BigDecimal discount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime grabTime;

    // ── getter / setter ──
    public Long getGrabId() { return grabId; }
    public void setGrabId(Long grabId) { this.grabId = grabId; }

    public Long getCouponId() { return couponId; }
    public void setCouponId(Long couponId) { this.couponId = couponId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getDiscount() { return discount; }
    public void setDiscount(BigDecimal discount) { this.discount = discount; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public LocalDateTime getGrabTime() { return grabTime; }
    public void setGrabTime(LocalDateTime grabTime) { this.grabTime = grabTime; }
}
