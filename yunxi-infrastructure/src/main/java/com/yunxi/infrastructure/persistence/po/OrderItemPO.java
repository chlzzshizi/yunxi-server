package com.yunxi.infrastructure.persistence.po;
import java.math.BigDecimal;
public class OrderItemPO {
    private Long id;
    private Long orderId;
    private Long categoryId;
    private Long washTypeId;
    private Integer quantity;
    private BigDecimal unitPrice;
    private String photos;

    // ── getter / setter ──
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public Long getWashTypeId() { return washTypeId; }
    public void setWashTypeId(Long washTypeId) { this.washTypeId = washTypeId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public String getPhotos() { return photos; }
    public void setPhotos(String photos) { this.photos = photos; }
}
