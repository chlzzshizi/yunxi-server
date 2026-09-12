package com.yunxi.interfaces.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建订单请求体
 * 注意：门店单忽略 storeId（用 token 里的门店）、网单忽略 customerId（用 token 身份）
 */
public record CreateOrderRequest(
        Long storeId,                // 网单：归属门店；门店单：忽略
        Long customerId,             // 门店单：必填（员工输入）；网单：忽略
        Integer source,              // 1=门店单  2=网单
        List<OrderItemRequest> items,// 前端传的衣物列表
        LocalDateTime appointmentTime, // 网单：预约取送时间
        String deliveryAddress,      // 网单：配送地址
        String remark,               // 备注
        Long couponId                // 优惠券（coupons.id，不是抢券记录 id）。门店单/网单都能用，见 §5.8
) {}
