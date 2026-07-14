package com.yunxi.infrastructure.persistence.mapper;

import com.yunxi.infrastructure.persistence.po.OrderPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
@Mapper
public interface OrderMapper {  /** 插入订单 */
void insert(OrderPO orderPO);

    /** 根据 ID 查询 */
    OrderPO selectById(Long id);

    /** 根据订单编号查询 */
    OrderPO selectByOrderNo(String orderNo);

    /** 更新订单状态 */
    void updateStatus(OrderPO orderPO);

}
