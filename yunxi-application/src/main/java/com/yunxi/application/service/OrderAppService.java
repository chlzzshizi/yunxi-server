package com.yunxi.application.service;

import com.yunxi.application.dto.OrderExtras;
import com.yunxi.application.dto.OrderItemCommand;
import com.yunxi.application.dto.OrderView;
import com.yunxi.common.BusinessException;
import com.yunxi.common.PageResult;
import com.yunxi.common.Result;
import com.yunxi.common.enums.OrderSource;
import com.yunxi.common.enums.OrderStatus;
import com.yunxi.common.enums.PayMethod;
import com.yunxi.domain.customer.CustomerRepository;
import com.yunxi.domain.order.Order;
import com.yunxi.domain.order.OrderItem;
import com.yunxi.domain.order.OrderRepository;
import com.yunxi.domain.price.ClothesCategory;
import com.yunxi.domain.price.ClothesPrice;
import com.yunxi.domain.price.PriceRepository;
import com.yunxi.domain.price.WashType;
import com.yunxi.domain.store.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private final StoreRepository storeRepository;
    private final CustomerRepository customerRepository;
    private final CouponAppService couponAppService;

    /** 构造注入 */
    public OrderAppService(OrderRepository orderRepository, PriceRepository priceRepository,
                           StoreRepository storeRepository, CustomerRepository customerRepository,
                           CouponAppService couponAppService) {
        this.orderRepository = orderRepository;
        this.priceRepository = priceRepository;
        this.storeRepository = storeRepository;
        this.customerRepository = customerRepository;
        this.couponAppService = couponAppService;
    }

    // ──────────────── 创建 ────────────────

    /**
     * 创建订单
     * @param storeId        归属门店（门店单=员工 token 里的店；网单=下单时选的店，**可不选**，则为 null）
     * @param customerId     顾客（门店单=员工输入；网单=顾客 token 身份）
     * @param source         门店单 / 网单
     * @param commands       订单明细（应用层命令对象，不含价格）
     * @param operatorStaffId 操作员工（门店单必填；网单为 null）
     * @param extras         网单要素/备注（可为 OrderExtras.EMPTY）
     * @param couponId        优惠券（可为 null）。门店单和网单都能用（§5.8）
     *
     * 为什么加 @Transactional：一次建单要写 orders + order_items 两张表，
     * 明细插入失败留下一个"零明细订单"会很难看（也难修）。加事务后要么全成要么全不成。
     * 订单号重试循环在事务内：MySQL 的 InnoDB 只回滚**失败的那条语句**，
     * 事务本身还能继续用，所以撞号重试不会把前面的写入弄成"已回滚"状态。
     *
     * **券的核销也在这个事务里**：券 CAS 命中 0 行时抛 409，订单跟着一起回滚 ——
     * 不会出现"券烧了单没建成"或"单建了券没烧掉"的半截状态。
     */
    @Transactional
    public Result<OrderView> createOrder(Long storeId, Long customerId,
                                         OrderSource source, List<OrderItemCommand> commands,
                                         Long operatorStaffId, OrderExtras extras, Long couponId) {
        // 错误一律抛 BusinessException（GlobalExceptionHandler 统一转 Result.fail），
        // 不要在这一层 return Result.fail —— 调用方没法 catch，风格也不统一
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException("订单至少需要一条明细");
        }
        // 门店单是员工代客下单——必须有人操作，谁操作的也要留痕
        if (source == OrderSource.STORE) {
            requireStaff(operatorStaffId);
        }
        // 两处「回库确认」，各守一个**来自请求体、无法自证**的值：
        //   网单   —— storeId 是顾客在请求体里挑的 → 确认它还在营业
        //   门店单 —— customerId 是员工在请求体里填的 → 确认这个人还在档案里
        // 另一半（门店单的 storeId、网单的 customerId）都取自 token，是服务器自己签发的。
        // 同一个值，一个来自请求体、一个来自 token，可信度不一样，校验也就不一样。
        //
        // orders 表对这两列**都没有外键**，不在这里守就没人守了：
        // 漏掉门店校验会建出一张"要送到不存在的地方"的网单；
        // 漏掉顾客校验会建出一张挂在幽灵顾客身上的单 —— 它不报错，
        // 但从此所有"按顾客查订单"的地方都会莫名其妙地少一条，极难排查
        //
        // 网单的门店**可选**（2026-09-13 口径）：不指定就存 NULL，所以这条是"给了才校"。
        // null = "顾客没选"，不是"挑了个坏店"，两者必须在判断里分开 —— 混在一起的话，
        // 不选门店会被报成"门店不存在或已停业"，一句话把顾客指向错误的方向。
        if (source == OrderSource.ONLINE && storeId != null
                && storeRepository.findOpenById(storeId).isEmpty()) {
            // 门店不存在 or 已停业，用同一句话：对外都是"这家店现在下不了单"，
            // 顾客不需要（也不该）知道是"没这家店"还是"店关了"
            throw new BusinessException("门店不存在或已停业，请重新选择门店");
        }
        if (source == OrderSource.STORE
                && customerRepository.findById(customerId).isEmpty()) {
            // 这里不区分"没这个人"和"customerId 没传"：门店单的流程是先
            // lookup-or-create 拿到 id 再建单，两种都是同一个动作出错 —— 重新查一遍
            throw new BusinessException("顾客不存在，请重新查询或建档");
        }
        // 券的友好校验：能不能用、是不是这位顾客的，用不了就带着具体原因 400。
        // 放在算价之前（和上面两条同样理由：坏参数先失败，省一次价目表查询）。
        // 真正的权威判定不在这里，在落库之后那条 CAS —— 这里只是让失败说得清原因。
        //
        // 注意校验用的是 couponId 对应的**券**、customerId 对应的**单的顾客**：
        // 门店单的 customerId 是员工填的，所以"券属于这位顾客"这件事由员工自证，
        // 拦不住冒用（§5.8 把这条代价写明了）。它的价值是让错误指对方向
        BigDecimal couponDiscount = couponId == null
                ? null
                : couponAppService.resolveDiscount(couponId, customerId);
        // 命令对象 → 领域明细。转换放在"校验之后、算价之前"：
        // 转换里可能要拆箱，先让上面两条校验把空/缺字段的请求挡掉
        List<OrderItem> items = toItems(commands);
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
            // 折扣必须在**循环内**打：每次重试都是一张新 new 出来的 Order，
            // 构造器会按明细重新算一遍折前总价。放到循环外就等于对上一轮那张
            // （已经丢掉的）对象打了折，重试成功后落库的是一张全价单 ——
            // 而这种错只出现在撞号的罕见路径上，平时的用例一次都碰不到
            if (couponDiscount != null) {
                order.applyCoupon(couponId, couponDiscount);
            }
            try {
                orderRepository.save(order);
                // 核销放在**落库成功之后**，两个独立理由：撞号重试会白白烧掉
                // 顾客的券；used_order_id 得等订单拿到自增 id 才写得出。
                // CAS 命中 0 行 → 409 → 整个事务回滚，上面那笔订单跟着消失 ——
                // 券和订单不会只成一半
                if (couponDiscount != null) {
                    couponAppService.consume(couponId, customerId,
                            order.getId(), operatorStaffId);
                }
                return Result.ok(OrderView.from(order));
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
    public Result<OrderView> getOrder(Long id, String requesterType, Long requesterId) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "订单不存在"));

        if ("customer".equals(requesterType)
                && !order.getCustomerId().equals(requesterId)) {
            throw new BusinessException(403, "无权查看该订单");
        }
        return Result.ok(OrderView.from(order));
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
    public Result<PageResult<OrderView>> listOrders(String requesterType, Long requesterId,
                                                    OrderStatus status, int page, int pageSize) {
        // 员工不筛任何条件（看全部店的单）；顾客只按自己的 customerId 筛
        Long customerId = "customer".equals(requesterType) ? requesterId : null;

        int safePage = Math.max(page, 1);           // 页码/页大小在这里兜底，
        int safeSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);

        long total = orderRepository.count(null, customerId, status);
        List<OrderView> list = total == 0
                ? List.of()                          // 没数据就别再查一次了
                : orderRepository.findPage(null, customerId, status,
                        (safePage - 1) * safeSize, safeSize)
                        .stream().map(OrderView::from).toList();
        return Result.ok(PageResult.of(list, total, safePage, safeSize));
    }

    // ──────────────── 状态操作 ────────────────
    //   pay / updateStatus / finalPay / fillExpressNo —— 员工
    //   onlinePay                                   —— 顾客自助（唯一的顾客侧写操作）

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

    /**
     * 顾客在线支付 —— 员工 {@link #pay} 的顾客侧对应物（现金/柜台代收仍走 pay）。
     *
     * 金额**不由顾客传**：直接取 order.getTotalAmount()。因为 total_amount 存的
     * 就是折后应付（§5.8），这里天然收的就是正确的钱 —— 这个端点上没有任何一个
     * 可以被篡改的数字，顾客改不了价，也改不了金额。
     *
     * 券也一样碰不到：券在**建单时**就核销掉了，支付这一步只是把钱补上。
     *
     * @param customerId 从 token 取的顾客 ID（不是请求体）
     */
    public Result<Void> onlinePay(Long orderId, PayMethod payMethod, Long customerId) {
        requireCustomer(customerId);
        Order order = load(orderId);                       // 404
        if (!order.getCustomerId().equals(customerId)) {
            // 403 而不是 404：这单确实存在，只是不是他的。说清楚比藏起来有用 ——
            // 藏起来会让"我明明下了这一单"变成一个查不出来的问题
            throw new BusinessException(403, "无权支付该订单");
        }
        // 只认微信/支付宝：这是"顾客自助"能用的支付方式。现金和余额是柜台动作，
        // 顾客在手机上点不出来 —— 放行的话等于让一张单凭空变成"已在柜台付了现金"
        if (payMethod != PayMethod.WECHAT && payMethod != PayMethod.ALIPAY) {
            throw new BusinessException("网单支付方式只支持微信或支付宝");
        }
        OrderStatus before = order.getStatus();
        order.pay(payMethod, order.getTotalAmount());      // 领域规则把关（1 态、金额）
        // 操作人是顾客，没有 staffId 可记 —— 传 null 表示"不动这一列"（见 saveStatusChange）
        saveStatusChange(order, before, null);
        return Result.ok(null);
    }

    /**
     * 录入快递单号（仅员工）。
     *
     * 状态**不变**（6 → 6），但仍然走同一条 CAS：设计文档 §11.4 要求所有会改
     * status / paid_amount / pay_method / final_pay_method / staff_id / express_no /
     * finish_time 的写入都从那一句出去，没有旁路。这次 CAS 还有个实际作用 ——
     * 一个员工点"录入单号"的同时另一个点了"推进"，后到的那次会命中 0 行拿到 409，
     * 而不是把单号写到一张已经完成的单上
     */
    public Result<Void> fillExpressNo(Long orderId, String expressNo, Long operatorStaffId) {
        requireStaff(operatorStaffId);
        Order order = load(orderId);
        OrderStatus before = order.getStatus();
        order.fillExpressNo(expressNo);     // 领域规则把关（来源/状态/空值/长度）
        saveStatusChange(order, before, operatorStaffId);
        return Result.ok(null);
    }

    // ──────────────── 内部工具 ────────────────

    /**
     * 命令对象 → 领域明细。
     *
     * 这一层再校验一次"字段全不全"，不是不信任 controller：
     * 应用服务是**用例的入口**，将来还会有别的入口（定时任务、后台脚本、
     * 别人写的第二个前端）调它，校验不能押在某一个入口的自觉上。
     * controller 那份校验留着是为了给出更好的 400 文案（带明细序号）。
     *
     * 这里只做"形状"校验；"这个分类能不能洗"“价格是多少"是算价的事，不在这。
     */
    private List<OrderItem> toItems(List<OrderItemCommand> commands) {
        List<OrderItem> items = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            OrderItemCommand c = commands.get(i);
            int no = i + 1;
            if (c == null || c.categoryId() == null
                    || c.washTypeId() == null || c.quantity() == null) {
                throw new BusinessException("第 " + no + " 条明细缺少衣物分类、洗涤方式或数量");
            }
            // 数量必须为正整数（2026-09-19 拍板，补的是下面这段注释自己立的规矩）：
            // `quantity=0` 时这条明细的 subtotal 是 0.00 —— 它是求和的中性元，
            // 所以**一条 0 就能把 totalAmount 拖到 0**，又走回 Bug 43 那条路
            // （pay(0) 同时满足"全额"和"0"两个条件 → requirePaidOff 放行 → 白洗到终态 7）。
            // 负数更坏：单价永远是正的（价目表那边 `signum() > 0` 卡着），
            // 负数量是唯一能把明细做成负数的手法，能和别的明细**对冲**把总额压低 ——
            // 少付钱还判"付清了"，而且 "-1 件衬衫"会直接落库。
            // 文案与 OrderController 那条**逐字相同**：同一个错误不该有两种说法。
            if (c.quantity() <= 0) {
                throw new BusinessException("第 " + no + " 条明细数量必须为正整数");
            }
            items.add(new OrderItem(c.categoryId(), c.washTypeId(),
                    c.quantity(), c.photos()));
        }
        return items;
    }

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
        // 只有真的有人时才覆盖 staff_id：顾客在线支付传的是 null，而无条件
        // recordOperator(null) 会把这一列**抹掉** —— 网单上它本来就是 null 无所谓，
        // 但门店单上那是建单员工，抹掉等于把"谁经手的"这条线索删了。
        // 对现有四个员工侧调用方来说这个 if 永远为真，行为一个字没变
        if (operatorStaffId != null) {
            order.recordOperator(operatorStaffId);
        }
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

    /** 顾客自助操作只允许顾客（员工 token 传到这里 customerId 为 null） */
    private void requireCustomer(Long customerId) {
        if (customerId == null) {
            throw new BusinessException(401, "请使用顾客账号登录");
        }
    }
}
