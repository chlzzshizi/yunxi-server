package com.yunxi.infrastructure.persistence.mapper;

import com.yunxi.infrastructure.persistence.po.CouponPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CouponMapper {

    /** 插入折扣券 */
    void insert(CouponPO couponPO);

    /** 根据 ID 查询 */
    CouponPO selectById(Long id);

    /** 更新状态 */
    void updateStatus(CouponPO couponPO);

    /** 查询券列表（按开抢时间倒序） */
    List<CouponPO> selectList();
}