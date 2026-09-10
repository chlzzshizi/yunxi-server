package com.yunxi.infrastructure.persistence.po;

import java.math.BigDecimal;

/**
 * 定价表 clothes_prices 的数据库映射对象。
 */
public class ClothesPricePO {

    private Long id;
    private Long categoryId;
    private Long washTypeId;
    private BigDecimal price;      // 不支持=0.00；精洗=普洗价+20

    // ── getter / setter ──
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public Long getWashTypeId() { return washTypeId; }
    public void setWashTypeId(Long washTypeId) { this.washTypeId = washTypeId; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
}
