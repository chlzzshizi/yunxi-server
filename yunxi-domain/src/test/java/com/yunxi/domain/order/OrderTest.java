package com.yunxi.domain.order;

import com.yunxi.common.BusinessException;
import com.yunxi.common.enums.OrderSource;
import com.yunxi.common.enums.OrderStatus;
import com.yunxi.common.enums.PayMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单状态机单元测试 —— 纯领域测试，不依赖 Spring / 数据库。
 *
 * 覆盖：
 *   1. 三条合法全路径（门店单先付 / 网单先付 / 两种洗后付）
 *   2. 非法流转（未支付推进、终态推进、未付清走终态、重复支付）
 *   3. 已知缺陷复现：网单洗后付在状态 7 卡死（见 §"缺陷复现"）
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
        @DisplayName("门店单·先付：1→2→3→4→5→8，全程 4 次推进，终态记录完成时间")
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

            order.updateStatus();                       // 5 → 8（已付清，放行）
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PICKED_UP);
            assertThat(order.getFinishTime()).isNotNull();
        }

        @Test
        @DisplayName("网单·先付：1→2→3→4→6→7→8，7 态是网单独有中间态")
        void onlineOrderPrepaid() {
            Order order = newOrder(OrderSource.ONLINE);
            order.pay(PayMethod.WECHAT, TOTAL);         // 1 → 2
            order.updateStatus();                       // 2 → 3
            order.updateStatus();                       // 3 → 4
            order.updateStatus();                       // 4 → 6（网单分叉走 6）
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERING);

            order.updateStatus();                       // 6 → 7
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(order.getFinishTime()).isNull(); // 中间态不算完成

            order.updateStatus();                       // 7 → 8
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PICKED_UP);
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

            order.finalPay(PayMethod.ALIPAY);           // 6 → 8
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PICKED_UP);
            assertThat(order.getPaidAmount()).isEqualByComparingTo(TOTAL);
            assertThat(order.getFinalPayMethod()).isEqualTo(PayMethod.ALIPAY);
        }

        @Test
        @DisplayName("门店单·洗后付：待取件时 finalPay 补齐 → 终态")
        void storeOrderPostpaid() {
            Order order = newOrder(OrderSource.STORE);
            order.pay(PayMethod.BALANCE, BigDecimal.ZERO);
            order.updateStatus();                       // 2 → 3
            order.updateStatus();                       // 3 → 4
            order.updateStatus();                       // 4 → 5

            order.finalPay(PayMethod.CASH);             // 5 → 8
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PICKED_UP);
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
        @DisplayName("终态不可逆（8 态点 next）")
        void terminalIsFinal() {
            Order order = newOrder(OrderSource.STORE);
            order.pay(PayMethod.CASH, TOTAL);
            order.updateStatus();
            order.updateStatus();
            order.updateStatus();
            order.updateStatus();                       // 到 8
            assertThatThrownBy(order::updateStatus)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不允许推进");
        }

        @Test
        @DisplayName("门店单未结账不能走终端态（5→8 被拦）")
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
        @DisplayName("网单未结账不能走终端态（7→8 被拦）")
        void cannotFinishUnpaidOnlineOrder() {
            Order order = newOrder(OrderSource.ONLINE);
            order.pay(PayMethod.BALANCE, BigDecimal.ZERO);
            order.updateStatus();                       // → 3
            order.updateStatus();                       // → 4
            order.updateStatus();                       // → 6
            order.updateStatus();                       // → 7
            assertThatThrownBy(order::updateStatus)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("未付清");
        }

        @Test
        @DisplayName("finalPay 只在 5/6/7 态放行（1 态、终态直接结账被拦）")
        void finalPayOnlyAtStatus5To7() {
            Order order = newOrder(OrderSource.STORE);
            assertThatThrownBy(() -> order.finalPay(PayMethod.CASH))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不允许洗后付结账");

            // 走到终态后再结账同样被拦
            order.pay(PayMethod.CASH, TOTAL);
            order.updateStatus();
            order.updateStatus();
            order.updateStatus();
            order.updateStatus();                       // → 8
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
    @DisplayName("缺陷回归：网单洗后付在状态 7 可结账（2026-09-10 修复）")
    class DeadlockRegression {

        @Test
        @DisplayName("先推进到 7 再 finalPay：应成功 7→8 并补齐款项（原缺陷：永久卡死）")
        void onlinePostpaidCanSettleAtDelivered() {
            Order order = newOrder(OrderSource.ONLINE);
            order.pay(PayMethod.BALANCE, BigDecimal.ZERO);  // 洗后付
            order.updateStatus();                       // 2 → 3
            order.updateStatus();                       // 3 → 4
            order.updateStatus();                       // 4 → 6
            order.updateStatus();                       // 6 → 7（先推进到"已送达"）
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);

            // 正常推进仍被未付清拦截（这一条不能松）
            assertThatThrownBy(order::updateStatus)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("未付清");

            // 修复点：7 态结账放行，订单能走到终点
            order.finalPay(PayMethod.CASH);             // 7 → 8
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PICKED_UP);
            assertThat(order.getPaidAmount()).isEqualByComparingTo(TOTAL);
            assertThat(order.getFinishTime()).isNotNull();
        }
    }
}
