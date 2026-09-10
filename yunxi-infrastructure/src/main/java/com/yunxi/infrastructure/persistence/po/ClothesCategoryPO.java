package com.yunxi.infrastructure.persistence.po;

/**
 * 衣物分类表 clothes_categories 的数据库映射对象。
 */
public class ClothesCategoryPO {

    private Long id;
    private String name;        // 分类名称，如"衬衫"
    private String icon;        // 图标URL
    private Long parentId;      // 父分类ID，null=一级分类
    private Integer sortOrder;  // 排序

    // ── getter / setter ──
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
