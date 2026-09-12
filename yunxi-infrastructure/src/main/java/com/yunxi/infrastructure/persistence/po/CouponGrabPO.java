package com.yunxi.infrastructure.persistence.po;

import java.time.LocalDateTime;

/**
 * 抢券记录表 coupon_grabs 的数据库映射对象。
 */
public class CouponGrabPO {

    private Long id;
    private Long couponId;
    private Long customerId;
    private LocalDateTime grabTime;
    /** 0=未使用 1=已使用（V5 加的列，核销走 CAS） */
    private Integer used;
    // ── 以下三列是**使用记录**（V10 加）。只由 markUsed 那一条 CAS 语句写入，
    //    没有任何别的 UPDATE 会碰它们 —— 审计字段一旦能被旁路改就失去意义了。
    private LocalDateTime usedTime;   // 核销时刻
    private Long usedOrderId;         // 用在哪张订单上
    private Long usedStaffId;         // 经手员工：门店单=建单员工，网单顾客自助=NULL

    // ── getter / setter ──
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCouponId() { return couponId; }
    public void setCouponId(Long couponId) { this.couponId = couponId; }

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public LocalDateTime getGrabTime() { return grabTime; }
    public void setGrabTime(LocalDateTime grabTime) { this.grabTime = grabTime; }

    public Integer getUsed() { return used; }
    public void setUsed(Integer used) { this.used = used; }

    public LocalDateTime getUsedTime() { return usedTime; }
    public void setUsedTime(LocalDateTime usedTime) { this.usedTime = usedTime; }

    public Long getUsedOrderId() { return usedOrderId; }
    public void setUsedOrderId(Long usedOrderId) { this.usedOrderId = usedOrderId; }

    public Long getUsedStaffId() { return usedStaffId; }
    public void setUsedStaffId(Long usedStaffId) { this.usedStaffId = usedStaffId; }
}