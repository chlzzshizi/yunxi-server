package com.yunxi.infrastructure.persistence.mapper;

import com.yunxi.infrastructure.persistence.po.CouponGrabPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CouponGrabMapper {

    /** 插入抢券记录 */
    void insert(CouponGrabPO grabPO);

    /** 统计某张券已被抢的数量（数据库真相，用于重建 Redis 库存） */
    long countByCouponId(Long couponId);
}
