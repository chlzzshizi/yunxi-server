package com.yunxi.interfaces.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtUtil 单元测试 —— 真的签发、真的解析，不 mock jjwt。
 *
 * 这是 interfaces 模块里最像纯函数的那个：两个普通构造参数，进去一串 token、
 * 出来几个 claim。所以它能单测，而 controller 只能靠脚本 —— 界线就是
 * "离了 HTTP 还剩多少东西"。
 *
 * 钉的是"签出去的和读回来的是同一件事"：
 *   1. 员工票带齐 staffId / role / storeId / type=staff，subject 是用户名
 *   2. **顾客票解不出 staffId / role / storeId** —— "顾客不能变成员工"的地基
 *   3. 过期 / 篡改 / 别人的钥匙签的 / 根本不是 JWT —— validateToken 一律 false
 *      （它的实现是"任何异常都算无效"，所以这是同一句话的四个入口）
 *   4. 有效期用的是**构造参数**，不是写死的 24 小时
 *
 * 做不到的：真机上的时钟偏差、Redis 里那个登出黑名单 —— 都不是这个类的事。
 */
class JwtUtilTest {

    /** 测试专用密钥。HS256 硬要求 ≥ 32 字节，真实密钥在 application.yml */
    private static final String SECRET = "test-only-secret-0123456789-0123456789-abcdef";
    private static final String OTHER_SECRET = "another-secret-9876543210-9876543210-abcdef";
    private static final long DAY = 86_400_000L;

    private static final Long STAFF_ID = 5L;
    private static final Long STORE_ID = 2L;
    private static final Long CUSTOMER_ID = 3L;
    private static final String USERNAME = "mgr3";
    private static final String PHONE = "13900000001";

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, DAY);
    }

    /** 用测试密钥自己解一遍 —— 只有要看 claim 本身（iat / exp）时才用得上 */
    private static Claims claimsOf(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static String flipFirst(String s) {
        return (s.charAt(0) == 'e' ? 'f' : 'e') + s.substring(1);
    }

    private static String flipLast(String s) {
        char last = s.charAt(s.length() - 1);
        return s.substring(0, s.length() - 1) + (last == 'e' ? 'f' : 'e');
    }

    // ════════════════ 员工票 ════════════════

    @Nested
    @DisplayName("员工票")
    class StaffToken {

        @Test
        @DisplayName("claims 齐了：staffId / role / storeId / type=staff，subject 是用户名")
        void carriesStaffIdentity() {
            String token = jwtUtil.generateToken(STAFF_ID, USERNAME, 1, STORE_ID);

            assertThat(jwtUtil.validateToken(token)).isTrue();
            assertThat(jwtUtil.getType(token)).isEqualTo("staff");
            assertThat(jwtUtil.getStaffId(token)).isEqualTo(STAFF_ID);
            assertThat(jwtUtil.getUsername(token)).isEqualTo(USERNAME);
            assertThat(jwtUtil.getRole(token)).isEqualTo(1);
            assertThat(jwtUtil.getStoreId(token)).isEqualTo(STORE_ID);
            // 员工票里**没有** customerId：两种身份要是能在同一张票里都解出来，
            // "拿员工票去干顾客的事"就只差一个忘记判断
            assertThat(jwtUtil.getCustomerId(token)).isNull();
        }

        @Test
        @DisplayName("管理员的 storeId 为 null —— 解出来是 null，不是抛")
        void adminHasNoStore() {
            String token = jwtUtil.generateToken(9L, "admin", 0, null);

            assertThat(jwtUtil.getRole(token)).isZero();
            assertThat(jwtUtil.getStoreId(token)).isNull();
        }

        @Test
        @DisplayName("iat 在票里（「停用即失效」那条水位线全靠它），秒精度所以略早于现在")
        void issuedAtIsPresent() {
            long before = System.currentTimeMillis();
            String token = jwtUtil.generateToken(STAFF_ID, USERNAME, 1, STORE_ID);
            long after = System.currentTimeMillis();

            Date iat = jwtUtil.getIssuedAt(token);

            assertThat(iat).isNotNull();
            // JWT 的 NumericDate 是**秒**精度：它会比"现在"早最多 1 秒，
            // 这正是 StaffTokenRevoker 里那个"同一秒内先签发后作废 → 判作废"的来源
            assertThat(iat.getTime()).isBetween(before - 1000, after);
        }

        @Test
        @DisplayName("有效期 = 构造参数（不是写死的 24 小时）：给 1 小时，exp - iat 就是 1 小时")
        void expirationComesFromConstructor() {
            String token = new JwtUtil(SECRET, 3_600_000L)
                    .generateToken(STAFF_ID, USERNAME, 1, STORE_ID);

            Claims claims = claimsOf(token);
            long span = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();

            // 两侧都是秒精度的 NumericDate，所以差值正好是整毫秒
            assertThat(span).isEqualTo(3_600_000L);
        }

        @Test
        @DisplayName("旧形状的票（没签 storeId / role / iat）→ 解出来 null，不抛")
        void legacyTokenWithoutNewClaims() {
            // getStoreId 的注释写着"旧 token 无此 claim 时返回 null"，生成器现在
            // 永远会签 storeId，所以这句只能手工造一张旧票来钉。
            // iat 缺了更要紧：水位线判据拿到 null 会**判作废**（宁可错杀，见 StaffTokenRevoker）
            String legacy = Jwts.builder()
                    .subject(USERNAME)
                    .claim("staffId", STAFF_ID)
                    .claim("type", "staff")
                    .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                    .compact();

            assertThat(jwtUtil.getStoreId(legacy)).isNull();
            assertThat(jwtUtil.getRole(legacy)).isNull();
            assertThat(jwtUtil.getIssuedAt(legacy)).isNull();
            // 但身份还是认得出来的 —— 老 token 不是废纸，只是少了几个 claim
            assertThat(jwtUtil.getStaffId(legacy)).isEqualTo(STAFF_ID);
        }
    }

    // ════════════════ 顾客票 ════════════════

    @Nested
    @DisplayName("顾客票")
    class CustomerToken {

        @Test
        @DisplayName("只有顾客身份：customerId + subject=手机号，**解不出任何员工身份**")
        void carriesCustomerIdentity() {
            String token = jwtUtil.generateCustomerToken(CUSTOMER_ID, PHONE);

            assertThat(jwtUtil.validateToken(token)).isTrue();
            assertThat(jwtUtil.getType(token)).isEqualTo("customer");
            assertThat(jwtUtil.getCustomerId(token)).isEqualTo(CUSTOMER_ID);
            assertThat(jwtUtil.getUsername(token)).isEqualTo(PHONE);   // subject 是手机号

            // 下面三个全是 null。拦截器读到 type=customer 就不会去取 staffId，
            // 而万一哪天顺序写错、真去取了，这里也解不出东西来 —— 两层都不给
            assertThat(jwtUtil.getStaffId(token)).isNull();
            assertThat(jwtUtil.getRole(token)).isNull();
            assertThat(jwtUtil.getStoreId(token)).isNull();
        }
    }

    // ════════════════ 无效票 ════════════════

    @Nested
    @DisplayName("validateToken：四种坏票都是 false")
    class Invalid {

        @Test
        @DisplayName("内容或签名被改过一个字符")
        void tamperedIsInvalid() {
            String token = jwtUtil.generateToken(STAFF_ID, USERNAME, 1, STORE_ID);
            String[] parts = token.split("\\.");
            assertThat(parts).hasSize(3);   // header.payload.signature

            assertThat(jwtUtil.validateToken(parts[0] + "." + flipFirst(parts[1]) + "." + parts[2]))
                    .isFalse();
            assertThat(jwtUtil.validateToken(parts[0] + "." + parts[1] + "." + flipLast(parts[2])))
                    .isFalse();
        }

        @Test
        @DisplayName("别人的钥匙签的票 —— 换钥匙就该立刻不认，这是整套东西的根")
        void signedByAnotherKeyIsInvalid() {
            String forged = new JwtUtil(OTHER_SECRET, DAY)
                    .generateToken(STAFF_ID, USERNAME, 1, STORE_ID);

            assertThat(jwtUtil.validateToken(forged)).isFalse();
        }

        @Test
        @DisplayName("已过期的票（把有效期直接给成负数，不用等 24 小时）")
        void expiredIsInvalid() {
            String expired = new JwtUtil(SECRET, -1000L)
                    .generateToken(STAFF_ID, USERNAME, 1, STORE_ID);

            assertThat(jwtUtil.validateToken(expired)).isFalse();
        }

        @Test
        @DisplayName("垃圾串 / 空串 / null → false，不抛")
        void garbageIsInvalid() {
            // 实现是"任何异常都算无效"，所以这是同一句话的几个入口；
            // 漏掉一个就会从 401 变成 500 —— 拦截器不会 catch 这里的异常
            assertThat(jwtUtil.validateToken(null)).isFalse();
            assertThat(jwtUtil.validateToken("")).isFalse();
            assertThat(jwtUtil.validateToken("not-a-jwt")).isFalse();
            assertThat(jwtUtil.validateToken("a.b.c")).isFalse();
        }
    }
}
