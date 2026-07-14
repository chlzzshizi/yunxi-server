package com.yunxi.infrastructure.persistence.po;

import com.yunxi.common.enums.OrderSource;
import com.yunxi.common.enums.OrderStatus;
import com.yunxi.common.enums.PayMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;
public class OrderPO {
    private Long id;
    private String orderNo;
    private Long storeId;
    private Long customerId;
    private Long staffId;
    private Integer source;      // 数据库存数字，不是枚举
    private Integer status;      // 数据库存数字
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private String payMethod;     // 数据库存字符串
    private String finalPayMethod;
    private LocalDateTime appointmentTime;
    private String deliveryAddress;
    private String expressNo;
    private String remark;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;

    // ── getter / setter ──
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }

    public Long getStaffId() { return staffId; }
    public void setStaffId(Long staffId) { this.staffId = staffId; }

    public Integer getSource() { return source; }
    public void setSource(Integer source) { this.source = source; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }

    public String getPayMethod() { return payMethod; }
    public void setPayMethod(String payMethod) { this.payMethod = payMethod; }

    public String getFinalPayMethod() { return finalPayMethod; }
    public void setFinalPayMethod(String finalPayMethod) { this.finalPayMethod = finalPayMethod; }

    public LocalDateTime getAppointmentTime() { return appointmentTime; }
    public void setAppointmentTime(LocalDateTime appointmentTime) { this.appointmentTime = appointmentTime; }

    public String getDeliveryAddress() { return deliveryAddress; }
    public void setDeliveryAddress(String deliveryAddress) { this.deliveryAddress = deliveryAddress; }

    public String getExpressNo() { return expressNo; }
    public void setExpressNo(String expressNo) { this.expressNo = expressNo; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public LocalDateTime getFinishTime() { return finishTime; }
    public void setFinishTime(LocalDateTime finishTime) { this.finishTime = finishTime; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
