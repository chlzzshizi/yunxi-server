package com.yunxi.interfaces.controller;

import com.yunxi.application.dto.OrderExtras;
import com.yunxi.application.service.OrderAppService;
import com.yunxi.common.BusinessException;
import com.yunxi.common.PageResult;
import com.yunxi.common.Result;
import com.yunxi.common.enums.OrderSource;
import com.yunxi.common.enums.OrderStatus;
import com.yunxi.common.enums.PayMethod;
import com.yunxi.domain.order.Order;
import com.yunxi.domain.order.OrderItem;
import com.yunxi.interfaces.dto.CreateOrderRequest;
import com.yunxi.interfaces.dto.OrderItemRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单接口 — 接收前端请求，转给 Application 层。
 *
 * 约定：
 *   - 参数校验放这一层：前端传坏数据要 400（可读），不是 500（NPE）
 *   - 身份与归属只信 token（request attribute），不信请求体
 *   - 真正的授权规则在 OrderAppService（可单元测试），这里只做身份提取
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
    public Result<Order> createOrder(@RequestBody CreateOrderRequest request,
                                     HttpServletRequest http) {
        // ── 参数校验：先拦坏数据，再碰业务 ──
        if (request.source() == null) {
            return Result.fail(400, "缺少订单来源 source");
        }
        List<OrderItemRequest> reqItems = request.items();
        if (reqItems == null || reqItems.isEmpty()) {
            return Result.fail(400, "订单至少需要一条明细");
        }
        for (int i = 0; i < reqItems.size(); i++) {
            OrderItemRequest it = reqItems.get(i);
            String no = "第 " + (i + 1) + " 条明细";
            if (it == null || it.categoryId() == null || it.washTypeId() == null) {
                return Result.fail(400, no + "缺少衣物分类或洗涤方式");
            }
            if (it.quantity() == null || it.quantity() <= 0) {
                return Result.fail(400, no + "数量必须为正整数");
            }
            if (it.unitPrice() == null || it.unitPrice().signum() < 0) {
                return Result.fail(400, no + "单价不能为空或负数");
            }
        }
        // 枚举转换失败会抛 IllegalArgumentException，由 GlobalExceptionHandler 统一转 400
        OrderSource source = OrderSource.fromCode(request.source());

        // ── 身份与归属：只信 token ──
        Long storeId;
        Long customerId;
        Long operatorStaffId = null;
        if (source == OrderSource.STORE) {
            // 门店单：员工代客下单，门店 = 员工所属门店（请求体里的 storeId 忽略）
            operatorStaffId = requireStaff(http);
            storeId = requireStaffStore(http);
            if (request.customerId() == null) {
                return Result.fail(400, "门店单必须指定顾客 customerId");
            }
            customerId = request.customerId();
        } else {
            // 网单：顾客自助下单，顾客 = token 身份（请求体里的 customerId 忽略）
            customerId = requireCustomer(http);
            storeId = request.storeId();
            if (storeId == null) {
                return Result.fail(400, "网单必须指定门店 storeId");
            }
        }

        // ── DTO → domain ──
        List<OrderItem> items = reqItems.stream()
                .map(it -> new OrderItem(
                        it.categoryId(), it.washTypeId(),
                        it.quantity(), it.unitPrice(), it.photos()))
                .toList();
        OrderExtras extras = new OrderExtras(
                request.appointmentTime(), request.deliveryAddress(), request.remark());

        return orderAppService.createOrder(
                storeId, customerId, source, items, operatorStaffId, extras);
    }

    /**
     * 订单列表（分页 + 可选状态筛选）
     * 员工看本店、顾客看自己的 —— 归属由 token 决定，前端传不了也改不了。
     * 例：GET /api/orders?status=2&page=1&pageSize=20
     */
    @GetMapping
    public Result<PageResult<Order>> listOrders(@RequestParam(required = false) Integer status,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int pageSize,
                                                HttpServletRequest http) {
        String type = (String) http.getAttribute("type");
        boolean isStaff = "staff".equals(type);
        Long requesterId = isStaff
                ? (Long) http.getAttribute("staffId")
                : (Long) http.getAttribute("customerId");
        // 管理员 / 旧 token 在这里就被挡掉，不会带着 null 门店混进查询
        Long requesterStoreId = isStaff ? requireStaffStore(http) : null;
        // 非法的状态码（如 status=99）会抛 IllegalArgumentException → 全局处理器转 400
        OrderStatus filter = status == null ? null : OrderStatus.fromCode(status);
        return orderAppService.listOrders(type, requesterId, requesterStoreId,
                filter, page, pageSize);
    }

    /** 查询订单（带归属校验：顾客只能看自己的、员工只能看本店的） */
    @GetMapping("/{id}")
    public Result<Order> getOrder(@PathVariable Long id, HttpServletRequest http) {
        String type = (String) http.getAttribute("type");
        boolean isStaff = "staff".equals(type);
        Long requesterId = isStaff
                ? (Long) http.getAttribute("staffId")
                : (Long) http.getAttribute("customerId");
        Long requesterStoreId = isStaff ? requireStaffStore(http) : null;
        return orderAppService.getOrder(id, type, requesterId, requesterStoreId);
    }

    /** 支付（仅员工） */
    @PostMapping("/{id}/pay")
    public Result<Void> pay(@PathVariable Long id,
                            @RequestParam String payMethod,
                            @RequestParam BigDecimal amount,
                            HttpServletRequest http) {
        Long staffId = requireStaff(http);
        PayMethod method = PayMethod.fromCode(payMethod);
        return orderAppService.pay(id, method, amount, staffId);
    }

    /** 状态推进（仅员工） */
    @PostMapping("/{id}/next")
    public Result<Void> nextStatus(@PathVariable Long id, HttpServletRequest http) {
        return orderAppService.updateStatus(id, requireStaff(http));
    }

    /** 洗后付结账（仅员工） */
    @PostMapping("/{id}/final-pay")
    public Result<Void> finalPay(@PathVariable Long id,
                                 @RequestParam String payMethod,
                                 HttpServletRequest http) {
        Long staffId = requireStaff(http);
        PayMethod method = PayMethod.fromCode(payMethod);
        return orderAppService.finalPay(id, method, staffId);
    }

    // ──────────────── 身份提取 ────────────────

    private Long requireStaff(HttpServletRequest http) {
        if (!"staff".equals(http.getAttribute("type"))) {
            throw new BusinessException(401, "请使用员工账号操作");
        }
        return (Long) http.getAttribute("staffId");
    }

    /**
     * 员工所属门店。
     *
     * storeId 为 null 有**两种**完全不同的原因，不能糊成一句"请重新登录"：
     *   - 管理员（role=0）：staff.store_id 按设计就是 NULL（不隶属门店），
     *     他重登一百次还是 null —— 该说清楚"订单是门店维度的事务，请用店长账号"
     *   - 店长（role=1）：店长必有门店，拿不到只可能是旧 token 缺 storeId claim，
     *     这才是"重新登录就能解决"的情况
     */
    private Long requireStaffStore(HttpServletRequest http) {
        Long storeId = (Long) http.getAttribute("storeId");
        if (storeId != null) {
            return storeId;
        }
        Integer role = (Integer) http.getAttribute("role");
        if (role != null && role == 0) {
            throw new BusinessException(403, "管理员账号不隶属门店，订单操作请使用店长账号");
        }
        throw new BusinessException(401, "登录信息已升级，请重新登录");
    }

    private Long requireCustomer(HttpServletRequest http) {
        if (!"customer".equals(http.getAttribute("type"))) {
            throw new BusinessException(401, "请使用顾客账号登录");
        }
        return (Long) http.getAttribute("customerId");
    }
}
