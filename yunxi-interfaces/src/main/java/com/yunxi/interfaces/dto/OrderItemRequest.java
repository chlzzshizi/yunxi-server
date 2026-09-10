package com.yunxi.interfaces.dto;

/**
 * 订单明细请求 — 嵌套 Record
 *
 * 为什么没有 unitPrice：单价是后端查 clothes_prices 算出来的。
 * 这个字段以前在前端手里，于是"1 块钱洗羽绒服"是能下单成功的。
 * 老前端多传 unitPrice 不会报错 —— Spring 默认忽略未知属性，只是被无视。
 */
public record OrderItemRequest(
        Long categoryId,
        Long washTypeId,
        Integer quantity,
        String photos
) {}
