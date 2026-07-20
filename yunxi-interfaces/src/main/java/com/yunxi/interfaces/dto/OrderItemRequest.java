package com.yunxi.interfaces.dto;

import java.math.BigDecimal; /**
 * 订单明细请求 — 嵌套 Record
 */
public record OrderItemRequest(
        Long categoryId,
        Long washTypeId,
        Integer quantity,
        BigDecimal unitPrice,
        String photos
) {}
