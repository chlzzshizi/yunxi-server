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
import static org.mockito.ArgumentMatchers.eq;
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
                    "13800138000", "123456");

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().customerId()).isEqualTo(42L);
            assertThat(r.data().phone()).isEqualTo("13800138000");
        }

        @Test
        @DisplayName("只要手机号+密码：落库的顾客没有姓名、没有门店归属")
        void registersWithoutName() {
            when(customerRepository.save(any())).thenReturn(42L);

            customerAuthAppService.register("13800138000", "123456");

            ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
            verify(customerRepository).save(captor.capture());
            Customer saved = captor.getValue();
            // 线上注册确实不知道他是谁 —— name 留空是**如实记录**，不是漏填。
            // 名字由店员在柜台建档时补（见 CustomerAppServiceTest）
            assertThat(saved.getName()).isNull();
            assertThat(saved.getStoreId()).isNull();
        }

        @Test
        @DisplayName("入库前密码必须已被 BCrypt 加密（Bug 5 教训）")
        void hashesPasswordBeforeSaving() {
            when(customerRepository.save(any())).thenReturn(42L);

            customerAuthAppService.register("13800138000", "123456");

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
        @DisplayName("手机号已注册**且有密码** → 400，且一笔都不写")
        void rejectDuplicatePhone() {
            existing(customer("13800138000", HASH));

            Result<CustomerIdentity> r = customerAuthAppService.register(
                    "13800138000", "123456");

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
                    "13800138000", "123456");

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("该手机号已注册，请直接登录");
        }

        @Test
        @DisplayName("手机号/密码任一为空 → 400「手机号和密码不能为空」")
        void rejectBlankFields() {
            assertThat(customerAuthAppService.register(null, "123456"))
                    .extracting(Result::code, Result::message)
                    .containsExactly(400, "手机号和密码不能为空");
            assertThat(customerAuthAppService.register("  ", "123456"))
                    .extracting(Result::code, Result::message)
                    .containsExactly(400, "手机号和密码不能为空");
            // 这条最要紧：password 为 null 若不拦住，会一路传到 BCrypt 里抛异常
            assertThat(customerAuthAppService.register("13800138000", null))
                    .extracting(Result::code, Result::message)
                    .containsExactly(400, "手机号和密码不能为空");
            verify(customerRepository, never()).save(any());
        }
    }

    // ════════════════ 激活（无密码老顾客）════════════════

    @Nested
    @DisplayName("激活：门店单顾客第一次线上注册")
    class Activate {

        @Test
        @DisplayName("手机号已有但没密码 → 给他补上密码，不是报「已注册」")
        void setsPasswordOnPasswordlessCustomer() {
            // 柜台建档的顾客：有名字、没密码
            existing(customer("13800138000", null));
            when(customerRepository.updatePassword(any(), any())).thenReturn(true);

            Result<CustomerIdentity> r = customerAuthAppService.register(
                    "13800138000", "123456");

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().customerId()).isEqualTo(7L);   // 还是原来那一行
            // 补进去的必须也是 BCrypt 哈希
            ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
            verify(customerRepository).updatePassword(eq(7L), hash.capture());
            assertThat(hash.getValue()).startsWith("$2a$");
            assertThat(encoder.matches("123456", hash.getValue())).isTrue();
            // 激活不是新建 —— 一笔 INSERT 都不该发生
            verify(customerRepository, never()).save(any());
        }

        @Test
        @DisplayName("这条路径以前是死的：有密码就说「已注册」、没密码登录又说「请先注册」")
        void bothBranchesNowLeadSomewhere() {
            // 同一个手机号，两种档案形状各自走到不同的出口，但**都能走通**
            // 1) 有的：报已注册 —— 他确实可以「直接登录」
            existing(customer("13800138000", HASH));
            assertThat(customerAuthAppService.register("13800138000", "123456").code())
                    .isEqualTo(400);
            assertThat(customerAuthAppService.login("13800138000", "123456").code())
                    .isEqualTo(200);

            // 2) 没密码的：注册即激活 —— 而不是被推去登录、登录又被推回来
            existing(customer("13800138000", null));
            when(customerRepository.updatePassword(any(), any())).thenReturn(true);
            assertThat(customerAuthAppService.register("13800138000", "123456").code())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("并发激活：CAS 命中 0 行（别人抢先设了密码）→ 400 同一句，不覆盖对方")
        void casConflictOnConcurrentActivate() {
            existing(customer("13800138000", null));
            when(customerRepository.updatePassword(any(), any())).thenReturn(false);

            Result<CustomerIdentity> r = customerAuthAppService.register(
                    "13800138000", "123456");

            // 对后到的人而言事实就是"这个号已经有密码了"，所以回同一句话。
            // 关键是不能覆盖：否则先设密码那个人的密码被换掉，他下次登录会莫名失败
            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("该手机号已注册，请直接登录");
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

        @Test
        @DisplayName("密码字段缺失/空白 → 401，不是 400（Bug 23）")
        void blankPasswordIsLoginFailureNotParamError() {
            existing(customer("13800138000", HASH));

            for (String blank : new String[]{null, "", "   "}) {
                assertThat(customerAuthAppService.login("13800138000", blank))
                        .extracting(Result::code, Result::message)
                        .containsExactly(401, "手机号或密码错误");
            }
        }

        @Test
        @DisplayName("门店单顾客 + 没带密码 → 仍回「未设置密码，请先注册」这条更管用的提示")
        void blankPasswordKeepsWalkInHint() {
            existing(customer("13700000002", null));

            assertThat(customerAuthAppService.login("13700000002", null))
                    .extracting(Result::code, Result::message)
                    .containsExactly(401, "该手机号未设置密码，请先注册");
            // 守卫放在 hasPassword 之后是有意的：提前拦会把"你去注册一下"换成
            // "手机号或密码错误"，对门店单顾客来说等于没给任何出路
        }
    }
}
