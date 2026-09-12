package com.yunxi.application.service;

import com.yunxi.application.dto.CustomerProfileView;
import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import com.yunxi.domain.customer.Customer;
import com.yunxi.domain.customer.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
 * CustomerProfileAppService 单元测试 —— 顾客在个人中心看/改自己的档案。
 *
 * 这里最要紧的一条不是"改名能成"，而是**改的一定是 token 里那个人**：
 * 应用层的 rename 没有第二道锁（它就是要覆盖旧名字），所以"改谁"这件事
 * 在应用层是拦不住的 —— 它由接口层保证（customerId 只从 token 取）。
 * 单测能钉的是"调用 rename 时用的就是传进来的那个 id、没被别的东西替换"。
 */
class CustomerProfileAppServiceTest {

    private CustomerRepository customerRepository;
    private CustomerProfileAppService customerProfileAppService;

    private static final Long ME = 7L;
    private static final String PHONE = "13700000001";

    @BeforeEach
    void setUp() {
        customerRepository = mock(CustomerRepository.class);
        customerProfileAppService = new CustomerProfileAppService(customerRepository);
    }

    private Customer customer(Long id, String name) {
        Customer c = new Customer();
        c.setId(id);
        c.setPhone(PHONE);
        c.setName(name);
        return c;
    }

    // ════════════════ 看自己的档案 ════════════════

    @Nested
    @DisplayName("GET /me：看自己的档案")
    class GetProfile {

        @Test
        @DisplayName("查得到 → 200，返回自己的 id、姓名、手机号")
        void returnsProfile() {
            when(customerRepository.findById(ME)).thenReturn(Optional.of(customer(ME, "张三")));

            Result<CustomerProfileView> result = customerProfileAppService.getProfile(ME);

            assertThat(result.code()).isEqualTo(200);
            assertThat(result.data().customerId()).isEqualTo(ME);
            assertThat(result.data().name()).isEqualTo("张三");
            assertThat(result.data().phone()).isEqualTo(PHONE);
        }

        @Test
        @DisplayName("档案里没名字（线上注册的顾客）→ 200 且 name 为 null，不是报错")
        void nullNameIsFine() {
            // 注册只要手机号+密码，所以 name 为 null 是**常态**而不是异常。
            // 个人中心的正确表现是把空的姓名框留着让他填，而不是开不了页面
            when(customerRepository.findById(ME)).thenReturn(Optional.of(customer(ME, null)));

            Result<CustomerProfileView> result = customerProfileAppService.getProfile(ME);

            assertThat(result.code()).isEqualTo(200);
            assertThat(result.data().name()).isNull();
        }

        @Test
        @DisplayName("行没了（账号被删）→ 404 顾客不存在")
        void goneCustomer() {
            // 不查库直接拿 token 里的数字编一个档案返回，才是真正的错 ——
            // 那等于给一个不存在的顾客发了个"你好，null"
            when(customerRepository.findById(ME)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerProfileAppService.getProfile(ME))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("顾客不存在");
        }

        @Test
        @DisplayName("customerId 为 null → 401（那是没带身份，不是这个顾客不存在）")
        void nullIdIsUnauthorized() {
            assertThatThrownBy(() -> customerProfileAppService.getProfile(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("请使用顾客账号登录");

            // 连库都不用查 —— 挡在最前面
            verify(customerRepository, never()).findById(any());
        }
    }

    // ════════════════ 改自己的名字 ════════════════

    @Nested
    @DisplayName("PUT /me：改自己的名字")
    class UpdateName {

        @Test
        @DisplayName("有名字的人照样能改（这正是 rename 与 fillName 的区别）")
        void overwritesExistingName() {
            // 补名字那条路（CustomerAppService.fillName）带第二道锁，只在
            // 名字为空时生效。个人中心是"本人主动改"，必须能覆盖 ——
            // 这个用例钉的就是两者不能互换
            when(customerRepository.findById(ME)).thenReturn(Optional.of(customer(ME, "张三")));

            Result<CustomerProfileView> result = customerProfileAppService.updateName(ME, "张三丰");

            assertThat(result.code()).isEqualTo(200);
            assertThat(result.data().name()).isEqualTo("张三丰");
            verify(customerRepository).rename(ME, "张三丰");
        }

        @Test
        @DisplayName("从没有名字改成有名字（注册时没填，现在来填）")
        void fillsNameFromNull() {
            when(customerRepository.findById(ME)).thenReturn(Optional.of(customer(ME, null)));

            Result<CustomerProfileView> result = customerProfileAppService.updateName(ME, "李四");

            assertThat(result.data().name()).isEqualTo("李四");
            verify(customerRepository).rename(ME, "李四");
        }

        @Test
        @DisplayName("改的永远是传进来的那个 id —— 换成别人就改别人，这里没有第二道防线")
        void writesToExactlyTheGivenId() {
            // 这条不是"测试通过"，是把**已知的脆弱点**写下来：
            // rename 的 SQL 是 `WHERE id = #{id}`，没有别的条件。
            // 传 99 进去它就会改 99 那一行，一声不吭。
            // 所以接口层"customerId 只从 token 取"那一行不是风格问题，是唯一的安全边界。
            when(customerRepository.findById(99L)).thenReturn(Optional.of(customer(99L, "别人")));

            customerProfileAppService.updateName(99L, "被改的人");

            verify(customerRepository).rename(99L, "被改的人");
            verify(customerRepository, never()).rename(ME, "被改的人");
        }

        @Test
        @DisplayName("名字为空 / 空白 → 400，且一个字都不写库")
        void blankRejected() {
            // 这里**故意不 stub findById**：这条用例要证明的是"坏参数根本走不到查库"。
            // 万一哪天有人把顺序改成"先查库再校验"，查询会落到未 stub 的 mock 上
            // 拿到空 Optional → 报 404 而不是 400，这条就会红
            for (String bad : new String[]{null, "", "   "}) {
                assertThatThrownBy(() -> customerProfileAppService.updateName(ME, bad))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("姓名不能为空");
            }
            verify(customerRepository, never()).rename(any(), any());
        }

        @Test
        @DisplayName("21 个字 → 400 姓名不能超过 20 个字（不是 500 的 SQL 异常）")
        void tooLongRejected() {
            // 不拦的话这个值会一路走到 UPDATE 才被 MySQL 弹回来，
            // 报的是 DataTooLong 那串英文 —— 用户看到 500。
            // 同样不 stub findById：超长也是"走不到查库"的坏参数
            assertThatThrownBy(() -> customerProfileAppService.updateName(ME, "衣".repeat(21)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("姓名不能超过 20 个字");

            verify(customerRepository, never()).rename(any(), any());
        }

        @Test
        @DisplayName("正好 20 个字 → 放行")
        void twentyExactly() {
            when(customerRepository.findById(ME)).thenReturn(Optional.of(customer(ME, "张三")));

            Result<CustomerProfileView> result =
                    customerProfileAppService.updateName(ME, "衣".repeat(20));

            assertThat(result.code()).isEqualTo(200);
        }

        @Test
        @DisplayName("customerId 为 null → 401，且不查库")
        void nullIdIsUnauthorized() {
            assertThatThrownBy(() -> customerProfileAppService.updateName(null, "张三"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("请使用顾客账号登录");

            verify(customerRepository, never()).findById(any());
        }

        @Test
        @DisplayName("行没了（账号被删）→ 404")
        void goneCustomer() {
            when(customerRepository.findById(ME)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerProfileAppService.updateName(ME, "张三"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("顾客不存在");

            verify(customerRepository, never()).rename(any(), any());
        }
    }

    // ════════════════ 出参里没有密码 ════════════════

    @Nested
    @DisplayName("出参形状：CustomerProfileView 没有 password 字段")
    class ResponseShape {

        @Test
        @DisplayName("字段里不存在 password —— 漏无可漏")
        void viewHasNoPasswordField() {
            assertThat(CustomerProfileView.class.getDeclaredFields())
                    .extracting(Field::getName)
                    .doesNotContain("password", "passwordHash");
        }
    }
}
