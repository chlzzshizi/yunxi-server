package com.yunxi.interfaces.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建折扣券请求体
 */
public record CreateCouponRequest(
        String name,               // 券名称
        BigDecimal discount,       // 折扣率，0.50 = 5折
        Integer totalStock,        // 总库存
        LocalDateTime startTime,   // 开抢时间
        LocalDateTime endTime      // 截止时间
) {}