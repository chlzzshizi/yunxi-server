package com.yunxi.domain.order;

import com.yunxi.common.BusinessException;
import com.yunxi.common.enums.OrderSource;
import com.yunxi.common.enums.OrderStatus;
import com.yunxi.common.enums.PayMethod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
public class Order {

    /** 快递单号长度上限，与 orders.express_no VARCHAR(50) 对齐 */
    public static final int EXPRESS_NO_MAX_LENGTH = 50;

    private Long id;
    private String orderNo;             // 订单编号
    private Long storeId;               // 所属门店
    private Long customerId;            // 客户ID
    private Long staffId;               // 操作员工ID
    private OrderSource source;         // 门店单 / 网单
    private OrderStatus status;         // 当前状态
    private BigDecimal totalAmount;     // 总金额（用券后是**折后应付**）
    private BigDecimal discountAmount;  // 券抵扣金额（只作展示/对账）
    private Long couponId;              // 使用的优惠券（coupon_grabs 的那一行）
    private BigDecimal paidAmount;      // 已付金额
    private PayMethod payMethod;        // 支付方式（pay 时填）
    private PayMethod finalPayMethod;   // 最终支付方式（finalPay 时填）
    private LocalDateTime appointmentTime; // 预约时间（网单）
    private String deliveryAddress;     // 配送地址（网单）
    private String expressNo;           // 快递单号（网单）
    private String remark;              // 备注
    private LocalDateTime finishTime;   // 完成时间
    private LocalDateTime createTime;   // 创建时间

    private List<OrderItem> items = new ArrayList<>();// 订单明细
    // ────────── 构造方法 ──────────

    /** 无参构造 — MyBatis 用 */
    public Order() {}
    /**
     * 创建新订单。
     *
     * **空明细当场拒**（2026-09-18 补的第二道闸）：明细为空时求和得到 reduce 的
     * 单位元 0，于是 totalAmount=0 → `pay(0)` 同时满足"等于全额"和"洗后付的 0"
     * 两个条件 → `requirePaidOff` 问的是"付得够不够"，0 &lt; 0 为假 → 一路推到终态 7，
     * 一分钱没收。原先唯一的闸在 `OrderController:50-52`，换定时任务 / 后台脚本 /
     * 第二个前端就绕过去了 —— 与 `fillExpressNo` 的长度校验同一个理由：
     * **订单自己的不变量，换任何入口进来都绕不掉**。
     *
     * **数量必须为正整数**（2026-09-19 补的第三道闸，紧挨着上一道）：空明细只是
     * "怎么把总额弄成 0"的一种输入；`quantity=0` 的明细 subtotal 也是 0.00 ——
     * **同一条路，另一个入口**。负数则能和其他明细对冲，把总额压到"付得起的那个数"。
     *
     * 闸放在这里而不是 `OrderItem` 构造器：那个带单价的构造器是**从库里恢复**用的
     * （注释自己写着），把规则塞进去，历史脏数据一读就炸，而这不是它该管的事 ——
     * 建单才是不变量成立的地方。
     */
    public Order(String orderNo, Long storeId, Long customerId,
                 OrderSource source, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            // 与 OrderController 那句用词一致：同一个错误不该有两种说法
            throw new BusinessException("订单至少要有一条明细");
        }
        for (int i = 0; i < items.size(); i++) {
            OrderItem it = items.get(i);
            if (it == null || it.getQuantity() <= 0) {
                // 文案与应用层 / 接口层逐字一致；这里带序号是因为到了这一层，
                // 调用方可能压根没分序号（定时任务、脚本）
                throw new BusinessException("第 " + (i + 1) + " 条明细数量必须为正整数");
            }
        }
        this.orderNo = orderNo;
        this.storeId = storeId;
        this.customerId = customerId;
        this.source = source;
        this.items = items;
        this.status = OrderStatus.PENDING_PAY;
        this.totalAmount = calcTotalAmount();
        // 必须是 0 而不是 null：discount_amount 列是 NOT NULL DEFAULT 0.00，
        // 没券的订单（绝大多数）插进去时带的是这个值。留 null 会撞数据库约束，
        // 而且是**每一张不带券的订单**都撞
        this.discountAmount = BigDecimal.ZERO;
        this.paidAmount = BigDecimal.ZERO;
        this.createTime = LocalDateTime.now();
    }
    // ────────── 核心业务方法（状态机）──────────

    /** 支付 — 状态 1→2。先付传全额，洗后付传 0 */
    public void pay(PayMethod payMethod, BigDecimal amount) {
        if (this.status != OrderStatus.PENDING_PAY) {
            throw new BusinessException("当前状态不允许支付: 状态=" + this.status.getCode());
        }
        // 金额校验：只允许"全额"（先付）或"0"（洗后付占位），
        // 否则 order 会带着半吊子 paidAmount 进入后续状态（终态检查才拦就晚了）
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("支付金额不能为空或负数");
        }
        if (amount.compareTo(this.totalAmount) != 0
                && amount.compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException("支付金额不正确：先付需付全额("
                    + this.totalAmount + ")，洗后付传 0");
        }
        this.payMethod = payMethod;
        this.paidAmount = amount;           // 先付=full，洗后付=0
        this.status = OrderStatus.PAID;     // 1 → 2
    }

    /** 创建订单时一次性填写网单要素（后续不可改）。全为 null 时跳过 */
    public void fillOrderInfo(LocalDateTime appointmentTime,
                              String deliveryAddress, String remark) {
        // 网单必须有配送地址 —— "衣服送到哪"是网单能不能履约的前提，没有它就谁也送不到。
        // 门店单不走快递（衣服就在店里等顾客来取），地址为空是正常的，所以不校验。
        //
        // 守在领域而不是应用层：这是订单自己的不变量，换任何入口进来都绕不掉
        // （应用层还有别的调用方：定时任务、后台脚本、第二个前端）。
        // 空白串也算没填 —— 前端把输入框的 "   " 原样提交上来是最常见的坏输入
        if (this.source == OrderSource.ONLINE
                && (deliveryAddress == null || deliveryAddress.isBlank())) {
            throw new BusinessException("网单必须填写配送地址");
        }
        if (appointmentTime == null && deliveryAddress == null && remark == null) {
            return;
        }
        this.appointmentTime = appointmentTime;
        this.deliveryAddress = deliveryAddress;
        this.remark = remark;
    }

    /**
     * 录入快递单号 —— 只在**网单派送中（6 态）**时填，填完状态不变、仍是 6。
     *
     * 为什么守在领域：这是订单自己的不变量（哪张单能寄快递、什么时候能填），
     * 换任何入口进来都绕不掉（应用层之外还有定时任务、后台脚本、第二个前端）。
     * 三条规则各有各的必要：
     *   - 门店单不寄快递（衣服就在店里等顾客来取），给它录单号说明操作的是另一件事
     *   - 只有派送中能录：单号是"已经在路上了"的凭证 —— 待出厂(4)就录等于提前宣布发货，
     *     已完成(7)再录是事后补票，两者都会让运单和订单状态对不上
     *   - 空白串也算没填：前端把输入框的 "   " 原样提交上来是最常见的坏输入
     *
     * 长度上限守在领域、**不留给数据库**：express_no 是 VARCHAR(50)，超长会一路
     * 走到 UPDATE 才被 MySQL 弹回来，报出来的是 DataTooLong 这种英文 SQL 异常（500），
     * 而不是一句给用户看的话。和 Customer.requireValidName 是同一个理由 ——
     * 50 这个数字只该有一个家，列加宽时改的是这里
     */
    public void fillExpressNo(String expressNo) {
        if (this.source != OrderSource.ONLINE) {
            throw new BusinessException("只有网单可以录入快递单号");
        }
        if (this.status != OrderStatus.DELIVERING) {
            throw new BusinessException("只有派送中的订单可以录入快递单号: 状态="
                    + this.status.getCode());
        }
        if (expressNo == null || expressNo.isBlank()) {
            throw new BusinessException("快递单号不能为空");
        }
        if (expressNo.codePointCount(0, expressNo.length()) > EXPRESS_NO_MAX_LENGTH) {
            throw new BusinessException("快递单号不能超过 " + EXPRESS_NO_MAX_LENGTH + " 个字符");
        }
        this.expressNo = expressNo;
    }

    /**
     * 应用优惠券 —— 折扣是订单自己的不变量，所以守在领域里，不在应用层。
     *
     * **total_amount 存折后应付**（不是折前价）：pay() / updateStatus() / finalPay()
     * 三处都拿 paid_amount 与 total_amount 比"付清没"，若存折前价，用了券的顾客
     * 到了收银台会被要求付全款。discount_amount 只作展示与对账，**不参与任何状态判断**。
     *
     * 必须在订单**落库之前**调用：构造器算出来的 totalAmount 是折前价，
     * 折后价只在这里产生 —— 晚了就已经写进库了。
     */
    public void applyCoupon(Long couponId, BigDecimal discount) {
        // 折扣率本该在券域把关，这里再校一次不是重复：领域方法不该假设调用方
        // 一定校验过（应用层之外还有别的入口），而且"折后应付怎么算"本身是订单的规则
        if (discount == null || discount.compareTo(BigDecimal.ZERO) <= 0
                || discount.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException("优惠券折扣率不合法");
        }
        if (this.couponId != null) {
            // 一张订单只挂一张券（coupon_id 是单列）。重复调用会把已经折过的
            // total_amount 再折一次 —— 静默少收钱，比报错难查得多
            throw new BusinessException("订单已经使用过优惠券");
        }
        BigDecimal before = this.totalAmount;
        BigDecimal after = before.multiply(discount).setScale(2, RoundingMode.HALF_UP);
        this.couponId = couponId;
        this.discountAmount = before.subtract(after);
        this.totalAmount = after;
    }

    /** 记录本次操作的员工（订单留痕：谁推进的状态、谁收的款） */
    public void recordOperator(Long staffId) {
        this.staffId = staffId;
    }
    /**
     * 正常推进 — 根据当前状态和来源自动路由（2026-09-11 口径：码值连号 1~7）。
     *
     *   1→2→3→4→(分叉)→5(门店待取件)/6(网单派送中)→7 已完成
     *
     * 4 之后两条路都得**付清**才允许走到终态 7：
     * 门店单是"取件即完成"（5→7），网单是"送到即完成"（6→7）。
     * 原先只有 5→7 校验付清，网单 6→7 是不校验的（那时 7 只是中间态，
     * 付清校验在后面 7→8 补），连号之后 7 变成终态，这个口子必须堵上，
     * 否则网单可以一路推进到"已完成"而一分钱没付。
     */
    public void updateStatus() {
        switch (this.status) {
            case PAID:               // 2 → 3
                this.status = OrderStatus.WASHING;
                break;
            case WASHING:            // 3 → 4
                this.status = OrderStatus.PENDING_DELIVERY;
                break;
            case PENDING_DELIVERY:   // 4 → 分叉：门店走5，网单走6
                // source 为 null 必须**抛**（2026-09-18 补）：这是全流程**唯一**
                // 按来源分叉的状态点，而判据原本写成 `if (== STORE) … else …`，
                // null 落进 else —— 等于"来源不明的订单静默当成网单"：之后会被要求
                // 录快递单号、状态文案也是网单那套，**不报错、不留痕**。
                // 安静地走错一支比抛异常难查得多（到录单号那步才会反着报出来）
                if (this.source == null) {
                    throw new BusinessException("订单缺少来源，无法判断后续流程");
                }
                if (this.source == OrderSource.STORE) {
                    this.status = OrderStatus.PENDING_PICKUP;
                } else {
                    this.status = OrderStatus.DELIVERING;
                }
                break;
            case PENDING_PICKUP:     // 5 → 7（门店单终态）
                requirePaidOff();
                finish();
                break;
            case DELIVERING:         // 6 → 7（网单终态）
                requirePaidOff();
                finish();
                break;
            default:
                throw new BusinessException("当前状态不允许推进: 状态=" + this.status.getCode());
        }
    }

    /**
     * 洗后付结账 — 状态 5(门店待取件) / 6(网单派送中) → 7 已完成。
     *
     * 为什么只放这两个状态：它们正是"衣服在店里等着/在路上"的两个点，
     * 顾客此时掏钱，一次付清直接收尾。1 态该走 pay()，终态 7 已经付过了。
     *
     * 历史注：2026-09-10 曾把 7"已送达"也放进来，治的是"网单洗后付
     * 推进到 7 后 next 被未付清拦、finalPay 又被状态限制拦、订单永久卡死"。
     * 2026-09-11 把付清校验前移到 6→7 之后，这个卡死情形不复存在，
     * 7 也不再是需要结账的中间态，于是收回到 5/6。
     */
    public void finalPay(PayMethod payMethod) {
        if (this.status != OrderStatus.PENDING_PICKUP
                && this.status != OrderStatus.DELIVERING) {
            throw new BusinessException("当前状态不允许洗后付结账: 状态="
                    + this.status.getCode());
        }
        this.paidAmount = this.totalAmount;
        this.finalPayMethod = payMethod;
        finish();
    }

    /** 收尾：两个终态入口（5→7、6→7、finalPay）共用的三件事 */
    private void finish() {
        this.status = OrderStatus.COMPLETED;
        this.finishTime = LocalDateTime.now();
    }

    /** 终态前的最后一道闸：没付清不许"完成" */
    private void requirePaidOff() {
        if (this.paidAmount.compareTo(this.totalAmount) < 0) {
            throw new BusinessException("未付清，请使用洗后付结账");
        }
    }
    // ────────── 内部工具方法 ──────────

    /** 计算订单总金额 = 所有明细小计之和 */
    private BigDecimal calcTotalAmount() {
        return items.stream()
                .map(OrderItem::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    // ────────── getter ──────────
    // 只用 getter，业务代码禁止用 setter 直接改字段

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public Long getStoreId() { return storeId; }
    public Long getCustomerId() { return customerId; }
    public Long getStaffId() { return staffId; }
    public OrderSource getSource() { return source; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public Long getCouponId() { return couponId; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public PayMethod getPayMethod() { return payMethod; }
    public PayMethod getFinalPayMethod() { return finalPayMethod; }
    public LocalDateTime getAppointmentTime() { return appointmentTime; }
    public String getDeliveryAddress() { return deliveryAddress; }
    public String getExpressNo() { return expressNo; }
    public String getRemark() { return remark; }
    public LocalDateTime getFinishTime() { return finishTime; }
    public LocalDateTime getCreateTime() { return createTime; }

    /** 订单明细 — 只读列表 */
    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    // ────────── setter（仅供 MyBatis 从数据库恢复数据时用）──────────

    public void setId(Long id) { this.id = id; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public void setStaffId(Long staffId) { this.staffId = staffId; }
    public void setSource(OrderSource source) { this.source = source; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount =
            totalAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount =
            discountAmount; }
    public void setCouponId(Long couponId) { this.couponId = couponId; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount;
    }
    public void setPayMethod(PayMethod payMethod) { this.payMethod = payMethod; }
    public void setFinalPayMethod(PayMethod finalPayMethod) { this.finalPayMethod =
            finalPayMethod; }
    public void setAppointmentTime(LocalDateTime appointmentTime) {
        this.appointmentTime = appointmentTime; }
    public void setDeliveryAddress(String deliveryAddress) { this.deliveryAddress =
            deliveryAddress; }
    public void setExpressNo(String expressNo) { this.expressNo = expressNo; }
    public void setRemark(String remark) { this.remark = remark; }
    public void setFinishTime(LocalDateTime finishTime) { this.finishTime =
            finishTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime =
            createTime; }
    public void setItems(List<OrderItem> items) { this.items = items; }

}
