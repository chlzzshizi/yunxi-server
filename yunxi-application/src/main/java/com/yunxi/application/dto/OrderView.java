package com.yunxi.application.dto;

import com.yunxi.common.enums.OrderSource;
import com.yunxi.common.enums.OrderStatus;
import com.yunxi.common.enums.PayMethod;
import com.yunxi.domain.order.Order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单的**出参** —— 订单详情、订单列表、建单返回共用这一个形状。
 *
 * 字段与整改前的 JSON 保持一一对应（前端与 E2E 脚本按老形状写的），
 * 所以这不是"顺手改个名"的地方：动它等于动接口契约。
 *
 * 枚举用的是 common 里的 OrderStatus / OrderSource / PayMethod，
 * 不是 domain 类型 —— common 被所有层依赖，接口层用它们不违反分层。
 * 序列化出来仍是名字（"PENDING_PAY"），与整改前一致。
 */
public record OrderView(
        Long id,
        String orderNo,
        Long storeId,
        Long customerId,
        Long staffId,
        OrderSource source,
        OrderStatus status,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        PayMethod payMethod,
        PayMethod finalPayMethod,
        LocalDateTime appointmentTime,
        String deliveryAddress,
        String expressNo,
        String remark,
        LocalDateTime finishTime,
        LocalDateTime createTime,
        List<OrderItemView> items
) {

    /**
     * 领域对象 → 出参。
     *
     * 放静态工厂而不是让每个调用方自己 new：字段有 18 个，
     * 三处（建单 / 详情 / 列表）各写一份映射，加字段时必漏其中一处。
     */
    public static OrderView from(Order order) {
        return new OrderView(
                order.getId(),
                order.getOrderNo(),
                order.getStoreId(),
                order.getCustomerId(),
                order.getStaffId(),
                order.getSource(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getPaidAmount(),
                order.getPayMethod(),
                order.getFinalPayMethod(),
                order.getAppointmentTime(),
                order.getDeliveryAddress(),
                order.getExpressNo(),
                order.getRemark(),
                order.getFinishTime(),
                order.getCreateTime(),
                order.getItems().stream().map(OrderItemView::from).toList());
    }
}
