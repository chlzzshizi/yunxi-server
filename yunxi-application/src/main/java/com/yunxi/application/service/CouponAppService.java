package com.yunxi.application.service;


import com.yunxi.common.Result;
import com.yunxi.infrastructure.persistence.mapper.CouponGrabMapper;
import com.yunxi.infrastructure.persistence.mapper.CouponMapper;
import com.yunxi.infrastructure.persistence.po.CouponGrabPO;
import com.yunxi.infrastructure.persistence.po.CouponPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
}
