package com.yunxi.domain.price;

/**
 * 衣物分类 —— clothes_categories 表。
 *
 * 层级用 parentId 自引用：parentId 为 null 是一级分类（"上衣"），
 * 有 parentId 的是叶子分类（"衬衫"）。**价格只挂在叶子上**：
 * "上衣"本身不洗，给它定价没有意义。
 *
 * 刻意没做成聚合根：它没有生命周期，没有需要守住的不变量，也没有并发写入。
 * 硬套聚合只会多出一堆没人调用的方法，还不如老实用个数据对象。
 */
public class ClothesCategory {

    private Long id;
    private String name;        // 分类名称，如"衬衫"
    private String icon;        // 图标URL
    private Long parentId;      // 父分类ID，null=一级分类
    private int sortOrder;      // 排序

    /** 无参构造 — 仓储从数据库重建时用 */
    public ClothesCategory() {}

    /** 二级（叶子）分类才能定价、才能被下单 */
    public boolean isLeaf() {
        return parentId != null;
    }

    // ── getter / setter ──
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
