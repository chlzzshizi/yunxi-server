package com.yunxi.common.enums;

import com.yunxi.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三个 fromCode 的单元测试 —— 纯枚举，不依赖 Spring / 数据库 / Mock。
 *
 * 为什么单独给它们写一测：fromCode 是"库里的数字/字符串 → 领域枚举"的**唯一入口**
 * （OrderRepositoryImpl:150/151/157 读订单三处全走它，OrderController 收 query 参数同理），
 * 而它在此之前**一行测试都没有** —— 未识别码抛什么、消息是哪句、码值改没改，
 * 全靠九个验收脚本顺带扫过，脚本一薄就没人钉了。
 *
 * 三条口径钉在这里：
 *   · 未识别码抛 **BusinessException**（不是 IllegalArgumentException，也不是 Error）
 *     —— 理由见 OrderStatus.fromCode 的注释（2026-09-12 BCrypt 英文原文漏给前端那次）
 *   · 默认码值是 **400** —— GlobalExceptionHandler 据此把它转成前端读得懂的 JSON
 *   · 码值是**落库契约**：orders.status 存的是 code 不是枚举名（V8 那次迁移就是为它配的），
 *     所以谁动码值谁先在这里红一次，而不是等库里出现"没有这个状态: 8"
 *
 * 刻意**不测** getter/setter：它们没有规则，测了只是刷数字。
 * `StaffRole.fromCode` 同属这批写法，但已有先例覆盖（StaffAdminAppServiceTest:241/682），
 * 不在这里重复 —— 测试也讲"一件事只钉一处"。
 */
class EnumCodeTest {

    // ════════════════ OrderStatus：状态机读库的唯一入口 ════════════════

    @Nested
    @DisplayName("OrderStatus.fromCode：码值连号 1–7")
    class OrderStatusCodes {

        @Test
        @DisplayName("每个码值都能原样回到自己（漏一个 / 重一个都会在这里红）")
        void everyCodeRoundTrips() {
            for (OrderStatus s : OrderStatus.values()) {
                assertThat(OrderStatus.fromCode(s.getCode())).isEqualTo(s);
            }
        }

        @Test
        @DisplayName("码值就是 1..7 连号 —— 这是**落库契约**，改它必须配迁移")
        void codesAreOneToSeven() {
            // 上面那条往返测不出"码值被整体挪了"：把 PENDING_PICKUP 从 5 改成 8，
            // 往返照样通过，而库里历史行的 5 会变成"没有这个状态: 5"。
            // 2026-09-11 敢把「7 已送达 / 8 已取件」压成「7 已完成」，是因为配了 V8 迁移。
            // 这一条就是那次教训留下的哨兵
            assertThat(OrderStatus.values()).hasSize(7);
            for (int code = 1; code <= 7; code++) {
                assertThat(OrderStatus.fromCode(code).getCode())
                        .as("码值 %d 的往返", code)
                        .isEqualTo(code);
            }
        }

        @Test
        @DisplayName("历史码 8（旧的「已取件」）现在算未识别码 —— V8 之后库里不该再有它")
        void code8IsGone() {
            assertThatThrownBy(() -> OrderStatus.fromCode(8))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("没有这个状态: 8");
        }

        @Test
        @DisplayName("0 / 负数 / 越界码 → 那句「没有这个状态」（不是别的异常）")
        void unknownCodes() {
            for (int bad : new int[]{0, -1, 99}) {
                assertThatThrownBy(() -> OrderStatus.fromCode(bad))
                        .as("码值 %d", bad)
                        .isInstanceOf(BusinessException.class)
                        .hasMessage("没有这个状态: " + bad);
            }
        }

        @Test
        @DisplayName("默认码值 400 —— 转成前端能读的 JSON，不是 500")
        void defaultsTo400() {
            assertThatThrownBy(() -> OrderStatus.fromCode(99))
                    .hasFieldOrPropertyWithValue("code", 400);
        }
    }

    // ════════════════ OrderSource ════════════════

    @Nested
    @DisplayName("OrderSource.fromCode：1 门店单 / 2 网单")
    class OrderSourceCodes {

        @Test
        @DisplayName("每个码值都能原样回到自己，且就是 1/2（同样是落库契约）")
        void codesRoundTrip() {
            for (OrderSource s : OrderSource.values()) {
                assertThat(OrderSource.fromCode(s.getCode())).isEqualTo(s);
            }
            assertThat(OrderSource.STORE.getCode()).isEqualTo(1);
            assertThat(OrderSource.ONLINE.getCode()).isEqualTo(2);
        }

        @Test
        @DisplayName("未识别码 → 抛「没有这个来源」那句")
        void unknownCode() {
            assertThatThrownBy(() -> OrderSource.fromCode(9))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("没有这个来源: 9");
        }
    }

    // ════════════════ PayMethod ════════════════

    @Nested
    @DisplayName("PayMethod.fromCode：code 是**字符串**，进的是 orders.pay_method 列")
    class PayMethodCodes {

        @Test
        @DisplayName("每个码值都能原样回到自己")
        void everyCodeRoundTrips() {
            for (PayMethod m : PayMethod.values()) {
                assertThat(PayMethod.fromCode(m.getCode())).isEqualTo(m);
            }
        }

        @Test
        @DisplayName("四个码值就是 cash / wechat / alipay / balance（早先没人钉过）")
        void codesAreTheDataContract() {
            // 这四个字符串同时活在三个地方：orders.pay_method 列、前端支付方式选择、
            // 脚本里 ?payMethod=cash 那几条。加一个支付方式却忘了另一头，
            // 在这里红，比在"顾客点了微信却提示 没有这个支付方式"那里红便宜得多
            assertThat(PayMethod.values()).extracting(PayMethod::getCode)
                    .containsExactlyInAnyOrder("cash", "wechat", "alipay", "balance");
        }

        @Test
        @DisplayName("未识别字符串 → 抛「没有这个支付方式」那句")
        void unknownCode() {
            assertThatThrownBy(() -> PayMethod.fromCode("bitcoin"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("没有这个支付方式: bitcoin");
        }

        @Test
        @DisplayName("大小写敏感：「CASH」不算 cash（前端漏转小写要在这里现形）")
        void caseSensitive() {
            // 钉的是当前行为：判据是 m.code.equals(code)，没有 equalsIgnoreCase。
            // 好处是码值只有一种写法，坏处是前端传 CASH 会拿到 400 —— 那是我们要的，
            // 静默匹配上才是麻烦（哪天多了个大小写不同的新码值，会挑不出错）
            assertThatThrownBy(() -> PayMethod.fromCode("CASH"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("没有这个支付方式: CASH");
        }

        @Test
        @DisplayName("null → 照抛 BusinessException（**不是 NPE**）")
        void nullCode() {
            // 说清楚这条的分量：现在**没有活调用点能传进 null** ——
            // 三个调用点（OrderController:146/162/181）都是 @RequestParam String，
            // 少传参数 Spring 自己先回 400，走不到 fromCode。
            // 所以它钉的是**防御性质**，不是活路径：判据写成 m.code.equals(code)
            //（常量在前）所以不 NPE。哪天有人"顺手"改成 code.equals(m.code)，
            // 这一条会立刻红 —— 那时若调用点也正好放宽成 required=false，
            // 顾客看到的就是一个 500 + 空指针栈，而不是"没有这个支付方式: null"
            assertThatThrownBy(() -> PayMethod.fromCode(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("没有这个支付方式: null");
        }
    }
}
