package com.yunxi.application.service;

import com.yunxi.application.dto.OrderExtras;
import com.yunxi.common.BusinessException;
import com.yunxi.common.PageResult;
import com.yunxi.common.Result;
import com.yunxi.common.enums.OrderSource;
import com.yunxi.common.enums.OrderStatus;
import com.yunxi.common.enums.PayMethod;
import com.yunxi.domain.order.Order;
import com.yunxi.domain.order.OrderItem;
import com.yunxi.domain.order.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单应用服务 —— 编排领域对象与仓储，并守住"谁能操作"的应用级规则。
 * 授权规则放在这一层（而不是 controller）便于单元测试与复用。
 */
@Service
public class OrderAppService {

    private static final Logger log = LoggerFactory.getLogger(OrderAppService.class);

    /** 订单号撞库后的最大重试次数 */
    private static final int ORDER_NO_MAX_ATTEMPTS = 3;

    /** 每页最大条数 —— 前端传再大也不给，防止一次拉爆数据库 */
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orderRepository;

    /** 构造注入 */
    public OrderAppService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    // ──────────────── 创建 ────────────────

    /**
     * 创建订单
     * @param storeId        归属门店（门店单=员工 token 里的店；网单=下单时选的店）
     * @param customerId     顾客（门店单=员工输入；网单=顾客 token 身份）
     * @param source         门店单 / 网单
     * @param items          订单明细列表
     * @param operatorStaffId 操作员工（门店单必填；网单为 null）
     * @param extras         网单要素/备注（可为 OrderExtras.EMPTY）
     */
    public Result<Order> createOrder(Long storeId, Long customerId,
                                     OrderSource source, List<OrderItem> items,
                                     Long operatorStaffId, OrderExtras extras) {
        // 错误一律抛 BusinessException（GlobalExceptionHandler 统一转 Result.fail），
        // 不要在这一层 return Result.fail —— 调用方没法 catch，风格也不统一
        if (items == null || items.isEmpty()) {
            throw new BusinessException("订单至少需要一条明细");
        }
        // 门店单是员工代客下单——必须有人操作，谁操作的也要留痕
        if (source == OrderSource.STORE) {
            requireStaff(operatorStaffId);
        }

        // 2. 建单 + 落库；撞上 orders.uk_order_no 唯一索引就换个号重来
        for (int attempt = 1; attempt <= ORDER_NO_MAX_ATTEMPTS; attempt++) {
            Order order = new Order(generateOrderNo(), storeId, customerId, source, items);
            if (operatorStaffId != null) {
                order.recordOperator(operatorStaffId);
            }
            if (extras != null) {
                order.fillOrderInfo(extras.appointmentTime(),
                        extras.deliveryAddress(), extras.remark());
            }
            try {
                orderRepository.save(order);
                return Result.ok(order);
            } catch (DuplicateKeyException e) {
                log.warn("订单号冲突（第 {} 次），换号重试: {}", attempt, order.getOrderNo());
            }
        }
        // 撞一两次是小概率，连着撞满说明生成策略有毛病 —— 别硬撑，报错让人看见
        throw new BusinessException(500, "订单号生成冲突，请稍后重试");
    }

    // ──────────────── 查询 ────────────────

    /**
     * 查询订单（带归属校验）
     * @param requesterType      staff / customer
     * @param requesterId        员工 ID 或顾客 ID
     * @param requesterStoreId   员工所属门店（顾客传 null）
     */
    public Result<Order> getOrder(Long id, String requesterType,
                                  Long requesterId, Long requesterStoreId) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "订单不存在"));

        if ("customer".equals(requesterType)) {
            // 顾客只能看自己的订单
            if (!order.getCustomerId().equals(requesterId)) {
                throw new BusinessException(403, "无权查看该订单");
            }
        } else {
            // 员工只能看本门店的订单（旧 token 无 storeId → 提示重登换取新 token）
            if (requesterStoreId == null) {
                throw new BusinessException(401, "登录信息已升级，请重新登录");
            }
            if (!requesterStoreId.equals(order.getStoreId())) {
                throw new BusinessException(403, "无权查看其他门店的订单");
            }
        }
        return Result.ok(order);
    }

    /**
     * 分页查询订单列表 —— 和 getOrder 一样带归属隔离：
     * 员工只看本店、顾客只看自己的，筛选条件由调用方身份决定，不用前端传。
     *
     * @param requesterType    staff / customer
     * @param requesterId      员工 ID 或顾客 ID
     * @param requesterStoreId 员工所属门店（顾客传 null）
     * @param status           状态筛选，null=全部
     * @param page             页码，从 1 开始（越界会归一化，不报错）
     * @param pageSize         每页条数（钳到 1~100，防止前端要 100 万条把库拖死）
     */
    public Result<PageResult<Order>> listOrders(String requesterType, Long requesterId,
                                                Long requesterStoreId, OrderStatus status,
                                                int page, int pageSize) {
        Long storeId = null;
        Long customerId = null;
        if ("customer".equals(requesterType)) {
            customerId = requesterId;               // 顾客：只能看自己的
        } else {
            if (requesterStoreId == null) {         // 员工：只能看本店的
                throw new BusinessException(401, "登录信息已升级，请重新登录");
            }
            storeId = requesterStoreId;
        }

        int safePage = Math.max(page, 1);           // 页码/页大小在这里兜底，
        int safeSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);

        long total = orderRepository.count(storeId, customerId, status);
        List<Order> list = total == 0
                ? List.of()                          // 没数据就别再查一次了
                : orderRepository.findPage(storeId, customerId, status,
                        (safePage - 1) * safeSize, safeSize);
        return Result.ok(PageResult.of(list, total, safePage, safeSize));
    }

    // ──────────────── 状态操作（仅员工）────────────────

    /**
     * 支付 — 先付传全额，洗后付传 0
     */
    public Result<Void> pay(Long orderId, PayMethod payMethod,
                            BigDecimal amount, Long operatorStaffId) {
        requireStaff(operatorStaffId);
        Order order = load(orderId);
        OrderStatus before = order.getStatus();
        order.pay(payMethod, amount);            // 领域规则把关（金额/状态）
        saveStatusChange(order, before, operatorStaffId);
        return Result.ok(null);
    }

    /**
     * 状态推进
     */
    public Result<Void> updateStatus(Long orderId, Long operatorStaffId) {
        requireStaff(operatorStaffId);
        Order order = load(orderId);
        OrderStatus before = order.getStatus();
        order.updateStatus();                    // 领域规则把关（状态机流转）
        saveStatusChange(order, before, operatorStaffId);
        return Result.ok(null);
    }

    /**
     * 洗后付结账
     */
    public Result<Void> finalPay(Long orderId, PayMethod payMethod, Long operatorStaffId) {
        requireStaff(operatorStaffId);
        Order order = load(orderId);
        OrderStatus before = order.getStatus();
        order.finalPay(payMethod);               // 领域规则把关（状态 5/6/7）
        saveStatusChange(order, before, operatorStaffId);
        return Result.ok(null);
    }

    // ──────────────── 内部工具 ────────────────

    /**
     * 生成订单号 —— "YX" + 秒级时间戳 + 4 位随机数。
     *
     * 为什么不是纯时间戳：同一秒内两笔单会拿到一模一样的号，
     * 撞上 uk_order_no 唯一索引直接插入失败。
     * 为什么不是 6 位"当日序号"：那要额外查一次库拿 max(seq)，
     * 并发下还得锁，为了个展示用的编号不值得。
     * 4 位随机 = 同一秒内 1/10000 的碰撞率，再加下面的重试兜底。
     */
    static String generateOrderNo() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int rand = ThreadLocalRandom.current().nextInt(10000);   // 0~9999
        return "YX" + ts + String.format("%04d", rand);
    }

    /** 取订单，没有就 404 */
    private Order load(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(404, "订单不存在"));
    }

    /**
     * 保存状态变更 —— 乐观并发控制。
     *
     * 读-改-写之间存在时间窗：两个员工同时点同一单，双方读到的都是"待支付"，
     * 各自在内存里推进一次再写回 —— 后写的覆盖先写的，同一单被推进两格。
     * 让 UPDATE 带上"期望状态"条件后，第二个人的 UPDATE 命中 0 行 → 409。
     *
     * 注意：此时内存里的 order 已经是新状态了，直接抛异常不回滚内存对象也没关系，
     * 因为它是一次请求内的临时对象，不会跑到别的地方去。
     */
    private void saveStatusChange(Order order, OrderStatus beforeStatus, Long operatorStaffId) {
        order.recordOperator(operatorStaffId);
        boolean updated = orderRepository.updateStatusCas(order, beforeStatus);
        if (!updated) {
            throw new BusinessException(409, "订单状态已被其他人变更，请刷新后重试");
        }
    }

    /** 状态操作只允许员工（顾客 token 传到这里 operatorStaffId 为 null） */
    private void requireStaff(Long operatorStaffId) {
        if (operatorStaffId == null) {
            throw new BusinessException(401, "请使用员工账号操作");
        }
    }
}
