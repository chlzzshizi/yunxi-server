package com.yunxi.domain.order;

import com.yunxi.common.BusinessException;
import com.yunxi.common.enums.OrderSource;
import com.yunxi.common.enums.OrderStatus;
import com.yunxi.common.enums.PayMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单状态机单元测试 —— 纯领域测试，不依赖 Spring / 数据库。
 *
 * 覆盖：
 *   1. 四条合法全路径（门店单先付 / 网单先付 / 两种洗后付）
 *   2. 非法流转（未支付推进、终态推进、未付清走终态、重复支付）
 *   3. 支付金额校验、优惠券抵扣（含上界恰好 1.0）、快递单号录入（含 emoji 边界）
 *   4. 明细列表是只读视图（getItems）
 *   5. **两条曾经是缺口、2026-09-18 已修**：空明细与 source=null ——
 *      见文件末尾那段，原先钉的是缺口本身，现在钉的是补上的那两道闸
 *
 * 2026-09-11 口径：码值连号 1~7，7 是通用终态，
 * 门店单 1→2→3→4→5→7、网单 1→2→3→4→6→7，两条路**都**要付清才能到 7。
 */
class OrderTest {

    /** 测试订单：1 条明细，单价 15.00 × 2 件 = 总额 30.00 */
    private static final BigDecimal TOTAL = new BigDecimal("30.00");

    private Order newOrder(OrderSource source) {
        List<OrderItem> items = List.of(
                new OrderItem(1L, 1L, 2, new BigDecimal("15.00"), null));
        return new Order("YX-TEST-0001", 1L, 1L, source, items);
    }

    // ════════════════ 合法路径 ════════════════

    @Nested
    @DisplayName("合法全路径")
    class HappyPath {

        @Test
        @DisplayName("门店单·先付：1→2→3→4→5→7，全程 4 次推进，终态记录完成时间")
        void storeOrderPrepaid() {
            Order order = newOrder(OrderSource.STORE);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAY);
            assertThat(order.getTotalAmount()).isEqualByComparingTo(TOTAL);

            order.pay(PayMethod.CASH, TOTAL);           // 1 → 2（先付=全款）
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(order.getPaidAmount()).isEqualByComparingTo(TOTAL);

            order.updateStatus();                       // 2 → 3
            order.updateStatus();                       // 3 → 4
            order.updateStatus();                       // 4 → 5（门店单分叉走 5）
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PICKUP);

            order.updateStatus();                       // 5 → 7（已付清，放行）
            assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
            assertThat(order.getFinishTime()).isNotNull();
        }

        @Test
        @DisplayName("网单·先付：1→2→3→4→6→7，两条路在 7 汇合成同一个终态")
        void onlineOrderPrepaid() {
            Order order = newOrder(OrderSource.ONLINE);
            order.pay(PayMethod.WECHAT, TOTAL);         // 1 → 2
            order.updateStatus();                       // 2 → 3
            order.updateStatus();                       // 3 → 4
            order.updateStatus();                       // 4 → 6（网单分叉走 6）
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERING);

            order.updateStatus();                       // 6 → 7（已付清，放行）
            assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
            assertThat(order.getFinishTime()).isNotNull();
        }

        @Test
        @DisplayName("网单·洗后付：pay(0) 占位，派送中 finalPay 补齐 → 直接终态")
        void onlineOrderPostpaid() {
            Order order = newOrder(OrderSource.ONLINE);
            order.pay(PayMethod.BALANCE, BigDecimal.ZERO);  // 1 → 2（洗后付=0 占位）
            assertThat(order.getPaidAmount()).isEqualByComparingTo(BigDecimal.ZERO);

            order.updateStatus();                       // 2 → 3
            order.updateStatus();                       // 3 → 4
            order.updateStatus();                       // 4 → 6
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERING);

            order.finalPay(PayMethod.ALIPAY);           // 6 → 7
            assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
            assertThat(order.getPaidAmount()).isEqualByComparingTo(TOTAL);
            assertThat(order.getFinalPayMethod()).isEqualTo(PayMethod.ALIPAY);
            assertThat(order.getFinishTime()).isNotNull();
        }

        @Test
        @DisplayName("门店单·洗后付：待取件时 finalPay 补齐 → 终态")
        void storeOrderPostpaid() {
            Order order = newOrder(OrderSource.STORE);
            order.pay(PayMethod.BALANCE, BigDecimal.ZERO);
            order.updateStatus();                       // 2 → 3
            order.updateStatus();                       // 3 → 4
            order.updateStatus();                       // 4 → 5

            order.finalPay(PayMethod.CASH);             // 5 → 7
            assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
            assertThat(order.getPaidAmount()).isEqualByComparingTo(TOTAL);
        }
    }

    // ════════════════ 非法流转 ════════════════

    @Nested
    @DisplayName("非法流转全部被拦截")
    class IllegalTransitions {

        @Test
        @DisplayName("未支付不能推进（1 态点 next）")
        void cannotAdvanceBeforePay() {
            Order order = newOrder(OrderSource.STORE);
            assertThatThrownBy(order::updateStatus)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不允许推进");
        }

        @Test
        @DisplayName("已支付不能重复支付")
        void cannotPayTwice() {
            Order order = newOrder(OrderSource.STORE);
            order.pay(PayMethod.CASH, TOTAL);
            assertThatThrownBy(() -> order.pay(PayMethod.CASH, TOTAL))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不允许支付");
        }

        @Test
        @DisplayName("终态不可逆（7 态点 next）")
        void terminalIsFinal() {
            Order order = newOrder(OrderSource.STORE);
            order.pay(PayMethod.CASH, TOTAL);
            order.updateStatus();
            order.updateStatus();
            order.updateStatus();
            order.updateStatus();                       // 到 7
            assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
            assertThatThrownBy(order::updateStatus)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不允许推进");
        }

        @Test
        @DisplayName("门店单未结账不能走终端态（5→7 被拦）")
        void cannotFinishUnpaidStoreOrder() {
            Order order = newOrder(OrderSource.STORE);
            order.pay(PayMethod.BALANCE, BigDecimal.ZERO);  // 洗后付
            order.updateStatus();                       // → 3
            order.updateStatus();                       // → 4
            order.updateStatus();                       // → 5
            assertThatThrownBy(order::updateStatus)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("未付清");
        }

        @Test
        @DisplayName("网单未结账不能走终端态（6→7 被拦——补上的那个口子）")
        void cannotFinishUnpaidOnlineOrder() {
            Order order = newOrder(OrderSource.ONLINE);
            order.pay(PayMethod.BALANCE, BigDecimal.ZERO);
            order.updateStatus();                       // → 3
            order.updateStatus();                       // → 4
            order.updateStatus();                       // → 6
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERING);
            // 6→7 现在也要付清：旧口径下 7 是中间态、校验在 7→8，
            // 网单可以一路"已完成"而一分钱没付
            assertThatThrownBy(order::updateStatus)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("未付清");
        }

        @Test
        @DisplayName("finalPay 只在 5/6 态放行（1 态、终态直接结账被拦）")
        void finalPayOnlyAtStatus5And6() {
            Order order = newOrder(OrderSource.STORE);
            assertThatThrownBy(() -> order.finalPay(PayMethod.CASH))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不允许洗后付结账");

            // 走到终态后再结账同样被拦
            order.pay(PayMethod.CASH, TOTAL);
            order.updateStatus();
            order.updateStatus();
            order.updateStatus();
            order.updateStatus();                       // → 7
            assertThatThrownBy(() -> order.finalPay(PayMethod.CASH))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不允许洗后付结账");
        }
    }

    // ════════════════ 支付金额校验 ════════════════

    @Nested
    @DisplayName("支付金额校验（2026-09-10 加固）")
    class PayAmountValidation {

        @Test
        @DisplayName("先付金额不足 → 拦截，状态仍是待支付")
        void cannotPayPartialForPrepaid() {
            Order order = newOrder(OrderSource.STORE);
            assertThatThrownBy(() -> order.pay(PayMethod.CASH, new BigDecimal("20.00")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("支付金额不正确");
            // 关键：被拦后订单不能被污染，还得是 1 态
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAY);
            assertThat(order.getPaidAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("先付金额超额 → 拦截（多收钱同样是事故）")
        void cannotOverpayForPrepaid() {
            Order order = newOrder(OrderSource.STORE);
            assertThatThrownBy(() -> order.pay(PayMethod.CASH, new BigDecimal("50.00")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("支付金额不正确");
        }

        @Test
        @DisplayName("金额为 null → 拦截（原来会 NPE 变 500）")
        void cannotPayWithNullAmount() {
            Order order = newOrder(OrderSource.STORE);
            assertThatThrownBy(() -> order.pay(PayMethod.CASH, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能为空或负数");
        }

        @Test
        @DisplayName("金额为负 → 拦截")
        void cannotPayWithNegativeAmount() {
            Order order = newOrder(OrderSource.STORE);
            assertThatThrownBy(() -> order.pay(PayMethod.CASH, new BigDecimal("-30.00")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能为空或负数");
        }

        @Test
        @DisplayName("金额只按数值比较：30、30.0、30.00 等价放行")
        void amountComparedByValueNotScale() {
            Order order = newOrder(OrderSource.STORE);
            order.pay(PayMethod.CASH, new BigDecimal("30"));   // 对比 TOTAL=30.00
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(order.getPaidAmount()).isEqualByComparingTo(TOTAL);
        }
    }

    // ════════════════ 缺陷回归 ════════════════

    @Nested
    @DisplayName("缺陷回归：网单洗后付不再有卡死路径（原缺陷 2026-09-10，2026-09-11 连号后结构性消失）")
    class NoDeadlockRegression {

        @Test
        @DisplayName("网单洗后付：6 态结账 6→7，一次走完不留中间态")
        void onlinePostpaidSettlesAtDelivering() {
            Order order = newOrder(OrderSource.ONLINE);
            order.pay(PayMethod.BALANCE, BigDecimal.ZERO);  // 洗后付
            order.updateStatus();                       // 2 → 3
            order.updateStatus();                       // 3 → 4
            order.updateStatus();                       // 4 → 6
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERING);

            // 原缺陷是"先 next 到 7、再靠 finalPay 解锁"，那个中间态已经没了：
            // 6→7 本身就要付清，所以订单不可能带着欠款停在终态
            order.finalPay(PayMethod.CASH);             // 6 → 7
            assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
            assertThat(order.getPaidAmount()).isEqualByComparingTo(TOTAL);
            assertThat(order.getFinishTime()).isNotNull();
        }
    }

    // ════════════════ 网单配送地址 ════════════════

    @Nested
    @DisplayName("fillOrderInfo：网单必须有配送地址")
    class DeliveryAddress {

        private static final String ADDRESS = "杭州市西湖区文一西路 100 号";

        @Test
        @DisplayName("网单不填地址 → 400（送到哪都不知道，这单根本没法履约）")
        void onlineWithoutAddressRejected() {
            Order order = newOrder(OrderSource.ONLINE);

            assertThatThrownBy(() -> order.fillOrderInfo(null, null, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("配送地址");

            // 被拒之后订单不能被改脏：抛异常前没写任何字段
            assertThat(order.getDeliveryAddress()).isNull();
        }

        @Test
        @DisplayName("网单地址是空白串 → 同样拒（前端把 \"   \" 原样提交是最常见的坏输入）")
        void onlineBlankAddressRejected() {
            Order order = newOrder(OrderSource.ONLINE);

            // 只判 null 的话，一个空格就能把这条例外绕过去
            assertThatThrownBy(() -> order.fillOrderInfo(null, "   ", null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("配送地址");
        }

        @Test
        @DisplayName("网单填了地址 → 地址、预约时间、备注一起落上")
        void onlineWithAddressFills() {
            Order order = newOrder(OrderSource.ONLINE);
            LocalDateTime at = LocalDateTime.now().plusDays(1);

            order.fillOrderInfo(at, ADDRESS, "袖口有污渍");

            assertThat(order.getDeliveryAddress()).isEqualTo(ADDRESS);
            assertThat(order.getAppointmentTime()).isEqualTo(at);
            assertThat(order.getRemark()).isEqualTo("袖口有污渍");
        }

        @Test
        @DisplayName("门店单不填地址 → 放行（衣服就在店里等顾客来取，本来就不需要地址）")
        void storeOrderNeedsNoAddress() {
            Order order = newOrder(OrderSource.STORE);

            order.fillOrderInfo(null, null, null);   // 全空 → 短路，一个字段都不动

            assertThat(order.getDeliveryAddress()).isNull();
            assertThat(order.getAppointmentTime()).isNull();
            assertThat(order.getRemark()).isNull();
        }
    }

    // ════════════════ 券抵扣 ════════════════

    @Nested
    @DisplayName("优惠券抵扣")
    class Coupon {

        private static final BigDecimal HALF = new BigDecimal("0.50");

        @Test
        @DisplayName("5 折：total_amount 变折后应付，discount_amount 记下省了多少")
        void halfDiscount() {
            Order order = newOrder(OrderSource.STORE);
            assertThat(order.getTotalAmount()).isEqualByComparingTo(TOTAL);

            order.applyCoupon(9L, HALF);

            assertThat(order.getTotalAmount()).isEqualByComparingTo("15.00");
            assertThat(order.getDiscountAmount()).isEqualByComparingTo("15.00");
            assertThat(order.getCouponId()).isEqualTo(9L);
        }

        @Test
        @DisplayName("折后价才是\"付清\"的基准 —— pay(全额) 收的是折后金额")
        void paysTheDiscountedAmount() {
            Order order = newOrder(OrderSource.STORE);
            order.applyCoupon(9L, HALF);

            // 传折前价 30.00 会被拒：金额校验比的是折后的 totalAmount。
            // 这就是 total_amount 存折后价的全部意义 —— 收银台不会要求顾客付全款
            assertThatThrownBy(() -> order.pay(PayMethod.CASH, TOTAL))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("支付金额不正确");

            order.pay(PayMethod.CASH, new BigDecimal("15.00"));
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        }

        @Test
        @DisplayName("舍入：折后价 HALF_UP 保留 2 位（不是直接截断）")
        void roundsHalfUp() {
            // 3 件 × 15.00 = 45.00，打 8.888 折 → 39.996 → 40.00（HALF_UP）
            List<OrderItem> items = List.of(
                    new OrderItem(1L, 1L, 3, new BigDecimal("15.00"), null));
            Order order = new Order("YX-TEST-0002", 1L, 1L, OrderSource.STORE, items);

            order.applyCoupon(9L, new BigDecimal("0.8888"));

            assertThat(order.getTotalAmount()).isEqualByComparingTo("40.00");
            // 抵扣额是**相减**出来的，不是另外算一遍折扣率 —— 免得两个数对不上
            assertThat(order.getDiscountAmount()).isEqualByComparingTo("5.00");
        }

        @Test
        @DisplayName("折扣率不合法（null / 0 / 负数 / 大于 1）→ 400，且订单一分钱没动")
        void illegalDiscountRejected() {
            Order order = newOrder(OrderSource.STORE);

            assertThatThrownBy(() -> order.applyCoupon(9L, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("折扣率不合法");
            assertThatThrownBy(() -> order.applyCoupon(9L, BigDecimal.ZERO))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> order.applyCoupon(9L, new BigDecimal("-0.5")))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> order.applyCoupon(9L, new BigDecimal("1.01")))
                    .isInstanceOf(BusinessException.class);

            // 被拒之后订单不能被改脏 —— 折扣率和券号一个都不该留下
            assertThat(order.getTotalAmount()).isEqualByComparingTo(TOTAL);
            assertThat(order.getDiscountAmount()).isEqualByComparingTo("0.00");
            assertThat(order.getCouponId()).isNull();
        }

        @Test
        @DisplayName("折扣率恰好 1.0 → 放行，但抵扣为 0（上界是「不超过 1」）")
        void exactlyOneIsAllowed() {
            // 边界两侧不对称：1.01 被拒（见上一条），1.0 放行。
            // 放行的后果是"0 折券"—— 金额一分不变、discount_amount 记 0.00，
            // 用户拿着券却什么也没省。这不是 bug，是"100% 折扣率"本身没意义；
            // 必须单独钉住它，是因为把判据改成"大于等于 1 就拒"
            // （compareTo(BigDecimal.ONE) >= 0）会让每次用券都变成不打折，
            // 而现有测试**全绿** —— 1.01 照样被拒。少的就是这一条
            Order order = newOrder(OrderSource.STORE);

            order.applyCoupon(9L, BigDecimal.ONE);

            assertThat(order.getTotalAmount()).isEqualByComparingTo(TOTAL);
            assertThat(order.getDiscountAmount()).isEqualByComparingTo("0.00");
            assertThat(order.getCouponId()).isEqualTo(9L);
        }

        @Test
        @DisplayName("重复用券 → 报错（一张订单只挂一张券，再折一次就是静默少收钱）")
        void secondCouponRejected() {
            Order order = newOrder(OrderSource.STORE);
            order.applyCoupon(9L, HALF);

            assertThatThrownBy(() -> order.applyCoupon(10L, HALF))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("已经使用过优惠券");

            // 第二次没生效：还是第一次折出来的 15.00，没有变成 7.50
            assertThat(order.getTotalAmount()).isEqualByComparingTo("15.00");
            assertThat(order.getCouponId()).isEqualTo(9L);
        }

        @Test
        @DisplayName("新建订单的 discount_amount 是 0 而不是 null（列是 NOT NULL）")
        void newOrderHasZeroDiscount() {
            // 不带券的订单是绝大多数，这个 0 若留成 null，每一张都插不进数据库
            assertThat(newOrder(OrderSource.STORE).getDiscountAmount())
                    .isEqualByComparingTo("0.00");
            assertThat(newOrder(OrderSource.ONLINE).getCouponId()).isNull();
        }
    }

    // ════════════════ 快递单号 ════════════════

    @Nested
    @DisplayName("录入快递单号（网单·派送中）")
    class ExpressNo {

        private static final String NO = "SF1234567890";

        /** 把一张网单推到派送中（6 态）：先付清，再连推三次（1→2→3→4→6） */
        private Order onlineAtDelivering() {
            Order order = newOrder(OrderSource.ONLINE);
            order.pay(PayMethod.WECHAT, TOTAL);
            order.updateStatus();
            order.updateStatus();
            order.updateStatus();
            return order;
        }

        @Test
        @DisplayName("派送中录入 → 单号落上，**状态仍是 6**（录单号不推进状态机）")
        void recordsExpressNo() {
            Order order = onlineAtDelivering();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERING);

            order.fillExpressNo(NO);

            assertThat(order.getExpressNo()).isEqualTo(NO);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERING);
            assertThat(order.getFinishTime()).isNull();
        }

        @Test
        @DisplayName("门店单 → 400（衣服就在店里等顾客来取，根本没有快递这回事）")
        void storeOrderRejected() {
            Order order = newOrder(OrderSource.STORE);
            order.pay(PayMethod.CASH, TOTAL);

            assertThatThrownBy(() -> order.fillExpressNo(NO))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("只有网单");
            assertThat(order.getExpressNo()).isNull();
        }

        @Test
        @DisplayName("还没派送就录 → 400（待支付 1 态 / 待出厂 4 态，都是提前宣布发货）")
        void notDeliveringRejected() {
            Order pending = newOrder(OrderSource.ONLINE);
            assertThatThrownBy(() -> pending.fillExpressNo(NO))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("派送中");

            Order ready = newOrder(OrderSource.ONLINE);
            ready.pay(PayMethod.WECHAT, TOTAL);
            ready.updateStatus();   // → 3
            ready.updateStatus();   // → 4
            assertThat(ready.getStatus()).isEqualTo(OrderStatus.PENDING_DELIVERY);
            assertThatThrownBy(() -> ready.fillExpressNo(NO))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("派送中");
        }

        @Test
        @DisplayName("已完成再补录 → 400（事后补票会让运单和订单状态对不上）")
        void completedRejected() {
            Order order = onlineAtDelivering();
            order.updateStatus();   // 6 → 7（已付清，放行）
            assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);

            assertThatThrownBy(() -> order.fillExpressNo(NO))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("派送中");
        }

        @Test
        @DisplayName("空白串 → 400（前端把 \"   \" 原样提交是最常见的坏输入）")
        void blankRejected() {
            Order order = onlineAtDelivering();

            assertThatThrownBy(() -> order.fillExpressNo(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能为空");
            assertThatThrownBy(() -> order.fillExpressNo("   "))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能为空");

            assertThat(order.getExpressNo()).isNull();
        }

        @Test
        @DisplayName("超过 50 个字符 → 400，而不是让 MySQL 抛 DataTooLong 变成 500")
        void tooLongRejected() {
            Order order = onlineAtDelivering();

            // 边界两侧都钉住：50 个字符是合法的，51 个不行
            // （express_no 是 VARCHAR(50)，没有这道校验就是一路走到 UPDATE 才炸）
            order.fillExpressNo("X".repeat(Order.EXPRESS_NO_MAX_LENGTH));
            assertThat(order.getExpressNo()).hasSize(50);

            assertThatThrownBy(() -> order.fillExpressNo("X".repeat(51)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能超过");
            // 被拒之后还是上一次那个 50 字符的号，没被改脏
            assertThat(order.getExpressNo()).hasSize(50);
        }

        @Test
        @DisplayName("emoji 按 1 个字算：50 个放行、51 个才拦（不是按 length() 数）")
        void emojiCountedAsOneChar() {
            // 上面那条用的是 "X".repeat(50) —— 对 ASCII 来说 length() 和码点数一样，
            // 所以它证明不了判据用的是哪一个。这一条用 50 个 emoji（length() = 100）：
            // 判据要是写成 length()，"50 个表情的快递单号"会被误判成超长 ——
            // 不是数据损坏，是"明明存得下却不让填"，一样是 bug
            Order order = onlineAtDelivering();
            assertThat("📦".repeat(50).length()).isEqualTo(100);   // 先证明这个前提成立

            order.fillExpressNo("📦".repeat(50));
            assertThat(order.getExpressNo()).isEqualTo("📦".repeat(50));

            assertThatThrownBy(() -> order.fillExpressNo("📦".repeat(51)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能超过");
            // 被拒之后还是上一次那个 50 个 emoji 的号，没被改脏
            assertThat(order.getExpressNo()).isEqualTo("📦".repeat(50));
        }
    }

    // ════════════════ 只读视图 ════════════════

    @Nested
    @DisplayName("明细列表是只读视图")
    class ItemsReadOnly {

        @Test
        @DisplayName("getItems() 拿到手也改不了订单的明细（交出去的不是内部那个 list）")
        void unmodifiable() {
            // 直接交出内部 list 的后果很具体：任何人拿到就能往订单里塞一条
            // 没算过价的明细，而 totalAmount 是构造时一次算好的 ——
            // 金额和明细当场对不上，而且没有任何东西会发觉
            Order order = newOrder(OrderSource.STORE);
            List<OrderItem> items = order.getItems();
            OrderItem extra = new OrderItem(11L, 1L, 1, new BigDecimal("1.00"), null);

            assertThatThrownBy(() -> items.add(extra))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(order.getItems()).hasSize(1);
        }
    }

    // ════════════════ 两个曾经的缺口 —— 2026-09-18 已修，这里钉的是补上的闸 ════════════════
    //
    // 这两个 Nested 原先叫"钉住现状"：当时刻意不写会红的测试，只把缺口本身钉死，
    // 并在 docs/bug-record.md 记了账。2026-09-18 深夜用户拍板修，于是它们翻了个面 ——
    // 现在钉的是**域层的第二道闸**。留在此处的理由和当初一样：
    // 谁把闸拆了，先在这里红，而不是等库里出现了 0 元订单才发现。

    @Nested
    @DisplayName("空明细：构造当场拒（域层第二道闸）")
    class EmptyItemsRejected {

        @Test
        @DisplayName("空明细 → 构造就抛「订单至少要有一条明细」，一步都走不出去")
        void emptyItemsRejected() {
            // 修之前的路，四个环节分开看都"没错"：
            //   · 构造器：空明细求和 = reduce 的单位元 0，总额 0
            //   · pay：0 既"等于 totalAmount"也"等于洗后付的 0"，两个条件都放行
            //   · requirePaidOff：问的是"付得够不够"，0 < 0 为假 —— 它没问"付的是不是 0"
            //   · finish：终态不看金额
            // 当初唯一挡着它的是 OrderController:50-52。现在挡在域里 ——
            // 定时任务 / 后台脚本 / 第二个前端这些入口同样绕不掉
            assertThatThrownBy(() ->
                    new Order("YX-EMPTY-0001", 1L, 1L, OrderSource.ONLINE, List.of()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("订单至少要有一条明细");
        }

        @Test
        @DisplayName("items=null 也是同一句话拒掉（不是 NPE、不是 500）")
        void nullItemsRejected() {
            // 判据写的是 `items == null || items.isEmpty()`，两者共用一句话。
            // 拆成两处写，迟早会变成"一个是 400、一个是 500"
            assertThatThrownBy(() ->
                    new Order("YX-EMPTY-0002", 1L, 1L, OrderSource.STORE, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("订单至少要有一条明细");
        }

        @Test
        @DisplayName("对照：有一条明细就正常建出来（别把闸做成「永远拒」）")
        void oneItemStillWorks() {
            assertThat(newOrder(OrderSource.STORE).getTotalAmount())
                    .isEqualByComparingTo(TOTAL);
        }
    }

    @Nested
    @DisplayName("source=null：4 态分叉时抛，不再静默走网单")
    class NullSourceRejected {

        @Test
        @DisplayName("source=null → 推到 4 之后那一步抛「订单缺少来源」，状态停在 4")
        void nullSourceThrowsAtFork() {
            // 修之前：判据是 `if (== STORE) … else …`，null 落进 else，
            // 一张来源不明的订单被静默当成网单 —— 之后会被要求录快递单号、
            // 状态文案也是网单那套，而且**不报错、不留痕**。
            // 现在分叉点先问一句"来源呢"，再决定走哪支。
            //
            // 前面 2→3、3→4 两步**照常推进**：闸只设在分叉点，不在更早的地方 ——
            // 否则一张 source 还没填上的在途订单会在无关的状态上炸，报错位置指不到真因
            Order order = newOrder(null);
            order.pay(PayMethod.CASH, TOTAL);
            order.updateStatus();   // 2 → 3
            order.updateStatus();   // 3 → 4
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_DELIVERY);

            assertThatThrownBy(order::updateStatus)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("订单缺少来源，无法判断后续流程");
            // 抛完之后**什么都没被改**：状态还是 4，没被顺手写成 5 也没写成 6
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_DELIVERY);
        }
    }
}
