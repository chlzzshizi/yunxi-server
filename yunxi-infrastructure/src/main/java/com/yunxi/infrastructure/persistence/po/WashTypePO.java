package com.yunxi.infrastructure.persistence.po;

/**
 * 洗涤方式表 wash_types 的数据库映射对象。
 */
public class WashTypePO {

    private Long id;
    private String name;            // 普洗 / 精洗 / 单熨
    private String description;     // 说明文案

    // ── getter / setter ──
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
