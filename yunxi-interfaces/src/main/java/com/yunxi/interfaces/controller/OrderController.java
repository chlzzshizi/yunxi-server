package com.yunxi.interfaces.controller;

import com.yunxi.application.dto.OrderExtras;
import com.yunxi.application.dto.OrderItemCommand;
import com.yunxi.application.dto.OrderView;
import com.yunxi.application.service.OrderAppService;
import com.yunxi.common.BusinessException;
import com.yunxi.common.PageResult;
import com.yunxi.common.Result;
import com.yunxi.common.enums.OrderSource;
import com.yunxi.common.enums.OrderStatus;
import com.yunxi.common.enums.PayMethod;
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
 *   - **归属授权**（顾客只能看自己的单）在 OrderAppService，可单元测试
 *   - **角色授权**（管理员一律进不来 /api/orders）在 JwtInterceptor 统一收口，
 *     走到这里的 staff token 一定是店长
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
    public Result<OrderView> createOrder(@RequestBody CreateOrderRequest request,
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
            // 这里**不再校验单价**：价格由后端算（OrderAppService.priceItems）。
            // 请求体里根本没有这个字段了，前端传了也会被 Spring 忽略。
        }
        // 枚举转换失败会抛 BusinessException，由 GlobalExceptionHandler 统一转 400
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
            // 门店**可选**（2026-09-13 口径）：不传就是不指定，存 NULL。
            // 这里不拦 null —— 在线顾客本来就没有门店归属（§4.4：网单顾客
            // customers.store_id 为 NULL），却必须替订单挑一家店，说不通。
            // 传了值才需要回库确认它在营业，那条校验在应用层（要查库）
            storeId = request.storeId();
        }

        // ── 接口层 DTO → 应用层命令对象（单价不传，由 OrderAppService 查价目表补）──
        // 不在这里 new domain 的 OrderItem：接口层引用 domain 就破了分层（§3.1）
        List<OrderItemCommand> items = reqItems.stream()
                .map(it -> new OrderItemCommand(
                        it.categoryId(), it.washTypeId(),
                        it.quantity(), it.photos()))
                .toList();
        OrderExtras extras = new OrderExtras(
                request.appointmentTime(), request.deliveryAddress(), request.remark());

        // couponId 直接透传：券能不能用是应用层的事（要查库），
        // 这一层连"券是不是他的"都判断不了，也不该在这里写半截校验。
        // 门店单也能用券（2026-09-12 放开，§5.8）—— 所以这里不再按 source 分流
        return orderAppService.createOrder(
                storeId, customerId, source, items, operatorStaffId, extras,
                request.couponId());
    }

    /**
     * 订单列表（分页 + 可选状态筛选）
     * 员工看全部（不限门店）、顾客看自己的 —— 归属由 token 决定，前端传不了也改不了。
     * 例：GET /api/orders?status=2&page=1&pageSize=20
     */
    @GetMapping
    public Result<PageResult<OrderView>> listOrders(@RequestParam(required = false) Integer status,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int pageSize,
                                                    HttpServletRequest http) {
        String type = (String) http.getAttribute("type");
        boolean isStaff = "staff".equals(type);
        Long requesterId = isStaff
                ? (Long) http.getAttribute("staffId")
                : (Long) http.getAttribute("customerId");
        // 非法的状态码（如 status=99）会抛 BusinessException → 全局处理器转 400
        OrderStatus filter = status == null ? null : OrderStatus.fromCode(status);
        return orderAppService.listOrders(type, requesterId, filter, page, pageSize);
    }

    /** 查询订单（带归属校验：顾客只能看自己的、员工不限门店） */
    @GetMapping("/{id}")
    public Result<OrderView> getOrder(@PathVariable Long id, HttpServletRequest http) {
        String type = (String) http.getAttribute("type");
        boolean isStaff = "staff".equals(type);
        Long requesterId = isStaff
                ? (Long) http.getAttribute("staffId")
                : (Long) http.getAttribute("customerId");
        return orderAppService.getOrder(id, type, requesterId);
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

    /**
     * 顾客在线支付（仅顾客，且只能付自己的单）。
     *
     * **没有 amount 参数**，这是刻意的：金额由后端从订单上取（已是折后应付），
     * 顾客传不了也就篡改不了。员工那条 pay 保留 amount 是因为柜台要收现金，
     * 金额得由收银员输 —— 两条路不得不分开，混在一起就等于把"金额可传"
     * 这个口子开给了顾客
     */
    @PostMapping("/{id}/online-pay")
    public Result<Void> onlinePay(@PathVariable Long id,
                                  @RequestParam String payMethod,
                                  HttpServletRequest http) {
        Long customerId = requireCustomer(http);
        // 枚举转换失败（如 payMethod=abc）会抛 BusinessException → 全局处理器转 400；
        // 语法合法但不该由顾客用的（如 cash）由应用层挡，报一句更具体的话
        PayMethod method = PayMethod.fromCode(payMethod);
        return orderAppService.onlinePay(id, method, customerId);
    }

    /** 录入快递单号（仅员工、仅网单派送中） */
    @PostMapping("/{id}/express")
    public Result<Void> express(@PathVariable Long id,
                                @RequestParam String expressNo,
                                HttpServletRequest http) {
        return orderAppService.fillExpressNo(id, expressNo, requireStaff(http));
    }

    // ──────────────── 身份提取 ────────────────

    private Long requireStaff(HttpServletRequest http) {
        if (!"staff".equals(http.getAttribute("type"))) {
            throw new BusinessException(401, "请使用员工账号操作");
        }
        return (Long) http.getAttribute("staffId");
    }

    /**
     * 门店单的归属门店 —— 只信 token 里的 storeId（建单以外的订单操作都不再需要它）。
     *
     * 它现在的角色只是**默认值**："门店单落在店长自己那家店"。不是权限边界 ——
     * 所有店长都能管理所有门店的订单（设计文档 §4.2），所以拿不到 storeId
     * 也不再意味着"越权"，而是"这单不知道算哪个店"。
     *
     * 管理员曾在这里被单独提示"请用店长账号"，2026-09-11 权限收回后，
     * 管理员在 JwtInterceptor 的角色闸门就被 403 拦在 /api/orders 之外了，
     * 根本进不到这个方法 —— 所以那条分支已经删掉，别再加回来。
     *
     * **谁还会走到这一支：只有 `staff.store_id` 真的是 NULL 的店长**（2026-09-19 更正，Bug 36）。
     * 建/改店长时 `storeId` 是**允许为空**的（`StaffAdminAppService` 只在非空时才校验它存在），
     * 所以"没有门店的店长"是个**合法状态**、不是坏数据 —— 他能登录、能读价目表、能读门店列表，
     * 只是开不了门店单（订单要有"物理的店"：取件地址落在哪家）。
     *
     * 这里原先写着"只剩**旧 token**"（签发时没有 storeId claim）—— 那句话在放开无店店长之后
     * 就是假的了。而旧 token 这条路**已经绝迹**：票的有效期是 24 小时
     * （`application.yml` 的 `jwt.expiration=86400000`），storeId 是 2026-09-11 随角色
     * 一起签进 claim 的，那之前的票 09-12 就全过期了。所以这一支今天**只可能**是无店店长。
     *
     * 文案随之改过一次（2026-09-19 拍板"说实话，仍然拒绝"，Bug 36）：原先报 401
     * 「登录信息已升级，请重新登录」—— 对一个"按设计就没有门店"的账号，**重登一万次也不会
     * 带上门店**，那句话把"你没有门店"说成了"你的票过期了"（Bug 20 的形状）。
     * 现在报 403，说清是什么事、该找谁。换掉 401 还有第二个理由：401 在本项目里到处都
     * 意味着"重新登录"，客户端会照着去做一件没有用的事。
     */
    private Long requireStaffStore(HttpServletRequest http) {
        Long storeId = (Long) http.getAttribute("storeId");
        if (storeId != null) {
            return storeId;
        }
        throw new BusinessException(403, "账号还没有归属门店，无法开单，请联系管理员分配门店");
    }

    private Long requireCustomer(HttpServletRequest http) {
        if (!"customer".equals(http.getAttribute("type"))) {
            throw new BusinessException(401, "请使用顾客账号登录");
        }
        return (Long) http.getAttribute("customerId");
    }
}
