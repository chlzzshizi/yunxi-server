package com.yunxi.domain.staff;

import com.yunxi.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 员工领域规则单元测试 —— 纯领域，不依赖 Spring / 数据库 / Mock。
 *
 * 补测之前这个类**一行测试都没有**（2026-09-18 接上 JaCoCo 后报 0%，整个类
 * 一行都没被执行过），而它恰好是员工管理那一轮改动最大的类。
 * 后果很具体：把它任一规则改坏，`./mvnw test` 不会红 —— 要跑一遍九连跑
 * 真机脚本才知道，而那些脚本分钟级、有状态、还得后端起着。
 *
 * 覆盖两件事：能不能登录（canLogin）、三个长度上限（用户名 30 / 姓名 20 / 手机号 20）。
 *
 * 刻意**不测** getter/setter：它们没有规则，测了只是把覆盖率数字刷上去。
 * 所以这个类的行覆盖注定到不了 100% —— 那不是洞。
 */
class StaffTest {

    // ════════════════ 能不能登录 ════════════════

    @Nested
    @DisplayName("canLogin：只有明确启用才放行")
    class CanLogin {

        @Test
        @DisplayName("status=1（启用）→ true")
        void enabled() {
            assertThat(staffWithStatus(1).canLogin()).isTrue();
        }

        @Test
        @DisplayName("status=0（停用）→ false")
        void disabled() {
            assertThat(staffWithStatus(0).canLogin()).isFalse();
        }

        @Test
        @DisplayName("status=null → false（**默认拒绝**，不是默认放行）")
        void nullStatus() {
            // 这条是 canLogin 写成 `status != null && status == 1`
            // 而不是 `status == null || status != 0` 的**唯一理由**：
            // 库里或映射里漏填 status（null）时，宁可登不上去，也不能静默放行。
            // 原先只在应用层间接测过 status=0 一档，null 这一档一次没测
            assertThat(staffWithStatus(null).canLogin()).isFalse();
        }

        @Test
        @DisplayName("status=2（未知值）→ false —— 将来加一个状态，默认是拒绝")
        void unknownStatus() {
            // 类注释里写着"将来多出别的状态值时，默认是拒绝而不是误放"。
            // 假设哪天真的加一个 status=2（比如"锁定"），
            // 写成 `status != 0` 的版本会把这个账号**当成启用**放进去，
            // 而停用机制会静默失效。这条测试就是为了让那次改动当场变红
            assertThat(staffWithStatus(2).canLogin()).isFalse();
        }

        private Staff staffWithStatus(Integer status) {
            Staff s = new Staff();
            s.setUsername("someone");
            s.setStatus(status);
            return s;
        }
    }

    // ════════════════ 长度上限 ════════════════
    //
    // 三条都是**只管长度不管必填**（null / 空串放行，必填由应用层判断），
    // 判据是 codePointCount 而不是 length()。

    @Nested
    @DisplayName("用户名长度：与 staff.username VARCHAR(30) 对齐")
    class UsernameLength {

        @Test
        @DisplayName("正好 30 个字 → 放行（边界是「不能超过」，不是「必须小于」）")
        void exactly30() {
            assertThatCode(() -> Staff.requireValidUsername("u".repeat(30)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("31 个字 → 400 用户名不能超过 30 个字")
        void thirtyOne() {
            // 拦在写库之前的意义：否则会一路走到 INSERT 才被 MySQL 弹成
            // DataTooLong 那串英文 SQL 异常（500），而不是一句给用户看的话。
            // 31 这个长度同时也是 verify-admin.sh A7 真机验的那个 ——
            // 那边证的是"这条校验在真实请求里真的接上了"，这里证的是规则本身
            assertThatThrownBy(() -> Staff.requireValidUsername("u".repeat(31)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("用户名不能超过 30 个字");
        }

        @Test
        @DisplayName("null / 空串 / 空白串放行 —— 必填与否是应用层的事")
        void nullAndBlankPass() {
            // 三个场合的必填规则不一样（建号必填、改资料必填但可传空白转 null），
            // 所以这条纯长度规则不能替它们做决定
            for (String pass : new String[]{null, "", "   "}) {
                assertThatCode(() -> Staff.requireValidUsername(pass))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("emoji 按 1 个字算：30 个放行、31 个才拦")
        void emojiCountedAsOneChar() {
            // 30 个 emoji 的 length() 是 60，但它插得进 VARCHAR(30) ——
            // MySQL 数的也是字符。用 length() 判断会在这里误报超长：
            // 不是数据损坏，是"明明能存却不让存"，一样是 bug
            assertThat("😀".repeat(30).length()).isEqualTo(60);   // 先证明这个前提成立
            assertThatCode(() -> Staff.requireValidUsername("😀".repeat(30)))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> Staff.requireValidUsername("😀".repeat(31)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("用户名不能超过 30 个字");
        }
    }

    @Nested
    @DisplayName("姓名长度：与 staff.name VARCHAR(20) 对齐")
    class NameLength {

        @Test
        @DisplayName("正好 20 个字 → 放行")
        void exactly20() {
            assertThatCode(() -> Staff.requireValidName("衣".repeat(20)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("21 个字 → 400 姓名不能超过 20 个字")
        void twentyOne() {
            assertThatThrownBy(() -> Staff.requireValidName("衣".repeat(21)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("姓名不能超过 20 个字");
        }

        @Test
        @DisplayName("null / 空串放行")
        void nullAndBlankPass() {
            assertThatCode(() -> Staff.requireValidName(null)).doesNotThrowAnyException();
            assertThatCode(() -> Staff.requireValidName("")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("手机号长度：与 staff.phone VARCHAR(20) 对齐")
    class PhoneLength {

        @Test
        @DisplayName("正好 20 位 → 放行")
        void exactly20() {
            assertThatCode(() -> Staff.requireValidPhone("1".repeat(20)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("21 位 → 400 手机号不能超过 20 个字")
        void twentyOne() {
            assertThatThrownBy(() -> Staff.requireValidPhone("1".repeat(21)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("手机号不能超过 20 个字");
        }

        @Test
        @DisplayName("null 放行 —— phone 本来就是可空字段")
        void nullPasses() {
            assertThatCode(() -> Staff.requireValidPhone(null)).doesNotThrowAnyException();
        }
    }
}
