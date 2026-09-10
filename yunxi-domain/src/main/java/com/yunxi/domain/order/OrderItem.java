package com.yunxi.domain.order;
import  java.math.BigDecimal;
public class OrderItem {

    private Long id;              // 数据库主键
    private Long orderId;         // 属于哪个订单
    private Long categoryId;      // 衣物分类ID（衬衫、裤子...）
    private Long washTypeId;      // 洗涤方式ID（水洗、干洗...）
    private int quantity;         // 数量
    private BigDecimal unitPrice; // 单价
    private String photos;        // 照片URL

    public OrderItem() {}

    /**
     * 下单用构造器 —— **不带单价**。
     * 单价由后端查价目表算出来（OrderAppService 算完后调 applyPrice 填上），
     * 外面传进来的价一律不认：不然 1 块钱就能把羽绒服洗了。
     */
    public OrderItem(long categoryId, long washTypeId, int quantity, String photos) {
        this.categoryId = categoryId;
        this.washTypeId = washTypeId;
        this.quantity = quantity;
        this.photos = photos;
    }

    /** 带单价的构造器 —— 从数据库恢复数据时用（单价是已经算好落库的事实） */
    public OrderItem(long  categoryId, long  washTypeId, int quantity, BigDecimal unitPrice, String photos) {
        this(categoryId, washTypeId, quantity, photos);
        this.unitPrice = unitPrice;
    }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public Long getWashTypeId() { return washTypeId; }
    public  void  setWashTypeId(Long washTypeId) { this.washTypeId = washTypeId; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public String getPhotos() { return photos; }
    public void setPhotos(String photos) { this.photos = photos; }

    /**
     * 定价 —— 订单创建流程算价后调用。
     * 单独开一个方法（而不是复用 setter）：这是"算价"这个业务动作的名字，
     * 搜索 applyPrice 就能找到唯一的算价入口；setter 是给 MyBatis 恢复数据用的。
     */
    public void applyPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal subtotal() {
        if (unitPrice == null) {
            // 故意不写成 unitPrice.multiply(...) 让它 NPE：
            // 走到这里说明有代码绕过了"先算价、再建单"的顺序。
            // null 的 NPE 堆栈只指向这一行，看不出是哪条明细、哪个入口，
            // 一句能读懂的话能省半小时排查。
            throw new IllegalStateException(
                    "明细还没定价就算钱：下单必须由后端算价（OrderAppService.priceItems）");
        }
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
