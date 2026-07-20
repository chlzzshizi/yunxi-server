package com.yunxi.interfaces.controller;

import com.yunxi.application.service.OrderAppService;
import com.yunxi.common.Result;
import com.yunxi.common.enums.OrderSource;
import com.yunxi.common.enums.PayMethod;
import com.yunxi.domain.order.Order;
import com.yunxi.domain.order.OrderItem;
import com.yunxi.interfaces.dto.CreateOrderRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单接口 — 接收前端请求，转给 Application 层。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderAppService orderAppService;

    public OrderController(OrderAppService orderAppService) {
        this.orderAppService = orderAppService;
    }

    /** 创建订单 */
    @PostMapping
    public Result<Order> createOrder(@RequestBody CreateOrderRequest request) {
        // DTO → domain 对象
        OrderSource source = OrderSource.fromCode(request.source());
        List<OrderItem> items = request.items().stream()
                .map(it -> new OrderItem(
                        it.categoryId(), it.washTypeId(),
                        it.quantity(), it.unitPrice(), it.photos()))
                .toList();
        // 交给店长
        return orderAppService.createOrder(
                request.storeId(), request.customerId(), source, items);
    }

    /** 查询订单 */
    @GetMapping("/{id}")
    public Result<Order> getOrder(@PathVariable Long id) {
        return orderAppService.getOrder(id);
    }

    /** 支付 */
    @PostMapping("/{id}/pay")
    public Result<Void> pay(@PathVariable Long id,
                            @RequestParam String payMethod,
                            @RequestParam BigDecimal amount) {
        PayMethod method = PayMethod.fromCode(payMethod);
        return orderAppService.pay(id, method, amount);
    }

    /** 状态推进 */
    @PostMapping("/{id}/next")
    public Result<Void> nextStatus(@PathVariable Long id) {
        return orderAppService.updateStatus(id);
    }

    /** 洗后付结账 */
    @PostMapping("/{id}/final-pay")
    public Result<Void> finalPay(@PathVariable Long id,
                                 @RequestParam String payMethod) {
        PayMethod method = PayMethod.fromCode(payMethod);
        return orderAppService.finalPay(id, method);
    }
}