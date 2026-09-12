package com.yunxi.infrastructure.persistence.mapper;

import com.yunxi.infrastructure.persistence.po.CouponGrabDetailPO;
import com.yunxi.infrastructure.persistence.po.CouponGrabPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CouponGrabMapper {

    /** 插入抢券记录 */
    void insert(CouponGrabPO grabPO);

    /** 统计某张券已被抢的数量（数据库真相，用于重建 Redis 库存） */
    long countByCouponId(Long couponId);

    /**
     * 查某人手里的某张券 —— 查不到就说明"这张券不是他的"。
     *
     * 「没抢到」和「是别人的券」在数据上是同一件事（没有这一行），
     * 所以调用方也只能用同一句话回它们 —— 反正对用户来说结果一样：用不了。
     */
    CouponGrabPO selectByCouponAndCustomer(@Param("couponId") Long couponId,
                                           @Param("customerId") Long customerId);

    /**
     * 核销券（CAS）+ 写下使用记录。返回受影响行数，0 = 这张券已经被用掉了。
     *
     * 为什么条件里必须有 used = 0：两个人同时拿同一张券下单，两次读到的都是
     * "未使用"，各自往下走 —— 带条件之后第二个人的 UPDATE 命中 0 行，被挡下。
     * 这与订单状态机的 CAS 是同一套思路（§5.7）。
     */
    int markUsed(@Param("couponId") Long couponId,
                 @Param("customerId") Long customerId,
                 @Param("orderId") Long orderId,
                 @Param("staffId") Long staffId);

    /** 某人未使用的券（联表带出券名/折扣/起止时间），供「我的券」列表用 */
    List<CouponGrabDetailPO> selectUnusedByCustomer(@Param("customerId") Long customerId);
}
