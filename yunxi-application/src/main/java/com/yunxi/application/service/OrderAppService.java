package com.yunxi.application.service;

import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import com.yunxi.common.enums.OrderSource;
import com.yunxi.common.enums.PayMethod;
import com.yunxi.domain.order.Order;
import com.yunxi.domain.order.OrderItem;
import com.yunxi.domain.order.OrderRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class OrderAppService {

    private final OrderRepository orderRepository;

    /** 构造注入 */
    public OrderAppService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * 创建订单
     * @param storeId    门店 ID
     * @param customerId 客户 ID
     * @param source     门店单 / 网单
     * @param items      订单明细列表
     */
    public Result<Order> createOrder(Long storeId, Long customerId,
                                     OrderSource source, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            return Result.fail(400, "订单至少需要一条明细");
        }
        // 1. 生成订单编号（日期 + 6 位序号，简化版用时间戳）
        String orderNo = "YX" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

        // 2. 创建订单（状态机初始化为"待支付"）
        Order order = new Order(orderNo, storeId, customerId, source, items);

        // 3. 落库
        orderRepository.save(order);

        // 4. 返回
        return Result.ok(order);
    }

    /**
     * 查询订单
     */
    public Result<Order> getOrder(Long id) {
        return orderRepository.findById(id)
                .map(Result::ok)
                .orElse(Result.fail(404, "订单不存在"));
    }

    /**
     * 支付 — 先付传全额，洗后付传 0
     */
    public Result<Void> pay(Long orderId, PayMethod payMethod, BigDecimal amount) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));
        order.pay(payMethod, amount);
        orderRepository.save(order);
        return Result.ok(null);
    }

    /**
     * 状态推进
     */
    public Result<Void> updateStatus(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));
        order.updateStatus();
        orderRepository.save(order);
        return Result.ok(null);
    }

    /**
     * 洗后付结账
     */
    public Result<Void> finalPay(Long orderId, PayMethod payMethod) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));
        order.finalPay(payMethod);
        orderRepository.save(order);
        return Result.ok(null);
    }

}
