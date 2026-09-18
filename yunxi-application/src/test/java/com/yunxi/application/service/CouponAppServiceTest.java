package com.yunxi.application.service;

import com.yunxi.application.dto.MyCouponView;
import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import com.yunxi.infrastructure.persistence.mapper.CouponGrabMapper;
import com.yunxi.infrastructure.persistence.mapper.CouponMapper;
import com.yunxi.infrastructure.persistence.po.CouponGrabDetailPO;
import com.yunxi.infrastructure.persistence.po.CouponGrabPO;
import com.yunxi.infrastructure.persistence.po.CouponPO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * CouponAppService 单元测试 —— Mockito 假 Mapper + 假 Redis，不连库、不连 Redis。
 *
 * ═══ 为什么这个类最该有测试 ═══
 *
 * 补测之前它**一个测试文件都没有**（2026-09-18 接上 JaCoCo 后报 0%：
 * 295 条指令、30 个分支、9 个方法，一条都没被执行过）。而它是**券**，
 * 券就是钱，这里的每一道判断漏掉都是钱：
 *   · 库存判错 → 超卖（发 100 张卖出 130 张）
 *   · 核销不 CAS → 同一张券打两次折
 *   · resolveDiscount 少一道检查 → 拿别人的券下单
 *   · 过期判据写成看 status → 定时任务滞后的一分钟里，过期券照样能用
 *
 * ═══ 这是仓库里第一个 mock Redis 的测试 ═══
 *
 * 此前没有任何测试碰过 StringRedisTemplate（grep 全仓 0 命中）。
 * 模板是：模板本身 mock，`opsForValue()` / `opsForSet()` 返回的两个
 * Operations 也 mock —— 这三层是 Spring Data Redis 给定的形状，绕不开，
 * 但**全程没有真 Redis**，所以仍然是毫秒级、不需要 docker。
 *
 * ⚠️ 这个类**测不到**两件事，都不是忘了：
 *   1. **顾客 token 能不能发券** —— 原先 CouponController 没有任何身份判断，
 *      顾客票也能 POST /api/coupons（Bug 42 的越权洞）。2026-09-18 修在了
 *      **Controller**（`if (!"staff".equals(http.getAttribute("type")))`），
 *      而这里 mock 掉的是 service，根本没有 controller 这一层 ——
 *      所以它的家在 verify-coupons.sh（顾客票发券 → 401），这里不写假测试
 *   2. **并发下的真实交错**（两个请求同时过了 isMember）—— 那要真 Redis + 真库，
 *      住 verify-race.sh 那一层，这里只钉"撞了唯一键之后怎么办"
 */
class CouponAppServiceTest {

    private static final Long COUPON_ID = 7L;
    private static final Long CUSTOMER_ID = 3L;
    private static final String STOCK_KEY = "coupon:stock:7";
    private static final String GRABBED_KEY = "coupon:grabbed:7";

    private CouponMapper couponMapper;
    private CouponGrabMapper couponGrabMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private SetOperations<String, String> setOps;
    private CouponAppService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        couponMapper = mock(CouponMapper.class);
        couponGrabMapper = mock(CouponGrabMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        setOps = mock(SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        service = new CouponAppService(couponMapper, couponGrabMapper, redisTemplate);
    }

    // ═══════════════════ 造数据的小工具 ═══════════════════

    /** 一张券。status：1=未开始 2=进行中 3=已结束 */
    private CouponPO coupon(Integer status, String discount) {
        CouponPO c = new CouponPO();
        c.setId(COUPON_ID);
        c.setName("5折洗护券");
        c.setDiscount(new BigDecimal(discount));
        c.setTotalStock(10);
        c.setStatus(status);
        return c;
    }

    /** 一条抢券记录。used 传 null 是在模拟"这一列没填"（老数据 / 裸 insert） */
    private CouponGrabPO grab(Integer used) {
        CouponGrabPO g = new CouponGrabPO();
        g.setCouponId(COUPON_ID);
        g.setCustomerId(CUSTOMER_ID);
        g.setUsed(used);
        return g;
    }

    /** 「我的券」那条联表查询的一行 */
    private CouponGrabDetailPO detail(Long grabId, String discount,
                                      LocalDateTime start, LocalDateTime end,
                                      LocalDateTime grabTime) {
        CouponGrabDetailPO d = new CouponGrabDetailPO();
        d.setGrabId(grabId);
        d.setCouponId(COUPON_ID);
        d.setName("5折洗护券");
        d.setDiscount(new BigDecimal(discount));
        d.setStartTime(start);
        d.setEndTime(end);
        d.setGrabTime(grabTime);
        return d;
    }

    /** 抢券的正常路径所需的 Redis 桩：库存键在、这个人还没抢过、扣完剩 remaining */
    private void stubNormalGrab(long remaining) {
        when(redisTemplate.hasKey(STOCK_KEY)).thenReturn(true);
        when(setOps.isMember(GRABBED_KEY, CUSTOMER_ID.toString())).thenReturn(false);
        when(valueOps.decrement(STOCK_KEY)).thenReturn(remaining);
    }

    // ═══════════════════ 发券 ═══════════════════

    @Nested
    @DisplayName("店长发券")
    class Create {

        @Test
        @DisplayName("status 被强制成 1（未开始），传进来是几都不算")
        void forcesStatusNotStarted() {
            CouponPO incoming = coupon(2, "0.50");   // 故意传 2（进行中）

            Result<CouponPO> r = service.createCoupon(incoming);

            assertThat(r.code()).isEqualTo(200);
            assertThat(incoming.getStatus()).isEqualTo(1);
            verify(couponMapper).insert(incoming);
        }

        @Test
        @DisplayName("库存预热到 Redis：coupon:stock:{id} = 总库存")
        void preheatsStock() {
            // 不预热的话，第一次抢券会走"按数据库真相重建"那条兜底路（见 Grab 那组）——
            // 那条路本身是对的，但要多查一次库，而且把正常路径变成了兜底路径
            CouponPO c = coupon(1, "0.50");
            c.setId(88L);
            c.setTotalStock(10);

            service.createCoupon(c);

            verify(valueOps).set("coupon:stock:88", "10");
        }

        // ────────── 折扣率范围（2026-09-18 补的闸）──────────
        //
        // 这一组挡的是**"建得出来但永远用不掉"的券**：越界的券插进库、预热好 Redis、
        // 顾客抢到手，到下单那一步才被 Order.applyCoupon:163-166 抛"优惠券折扣率不合法"——
        // 那时报错的是顾客，填错的是店长，两个人隔着好几步。
        //
        // ⚠️ 别把这一组当成"挡住了 0.01 一折券"：0.01 落在 (0,1] 里，这里**放行**。
        // 那条路归 CouponController 的身份闸（Bug 42），两半别记混

        @Test
        @DisplayName("discount=0 → 400，不写库、不预热 Redis（0 折券永远用不掉）")
        void rejectsZeroDiscount() {
            Result<CouponPO> r = service.createCoupon(coupon(1, "0.00"));

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).contains("折扣率必须大于 0");
            verify(couponMapper, never()).insert(any());
            verify(valueOps, never()).set(anyString(), anyString());
        }

        @Test
        @DisplayName("discount 为负 → 同样 400（判据是 <= 0，不是 == 0）")
        void rejectsNegativeDiscount() {
            assertThat(service.createCoupon(coupon(1, "-0.50")).code()).isEqualTo(400);
            verify(couponMapper, never()).insert(any());
        }

        @Test
        @DisplayName("discount 为 null → 400 不是 NPE（外面看到的是句话，不是 500）")
        void rejectsNullDiscount() {
            // 判据第一项就是 `== null`：漏了它，下一行的 compareTo 会 NPE，
            // 全局异常处理器回的是 500 + 一句英文栈顶，店员不知道该填什么
            CouponPO c = coupon(1, "0.50");
            c.setDiscount(null);

            assertThat(service.createCoupon(c).code()).isEqualTo(400);
            verify(couponMapper, never()).insert(any());
        }

        @Test
        @DisplayName("discount=1.01 → 400；**恰好 1.00 放行**（不打折券是合法的）")
        void rejectsAboveOneButAllowsExactlyOne() {
            // 上界的写法是 `compareTo(ONE) > 0`，1.00 正好落在边界内侧。
            // 这是这一组里最容易改错的一条：写成 `>= 0` 会让"不打折券"建不出来
            assertThat(service.createCoupon(coupon(1, "1.01")).code()).isEqualTo(400);

            Result<CouponPO> ok = service.createCoupon(coupon(1, "1.00"));
            assertThat(ok.code()).isEqualTo(200);
            verify(couponMapper).insert(any());
        }
    }

    // ═══════════════════ 抢券 ═══════════════════

    @Nested
    @DisplayName("顾客抢券")
    class Grab {

        @Test
        @DisplayName("券不存在 → 400 活动未开始或已结束，不写库")
        void couponMissing() {
            when(couponMapper.selectById(COUPON_ID)).thenReturn(null);

            Result<String> r = service.grabCoupon(COUPON_ID, CUSTOMER_ID);

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("活动未开始或已结束");
            verify(couponGrabMapper, never()).insert(any());
        }

        @Test
        @DisplayName("status 不是 2（未开始 1 / 已结束 3）→ 同一句 400")
        void notRunning() {
            // 1 和 3 共用一句话是**故意的**：对抢券的人来说"还没开始"和"已经结束"
            // 是同一种结果，分开说反而把运营节奏透出去了
            for (Integer status : new Integer[]{1, 3}) {
                when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon(status, "0.50"));

                Result<String> r = service.grabCoupon(COUPON_ID, CUSTOMER_ID);

                assertThat(r.code()).isEqualTo(400);
                assertThat(r.message()).isEqualTo("活动未开始或已结束");
            }
            verify(couponGrabMapper, never()).insert(any());
        }

        @Test
        @DisplayName("Redis 里已标记抢过 → 400 你已经抢过了，且**连库存键都不碰**")
        void alreadyGrabbedFastPath() {
            // 这个 Set 存在的全部意义就是"让绝大多数重复请求连库存都不用过问"。
            // 所以这里断言的不只是 400，还有 hasKey/decrement **一次都没调** ——
            // 少了这一条，把快路径写成"先扣库存再判重复"照样能绿，
            // 而那样每来一次重复请求都白扣一张
            when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon(2, "0.50"));
            when(setOps.isMember(GRABBED_KEY, CUSTOMER_ID.toString())).thenReturn(true);

            Result<String> r = service.grabCoupon(COUPON_ID, CUSTOMER_ID);

            assertThat(r.message()).isEqualTo("你已经抢过了");
            verify(couponGrabMapper, never()).insert(any());
            verify(redisTemplate, never()).hasKey(anyString());
            verify(valueOps, never()).decrement(anyString());
        }

        @Test
        @DisplayName("抢到了 → 写库 + 标记 Redis + 回折扣；库存键在时**不查库重建**")
        void success() {
            when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon(2, "0.50"));
            stubNormalGrab(9L);

            Result<String> r = service.grabCoupon(COUPON_ID, CUSTOMER_ID);

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data()).contains("抢到了").contains("0.50");

            var captor = org.mockito.ArgumentCaptor.forClass(CouponGrabPO.class);
            verify(couponGrabMapper).insert(captor.capture());
            assertThat(captor.getValue().getCouponId()).isEqualTo(COUPON_ID);
            assertThat(captor.getValue().getCustomerId()).isEqualTo(CUSTOMER_ID);
            assertThat(captor.getValue().getGrabTime()).isNotNull();

            verify(setOps).add(GRABBED_KEY, CUSTOMER_ID.toString());
            // 库存键在的时候不该去数库 —— 那是兜底路径，不是正常路径
            verify(couponGrabMapper, never()).countByCouponId(any());
        }

        @Test
        @DisplayName("库存键丢了 → 按数据库真相重建：总库存 − 已抢数，写 6 不是写 10")
        void rebuildsStockFromDb() {
            // 这是"Redis 数据丢了"之后唯一的恢复路径。写成 set(key, totalStock)
            // 就会**多发** already-grabbed 那么多张 —— 每丢一次 Redis 超卖一批
            when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon(2, "0.50"));
            when(redisTemplate.hasKey(STOCK_KEY)).thenReturn(false);
            when(couponGrabMapper.countByCouponId(COUPON_ID)).thenReturn(4L);
            when(setOps.isMember(GRABBED_KEY, CUSTOMER_ID.toString())).thenReturn(false);
            when(valueOps.decrement(STOCK_KEY)).thenReturn(5L);

            service.grabCoupon(COUPON_ID, CUSTOMER_ID);

            verify(valueOps).setIfAbsent(STOCK_KEY, "6");   // 10 − 4
        }

        @Test
        @DisplayName("重建值夹在 0：已抢数比总库存还多时不写负数")
        void rebuildClampsAtZero() {
            // 负库存的后果不是"少卖一张"，是"这张券从此废掉"：
            // DECR 之后还是负的，每一次抢都判已抢完，而 DECR 还在继续往下走
            when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon(2, "0.50"));
            when(redisTemplate.hasKey(STOCK_KEY)).thenReturn(false);
            when(couponGrabMapper.countByCouponId(COUPON_ID)).thenReturn(99L);
            when(setOps.isMember(GRABBED_KEY, CUSTOMER_ID.toString())).thenReturn(false);
            when(valueOps.decrement(STOCK_KEY)).thenReturn(-1L);

            service.grabCoupon(COUPON_ID, CUSTOMER_ID);

            verify(valueOps).setIfAbsent(STOCK_KEY, "0");
        }

        @Test
        @DisplayName("DECR 之后是负数 → 400 已抢完，且**不加回去**（负数是有意留着的）")
        void soldOut() {
            when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon(2, "0.50"));
            stubNormalGrab(-1L);

            Result<String> r = service.grabCoupon(COUPON_ID, CUSTOMER_ID);

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("已抢完");
            verify(couponGrabMapper, never()).insert(any());
            // 并发下"减了再加"不是原子操作，加不回来 —— 负数留着表示
            // "超出多少人想抢"，下次发券 SET 覆盖即可
            verify(valueOps, never()).increment(anyString());
        }

        @Test
        @DisplayName("DECR 返回 null（键在两次调用之间被删）→ 也当已抢完，不 NPE")
        void decrementNull() {
            when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon(2, "0.50"));
            stubNormalGrab(0L);
            when(valueOps.decrement(STOCK_KEY)).thenReturn(null);

            Result<String> r = service.grabCoupon(COUPON_ID, CUSTOMER_ID);

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("已抢完");
            verify(couponGrabMapper, never()).insert(any());
        }

        @Test
        @DisplayName("并发下撞唯一键（uk_coupon_customer）→ 还回库存 + 同一句 400，不标记 Redis")
        void duplicateKeyReturnsStock() {
            when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon(2, "0.50"));
            stubNormalGrab(9L);
            org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_coupon_customer"))
                    .when(couponGrabMapper).insert(any());

            Result<String> r = service.grabCoupon(COUPON_ID, CUSTOMER_ID);

            assertThat(r.code()).isEqualTo(400);
            assertThat(r.message()).isEqualTo("你已经抢过了");
            // 这一张没抢到，多扣的那一次必须还回去 —— 否则每撞一次键就白少一张
            verify(valueOps).increment(STOCK_KEY);
            // 也**不能**标记"已抢过"：他没抢到，标记了就再也抢不了了。
            // setOps 到这一步只该被问过一次 isMember
            verify(setOps).isMember(GRABBED_KEY, CUSTOMER_ID.toString());
            verifyNoMoreInteractions(setOps);
        }
    }

    // ═══════════════════ 能不能用这张券 ═══════════════════

    @Nested
    @DisplayName("用券前的校验（resolveDiscount）")
    class ResolveDiscount {

        @Test
        @DisplayName("券不存在 → 抛「优惠券不存在」")
        void couponMissing() {
            when(couponMapper.selectById(COUPON_ID)).thenReturn(null);

            assertThatThrownBy(() -> service.resolveDiscount(COUPON_ID, CUSTOMER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("优惠券不存在");
        }

        @Test
        @DisplayName("没抢到 / 是别人的券 → 同一句「该优惠券不属于这位顾客」")
        void notHisCoupon() {
            // 「没抢到」和「是别人的」在数据上是同一件事（查不到那一行），
            // 只能共用一句话 —— 分成两句等于告诉别人"这张券确实存在，只是不是你的"
            when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon(2, "0.50"));
            when(couponGrabMapper.selectByCouponAndCustomer(COUPON_ID, CUSTOMER_ID))
                    .thenReturn(null);

            assertThatThrownBy(() -> service.resolveDiscount(COUPON_ID, CUSTOMER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("该优惠券不属于这位顾客");
        }

        @Test
        @DisplayName("已核销（used=1）→ 抛「该优惠券已被使用」")
        void used() {
            when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon(2, "0.50"));
            when(couponGrabMapper.selectByCouponAndCustomer(COUPON_ID, CUSTOMER_ID))
                    .thenReturn(grab(1));

            assertThatThrownBy(() -> service.resolveDiscount(COUPON_ID, CUSTOMER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("该优惠券已被使用");
        }

        @Test
        @DisplayName("used=null 当**没用过**放行（判据是 == 1，不是 != 0）")
        void usedNullPasses() {
            // 钉的是判据的形状：`used != null && used == 1`。
            // 写成 `used == null || used != 0` 的话，used 列没值的行会被当成
            // "已使用"，那个人从此用不了任何券 —— 而数据库里最可能出现的坏形状
            // 就是 null（老数据 / 绕过默认值的裸 insert）
            when(couponMapper.selectById(COUPON_ID)).thenReturn(coupon(2, "0.50"));
            when(couponGrabMapper.selectByCouponAndCustomer(COUPON_ID, CUSTOMER_ID))
                    .thenReturn(grab(null));

            assertThat(service.resolveDiscount(COUPON_ID, CUSTOMER_ID))
                    .isEqualByComparingTo("0.50");
        }

        @Test
        @DisplayName("已过期 → 抛「该优惠券已过期」（看 end_time，不看 status）")
        void expired() {
            // 为什么不能看 coupons.status：status 由定时任务每分钟推进，
            // 最多滞后一分钟。判"现在能不能用"不能等 cron —— 到点了就该拒
            CouponPO c = coupon(2, "0.50");
            c.setEndTime(LocalDateTime.now().minusMinutes(1));
            when(couponMapper.selectById(COUPON_ID)).thenReturn(c);
            when(couponGrabMapper.selectByCouponAndCustomer(COUPON_ID, CUSTOMER_ID))
                    .thenReturn(grab(0));

            assertThatThrownBy(() -> service.resolveDiscount(COUPON_ID, CUSTOMER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("该优惠券已过期");
        }

        @Test
        @DisplayName("end_time 为 null → 长期有效，不判过期")
        void noEndTimeNeverExpires() {
            CouponPO c = coupon(2, "0.50");
            c.setEndTime(null);
            when(couponMapper.selectById(COUPON_ID)).thenReturn(c);
            when(couponGrabMapper.selectByCouponAndCustomer(COUPON_ID, CUSTOMER_ID))
                    .thenReturn(grab(0));

            assertThat(service.resolveDiscount(COUPON_ID, CUSTOMER_ID))
                    .isEqualByComparingTo("0.50");
        }

        @Test
        @DisplayName("都合法 → 返回折扣率（订单拿去乘的那个数）")
        void returnsDiscount() {
            CouponPO c = coupon(2, "0.88");
            c.setEndTime(LocalDateTime.now().plusDays(1));
            when(couponMapper.selectById(COUPON_ID)).thenReturn(c);
            when(couponGrabMapper.selectByCouponAndCustomer(COUPON_ID, CUSTOMER_ID))
                    .thenReturn(grab(0));

            assertThat(service.resolveDiscount(COUPON_ID, CUSTOMER_ID))
                    .isEqualByComparingTo("0.88");
        }
    }

    // ═══════════════════ 核销 ═══════════════════

    @Nested
    @DisplayName("核销（consume）")
    class Consume {

        @Test
        @DisplayName("CAS 命中 1 行 → 平安返回，四个参数原样交给 SQL")
        void ok() {
            when(couponGrabMapper.markUsed(COUPON_ID, CUSTOMER_ID, 55L, 9L)).thenReturn(1);

            service.consume(COUPON_ID, CUSTOMER_ID, 55L, 9L);

            verify(couponGrabMapper).markUsed(COUPON_ID, CUSTOMER_ID, 55L, 9L);
        }

        @Test
        @DisplayName("网单顾客自助：staffId 传 null 是合法调用（这一列只有门店单有值）")
        void onlineWithNullStaff() {
            when(couponGrabMapper.markUsed(COUPON_ID, CUSTOMER_ID, 55L, null)).thenReturn(1);

            service.consume(COUPON_ID, CUSTOMER_ID, 55L, null);

            verify(couponGrabMapper).markUsed(COUPON_ID, CUSTOMER_ID, 55L, null);
        }

        @Test
        @DisplayName("CAS 命中 0 行 → **抛 409**，不是返回失败（必须让事务回滚）")
        void conflictRollsBack() {
            // 两件事一起钉：① 它是**抛**不是 return —— 这个方法跑在 createOrder
            // 的事务里，Result.fail 是个普通返回值，抛不出异常也就回滚不了订单，
            // 结果是券没了订单也留下了；② 码是 409 不是 400 ——
            // 这不是"参数不对"，是"并发抢"，重试就有意义
            when(couponGrabMapper.markUsed(COUPON_ID, CUSTOMER_ID, 55L, null)).thenReturn(0);

            assertThatThrownBy(() -> service.consume(COUPON_ID, CUSTOMER_ID, 55L, null))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(409);
                        assertThat(e.getMessage()).contains("已被使用").contains("请刷新后重试");
                    });
        }
    }

    // ═══════════════════ 我的券 ═══════════════════

    @Nested
    @DisplayName("我的券")
    class MyCoupons {

        // ⚠️ 这里**故意没有**「已核销的券不再出现在列表里」那一条 —— 它不归单测管，
        // 谁想补它先读完这段。
        // 判据在 CouponGrabMapper.xml:64 的 `AND g.used = 0` 里，而本类把 mapper
        // 整个 mock 掉了：在这儿写一条，等于**先 stub 一个已经滤好的列表、再断言它没被改动**，
        // 断言的是自己上面那行 when(...)，规则本身一个字都没碰到 —— 典型的"测了只是刷数字"。
        // 这条规则的真机看守是 scripts/verify-coupons.sh 的 H4（三张已消费的券都不在）。
        // 一句话：**规则发生在被 mock 掉的那一层里时，单测证明不了它。**

        @Test
        @DisplayName("字段逐个搬过来（grabId / couponId / 名字 / 折扣 / 起止 / 抢券时间）")
        void mapsFields() {
            LocalDateTime start = LocalDateTime.now().minusDays(1);
            LocalDateTime end = LocalDateTime.now().plusDays(1);
            LocalDateTime grabbed = LocalDateTime.now().minusHours(2);
            when(couponGrabMapper.selectUnusedByCustomer(CUSTOMER_ID))
                    .thenReturn(List.of(detail(1L, "0.50", start, end, grabbed)));

            Result<List<MyCouponView>> r = service.listMyCoupons(CUSTOMER_ID);

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data()).hasSize(1);
            MyCouponView v = r.data().get(0);
            assertThat(v.grabId()).isEqualTo(1L);
            assertThat(v.couponId()).isEqualTo(COUPON_ID);
            assertThat(v.name()).isEqualTo("5折洗护券");
            assertThat(v.discount()).isEqualByComparingTo("0.50");
            assertThat(v.startTime()).isEqualTo(start);
            assertThat(v.endTime()).isEqualTo(end);
            assertThat(v.grabTime()).isEqualTo(grabbed);
            assertThat(v.expired()).isFalse();
        }

        @Test
        @DisplayName("过期的券**仍在列表里**，只打 expired 标记（不是让它消失）")
        void expiredIsMarkedNotHidden() {
            // "我抢的券去哪了"比"这里本来就没有东西"好回答得多 ——
            // 前端拿到 true 就置灰，后端不替用户决定它该不该看见
            when(couponGrabMapper.selectUnusedByCustomer(CUSTOMER_ID))
                    .thenReturn(List.of(detail(1L, "0.50", LocalDateTime.now().minusDays(2),
                            LocalDateTime.now().minusMinutes(1), LocalDateTime.now().minusDays(1))));

            Result<List<MyCouponView>> r = service.listMyCoupons(CUSTOMER_ID);

            assertThat(r.data()).hasSize(1);
            assertThat(r.data().get(0).expired()).isTrue();
        }

        @Test
        @DisplayName("end_time 为 null → expired=false（长期有效，不是「没有时间所以过期」）")
        void nullEndTimeNotExpired() {
            when(couponGrabMapper.selectUnusedByCustomer(CUSTOMER_ID))
                    .thenReturn(List.of(detail(1L, "0.50", LocalDateTime.now().minusDays(1),
                            null, LocalDateTime.now().minusHours(1))));

            assertThat(service.listMyCoupons(CUSTOMER_ID).data().get(0).expired()).isFalse();
        }

        @Test
        @DisplayName("一张券都没有 → 200 + 空列表，不是 404")
        void empty() {
            when(couponGrabMapper.selectUnusedByCustomer(CUSTOMER_ID)).thenReturn(List.of());

            Result<List<MyCouponView>> r = service.listMyCoupons(CUSTOMER_ID);

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data()).isEmpty();
        }
    }

    // ═══════════════════ 列表与定时推进 ═══════════════════

    @Nested
    @DisplayName("列表与定时推进")
    class ListAndRefresh {

        @Test
        @DisplayName("可用券列表原样透传（不在应用层过滤）")
        void listCoupons() {
            when(couponMapper.selectList()).thenReturn(List.of(coupon(2, "0.50")));

            Result<List<CouponPO>> r = service.listCoupons();

            assertThat(r.code()).isEqualTo(200);
            assertThat(r.data()).hasSize(1);
        }

        @Test
        @DisplayName("定时推进 = 开抢 + 结束，返回**两次影响行数之和**")
        void refreshSumsBoth() {
            // 钉的是"两条 UPDATE 都要跑"：只留 startReadyCoupons 的话券永远结束不了，
            // status 停在 2，抢券接口一直放行 —— 而过期的券本该被 resolveDiscount
            // 拦下（那条也在测），于是这个漏点会以"定时任务少跑一条"的形态藏着，
            // 没有任何日志会告诉你
            when(couponMapper.startReadyCoupons()).thenReturn(3);
            when(couponMapper.endExpiredCoupons()).thenReturn(5);

            assertThat(service.refreshCouponStatus()).isEqualTo(8);

            verify(couponMapper).startReadyCoupons();
            verify(couponMapper).endExpiredCoupons();
        }
    }
}
