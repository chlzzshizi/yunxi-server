package com.yunxi.domain.customer;

import com.yunxi.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 顾客领域规则单元测试 —— 纯领域，不依赖 Spring / 数据库 / Mock。
 *
 * 覆盖两件事：有没有密码可用（hasPassword）、姓名长度（requireValidName）。
 *
 * 姓名长度这条以前**根本没有**：name 列是 VARCHAR(20)，而代码一路都不校验，
 * 于是 21 个字的姓名会走到 INSERT 才被 MySQL 弹回来，报的是 DataTooLong
 * 那串英文 SQL 异常 —— 用户看到 500。现在拦在门口，这里钉住它。
 */
class CustomerTest {

    @Nested
    @DisplayName("hasPassword：门店单顾客没有密码可用")
    class HasPassword {

        @Test
        @DisplayName("从没设过密码（柜台建档）→ false")
        void nullPassword() {
            assertThat(customer(null).hasPassword()).isFalse();
        }

        @Test
        @DisplayName("空串也算没有 —— 库里若是 '' 而不是 NULL，只判 null 会漏")
        void blankPassword() {
            // password 为 null 传给 BCrypt 会抛异常变成 500，所以这道判断
            // 必须在调 BCrypt 之前就问；而判据必须是"空白"不只是"null"
            assertThat(customer("  ").hasPassword()).isFalse();
        }

        @Test
        @DisplayName("有 BCrypt 哈希 → true")
        void hasPassword() {
            assertThat(customer("$2a$10$abcdefghijklmnopqrstuv").hasPassword()).isTrue();
        }

        private Customer customer(String passwordHash) {
            Customer c = new Customer();
            c.setPhone("13700000001");
            c.setPasswordHash(passwordHash);
            return c;
        }
    }

    @Nested
    @DisplayName("姓名长度：与 customers.name VARCHAR(20) 对齐")
    class NameLength {

        @Test
        @DisplayName("正好 20 个字 → 放行（边界是「不能超过」，不是「必须小于」）")
        void exactly20() {
            assertThatCode(() -> Customer.requireValidName("衣".repeat(20)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("21 个字 → 400 姓名不能超过 20 个字")
        void twentyOne() {
            assertThatThrownBy(() -> Customer.requireValidName("衣".repeat(21)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("姓名不能超过 20 个字");
        }

        @Test
        @DisplayName("null / 空串放行 —— 必填与否是应用层的事，这里只管长度")
        void nullAndBlankPass() {
            // 三个场合的必填规则各不相同（柜台新建必填、个人中心改名必填、
            // "补名字"可选），所以这条纯长度规则不能替它们做决定。
            // 真判了 null 就该抛的话，"不传名字"这个合法请求会被误杀
            assertThatCode(() -> Customer.requireValidName(null)).doesNotThrowAnyException();
            assertThatCode(() -> Customer.requireValidName("")).doesNotThrowAnyException();
            assertThatCode(() -> Customer.requireValidName("   ")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("emoji 按 1 个字算，不是 2 个 —— 数的是字符不是 UTF-16 码元")
        void emojiCountedAsOneChar() {
            // 20 个 emoji：Java 的 length() 会说 40（每个占一个代理对），
            // 但 MySQL 的 VARCHAR(20) 数的是字符、只算 20，插得进去。
            // 用 length() 判断就会在这里误报超长 —— 不是数据损坏，
            // 是"明明能存却不让存"，一样是 bug
            String twentyEmoji = "😀".repeat(20);
            assertThat(twentyEmoji.length()).isEqualTo(40);   // 先证明这个前提成立
            assertThatCode(() -> Customer.requireValidName(twentyEmoji))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> Customer.requireValidName("😀".repeat(21)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("姓名不能超过 20 个字");
        }
    }
}
