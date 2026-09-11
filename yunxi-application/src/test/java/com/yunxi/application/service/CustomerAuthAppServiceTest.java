package com.yunxi.application.service;

import com.yunxi.application.dto.CustomerIdentity;
import com.yunxi.common.Result;
import com.yunxi.domain.customer.Customer;
import com.yunxi.domain.customer.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CustomerAuthAppService 单元测试 —— 用 Mockito 假造仓储，只测注册/登录规则。
 *
 * 不依赖 Spring 容器、不连数据库。同样用**真的** BCrypt：
 * "入库的是哈希不是明文"这条只能拿真编码器验，mock 掉它等于什么都没验。
 */
class CustomerAuthAppServiceTest {

    private CustomerRepository customerRepository;
    private CustomerAuthAppService customerAuthAppService;
    private BCryptPasswordEncoder encoder;

    /** 老顾客（注册过，有密码）的哈希 */
    private static final String HASH = new BCryptPasswordEncoder().encode("123456");

    @BeforeEach
    void setUp() {
        customerRepository = mock(CustomerRepository.class);
        encoder = new BCryptPasswordEncoder();
        customerAuthAppService = new CustomerAuthAppService(customerRepository, encoder);
    }

    /** 造一个顾客。passwordHash 传 null 就是门店单顾客（柜台建档，从没设过密码） */
    private Customer customer(String phone, String passwordHash) {
        Customer c = new Customer();
        c.setId(7L);
        c.setName("李顾客");
        c.setPhone(phone);
        c.setPasswordHash(passwordHash);
        return c;
    }

    private void existing(Customer c) {
        when(customerRepository.findByPhone(c.getPhone())).thenReturn(Optional.of(c));
    }

    // ════════════════ 注册 ════════════════

    @Nested
    @DisplayName("注册（注册即登录）")
    class Register {

        @Test
        @DisplayName("新手机号 → 返回回填的自增 id 与手机号")
        void returnsGeneratedId() {
            when(customerRepository.save(any())).thenReturn(42L);

            Result<CustomerIdentity> r = customerAuthAppService.register(
                    "王新人", "13800138000", "123456");

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().customerId()).isEqualTo(42L);
            assertThat(r.data().phone()).isEqualTo("13800138000");
        }

        @Test
        @DisplayName("入库前密码必须已被 BCrypt 加密（Bug 5 教训）")
        void hashesPasswordBeforeSaving() {
            when(customerRepository.save(any())).thenReturn(42L);

            customerAuthAppService.register("王新人", "13800138000", "123456");

            // 抓真正传进仓储的那个对象来看 —— 只看接口回显是验不出明文的
            ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
            verify(customerRepository).save(captor.capture());
            Customer saved = captor.getValue();

            assertThat(saved.getPasswordHash()).isNotEqualTo("123456");
            assertThat(saved.getPasswordHash()).startsWith("$2a$");
            // 而且这个哈希得真的能验回原密码，不能是随便一个长得像哈希的字符串
            assertThat(encoder.matches("123456", saved.getPasswordHash())).isTrue();
        }

        @Test
        @DisplayName("手机号已注册 → 400，且一笔都不写")
        void rejectDuplicatePhone() {
            existing(customer("13800138000", HASH));

            Result<CustomerIdentity> r = customerAuthAppService.register(
                    "王新人", "13800138000", "123456");

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("该手机号已注册，请直接登录");
            verify(customerRepository, never()).save(any());
        }

        @Test
        @DisplayName("并发下两个请求同时过了查重 → 唯一键拦下后一个，也是 400 不是 500")
        void handleDuplicateKeyRace() {
            // 查重查不到（说明两个请求是同时进来的），但写的时候撞了 uk_phone
            when(customerRepository.save(any()))
                    .thenThrow(new DuplicateKeyException("uk_phone"));

            Result<CustomerIdentity> r = customerAuthAppService.register(
                    "王新人", "13800138000", "123456");

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("该手机号已注册，请直接登录");
        }

        @Test
        @DisplayName("姓名/手机号/密码任一为空 → 400「姓名、手机号、密码不能为空」")
        void rejectBlankFields() {
            assertThat(customerAuthAppService.register(null, "13800138000", "123456"))
                    .extracting(Result::code, Result::message)
                    .containsExactly(400, "姓名、手机号、密码不能为空");
            assertThat(customerAuthAppService.register("王新人", "  ", "123456"))
                    .extracting(Result::code, Result::message)
                    .containsExactly(400, "姓名、手机号、密码不能为空");
            // 这条最要紧：password 为 null 若不拦住，会一路传到 BCrypt 里抛异常
            assertThat(customerAuthAppService.register("王新人", "13800138000", null))
                    .extracting(Result::code, Result::message)
                    .containsExactly(400, "姓名、手机号、密码不能为空");
            verify(customerRepository, never()).save(any());
        }
    }

    // ════════════════ 登录 ════════════════

    @Nested
    @DisplayName("登录")
    class Login {

        @Test
        @DisplayName("凭据正确 → 返回身份")
        void success() {
            existing(customer("13800138000", HASH));

            Result<CustomerIdentity> r = customerAuthAppService.login("13800138000", "123456");

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().customerId()).isEqualTo(7L);
            assertThat(r.data().phone()).isEqualTo("13800138000");
        }

        @Test
        @DisplayName("手机号不存在与密码错 → 都是 401 且逐字同句（防手机号枚举）")
        void badCredentialsAreIndistinguishable() {
            existing(customer("13800138000", HASH));
            when(customerRepository.findByPhone("13900000000")).thenReturn(Optional.empty());

            Result<CustomerIdentity> wrongPwd =
                    customerAuthAppService.login("13800138000", "wrong-password");
            Result<CustomerIdentity> noSuchPhone =
                    customerAuthAppService.login("13900000000", "123456");

            assertThat(wrongPwd.code()).isEqualTo(401);
            assertThat(noSuchPhone.code()).isEqualTo(401);
            assertThat(wrongPwd.message()).isEqualTo(noSuchPhone.message());
            assertThat(wrongPwd.message()).isEqualTo("手机号或密码错误");
        }

        @Test
        @DisplayName("门店单顾客（从没设过密码）→ 401「未设置密码」，不是 500")
        void walkInCustomerHasNoPassword() {
            existing(customer("13700000002", null));

            Result<CustomerIdentity> r =
                    customerAuthAppService.login("13700000002", "123456");

            assertThat(r.code()).isEqualTo(401);
            assertThat(r.message()).isEqualTo("该手机号未设置密码，请先注册");
            // 提示得能指导人：他不知道密码，得告诉他去注册而不是反复试
            assertThat(r.message()).isNotEqualTo("手机号或密码错误");
        }

        @Test
        @DisplayName("密码是空字符串的门店单顾客也一样拦住（hasPassword 判的是空白）")
        void blankHashAlsoBlocked() {
            existing(customer("13700000002", "   "));

            Result<CustomerIdentity> r =
                    customerAuthAppService.login("13700000002", "123456");

            assertThat(r.code()).isEqualTo(401);
            assertThat(r.message()).isEqualTo("该手机号未设置密码，请先注册");
        }
    }
}
