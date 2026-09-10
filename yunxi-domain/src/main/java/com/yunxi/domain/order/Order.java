package com.yunxi.domain.order;

import com.yunxi.common.BusinessException;
import com.yunxi.common.enums.OrderSource;
import com.yunxi.common.enums.OrderStatus;
import com.yunxi.common.enums.PayMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
public class Order {
    private Long id;
    private String orderNo;             // 订单编号
    private Long storeId;               // 所属门店
    private Long customerId;            // 客户ID
    private Long staffId;               // 操作员工ID
    private OrderSource source;         // 门店单 / 网单
    private OrderStatus status;         // 当前状态
    private BigDecimal totalAmount;     // 总金额
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
    /** 创建新订单 */
    public Order(String orderNo, Long storeId, Long customerId,
                 OrderSource source, List<OrderItem> items) {
        this.orderNo = orderNo;
        this.storeId = storeId;
        this.customerId = customerId;
        this.source = source;
        this.items = items;
        this.status = OrderStatus.PENDING_PAY;
        this.totalAmount = calcTotalAmount();
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
        if (appointmentTime == null && deliveryAddress == null && remark == null) {
            return;
        }
        this.appointmentTime = appointmentTime;
        this.deliveryAddress = deliveryAddress;
        this.remark = remark;
    }

    /** 记录本次操作的员工（订单留痕：谁推进的状态、谁收的款） */
    public void recordOperator(Long staffId) {
        this.staffId = staffId;
    }
    /** 正常推进 — 根据当前状态和来源自动路由 */
    public void updateStatus() {
        switch (this.status) {
            case PAID:               // 2 → 3
                this.status = OrderStatus.WASHING;
                break;
            case WASHING:            // 3 → 4
                this.status = OrderStatus.PENDING_DELIVERY;
                break;
            case PENDING_DELIVERY:   // 4 → 分叉：门店走5，网单走6
                if (this.source == OrderSource.STORE) {
                    this.status = OrderStatus.PENDING_PICKUP;
                } else {
                    this.status = OrderStatus.DELIVERING;
                }
                break;
            case PENDING_PICKUP:     // 5 → 8
                if (this.paidAmount.compareTo(this.totalAmount) < 0) {
                    throw new BusinessException("未付清，请使用洗后付结账");
                }
                this.status = OrderStatus.PICKED_UP;
                this.finishTime = LocalDateTime.now();
                break;
            case DELIVERING:         // 6 → 7
                this.status = OrderStatus.DELIVERED;
                break;
            case DELIVERED:          // 7 → 8
                if (this.paidAmount.compareTo(this.totalAmount) < 0) {
                    throw new BusinessException("未付清，请使用洗后付结账");
                }
                this.status = OrderStatus.PICKED_UP;
                this.finishTime = LocalDateTime.now();
                break;
            default:
                throw new BusinessException("当前状态不允许推进: 状态=" + this.status.getCode());
        }
    }

    /** 洗后付结账 — 状态 5(门店待取件) / 6(网单派送中) / 7(网单已送达) → 8
     *  修复（2026-09-10）：原实现只允许 5/6。网单洗后付若先推进到 7"已送达"，
     *  next 会被"未付清"拦、finalPay 又被状态限制拦 —— 订单永久卡死。
     *  配送是物流事实，不该被付款状态阻断，故补上 7。 */
    public void finalPay(PayMethod payMethod) {
        if (this.status != OrderStatus.PENDING_PICKUP
                && this.status != OrderStatus.DELIVERING
                && this.status != OrderStatus.DELIVERED) {
            throw new BusinessException("当前状态不允许洗后付结账: 状态="
                    + this.status.getCode());
        }
        this.paidAmount = this.totalAmount;
        this.finalPayMethod = payMethod;
        this.status = OrderStatus.PICKED_UP;
        this.finishTime = LocalDateTime.now();
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
