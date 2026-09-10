package com.yunxi.domain.price;

import java.math.BigDecimal;

/**
 * 一条价格 —— clothes_prices 表的一行（分类 × 洗涤方式 → 价格）。
 *
 * "不支持"这件事在数据库里的表达方式就是 **价格 = 0.00**（设计文档 §4.5），
 * 没有单独的 available 标志位。为什么不加标志位：价格本身已经承载了
 * "支持/不支持"的信息，再加一列就有两个真相来源，早晚会互相矛盾
 * （价格 0 但标志位为 true 时，到底听谁的？）。
 */
public class ClothesPrice {

    private Long categoryId;
    private Long washTypeId;
    private BigDecimal price;

    /** 无参构造 — 仓储从数据库重建时用 */
    public ClothesPrice() {}

    public ClothesPrice(Long categoryId, Long washTypeId, BigDecimal price) {
        this.categoryId = categoryId;
        this.washTypeId = washTypeId;
        this.price = price;
    }

    /**
     * 这个价位是否真的可用（> 0）。
     * 前端拿到 false 就把该选项置灰；下单时后端也用它挡。
     */
    public boolean isSupported() {
        return price != null && price.signum() > 0;
    }

    // ── getter / setter ──
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public Long getWashTypeId() { return washTypeId; }
    public void setWashTypeId(Long washTypeId) { this.washTypeId = washTypeId; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
}
