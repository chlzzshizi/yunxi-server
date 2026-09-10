package com.yunxi.domain.price;

import com.yunxi.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 定价规则单元测试 —— 纯领域测试，不依赖 Spring / 数据库 / Mock。
 *
 * 覆盖：
 *   1. 精洗派生（含小数位与 0 价边界）
 *   2. 洗涤方式枚举校验
 *   3. "价格 0 = 不支持"这条约定
 */
class PricePolicyTest {

    @Nested
    @DisplayName("精洗派生：精洗 = 普洗 + 20")
    class DeriveRefined {

        @Test
        @DisplayName("衬衫 15.00 → 精洗 35.00（设计文档 §4.5 的样例）")
        void plain15() {
            assertThat(PricePolicy.deriveRefined(new BigDecimal("15.00")))
                    .isEqualByComparingTo("35.00");
        }

        @Test
        @DisplayName("带小数的普洗价：12.50 → 32.50，小数位不丢")
        void plainWithCents() {
            assertThat(PricePolicy.deriveRefined(new BigDecimal("12.50")))
                    .isEqualByComparingTo("32.50");
        }

        @Test
        @DisplayName("普洗 0（不支持普洗）也能算出 20 —— 是否采用由应用层决定")
        void plainZero() {
            // 规则本身只做加法，"价格为 0 时不派生精洗"是写路径的取舍，
            // 不该混进这条纯算术规则里（否则这里的 if 会越加越多）
            assertThat(PricePolicy.deriveRefined(BigDecimal.ZERO))
                    .isEqualByComparingTo("20");
        }

        @Test
        @DisplayName("加价常量是 20，改价只改这一处")
        void extraIs20() {
            assertThat(PricePolicy.REFINED_EXTRA).isEqualByComparingTo("20");
        }
    }

    @Nested
    @DisplayName("洗涤方式校验")
    class KnownWashType {

        @Test
        @DisplayName("1/2/3 是仅有的三种合法取值")
        void known() {
            PricePolicy.requireKnownWashType(PricePolicy.PLAIN);
            PricePolicy.requireKnownWashType(PricePolicy.REFINED);
            PricePolicy.requireKnownWashType(PricePolicy.IRON);
        }

        @Test
        @DisplayName("4 号洗涤方式不存在 → 400 且报出编号")
        void unknown() {
            assertThatThrownBy(() -> PricePolicy.requireKnownWashType(4L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("没有这个洗涤方式")
                    .hasMessageContaining("4");
        }

        @Test
        @DisplayName("null 也不能蒙混过关（前端漏传）")
        void nullWashType() {
            assertThatThrownBy(() -> PricePolicy.requireKnownWashType(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("没有这个洗涤方式");
        }
    }

    @Nested
    @DisplayName("价格 0 = 不支持")
    class Supported {

        @Test
        @DisplayName("0.00 不支持（羽绒服的普洗）")
        void zeroNotSupported() {
            assertThat(new ClothesPrice(13L, PricePolicy.PLAIN, new BigDecimal("0.00"))
                    .isSupported()).isFalse();
        }

        @Test
        @DisplayName("60.00 支持（羽绒服的精洗）")
        void positiveSupported() {
            assertThat(new ClothesPrice(13L, PricePolicy.REFINED, new BigDecimal("60.00"))
                    .isSupported()).isTrue();
        }

        @Test
        @DisplayName("null 价格当作不支持，不抛 NPE")
        void nullNotSupported() {
            assertThat(new ClothesPrice(13L, PricePolicy.PLAIN, null).isSupported())
                    .isFalse();
        }
    }
}
