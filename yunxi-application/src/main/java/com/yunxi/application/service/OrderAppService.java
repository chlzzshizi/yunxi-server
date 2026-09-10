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
import com.yunxi.domain.price.ClothesCategory;
import com.yunxi.domain.price.ClothesPrice;
import com.yunxi.domain.price.PriceRepository;
import com.yunxi.domain.price.WashType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final PriceRepository priceRepository;

    /** 构造注入 */
    public OrderAppService(OrderRepository orderRepository, PriceRepository priceRepository) {
        this.orderRepository = orderRepository;
        this.priceRepository = priceRepository;
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
     *
     * 为什么加 @Transactional：一次建单要写 orders + order_items 两张表，
     * 明细插入失败留下一个"零明细订单"会很难看（也难修）。加事务后要么全成要么全不成。
     * 订单号重试循环在事务内：MySQL 的 InnoDB 只回滚**失败的那条语句**，
     * 事务本身还能继续用，所以撞号重试不会把前面的写入弄成"已回滚"状态。
     */
    @Transactional
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
        // 上面两个校验都在算价之前：它们不查库，早失败早省一次查询。
        // 算价必须在构造 Order **之前** —— Order 构造器会立刻累加总金额，
        // 单价没填上时分不出"0 元"和"还没算"
        priceItems(items);

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
     * 查询订单（带归属校验）—— 校验只针对顾客。
     *
     * 员工**不限门店**：所有店长都能管理所有门店的订单，门店只是地理位置的标记，
     * 不是数据隔离的边界（设计文档 §4.2）。所以员工查任何一单都放行。
     *
     * @param requesterType staff / customer
     * @param requesterId   员工 ID 或顾客 ID
     */
    public Result<Order> getOrder(Long id, String requesterType, Long requesterId) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "订单不存在"));

        if ("customer".equals(requesterType)
                && !order.getCustomerId().equals(requesterId)) {
            throw new BusinessException(403, "无权查看该订单");
        }
        return Result.ok(order);
    }

    /**
     * 分页查询订单列表 —— 和 getOrder 同一套归属规则：
     * 顾客只看自己的；员工不限门店，所以筛选条件里只可能出现 customerId，
     * 门店不参与过滤（所有店长都能管理所有门店的订单，§4.2）。
     *
     * @param requesterType staff / customer
     * @param requesterId   员工 ID 或顾客 ID
     * @param status        状态筛选，null=全部
     * @param page          页码，从 1 开始（越界会归一化，不报错）
     * @param pageSize      每页条数（钳到 1~100，防止前端要 100 万条把库拖死）
     */
    public Result<PageResult<Order>> listOrders(String requesterType, Long requesterId,
                                                OrderStatus status, int page, int pageSize) {
        // 员工不筛任何条件（看全部店的单）；顾客只按自己的 customerId 筛
        Long customerId = "customer".equals(requesterType) ? requesterId : null;

        int safePage = Math.max(page, 1);           // 页码/页大小在这里兜底，
        int safeSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);

        long total = orderRepository.count(null, customerId, status);
        List<Order> list = total == 0
                ? List.of()                          // 没数据就别再查一次了
                : orderRepository.findPage(null, customerId, status,
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
     * 给每条明细填上价目表里的单价 —— 价格只有后端说了算。
     *
     * 一次查完所有涉及分类，而不是每条明细查一次：
     * N 条明细 1 次 SQL，否则就是 N+1（前端传 10 条明细就多 10 次往返，
     * 而且多出来的都是同一个查询）。
     *
     * 两类失败要分开：
     *   - 价目表里没这个组合 = **配置缺口**（管理员忘了定价），日志留痕便于排查
     *   - 价格是 0 = **业务答案**（这个分类就是不支持这种洗法），文案能直接给用户看
     */
    private void priceItems(List<OrderItem> items) {
        Set<Long> categoryIds = new HashSet<>();
        for (OrderItem it : items) {
            if (it.getCategoryId() != null) {
                categoryIds.add(it.getCategoryId());
            }
        }
        Map<Long, List<ClothesPrice>> pricesByCategory =
                priceRepository.findPricesByCategoryIds(categoryIds);

        for (int i = 0; i < items.size(); i++) {
            OrderItem it = items.get(i);
            int no = i + 1;
            ClothesPrice matched = matchPrice(pricesByCategory, it);
            if (matched == null) {
                log.warn("价目表缺行：第 {} 条明细 categoryId={} washTypeId={}",
                        no, it.getCategoryId(), it.getWashTypeId());
                throw new BusinessException("第 " + no + " 条明细的衣物分类或洗涤方式不存在（分类 "
                        + it.getCategoryId() + " / 洗涤方式 " + it.getWashTypeId() + "）");
            }
            if (!matched.isSupported()) {
                // 分类名/洗涤方式名只为把话说清楚，所以放在这个分支里懒查 ——
                // 正常下单路径一次都不用查，不会为"报错文案"多花两次查询
                throw new BusinessException("第 " + no + " 条明细「"
                        + categoryName(it.getCategoryId()) + "」不支持「"
                        + washTypeName(it.getWashTypeId()) + "」，请更换洗涤方式");
            }
            it.applyPrice(matched.getPrice());
        }
    }

    /** 在某个分类的价格行里找指定洗涤方式；找不到返回 null（分类或洗涤方式为空也算找不到） */
    private ClothesPrice matchPrice(Map<Long, List<ClothesPrice>> pricesByCategory, OrderItem item) {
        if (item.getCategoryId() == null || item.getWashTypeId() == null) {
            return null;   // HTTP 层已先拦形状问题，这里兜底，免得 Map.get(null) 抛 NPE
        }
        List<ClothesPrice> rows = pricesByCategory.get(item.getCategoryId());
        if (rows == null) {
            return null;
        }
        for (ClothesPrice p : rows) {
            if (item.getWashTypeId().equals(p.getWashTypeId())) {
                return p;
            }
        }
        return null;
    }

    /** 分类名 —— 只为错误消息好看 */
    private String categoryName(Long categoryId) {
        return priceRepository.findCategoryById(categoryId)
                .map(ClothesCategory::getName)
                .orElse("分类 " + categoryId);
    }

    /** 洗涤方式名 —— 同上。只有 3 种，一次查回来在内存里找 */
    private String washTypeName(Long washTypeId) {
        for (WashType w : priceRepository.findAllWashTypes()) {
            if (washTypeId.equals(w.getId())) {
                return w.getName();
            }
        }
        return "洗涤方式 " + washTypeId;
    }

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
