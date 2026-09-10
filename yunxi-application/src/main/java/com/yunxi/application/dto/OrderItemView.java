package com.yunxi.application.dto;

import com.yunxi.domain.order.OrderItem;

import java.math.BigDecimal;

/**
 * 订单明细的**出参** —— 给前端看的形状。
 *
 * 为什么要有它（而不是把 domain 的 OrderItem 直接发出去）：
 * 分层规则 interfaces → application → domain ← infrastructure 是单向的，
 * domain 对象只属于 domain 和它的使用者（application / infrastructure），
 * 不能让 HTTP 层的签名里出现 domain 类型 —— 那样 domain 改个字段名
 * 就会牵动接口契约，接口契约和领域模型被迫绑死。
 *
 * 和 PriceRow 一样：它不表达业务规则，只表达"前端想要的样子"。
 * 注意这里**没有** orderId：明细已经嵌在订单里，再带一次父 id 是冗余，
 * 前端要父 id 从外层订单对象上取就行。
 */
public record OrderItemView(
        Long id,
        Long categoryId,
        Long washTypeId,
        int quantity,
        BigDecimal unitPrice,   // 后端算出来的价（老前端传的 unitPrice 不作数）
        String photos
) {

    /** 领域对象 → 出参。转换只此一处，避免各调用方各写一份映射 */
    public static OrderItemView from(OrderItem item) {
        return new OrderItemView(
                item.getId(),
                item.getCategoryId(),
                item.getWashTypeId(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getPhotos());
    }
}
