package com.yunxi.application.dto;

import java.time.LocalDateTime;

/**
 * 创建订单时的可选信息 —— 网单预约时间/配送地址 + 通用备注。
 * 创建时一次性填写（domain 的 fillOrderInfo），后续不可改。
 */
public record OrderExtras(
        LocalDateTime appointmentTime,   // 预约取送时间（网单）
        String deliveryAddress,          // 配送地址（网单）
        String remark                    // 备注
) {
    public static final OrderExtras EMPTY = new OrderExtras(null, null, null);
}
