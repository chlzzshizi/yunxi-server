package com.yunxi.interfaces.controller;

import com.yunxi.application.dto.StaffIdentity;
import com.yunxi.application.service.StaffAuthAppService;
import com.yunxi.application.service.StaffTokenRevoker;
import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import com.yunxi.interfaces.security.JwtInterceptor;
import com.yunxi.interfaces.security.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthController 单元测试 —— 只测两件事，两件都是"接线"里最不该出错的那种。
 *
 * **一、登出写下的那把键，得是拦截器读的那把。**
 * `blacklist:token:` 这个字面量在两个文件里各写一份（这里写、JwtInterceptor 读），
 * 中间没有任何编译期联系 —— 谁改了一处，登出就变成**静默失效**：点了退出、
 * 界面回到了登录页，票却还能用到过期。E2E 脚本也验不到（要先登出、再拿同一张票
 * 去调接口，而现在没有一条脚本这么做）。所以这个类里最要紧的是那条
 * 「跨两个类」的测试：用一个当成 Redis 的 Map 把写和读接起来。
 *
 * **二、登录失败的原因不许被这一层抹平。** 401「用户名或密码错误」和
 * 403「账号已被停用」对用户是两件事：一个该重试、一个该找管理员。
 *
 * 不测的：请求体怎么绑定成 Map、@RequestHeader 取不到头时谁来报 400
 * （那是 Spring 的活，脚本覆盖着）；"能不能登录"的规则在 StaffAuthAppService
 * 自己的测试里。
 */
class AuthControllerTest {

    private static final String SECRET = "test-only-secret-0123456789-0123456789-abcdef";
    private static final long DAY = 86_400_000L;
    private static final String TOKEN = "header.payload.signature";

    private StaffAuthAppService staffAuthAppService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private JwtUtil jwtUtil;
    private AuthController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        staffAuthAppService = mock(StaffAuthAppService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        jwtUtil = new JwtUtil(SECRET, DAY);   // 真 JwtUtil：登录那条真的签真票
        controller = new AuthController(staffAuthAppService, jwtUtil, redisTemplate);
    }

    // ════════════════ 登出 ════════════════

    @Nested
    @DisplayName("登出：往黑名单里写一张票")
    class Logout {

        @Test
        @DisplayName("键是 blacklist:token:<票本身>（Bearer 前缀已剥掉），值 1，TTL 24 小时")
        void writesKeyValueAndTtl() {
            var key = org.mockito.ArgumentCaptor.forClass(String.class);
            var value = org.mockito.ArgumentCaptor.forClass(String.class);
            var ttl = org.mockito.ArgumentCaptor.forClass(Long.class);
            var unit = org.mockito.ArgumentCaptor.forClass(TimeUnit.class);

            Result<Void> r = controller.logout("Bearer " + TOKEN);

            verify(valueOps).set(key.capture(), value.capture(), ttl.capture(), unit.capture());
            // 前缀必须剥掉：拦截器读的是 Authorization 头去掉 "Bearer " 之后那一串
            // （JwtInterceptor:37-41）。这里要是原样写进键里，写进去的键永远没人读
            assertThat(key.getValue()).isEqualTo("blacklist:token:" + TOKEN);
            // 值是什么无所谓（拦的是"键在不在"），但 TTL 不能少：
            // 没有它，每登出一次就往 Redis 里留一条长生不老的记录
            assertThat(value.getValue()).isEqualTo("1");
            assertThat(ttl.getValue()).isEqualTo(24L);
            assertThat(unit.getValue()).isEqualTo(TimeUnit.HOURS);
            assertThat(r.code()).isEqualTo(200);
        }

        @Test
        @DisplayName("和拦截器对得上：这里写进去的键，那边读得到（两半字面量合成一对）")
        void writtenKeyIsTheOneInterceptorReads() {
            // 拿一个 Map 当小号 Redis：set 存进去、get 取出来。
            // 这条测试**只有跨过两个类才成立** —— 它验的不是"键拼得对"，
            // 而是 AuthController 和 JwtInterceptor 拼出来的是同一把键
            Map<String, String> fakeRedis = new HashMap<>();
            when(valueOps.get(anyString())).thenAnswer(inv -> fakeRedis.get(inv.getArgument(0)));
            doAnswer(inv -> {
                fakeRedis.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

            String token = jwtUtil.generateToken(7L, "mgr3", 1, 1L);
            controller.logout("Bearer " + token);

            JwtInterceptor interceptor = new JwtInterceptor(jwtUtil, redisTemplate,
                    mock(StaffTokenRevoker.class));
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

            Throwable t = catchThrowable(() -> interceptor.preHandle(
                    request, mock(HttpServletResponse.class), new Object()));

            // 登出之前这张票是好的，登出之后它必须被拦下 —— 中间只有那把键在起作用
            assertThat(t).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) t).getCode()).isEqualTo(401);
            assertThat(((BusinessException) t).getMessage()).isEqualTo("Token 已失效，请重新登录");
        }
    }

    // ════════════════ 登录 ════════════════

    @Nested
    @DisplayName("登录：把身份签成票")
    class Login {

        @Test
        @DisplayName("票里的 staffId / username / role / storeId 就是查出来的那个人")
        void tokenCarriesIdentity() {
            when(staffAuthAppService.login("mgr3", "pw"))
                    .thenReturn(Result.ok(new StaffIdentity(7L, "mgr3", 1, 3L)));

            String token = controller.login(
                    Map.of("username", "mgr3", "password", "pw")).data().get("token");

            // 这四个 claim 就是后面所有判断的依据：storeId 决定订单归属、
            // role 决定闸门放不放行、staffId 决定那张票有没有被作废。
            // 这一层是它们唯一一次从库里的行变成票的地方
            assertThat(jwtUtil.validateToken(token)).isTrue();
            assertThat(jwtUtil.getStaffId(token)).isEqualTo(7L);
            assertThat(jwtUtil.getUsername(token)).isEqualTo("mgr3");
            assertThat(jwtUtil.getRole(token)).isEqualTo(1);
            assertThat(jwtUtil.getStoreId(token)).isEqualTo(3L);
            assertThat(jwtUtil.getType(token)).isEqualTo("staff");
        }

        @Test
        @DisplayName("失败原因原样透传：401 和 403 是给用户的两条不同出路")
        void failurePassesCodeAndMessageThrough() {
            when(staffAuthAppService.login("gone", "pw"))
                    .thenReturn(Result.fail(403, "账号已被停用"));
            when(staffAuthAppService.login("mgr3", "bad"))
                    .thenReturn(Result.fail(401, "用户名或密码错误"));

            Result<Map<String, String>> disabled = controller.login(
                    Map.of("username", "gone", "password", "pw"));
            Result<Map<String, String>> wrongPassword = controller.login(
                    Map.of("username", "mgr3", "password", "bad"));

            // 403 要留得住：前端靠它把"去找管理员"和"再试一次"分开。
            // 这一层要是把失败统一成 401，用户就会一直重试一个已经停用的账号
            assertThat(disabled.code()).isEqualTo(403);
            assertThat(disabled.message()).isEqualTo("账号已被停用");
            assertThat(wrongPassword.code()).isEqualTo(401);
            assertThat(wrongPassword.message()).isEqualTo("用户名或密码错误");
            // 失败时不许带票出去（哪怕仓储那边返回了半个身份）
            assertThat(disabled.data()).isNull();
            assertThat(wrongPassword.data()).isNull();
        }
    }
}
