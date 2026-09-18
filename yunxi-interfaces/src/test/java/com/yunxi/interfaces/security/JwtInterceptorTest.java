package com.yunxi.interfaces.security;

import com.yunxi.application.service.StaffTokenRevoker;
import com.yunxi.common.BusinessException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JwtInterceptor 单元测试 —— mock 掉 Redis 与请求对象，不启动 Spring。
 *
 * 这个类是**每个请求的第一道门**，而且里面全是"看一眼就知道对不对、
 * 但错了没人会发现"的判断：谁被拦、报哪句话、按什么顺序拦。
 * 一个 mock 的 HttpServletRequest 就够了 —— 这也正是它值得单测的原因：
 * 闸门的规则是路径字符串 + 角色码的函数，跑 E2E 只是把它糊在一堆真机状态里。
 *
 * 三道门各管各的（StaffController 的类注释里写着同一份分工）：
 *   · 无票 / 坏票 / 已登出 / 已作废 → 401（**这里**）
 *   · 店长 vs 管理员这两类员工之间的边界 → 403（**这里**，checkRoleGate）
 *   · 顾客能用哪些接口 → 各 controller 自己判断（**不在这里**）
 *
 * 不测的：过滤器链的顺序、WebMvcConfig 排除了哪些路径（`/api/auth/**` 那条）。
 * 那些是 Spring 装配，看得见它们的地方是真机 —— 脚本守着。
 */
class JwtInterceptorTest {

    private static final String SECRET = "test-only-secret-0123456789-0123456789-abcdef";
    private static final String OTHER_SECRET = "another-secret-9876543210-9876543210-abcdef";
    private static final long DAY = 86_400_000L;

    private static final Long STAFF_ID = 5L;
    private static final Long STORE_ID = 1L;
    private static final Long CUSTOMER_ID = 3L;
    private static final int ADMIN = 0;
    private static final int MANAGER = 1;

    private JwtUtil jwtUtil;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private StaffTokenRevoker tokenRevoker;
    private HttpServletResponse response;
    private JwtInterceptor interceptor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, DAY);   // 真 JwtUtil：签真票、解真票
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);   // 黑名单默认未命中
        tokenRevoker = mock(StaffTokenRevoker.class);             // 水位线默认"没作废过"
        response = mock(HttpServletResponse.class);
        interceptor = new JwtInterceptor(jwtUtil, redisTemplate, tokenRevoker);
    }

    private String staffToken(int role) {
        return jwtUtil.generateToken(STAFF_ID, "mgr3", role, STORE_ID);
    }

    private String customerToken() {
        return jwtUtil.generateCustomerToken(CUSTOMER_ID, "13900000001");
    }

    private HttpServletRequest req(String method, String uri, String token) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getMethod()).thenReturn(method);
        when(r.getRequestURI()).thenReturn(uri);
        when(r.getHeader("Authorization")).thenReturn(token == null ? null : "Bearer " + token);
        return r;
    }

    /** 放行：不抛、返回 true。返回那个 request，好接着断言往它身上放了什么属性 */
    private HttpServletRequest passes(String method, String uri, String token) {
        HttpServletRequest request = req(method, uri, token);
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        return request;
    }

    /** 拦下：抛 BusinessException，把它交回来 —— 是**哪一句**和"拦没拦住"一样重要 */
    private BusinessException blocked(String method, String uri, String token) {
        return expectBusinessException(req(method, uri, token));
    }

    /** 同上，但 Authorization 头原样传（测"格式不对"那类，不能加 Bearer 前缀） */
    private BusinessException blockedWithRawHeader(String rawHeader) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/orders");
        when(request.getHeader("Authorization")).thenReturn(rawHeader);
        return expectBusinessException(request);
    }

    private BusinessException expectBusinessException(HttpServletRequest request) {
        Throwable t = catchThrowable(() -> interceptor.preHandle(request, response, new Object()));
        assertThat(t).isInstanceOf(BusinessException.class);
        return (BusinessException) t;
    }

    // ════════════════ 身份闸门 ════════════════

    @Nested
    @DisplayName("三道身份闸门：未登录 / 已登出 / 无效或过期")
    class Identity {

        @Test
        @DisplayName("没有 Authorization 头 → 401 未登录")
        void missingHeader() {
            BusinessException e = blocked("GET", "/api/orders", null);

            assertThat(e.getCode()).isEqualTo(401);
            assertThat(e.getMessage()).isEqualTo("未登录");
        }

        @Test
        @DisplayName("头不是 Bearer 开头 → 401 未登录（裸传一个真票也进不来）")
        void notBearer() {
            // 票是真的，但这张请求没有按约定声明"我在带票" ——
            // 两件事分开判，否则一个 Basic 头里的字符串也能被当成身份
            assertThat(blockedWithRawHeader(staffToken(MANAGER)).getMessage()).isEqualTo("未登录");
            assertThat(blockedWithRawHeader("Basic abc").getMessage()).isEqualTo("未登录");
            assertThat(blockedWithRawHeader("").getMessage()).isEqualTo("未登录");
        }

        @Test
        @DisplayName("黑名单命中（已登出）→ 401 已失效，读的就是登出写的那把键")
        void blacklisted() {
            String token = staffToken(MANAGER);
            when(valueOps.get("blacklist:token:" + token)).thenReturn("1");

            BusinessException e = blocked("GET", "/api/orders", token);

            assertThat(e.getMessage()).isEqualTo("Token 已失效，请重新登录");
            // 键的拼法必须和 AuthController.logout 写的那一份**逐字**一致。
            // 两份字面量在两个类里，写岔了就是"点了退出但票还能用"——
            // 而两边的单测各自都绿（AuthControllerTest 里有另一半，两半合起来才成一对）
            verify(valueOps).get("blacklist:token:" + token);
        }

        @Test
        @DisplayName("篡改 / 别人的钥匙签的 / 过期的 / 不是 JWT → 401 无效或已过期")
        void invalidTokens() {
            String tampered = staffToken(MANAGER) + "x";     // 尾巴上多一个字符，签名立刻对不上
            String forged = new JwtUtil(OTHER_SECRET, DAY)
                    .generateToken(STAFF_ID, "mgr3", MANAGER, STORE_ID);
            String expired = new JwtUtil(SECRET, -1000L)
                    .generateToken(STAFF_ID, "mgr3", MANAGER, STORE_ID);

            assertThat(blocked("GET", "/api/orders", tampered).getMessage())
                    .isEqualTo("Token 无效或已过期");
            assertThat(blocked("GET", "/api/orders", forged).getMessage())
                    .isEqualTo("Token 无效或已过期");
            assertThat(blocked("GET", "/api/orders", expired).getMessage())
                    .isEqualTo("Token 无效或已过期");
            assertThat(blocked("GET", "/api/orders", "not-a-jwt").getMessage())
                    .isEqualTo("Token 无效或已过期");
        }

        @Test
        @DisplayName("票里的 type 不是 staff/customer（自己造的第三种身份）→ 401")
        void unknownTypeRejected() {
            // type 是白名单，不是"有就行"：早先按非 staff 即顾客处理过，
            // 那种写法会让任何一张自签的票都变成顾客
            String alien = Jwts.builder()
                    .subject("whoever")
                    .claim("type", "admin")
                    .claim("staffId", STAFF_ID)
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + DAY))
                    .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                    .compact();

            BusinessException e = blocked("GET", "/api/orders", alien);

            assertThat(e.getCode()).isEqualTo(401);
            assertThat(e.getMessage()).isEqualTo("Token 无效或已过期");
        }
    }

    // ════════════════ 身份落地 ════════════════

    @Nested
    @DisplayName("身份落进 request 属性（controller 靠这个判断「你是谁」）")
    class Attributes {

        @Test
        @DisplayName("员工：type / staffId / username / role / storeId 五样都放")
        void staffAttributes() {
            HttpServletRequest request = passes("GET", "/api/orders", staffToken(MANAGER));

            verify(request).setAttribute("type", "staff");
            verify(request).setAttribute("staffId", STAFF_ID);
            verify(request).setAttribute("username", "mgr3");
            verify(request).setAttribute("role", MANAGER);
            verify(request).setAttribute("storeId", STORE_ID);
        }

        @Test
        @DisplayName("顾客：只放 type 和 customerId，一个员工属性都不放")
        void customerAttributes() {
            HttpServletRequest request = passes("GET", "/api/orders", customerToken());

            verify(request).setAttribute("type", "customer");
            verify(request).setAttribute("customerId", CUSTOMER_ID);
            // 顾客票里本来就没有这些 claim；真正要防的是"哪天有人把两段合并了"，
            // 所以这里连 setAttribute 都不许发生
            verify(request, never()).setAttribute(eq("staffId"), any());
            verify(request, never()).setAttribute(eq("username"), any());
            verify(request, never()).setAttribute(eq("role"), any());
            verify(request, never()).setAttribute(eq("storeId"), any());
        }
    }

    // ════════════════ 作废水位线 ════════════════

    @Nested
    @DisplayName("作废水位线：停用 / 降级 / 调岗 / 改密码之后旧票当场失效")
    class Revoked {

        @Test
        @DisplayName("水位线说作废了 → 401，文案指向「重新登录」这个能做的动作")
        void revokedTokenRejected() {
            when(tokenRevoker.isRevoked(any(), any())).thenReturn(true);

            BusinessException e = blocked("GET", "/api/orders", staffToken(MANAGER));

            assertThat(e.getCode()).isEqualTo(401);
            assertThat(e.getMessage()).isEqualTo("账号已被停用或权限已变更，请重新登录");
        }

        @Test
        @DisplayName("问水位线时带的是**这张票的 staffId 和 iat**（不是当前时间、不是 null）")
        void asksWithStaffIdAndIssuedAt() {
            String token = staffToken(MANAGER);

            passes("GET", "/api/orders", token);

            // iat 传错（传 null 或传 now）会让人动不动就被踢下线：
            // isRevoked 那边对 null 一律判作废、对 now 则会把所有旧票判成新的。
            // 那边有它自己的单测，接线对不对只能在这里钉
            verify(tokenRevoker).isRevoked(STAFF_ID, jwtUtil.getIssuedAt(token));
        }

        @Test
        @DisplayName("水位线排在角色闸门**之前**：被停用的管理员访问 /api/staff → 401，不是 403")
        void watermarkRunsBeforeRoleGate() {
            when(tokenRevoker.isRevoked(any(), any())).thenReturn(true);

            BusinessException e = blocked("GET", "/api/staff", staffToken(ADMIN));

            // 报 403「请使用管理员账号」会把他指向一个他做不到的动作 ——
            // 他连登录都登不进来。顺序是这里的内容，不是实现细节（Bug 20 的教训）
            assertThat(e.getCode()).isEqualTo(401);
            assertThat(e.getMessage()).contains("请重新登录");
        }

        @Test
        @DisplayName("顾客没有水位线：一次都不问")
        void customersAreNotChecked() {
            passes("GET", "/api/orders", customerToken());

            verifyNoInteractions(tokenRevoker);
        }
    }

    // ════════════════ 方向一：拦店长 ════════════════

    @Nested
    @DisplayName("方向一：员工管理只许管理员（拦店长）")
    class AdminOnly {

        @Test
        @DisplayName("店长访问 /api/staff → 403，**读接口也一样**")
        void managerBlockedFromStaff() {
            String token = staffToken(MANAGER);

            assertThat(blocked("GET", "/api/staff", token).getCode()).isEqualTo(403);
            assertThat(blocked("GET", "/api/staff/5", token).getCode()).isEqualTo(403);
            assertThat(blocked("POST", "/api/staff", token).getCode()).isEqualTo(403);
            assertThat(blocked("PUT", "/api/staff/5", token).getCode()).isEqualTo(403);
            assertThat(blocked("PUT", "/api/staff/5/status", token).getCode()).isEqualTo(403);
        }

        @Test
        @DisplayName("这条 403 的文案要指对路：不是「未登录」，是「用管理员账号」")
        void managerSeesActionableMessage() {
            BusinessException e = blocked("GET", "/api/staff", staffToken(MANAGER));

            assertThat(e.getMessage())
                    .isEqualTo("员工与门店管理只对管理员开放，请使用管理员账号");
        }

        @Test
        @DisplayName("店长写门店 → 403；**读门店放行**（能下单的店本来就该人人看得见）")
        void storeWriteIsAdminOnlyButReadIsNot() {
            String token = staffToken(MANAGER);

            assertThat(blocked("POST", "/api/stores", token).getCode()).isEqualTo(403);
            assertThat(blocked("PUT", "/api/stores/1", token).getCode()).isEqualTo(403);
            assertThat(blocked("PUT", "/api/stores/1/status", token).getCode()).isEqualTo(403);

            // 门店这条按**方法**分，不能按前缀一刀切 —— 这正是 verify-stores.sh B9b
            // 硬钉着"管理员 GET /api/stores 必须 200"的那条规则在单测里的镜像
            passes("GET", "/api/stores", token);
        }

        @Test
        @DisplayName("管理员在这两个前缀上畅通（他就是为它们存在的）")
        void adminPasses() {
            String token = staffToken(ADMIN);

            passes("GET", "/api/staff", token);
            passes("POST", "/api/staff", token);
            passes("PUT", "/api/staff/5/password", token);
            passes("POST", "/api/stores", token);
            passes("GET", "/api/stores", token);
        }

        @Test
        @DisplayName("店长干本职工作不受影响：订单、定价、顾客建档都放行")
        void managerKeepsWorking() {
            String token = staffToken(MANAGER);

            passes("GET", "/api/orders", token);
            passes("PUT", "/api/prices/11", token);
            passes("GET", "/api/prices", token);
            passes("POST", "/api/customers/lookup-or-create", token);
            passes("POST", "/api/coupons", token);   // 发券是店长的本职（2026-09-19）
        }
    }

    // ════════════════ 方向二：拦管理员 ════════════════

    @Nested
    @DisplayName("方向二：管理员不参与日常经营（拦管理员）")
    class AdminStaysOut {

        @Test
        @DisplayName("管理员碰订单 → 403，**连读也拦**")
        void adminBlockedFromOrders() {
            String token = staffToken(ADMIN);

            assertThat(blocked("GET", "/api/orders", token).getMessage())
                    .isEqualTo("管理员不参与订单操作，请使用店长账号");
            assertThat(blocked("POST", "/api/orders", token).getCode()).isEqualTo(403);
        }

        @Test
        @DisplayName("管理员改价 → 403；看价目表放行（价他得看得见，只是不能改）")
        void priceWriteBlockedReadAllowed() {
            String token = staffToken(ADMIN);

            assertThat(blocked("PUT", "/api/prices/11", token).getMessage())
                    .isEqualTo("管理员不能修改价格，请使用店长账号");

            // 判据是 "PUT + /api/prices" 两个条件同时成立：只按下标就会误拦读价目表
            passes("GET", "/api/prices", token);
        }

        @Test
        @DisplayName("管理员碰顾客业务 → 403（/me 也一样：他压根没有「自己」这个档案）")
        void adminBlockedFromCustomers() {
            String token = staffToken(ADMIN);

            assertThat(blocked("POST", "/api/customers/lookup-or-create", token).getCode())
                    .isEqualTo(403);
            assertThat(blocked("GET", "/api/customers/me", token).getMessage())
                    .isEqualTo("管理员不参与顾客相关操作，请使用店长账号");
        }

        @Test
        @DisplayName("管理员发券 → 403；读券列表与抢券的路径不归这条管（2026-09-19 拍板）")
        void couponIssueBlocked() {
            String token = staffToken(ADMIN);

            assertThat(blocked("POST", "/api/coupons", token).getMessage())
                    .isEqualTo("管理员不参与发券，请使用店长账号");

            // 判据是 "POST" 且路径**精确等于** /api/coupons 两个条件同时成立。
            // 下面两条是它的两侧边界，各防一个具体的错法：
            //   · 改成只按方法拦（不看路径）→ POST /{id}/grab 会被截成"不参与发券"，
            //     而那是顾客的动作，管理员该收到的是「请使用顾客账号登录」
            //   · 改成只按前缀拦（不看方法）→ GET 也被拦，读被顺手收掉了；
            //     读本次**刻意没动**，与 /api/prices 同形："看得见，不能写"
            passes("POST", "/api/coupons/12/grab", token);
            passes("GET", "/api/coupons", token);
        }
    }

    // ════════════════ 分工与现状 ════════════════

    @Nested
    @DisplayName("闸门不管顾客：顾客能用哪些接口由各 controller 自己判断")
    class CustomersNotGated {

        @Test
        @DisplayName("顾客票打到 /api/staff 在闸门这层放行 —— 拦他的是 controller 那道")
        void gateLetsCustomersThrough() {
            // 这是**分工**不是漏：checkRoleGate 只管员工之间的事（管理员 vs 店长），
            // 顾客的边界在各 controller（如 StaffController.requireStaff 那句 401）。
            // 钉住它是为了把这个分工写死 —— 哪天有人以为"闸门会拦顾客"，
            // 就可能把 controller 里那道判断删掉
            passes("GET", "/api/staff", customerToken());
            passes("POST", "/api/orders", customerToken());
        }
    }

    @Nested
    @DisplayName("⚠️ 票里没有 role claim 时：管理员专区拦下，日常经营当店长放行（当前行为）")
    class MissingRoleClaim {

        @Test
        @DisplayName("没 role 的票 → /api/staff 403（fail-closed），/api/orders 放行（当店长）")
        void missingRoleIsTreatedAsManager() {
            // generateToken **永远**会签 role，所以这张票只能由我们自己拿同一把钥匙
            // 手工造出来 —— 也就是说它不是"别人能利用的洞"，而是"钥匙泄漏之后
            // 世界会怎样"。这里钉的是当前行为：管理员专区 fail-closed（role 为 null
            // 就不算管理员），而日常经营那一侧 fail-open（判据是 role != ADMIN 即放行）
            String noRole = Jwts.builder()
                    .subject("legacy")
                    .claim("staffId", STAFF_ID)
                    .claim("type", "staff")
                    .claim("storeId", STORE_ID)
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + DAY))
                    .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                    .compact();

            assertThat(blocked("GET", "/api/staff", noRole).getCode()).isEqualTo(403);
            passes("GET", "/api/orders", noRole);
        }
    }
}
