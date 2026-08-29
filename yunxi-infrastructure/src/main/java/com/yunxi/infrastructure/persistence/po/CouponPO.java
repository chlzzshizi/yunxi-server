package com.yunxi.infrastructure.persistence.po;


import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 折扣券表 coupons 的数据库映射对象。
 */
public class CouponPO {

    private Long id;
    private String name;
    private BigDecimal discount;     // 折扣率，0.50 = 5折
    private Integer totalStock;      // 总库存
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;          // 1=未开始 2=进行中 3=已结束
    private LocalDateTime createTime;

    // ── getter / setter ──
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getDiscount() { return discount; }
    public void setDiscount(BigDecimal discount) { this.discount = discount; }

    public Integer getTotalStock() { return totalStock; }
    public void setTotalStock(Integer totalStock) { this.totalStock = totalStock; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}