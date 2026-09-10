package com.yunxi.domain.price;

/**
 * 洗涤方式 —— wash_types 表。
 *
 * 固定 3 种（普洗/精洗/单熨），由 V1 建表时就插入，不可增删（设计文档 §4.5）。
 * id 见 {@link PricePolicy} 里的常量。
 */
public class WashType {

    private Long id;
    private String name;            // 普洗 / 精洗 / 单熨
    private String description;     // 说明文案，前端可直接展示

    /** 无参构造 — 仓储从数据库重建时用 */
    public WashType() {}

    // ── getter / setter ──
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
