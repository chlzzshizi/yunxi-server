package com.yunxi.application.service;


import com.yunxi.application.dto.MyCouponView;
import com.yunxi.common.BusinessException;
import com.yunxi.common.Result;
import com.yunxi.infrastructure.persistence.mapper.CouponGrabMapper;
import com.yunxi.infrastructure.persistence.mapper.CouponMapper;
import com.yunxi.infrastructure.persistence.po.CouponGrabPO;
import com.yunxi.infrastructure.persistence.po.CouponPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 折扣券应用服务 —— 发券 + 抢券。
 */
@Service
public class CouponAppService {

    private final CouponMapper couponMapper;
    private final CouponGrabMapper couponGrabMapper;
    private final StringRedisTemplate redisTemplate;

    public CouponAppService(CouponMapper couponMapper,
                            CouponGrabMapper couponGrabMapper,
                            StringRedisTemplate redisTemplate) {
        this.couponMapper = couponMapper;
        this.couponGrabMapper = couponGrabMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 可用券列表
     */
    public Result<List<CouponPO>> listCoupons() {
        return Result.ok(couponMapper.selectList());
    }
    /**
     * 推进券状态（定时任务每分钟调用）：
     * 1→2（开抢时间到），2→3（截止时间过）。返回本次推进的总行数
     */
    public int refreshCouponStatus() {
        return couponMapper.startReadyCoupons() + couponMapper.endExpiredCoupons();
    }
    /**
     * 店长发券
     */
    public Result<CouponPO> createCoupon(CouponPO coupon) {
        // 折扣率范围守在**建券**这一刻：0 < discount <= 1（0.50 = 5 折、1 = 不打折）。
        // 越界的券建得出来却永远用不掉 —— Order.applyCoupon:150-153 到**用券时**才抛
        // "优惠券折扣率不合法"，那时券已经躺在库里、顾客也抢到手了，报错的人
        // （顾客）跟填错的人（店长）不是同一个。建的时候拦下来，填错的人当场看到。
        //
        // ⚠️ 它挡住的是"建了一张永远用不掉的券"，**不是**"一折券" —— 0.01 落在 (0,1] 里，
        // 那条路只能靠 Controller 的身份闸（2026-09-18 Bug 42 的两半，别记混）
        if (coupon.getDiscount() == null
                || coupon.getDiscount().compareTo(BigDecimal.ZERO) <= 0
                || coupon.getDiscount().compareTo(BigDecimal.ONE) > 0) {
            return Result.fail(400, "折扣率必须大于 0 且不超过 1（0.50 = 5 折）");
        }
        coupon.setStatus(1);  // 未开始
        couponMapper.insert(coupon);
        // 库存预热到 Redis
        String key = "coupon:stock:" + coupon.getId();
        redisTemplate.opsForValue().set(key, String.valueOf(coupon.getTotalStock()));
        return Result.ok(coupon);
    }

    /**
     * 顾客抢券（核心）
     */
    public Result<String> grabCoupon(Long couponId, Long customerId) {
        // 1. 查活动信息
        CouponPO coupon = couponMapper.selectById(couponId);
        if (coupon == null || coupon.getStatus() != 2) {
            return Result.fail(400, "活动未开始或已结束");
        }
        // 2. 检查是否重复抢（Redis Set）——快速路径，最终以数据库唯一键为准
        String grabbedKey = "coupon:grabbed:" + couponId;
        Boolean alreadyGrabbed = redisTemplate.opsForSet().isMember(grabbedKey, customerId.toString());
        if (Boolean.TRUE.equals(alreadyGrabbed)) {
            return Result.fail(400, "你已经抢过了");
        }
        // 3. 原子扣库存（DECR 单条命令，天然无锁，不会超卖）
        String stockKey = "coupon:stock:" + couponId;
        // 3.1 兜底：库存键不存在（Redis 数据丢失/删库等）→ 按数据库真相重建
        //     setIfAbsent 单条命令原子：并发同时重建时只有第一个生效，不会覆盖已扣减的值
        if (Boolean.FALSE.equals(redisTemplate.hasKey(stockKey))) {
            long grabbed = couponGrabMapper.countByCouponId(couponId);
            long remainingStock = Math.max(coupon.getTotalStock() - grabbed, 0L);
            redisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(remainingStock));
        }
        Long remaining = redisTemplate.opsForValue().decrement(stockKey);

        if (remaining == null || remaining < 0) {
            // 已抢完。不加回：并发下"减了再加"不是原子操作，加不回来；
            // 库存键保留负数表示"超出多少人想抢"，下次发券 SET 覆盖即可
            return Result.fail(400, "已抢完");
        }
        // 4. 抢到了 → 先写库（DB 唯一键 uk_coupon_customer 兜底防并发重复抢），成功后再标记 Redis
        CouponGrabPO grab = new CouponGrabPO();
        grab.setCouponId(couponId);
        grab.setCustomerId(customerId);
        grab.setGrabTime(LocalDateTime.now());
        try {
            couponGrabMapper.insert(grab);
        } catch (DuplicateKeyException e) {
            // 并发下同一用户两个请求同时过了 isMember 检查，数据库唯一键拦下后一个
            redisTemplate.opsForValue().increment(stockKey);   // 还回多扣的库存
            return Result.fail(400, "你已经抢过了");
        }
        redisTemplate.opsForSet().add(grabbedKey, customerId.toString());
        return Result.ok("抢到了！折扣：" + coupon.getDiscount());
    }

    // ──────────────── 消费（被 OrderAppService 调用）────────────────
    //
    // 下面两个方法**抛异常**而不是 return Result.fail —— 和上面几个方法风格不同，
    // 是有意的：它们跑在 createOrder 的**事务里**，失败必须让事务回滚。
    // Result.fail 是个普通返回值，抛不出异常也就回滚不了，订单会留在库里。
    // （同理，整个「券-订单抵扣」都走 BusinessException，见设计文档 §5.8）

    /**
     * 校验这张券能不能用在这张单上，能用就返回折扣率。
     *
     * 这一串检查全是**友好提示**，不是权威判定 —— 真正的权威是
     * {@link #consume} 里那条 CAS。两次检查之间券可能被别人用掉（TOCTOU），
     * 那时 CAS 会挡下并回 409。这里做一遍只是为了让绝大多数失败
     * 得到一句"指对方向"的话，而不是笼统的"被人用过了"。
     *
     * @param couponId   券 id（不是抢券记录 id）
     * @param customerId **这张单的顾客**，不是操作人 —— 券必须属于这张单的顾客
     */
    public BigDecimal resolveDiscount(Long couponId, Long customerId) {
        CouponPO coupon = couponMapper.selectById(couponId);
        if (coupon == null) {
            throw new BusinessException("优惠券不存在");
        }
        // 「没抢到」和「是别人的券」查的是同一件事：有没有这一行。
        // 对用户来说结果也一样 —— 反正不是他的，所以共用一句话。
        // （门店单的 customerId 是员工填的，所以这句话也拦不住冒用，
        //   它买到的是**让错误指对方向**：填错人时立刻说"券不是他的"，
        //   而不是走到 CAS 报一句 409「已被使用」，把人引去查券是不是重复了）
        CouponGrabPO grab = couponGrabMapper.selectByCouponAndCustomer(couponId, customerId);
        if (grab == null) {
            throw new BusinessException("该优惠券不属于这位顾客");
        }
        if (grab.getUsed() != null && grab.getUsed() == 1) {
            throw new BusinessException("该优惠券已被使用");
        }
        // 为什么不用 coupons.status 判断过期：status 由定时任务每分钟推进（§11.10），
        // 最多滞后一分钟。判"现在能不能用"不能等 cron —— 到点了就该拒。
        if (coupon.getEndTime() != null && coupon.getEndTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException("该优惠券已过期");
        }
        return coupon.getDiscount();
    }

    /**
     * 核销：CAS 置 used=1，并写下使用记录（时间/订单/经手员工）。
     *
     * **必须在订单落库成功之后调用**，两个独立的理由：
     *   ① 订单号冲突的重试循环内部会吞掉异常，先核销会在三次撞号后白白烧掉顾客的券
     *   ② `used_order_id` 要等订单拿到自增 id 才写得出
     *
     * 吃 CAS 的前提是调用方**开着事务**（createOrder 上的 @Transactional）：
     * 0 行 → 409 → 事务回滚 → 订单不落库，券和订单不会只成一半。
     *
     * @param staffId 经手员工。网单是顾客自助，传 null —— 这一列只有门店单才有值，
     *                它正是"这券是谁烧的"那个问题的答案
     */
    public void consume(Long couponId, Long customerId, Long orderId, Long staffId) {
        int rows = couponGrabMapper.markUsed(couponId, customerId, orderId, staffId);
        if (rows == 0) {
            throw new BusinessException(409, "该优惠券已被使用，请刷新后重试");
        }
    }

    /**
     * 我的券（未使用）。过期的也返回，只是打上 expired 标记 —— 前端置灰。
     *
     * 用应用时间判过期，和 resolveDiscount 保持一致（两处若用不同的钟，
     * 会出现"列表里看着没过期、下单却说已过期"的错位）。
     */
    public Result<List<MyCouponView>> listMyCoupons(Long customerId) {
        LocalDateTime now = LocalDateTime.now();
        List<MyCouponView> list = couponGrabMapper.selectUnusedByCustomer(customerId)
                .stream()
                .map(po -> new MyCouponView(
                        po.getGrabId(),
                        po.getCouponId(),
                        po.getName(),
                        po.getDiscount(),
                        po.getStartTime(),
                        po.getEndTime(),
                        po.getGrabTime(),
                        po.getEndTime() != null && po.getEndTime().isBefore(now)))
                .toList();
        return Result.ok(list);
    }
}
