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
    public OrderItem(long  categoryId, long  washTypeId, int quantity, BigDecimal unitPrice, String photos) {
        this.categoryId = categoryId;
        this.washTypeId = washTypeId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.photos = photos;
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
    public BigDecimal subtotal() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }
}
