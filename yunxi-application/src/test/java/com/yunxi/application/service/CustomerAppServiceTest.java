package com.yunxi.application.service;

import com.yunxi.application.dto.CustomerView;
import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import com.yunxi.domain.customer.Customer;
import com.yunxi.domain.customer.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CustomerAppService 单元测试 —— 门店单建单前的"手机号查 / 建档"。
 *
 * 它和 CustomerAuthAppService 是**反着**的，所以这里的断言也都在钉这一点：
 * 注册查重后报错，建档查重后返回老顾客；注册必设密码，建档不设密码。
 * 两套规则若被合并成一个类，这两个 Nested 就会开始互相打架。
 */
class CustomerAppServiceTest {

    private CustomerRepository customerRepository;
    private CustomerAppService customerAppService;

    private static final Long STORE_A = 1L;
    private static final String PHONE = "13700000001";

    @BeforeEach
    void setUp() {
        customerRepository = mock(CustomerRepository.class);
        customerAppService = new CustomerAppService(customerRepository);
    }

    /** 造一个门店单顾客（柜台建档，passwordHash 为 null） */
    private Customer customer(Long id, String phone, String name) {
        Customer c = new Customer();
        c.setId(id);
        c.setPhone(phone);
        c.setName(name);
        return c;
    }

    // ════════════════ 老顾客 ════════════════

    @Nested
    @DisplayName("老顾客：手机号认人")
    class Existing {

        @Test
        @DisplayName("手机号已存在 → created=false，返回档案里的姓名")
        void returnsExisting() {
            when(customerRepository.findByPhone(PHONE))
                    .thenReturn(Optional.of(customer(7L, PHONE, "张三")));

            Result<CustomerView> result = customerAppService.lookupOrCreate(PHONE, "张三", STORE_A);

            assertThat(result.code()).isEqualTo(200);
            assertThat(result.data().customerId()).isEqualTo(7L);
            assertThat(result.data().created()).isFalse();
            verify(customerRepository, never()).save(any());
        }

        @Test
        @DisplayName("老顾客不会被改名 —— 柜台顺手打错字不该悄悄改档案")
        void neverRenames() {
            // 请求里的姓名和档案里的不一样，返回的必须是**档案里的**那个
            when(customerRepository.findByPhone(PHONE))
                    .thenReturn(Optional.of(customer(7L, PHONE, "张三")));

            Result<CustomerView> result = customerAppService.lookupOrCreate(PHONE, "张三三", STORE_A);

            assertThat(result.data().name()).isEqualTo("张三");
            verify(customerRepository, never()).save(any());
            verify(customerRepository, never()).fillName(any(), any());
        }

        @Test
        @DisplayName("老顾客不传姓名也放行 —— 手机号就够了，不用逼店员再填一遍")
        void nameNotRequiredForExisting() {
            when(customerRepository.findByPhone(PHONE))
                    .thenReturn(Optional.of(customer(7L, PHONE, "张三")));

            Result<CustomerView> result = customerAppService.lookupOrCreate(PHONE, null, STORE_A);

            assertThat(result.code()).isEqualTo(200);
            assertThat(result.data().created()).isFalse();
        }
    }

    // ════════════════ 补名字（线上注册的顾客没有名字）════════════════

    @Nested
    @DisplayName("补名字：只在档案里没有名字时补，有名字一个字都不动")
    class FillName {

        @Test
        @DisplayName("老顾客没名字 + 店员传了名字 → 补上，且仍是 created=false")
        void fillsMissingName() {
            // 线上注册的顾客（V9 起 name 可为空）：手机号有了，名字还没有
            Customer online = customer(7L, PHONE, null);
            when(customerRepository.findByPhone(PHONE)).thenReturn(Optional.of(online));

            Result<CustomerView> result = customerAppService.lookupOrCreate(PHONE, "张三", STORE_A);

            assertThat(result.data().created()).isFalse();       // 他还是老顾客，不是新建
            assertThat(result.data().name()).isEqualTo("张三");     // 但回给前端的名字有了
            verify(customerRepository).fillName(7L, "张三");
            verify(customerRepository, never()).save(any());      // 补名字不是新建一行
        }

        @Test
        @DisplayName("老顾客没名字 + 店员也没传名字 → 不补，什么都不写")
        void noNameNoUpdate() {
            when(customerRepository.findByPhone(PHONE))
                    .thenReturn(Optional.of(customer(7L, PHONE, null)));

            Result<CustomerView> result = customerAppService.lookupOrCreate(PHONE, null, STORE_A);

            assertThat(result.code()).isEqualTo(200);
            assertThat(result.data().name()).isNull();
            verify(customerRepository, never()).fillName(any(), any());
        }

        @Test
        @DisplayName("名字是空串也算「没有名字」—— 空串和 null 都得能补上")
        void emptyStringCountsAsMissing() {
            when(customerRepository.findByPhone(PHONE))
                    .thenReturn(Optional.of(customer(7L, PHONE, "  ")));

            customerAppService.lookupOrCreate(PHONE, "张三", STORE_A);

            // 判据必须是“空白”而不是“null”：库里一旦有 '' 或空格，
            // 只判 null 就会漏掉，那个顾客永远补不上名字
            verify(customerRepository).fillName(7L, "张三");
        }

        @Test
        @DisplayName("要补的名字有 21 个字 → 400，一个字都不写（长度校验也守在这里）")
        void tooLongNameNotFilled() {
            // 补名字是**唯一**写 name 的柜台路径，长度校验漏在这就等于漏了一半：
            // 新建那边拦住了，补名字这边照样能把 21 个字捅进 VARCHAR(20)
            when(customerRepository.findByPhone(PHONE))
                    .thenReturn(Optional.of(customer(7L, PHONE, null)));

            assertThatThrownBy(() -> customerAppService.lookupOrCreate(PHONE, "衣".repeat(21), STORE_A))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("姓名不能超过 20 个字");

            verify(customerRepository, never()).fillName(any(), any());
        }

        @Test
        @DisplayName("老顾客已经有名字 + 传了超长名字 → 放行（那个名字根本用不上，不校验）")
        void tooLongNameIgnoredWhenNotUsed() {
            // 这条是"长度校验放在真要用这个值的地方"的直接后果：
            // 有名字的老顾客走的是"一个字都不动"，那就没有理由拿一个
            // 用不上的值去 400 —— 店员在旧表单里残留的超长名字不该挡住建档
            when(customerRepository.findByPhone(PHONE))
                    .thenReturn(Optional.of(customer(7L, PHONE, "张三")));

            Result<CustomerView> result =
                    customerAppService.lookupOrCreate(PHONE, "衣".repeat(21), STORE_A);

            assertThat(result.code()).isEqualTo(200);
            assertThat(result.data().name()).isEqualTo("张三");
            verify(customerRepository, never()).fillName(any(), any());
        }
    }

    // ════════════════ 新顾客 ════════════════

    @Nested
    @DisplayName("新顾客：建档")
    class Create {

        @Test
        @DisplayName("手机号查不到 + 有姓名 → created=true，storeId 落库、passwordHash 为空")
        void createsWithoutPassword() {
            when(customerRepository.findByPhone(PHONE)).thenReturn(Optional.empty());
            when(customerRepository.save(any())).thenReturn(42L);

            Result<CustomerView> result = customerAppService.lookupOrCreate(PHONE, "李四", STORE_A);

            assertThat(result.code()).isEqualTo(200);
            assertThat(result.data().customerId()).isEqualTo(42L);
            assertThat(result.data().created()).isTrue();

            // 落库的到底是什么 —— 这里才是这条用例的重点
            ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
            verify(customerRepository).save(captor.capture());
            Customer saved = captor.getValue();
            assertThat(saved.getStoreId()).isEqualTo(STORE_A);
            assertThat(saved.getPasswordHash()).isNull();   // 门店单顾客不上线登录
        }

        @Test
        @DisplayName("新顾客 + 没姓名 → 400（老顾客才不要姓名，新档案必须有名字）")
        void newCustomerNeedsName() {
            when(customerRepository.findByPhone(PHONE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerAppService.lookupOrCreate(PHONE, "  ", STORE_A))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("新顾客必须填写姓名");

            verify(customerRepository, never()).save(any());
        }

        @Test
        @DisplayName("新顾客 + 21 个字的姓名 → 400（不是 500 的 SQL 异常）")
        void newCustomerNameTooLong() {
            // name 列是 VARCHAR(20)，不拦的话这个值会一路走到 INSERT 才被
            // MySQL 弹回来，报的是 DataTooLong 那串英文 —— 用户看到 500
            when(customerRepository.findByPhone(PHONE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerAppService.lookupOrCreate(PHONE, "衣".repeat(21), STORE_A))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("姓名不能超过 20 个字");

            verify(customerRepository, never()).save(any());
        }
    }

    // ════════════════ 手机号必填 ════════════════

    @Nested
    @DisplayName("手机号必填")
    class PhoneRequired {

        @Test
        @DisplayName("手机号为空 → 400，且不查库")
        void nullPhoneRejected() {
            assertThatThrownBy(() -> customerAppService.lookupOrCreate(null, "王五", STORE_A))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("手机号不能为空");

            // 空手机号连查都不用查 —— 挡在最前面，省一次数据库往返
            verify(customerRepository, never()).findByPhone(any());
        }

        @Test
        @DisplayName("手机号全是空格 → 同样 400（isBlank 不是 isEmpty）")
        void blankPhoneRejected() {
            assertThatThrownBy(() -> customerAppService.lookupOrCreate("   ", "王五", STORE_A))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("手机号不能为空");
        }
    }

    // ════════════════ 并发建档 ════════════════

    @Nested
    @DisplayName("并发：两个店员同时给同一手机号建档")
    class Race {

        @Test
        @DisplayName("撞上 uk_phone → 当老顾客返回 created=false（单子照样能建）")
        void duplicatesFallBackToExisting() {
            // 第一次查不到 → 去建档 → 撞键（别人抢先建了）→ 再查就有了
            when(customerRepository.findByPhone(PHONE))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(customer(43L, PHONE, "王五")));
            when(customerRepository.save(any()))
                    .thenThrow(new DuplicateKeyException("uk_phone"));

            Result<CustomerView> result = customerAppService.lookupOrCreate(PHONE, "王五", STORE_A);

            assertThat(result.code()).isEqualTo(200);
            assertThat(result.data().customerId()).isEqualTo(43L);
            assertThat(result.data().created()).isFalse();
        }

        @Test
        @DisplayName("撞键后却查不到人 → 原样抛出，不把真异常吞成「老顾客」")
        void unexpectedDuplicateIsNotSwallowed() {
            when(customerRepository.findByPhone(PHONE)).thenReturn(Optional.empty());
            when(customerRepository.save(any()))
                    .thenThrow(new DuplicateKeyException("uk_something_else"));

            // 查不到说明撞的不是手机号唯一键（比如别处的约束），
            // 这时候返回"老顾客"就是凭空编了一个 id 出来
            assertThatThrownBy(() -> customerAppService.lookupOrCreate(PHONE, "王五", STORE_A))
                    .isInstanceOf(DuplicateKeyException.class);
        }
    }

    // ════════════════ 出参里没有密码 ════════════════

    @Nested
    @DisplayName("出参形状：CustomerView 没有 password 字段")
    class ResponseShape {

        @Test
        @DisplayName("CustomerView 的字段里不存在 password —— 漏无可漏")
        void viewHasNoPasswordField() {
            // 这条不是"现在没漏"，是"将来也漏不了"：真有人给 CustomerView 加回
            // password 字段那天，这个断言会先红
            assertThat(CustomerView.class.getDeclaredFields())
                    .extracting(Field::getName)
                    .doesNotContain("password", "passwordHash");
        }
    }
}
