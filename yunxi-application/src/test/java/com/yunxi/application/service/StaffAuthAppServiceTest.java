package com.yunxi.application.service;

import com.yunxi.application.dto.StaffIdentity;
import com.yunxi.common.Result;
import com.yunxi.domain.staff.Staff;
import com.yunxi.domain.staff.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StaffAuthAppService 单元测试 —— 用 Mockito 假造仓储，只测登录规则。
 *
 * 不依赖 Spring 容器、不连数据库：这就是把登录逻辑从 controller 搬进应用层的
 * 直接回报 —— 在此之前，"停用账号该不该放行"只能靠打真机接口来验。
 *
 * 密码编码器用的是**真的** BCrypt（不是 mock）：拿假编码器测"密码错 → 401"，
 * 测的是"我 stub 了什么"，不是"BCrypt 比不比得出差异"。
 */
class StaffAuthAppServiceTest {

    private StaffRepository staffRepository;
    private StaffAuthAppService staffAuthAppService;

    /** admin123 的 BCrypt 哈希。真实哈希只算一次，省下每个用例 60ms */
    private static final String HASH = new BCryptPasswordEncoder().encode("admin123");

    @BeforeEach
    void setUp() {
        staffRepository = mock(StaffRepository.class);
        staffAuthAppService = new StaffAuthAppService(staffRepository,
                new BCryptPasswordEncoder());
    }

    /** 造一个员工。status 默认 1=启用 */
    private Staff staff(String username, Integer status) {
        Staff s = new Staff();
        s.setId(2L);
        s.setUsername(username);
        s.setPasswordHash(HASH);
        s.setName("张店长");
        s.setRole(1);
        s.setStoreId(1L);
        s.setStatus(status);
        return s;
    }

    /** 让仓储"存着这么一个员工" */
    private void existing(Staff s) {
        when(staffRepository.findByUsername(s.getUsername())).thenReturn(Optional.of(s));
    }

    // ════════════════ 成功 ════════════════

    @Nested
    @DisplayName("登录成功")
    class Success {

        @Test
        @DisplayName("凭据正确 → 返回身份，且四样都带上（签 token 全靠它们）")
        void returnsIdentity() {
            existing(staff("manager", 1));

            Result<StaffIdentity> r = staffAuthAppService.login("manager", "admin123");

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data().staffId()).isEqualTo(2L);
            assertThat(r.data().username()).isEqualTo("manager");
            assertThat(r.data().role()).isEqualTo(1);
            assertThat(r.data().storeId()).isEqualTo(1L);
        }
    }

    // ════════════════ 凭据不对 ════════════════

    @Nested
    @DisplayName("凭据不对：两种失败回同一句话")
    class BadCredentials {

        @Test
        @DisplayName("用户名不存在 → 401 用户名或密码错误")
        void unknownUser() {
            when(staffRepository.findByUsername("nobody")).thenReturn(Optional.empty());

            Result<StaffIdentity> r = staffAuthAppService.login("nobody", "admin123");

            assertThat(r.code()).isEqualTo(401);
            assertThat(r.message()).isEqualTo("用户名或密码错误");
            assertThat(r.data()).isNull();
        }

        @Test
        @DisplayName("密码错 → 401，且消息与「用户名不存在」逐字相同（否则能枚举账号）")
        void wrongPassword() {
            existing(staff("manager", 1));

            Result<StaffIdentity> r = staffAuthAppService.login("manager", "wrong-password");

            assertThat(r.code()).isEqualTo(401);
            // 逐字相同才叫不可枚举；只要差一个字，就等于承认"这个用户名是存在的"
            assertThat(r.message()).isEqualTo("用户名或密码错误");
        }

        @Test
        @DisplayName("密码字段缺失/空白 → 也是 401 那句话，不是 400（Bug 23）")
        void blankPasswordIsAWrongCredentialNotAParamError() {
            existing(staff("manager", 1));

            // 拿真的 BCrypt 跑，所以这条测试同时是"没把 null 递给编码器"的证明：
            // 守卫一旦被删掉，matches(null, ..) 会当场抛 IllegalArgumentException
            for (String blank : new String[]{null, "", "   "}) {
                assertThat(staffAuthAppService.login("manager", blank))
                        .extracting(Result::code, Result::message)
                        .containsExactly(401, "用户名或密码错误");
            }
            // 为什么不能是 400：400 等于用响应码承认"这次请求没带密码"，
            // 而 401 与"密码错"分不出来 —— 连"传没传这个字段"都不该被对方知道
        }
    }

    // ════════════════ 账号状态 ════════════════

    @Nested
    @DisplayName("账号状态：停用是单独一类失败")
    class AccountStatus {

        @Test
        @DisplayName("密码完全正确但已停用 → 403，消息必须与上面那句不同")
        void disabledWinsOverCorrectPassword() {
            existing(staff("mgr_disabled", 0));

            Result<StaffIdentity> r = staffAuthAppService.login("mgr_disabled", "admin123");

            assertThat(r.code()).isEqualTo(403);
            assertThat(r.message()).isEqualTo("账号已被停用");
            // 混进"用户名或密码错误"会让人反复重试密码，白费功夫
            assertThat(r.message()).isNotEqualTo("用户名或密码错误");
        }

        @Test
        @DisplayName("停用判断在密码之前：密码也错时，回的是「已停用」不是「密码错误」")
        void disabledCheckedBeforePassword() {
            existing(staff("mgr_disabled", 0));

            Result<StaffIdentity> r = staffAuthAppService.login("mgr_disabled", "wrong-password");

            assertThat(r.code()).isEqualTo(403);
            assertThat(r.message()).isEqualTo("账号已被停用");
        }
    }
}
