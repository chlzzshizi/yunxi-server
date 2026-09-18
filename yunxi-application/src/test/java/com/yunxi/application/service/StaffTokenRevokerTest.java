package com.yunxi.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StaffTokenRevoker 单元测试 —— Mockito 假 Redis，不连真 Redis。
 *
 * 这个类只有 55 条指令，但它守着"停用一个人 / 改他的密码，他手上那张票立刻失效"。
 * 漏了它的代价很具体：被降级或被停用的店长，拿着停用前签发的 token 继续干到票自然过期
 * （默认 24 小时）。脚本层目前只有 verify-admin.sh 的两处 E2E 兜着（:277 / :358，
 * 都是把 Redis 里的水位线读出来看），而那两处只能证明"真机上那一次动作生效了"，
 * 证明不了下面这些边界。
 *
 * 键前缀字面量在主代码里**只有一处**（grep invalidAfter → 本类 :66）；读的一侧
 * JwtInterceptor:74 是调 isRevoked，不自己拼 key。所以"读写两侧前缀写岔"在当前
 * 形状下不可能发生，也就不需要为它写测试 —— 真要防，得先在别处冒出第二份字面量。
 *
 * 三件事这里做不到，别指望：
 *   1. 真 Redis 会不会提前淘汰这个键 —— 假 Redis 不淘汰任何东西
 *      （所以类注释那条"TTL 必须 ≥ token 有效期"的不变式，单测只能钉住
 *      "TTL 原样传给了 Redis"，钉不住"Redis 真会留它 24 小时"）
 *   2. 并发：停用与"正在路上的请求"交错 —— 跨请求，归脚本
 *   3. iat 的秒精度是 JwtUtil 给的（JwtUtil.java:133），这里只当它是输入
 */
class StaffTokenRevokerTest {

    /** 24 小时，与 application.yml 的 jwt.expiration 同值 */
    private static final long TTL = 86_400_000L;
    private static final String KEY = "auth:staff:invalidAfter:5";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private StaffTokenRevoker revoker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        // 故意**不**在这里 when(redisTemplate.opsForValue())：
        // 桩了它，下面几条"一个 Redis 调用都没有"的 never() 断言就会被那次
        // 打桩本身撞红（when 也会在 mock 上记一笔交互）。按需桩，断言才干净
        revoker = new StaffTokenRevoker(redisTemplate, TTL);
    }

    /** revokeAll 要写 Redis；不桩 opsForValue() 拿到的是 null，会 NPE */
    private void writableRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    /** 水位线已在 Redis 里 —— 读的一侧才会走到解析逻辑 */
    private void watermark(String value) {
        writableRedis();
        when(valueOps.get(KEY)).thenReturn(value);
    }

    // ════════════════════ 写作废（停用/改密码/降级时调） ════════════════════

    @Nested
    @DisplayName("revokeAll：写下水位线")
    class RevokeAll {

        @Test
        @DisplayName("写 auth:staff:invalidAfter:{id}，值是当前毫秒")
        void writesWatermark() {
            writableRedis();

            long before = System.currentTimeMillis();
            revoker.revokeAll(5L);
            long after = System.currentTimeMillis();

            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(eq(KEY), captor.capture(), eq(TTL), eq(TimeUnit.MILLISECONDS));

            // 值必须是"此刻"。写成 0、写成 token 自己的 iat、写成秒 ——
            // 都会让这张水位线要么什么都杀不掉，要么把不该杀的也杀了
            assertThat(Long.parseLong(captor.getValue())).isBetween(before, after);
        }

        @Test
        @DisplayName("TTL 的单位是**毫秒**（写成秒 = 水位线 24 秒后消失、旧票复活）")
        void ttlUnitIsMillis() {
            // 这条不是上一条的重复：单位写错时数字仍然对，只有单位是错的。
            // 86400000 当秒算是 1000 天（挡得住，但键白留），而"把 TTL 除以 1000
            // 再当秒用"这种写法才是真事故 —— 水位线会比它该杀的票先消失。
            // 单位是这一层唯一能被钉住的东西，真 Redis 那边钉不到
            writableRedis();

            revoker.revokeAll(5L);

            verify(valueOps).set(anyString(), anyString(), eq(TTL), eq(TimeUnit.MILLISECONDS));
        }

        @Test
        @DisplayName("staffId 为 null → 静默跳过，一个 Redis 调用都没有")
        void nullStaffIdSkips() {
            // 没有 id 就没有"这个人"。这里若抛 NPE，会盖住真正的原因
            // （比如建号失败后 id 没回填），报出来的是空指针而不是那件事
            revoker.revokeAll(null);

            verify(redisTemplate, never()).opsForValue();
            verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
        }
    }

    // ════════════════════ 读（每个请求都过一遍） ════════════════════

    @Nested
    @DisplayName("isRevoked：这张票作废了没")
    class IsRevoked {

        @Test
        @DisplayName("水位线不存在 → false（绝大多数请求走这一支）")
        void noWatermarkNotRevoked() {
            watermark(null);

            assertThat(revoker.isRevoked(5L, new Date())).isFalse();
        }

        @Test
        @DisplayName("iat 早于水位线 → true（停用/改密码之前的票全杀）")
        void issuedBeforeRevoked() {
            watermark("1000");

            assertThat(revoker.isRevoked(5L, new Date(999))).isTrue();
        }

        @Test
        @DisplayName("iat 恰好等于水位线 → false（判据是严格小于，不是 ≤）")
        void exactlyEqualToWatermarkNotRevoked() {
            // 方向是**故意的**，不是随手写的：iat 是秒精度（JWT 的 NumericDate
            // 就是秒，见 JwtUtil.java:133），水位线是毫秒。同一个自然秒内
            // "先签发、后作废"时，那张新票的 iat 会被截到该秒的起点，
            // 于是 iat < 水位线成立 → 判作废（宁可错杀）。
            // 反过来若判据写成 <=，那个方向不会更安全，只会让"恰好相等"
            // 这一格的含义变得依赖 iat 的精度 —— 而精度是别人给的
            watermark("1000");

            assertThat(revoker.isRevoked(5L, new Date(1000))).isFalse();
        }

        @Test
        @DisplayName("iat 晚于水位线 → false（作废之后重新登录拿到的票活得下来）")
        void issuedAfterNotRevoked() {
            // 这一条要是错了，"停用"就变成了"永久封号"：重新登录也没用，
            // 而重新登录恰恰是那个报错文案让用户去做的事
            watermark("1000");

            assertThat(revoker.isRevoked(5L, new Date(1001))).isFalse();
        }

        @Test
        @DisplayName("staffId 为 null → true，且不查 Redis")
        void nullStaffIdRevoked() {
            assertThat(revoker.isRevoked(null, new Date())).isTrue();

            verify(redisTemplate, never()).opsForValue();
        }

        @Test
        @DisplayName("iat 为 null → true，且不查 Redis（老 token 没有这个 claim）")
        void nullIssuedAtRevoked() {
            // jjwt 原生的 iat 可能是 null（JwtUtil:129 明说了）。拿不到签发时间
            // 就证明不了这张票是新的 —— 判作废，让人重新登录
            assertThat(revoker.isRevoked(5L, null)).isTrue();

            verify(redisTemplate, never()).opsForValue();
        }

        @Test
        @DisplayName("水位线读不懂（被写脏）→ true，**判作废**")
        void garbageWatermarkFailsClosed() {
            // 放行比拦下危险得多：读不懂只可能是异常状态（有人手工 SET、
            // 序列化格式变过）。此时把票当成有效，等于在异常状态下开后门 ——
            // 而这个类的全部意义就是"拦住已经不该进来的人"
            watermark("not-a-number");

            assertThat(revoker.isRevoked(5L, new Date())).isTrue();
        }
    }
}
