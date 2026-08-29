package com.yunxi.infrastructure.persistence.po;

import java.time.LocalDateTime;

public class CouponGrabPO {

    private Long id;
    private Long couponId;
    private Long customerId;
    private LocalDateTime grabTime;

    // ── getter / setter ──
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCouponId() { return couponId; }
    public void setCouponId(Long couponId) { this.couponId = couponId; }

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public LocalDateTime getGrabTime() { return grabTime; }
    public void setGrabTime(LocalDateTime grabTime) { this.grabTime = grabTime; }
}