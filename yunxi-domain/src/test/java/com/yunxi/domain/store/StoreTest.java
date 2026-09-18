package com.yunxi.domain.store;

import com.yunxi.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 门店领域规则单元测试 —— 纯领域，不依赖 Spring / 数据库 / Mock。
 *
 * 补测前这个类**一行测试都没有**（2026-09-18 的 JaCoCo 报 0%），
 * 而它是四处 `codePointCount` 副本里**唯一没被 emoji 钉住**的那一处：
 * Customer / Staff 早有 emoji 边界用例，Store 一处没有 ——
 * 也就是说有人把它改回 length() 不会有任何测试变红。这里把它补上。
 * （写这段时核实过：Order.fillExpressNo 那处当时也**只有** ASCII 的
 *   "X".repeat(50/51)，同样没被 emoji 钉住 —— 已在同一轮里一并补上。
 *   所以"这四处现在都被钉住"这句是在那一轮之后才成立的。）
 *
 * ⚠️ 这个类**测不到门店域仅有那条业务规则** ——"营业中才能接单"。
 * 它不在 Store 里，而在 StoreRepository.findOpen / findOpenById 的 SQL 里
 * （Store 的类注释写明了为什么：过滤只放在 SQL，靠方法名表态，
 * findOpen = 只要营业的、findAll = 全都要）。
 * 域层够不着 SQL，§7 又规定 Mapper 不写测试 —— 所以那条规则
 * **至今只有真机脚本在验**（verify-stores.sh 的 A3 一组）。
 * 留这个记号，免得下次看到"Store 也有测试了"就以为那条规则被单测保住了。
 *
 * 刻意**不测** getter/setter：它们没有规则，测了只是刷数字。
 */
class StoreTest {

    // ════════════════ 长度上限 ════════════════
    //
    // 三条都是**只管长度不管必填**（null / 空串放行，必填由应用层判断），
    // 判据是 codePointCount 而不是 length()。

    @Nested
    @DisplayName("门店名称长度：与 stores.name VARCHAR(50) 对齐")
    class NameLength {

        @Test
        @DisplayName("正好 50 个字 → 放行（边界是「不能超过」，不是「必须小于」）")
        void exactly50() {
            assertThatCode(() -> Store.requireValidName("店".repeat(50)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("51 个字 → 400 门店名称不能超过 50 个字")
        void fiftyOne() {
            // 51 这个长度同时也是 verify-admin.sh D12 与 verify-stores.sh D5
            // 真机验的那个（那边还顺带断言"被拒之后库里没被写脏"）。
            // 那边证"这条校验在真实请求里接上了"，这里证规则本身。
            // 文案里是「门店名称」不是「名称」—— 门店和员工的说的是两句话，别混
            assertThatThrownBy(() -> Store.requireValidName("店".repeat(51)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("门店名称不能超过 50 个字");
        }

        @Test
        @DisplayName("null / 空串放行 —— 必填与否是应用层的事")
        void nullAndBlankPass() {
            // 建店要必填、改店也要必填，但那是 StoreAdminAppService.validate 的事；
            // 这条纯长度规则真判了 null 就该抛的话，"没传名字"会被误杀成超长
            assertThatCode(() -> Store.requireValidName(null)).doesNotThrowAnyException();
            assertThatCode(() -> Store.requireValidName("")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("emoji 按 1 个字算：50 个放行、51 个才拦（这一处原先没被钉住）")
        void emojiCountedAsOneChar() {
            // 50 个 emoji 的 length() 是 100，但它插得进 VARCHAR(50) ——
            // MySQL 数的也是字符
            assertThat("😀".repeat(50).length()).isEqualTo(100);   // 先证明这个前提成立
            assertThatCode(() -> Store.requireValidName("😀".repeat(50)))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> Store.requireValidName("😀".repeat(51)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("门店名称不能超过 50 个字");
        }
    }

    @Nested
    @DisplayName("地址长度：与 stores.address VARCHAR(200) 对齐")
    class AddressLength {

        @Test
        @DisplayName("正好 200 个字 → 放行")
        void exactly200() {
            assertThatCode(() -> Store.requireValidAddress("路".repeat(200)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("201 个字 → 400 地址不能超过 200 个字")
        void twoHundredOne() {
            // 200 是三处上限里最大的一个，也是最不可能被人手工数出来的 ——
            // 少了这条，改错一位数字不会有任何东西发现
            assertThatThrownBy(() -> Store.requireValidAddress("路".repeat(201)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("地址不能超过 200 个字");
        }

        @Test
        @DisplayName("null / 空串放行")
        void nullAndBlankPass() {
            assertThatCode(() -> Store.requireValidAddress(null)).doesNotThrowAnyException();
            assertThatCode(() -> Store.requireValidAddress("")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("电话长度：与 stores.phone VARCHAR(20) 对齐")
    class PhoneLength {

        @Test
        @DisplayName("正好 20 位 → 放行")
        void exactly20() {
            assertThatCode(() -> Store.requireValidPhone("0".repeat(20)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("21 位 → 400 电话不能超过 20 个字（**说的是「电话」不是「手机号」**）")
        void twentyOne() {
            // 门店电话可以是座机（verify-stores.sh 种子用的是 '0571-00000000'），
            // 所以文案跟员工那条不一样。两句话混了用户会看不懂自己在填哪个
            assertThatThrownBy(() -> Store.requireValidPhone("0".repeat(21)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("电话不能超过 20 个字");
        }

        @Test
        @DisplayName("null 放行 —— phone 可空")
        void nullPasses() {
            assertThatCode(() -> Store.requireValidPhone(null)).doesNotThrowAnyException();
        }
    }
}
