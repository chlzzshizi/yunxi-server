package com.yunxi.interfaces.dto;

import java.util.List;

/**
 * 创建订单请求体
 */
public record CreateOrderRequest(
        Long storeId,
        Long customerId,
        Integer source,              // 1=门店单  2=网单
        List<OrderItemRequest> items // 前端传的衣物列表
) {}

