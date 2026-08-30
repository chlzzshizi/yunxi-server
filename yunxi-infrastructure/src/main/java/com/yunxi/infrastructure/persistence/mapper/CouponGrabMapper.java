package com.yunxi.infrastructure.persistence.mapper;

import com.yunxi.infrastructure.persistence.po.CouponGrabPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CouponGrabMapper {

    /** 插入抢券记录 */
    void insert(CouponGrabPO grabPO);
}
