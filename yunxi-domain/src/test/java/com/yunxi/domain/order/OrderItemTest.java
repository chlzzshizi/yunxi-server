package com.yunxi.domain.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单明细单元测试 —— 纯领域，不依赖 Spring / 数据库 / Mock。
 *
 * 这个小类只有一条规则：subtotal() 怎么算钱。但它错一位数，全仓的金额都跟着错 ——
 * Order.calcTotalAmount 就是"把每条明细的 subtotal 加起来"。
 *
 * 补测之前它 99 条指令里有 61 条没被执行过（2026-09-18 的 JaCoCo 报告），
 * 其中**恰好是那条防御分支**（没定价就算钱 → 抛 IllegalStateException）——
 * 也是这个类唯一一条从没被走到的路。
 *
 * 类的另一半不变量在**签名**里，不在 if 里：下单用的构造器根本没有单价参数，
 * 单价只能由后端查价目表算出来再 applyPrice。于是"不认外部传价"是编译期成立的，
 * 不存在"哪个入口忘了判"—— 1 块钱洗羽绒服那个洞，就是从这类"顺手加个参数"开始的。
 *
 * 刻意**不测** getter/setter 本身（唯一例外是下面那条"单价确实是空的"，
 * 因为它是"外部传价进不来"的证据，不是刷数字）。
 */
class OrderItemTest {

    @Nested
    @DisplayName("subtotal：单价 × 数量")
    class Subtotal {

        @Test
        @DisplayName("15.00 × 2 = 30.00（订单总额就是这些 subtotal 的和）")
        void plain() {
            assertThat(item(2, new BigDecimal("15.00")).subtotal())
                    .isEqualByComparingTo("30.00");
        }

        @Test
        @DisplayName("数量 1 时就是单价本身")
        void singlePiece() {
            assertThat(item(1, new BigDecimal("15.00")).subtotal())
                    .isEqualByComparingTo("15.00");
        }

        @Test
        @DisplayName("数量 0 → 0.00（不抛，也不是 null —— 它是求和的中性元）")
        void zeroQuantity() {
            // "数量必须为正整数"是**下单入口**的形状校验（OrderController 那条
            // 400 的文案就是它），明细自己不管这一层。真塞进 0 时它老实算 0，
            // 于是一张全是 0 件的订单总额也是 0 —— 这正是 OrderTest 末尾
            // "空明细能走到终态"那条钉住的组合的另一半
            assertThat(item(0, new BigDecimal("15.00")).subtotal())
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("单价 0.00 → 0.00（0 价 = 不支持，能走到这里说明价目表漏了行）")
        void zeroPrice() {
            assertThat(item(1, new BigDecimal("0.00")).subtotal())
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("带小数的单价：12.50 × 3 = 37.50，分不丢")
        void withCents() {
            // 乘以整数不会产生新的小数位，所以这里**不需要**舍入 ——
            // 舍入只发生在 applyCoupon（折扣率乘出来才是无限小数）。
            // 这条钉的是"别在这里顺手加一句 setScale"，加了会把 0.005 这类
            // 本来就该保留的精度提前抹掉
            assertThat(item(3, new BigDecimal("12.50")).subtotal())
                    .isEqualByComparingTo("37.50");
        }

        /** 从数据库里那个形状造一条明细：单价是算好落库的事实，所以走带单价的那个构造器 */
        private OrderItem item(int quantity, BigDecimal unitPrice) {
            return new OrderItem(11L, 1L, quantity, unitPrice, null);
        }
    }

    @Nested
    @DisplayName("没定价就算钱：抛 IllegalStateException，并说清为什么")
    class NotPricedYet {

        @Test
        @DisplayName("抛的是 IllegalStateException（不是 NPE），消息指向算价入口")
        void missingPriceThrowsWithGuidance() {
            // 这一条就是补测前唯一没被走到的分支。
            // 类型和文案都要钉：若它退化成 NPE（直接 unitPrice.multiply 的写法），
            // 堆栈只指向 multiply 那一行，排查的人得自己反推是哪一步漏了算价；
            // 现在是类型不同，而且带着一句人话 ——
            // OrderAppService.priceItems 的调用顺序错了，看消息就知道
            OrderItem unpriced = new OrderItem(11L, 1L, 2, null);

            assertThatThrownBy(unpriced::subtotal)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("下单必须由后端算价")
                    .hasMessageContaining("OrderAppService.priceItems");
        }

        @Test
        @DisplayName("applyPrice 一填就能算 —— 算价 → 填价 → 求和，就是这个顺序")
        void afterApplyPrice() {
            OrderItem unpriced = new OrderItem(11L, 1L, 2, null);

            unpriced.applyPrice(new BigDecimal("15.00"));

            assertThat(unpriced.getUnitPrice()).isEqualByComparingTo("15.00");
            assertThat(unpriced.subtotal()).isEqualByComparingTo("30.00");
        }

        @Test
        @DisplayName("applyPrice 是覆盖不是累加（算价入口只有一个，重复调用不该翻倍）")
        void applyPriceOverwrites() {
            // "覆盖"这件事值得单独钉：哪天它被写成 add，一次重试算价
            // （订单号撞车后重试那条路径真的会再算一遍）金额就翻倍，
            // 而没有任何东西会发觉
            OrderItem item = new OrderItem(11L, 1L, 2, new BigDecimal("15.00"), null);

            item.applyPrice(new BigDecimal("8.00"));

            assertThat(item.subtotal()).isEqualByComparingTo("16.00");
        }
    }

    @Nested
    @DisplayName("外面的价一律不认")
    class ExternalPriceRejected {

        @Test
        @DisplayName("下单构造器没有单价这个参数 —— 想传也没地方传，留下的就是 null")
        void createConstructorTakesNoPrice() {
            // "不认外部传价"是靠**签名里没有这个参数**做到的，比在方法体里加一句
            // if 更强：它编译期就成立，不存在"哪个入口漏判"。
            // 这条测试拦的是"为了图方便在构造器上加个 unitPrice"那种改动 ——
            // 那正是 1 块钱洗羽绒服那个洞的起点
            OrderItem item = new OrderItem(11L, 1L, 2, null);

            assertThat(item.getUnitPrice()).isNull();
        }
    }
}
