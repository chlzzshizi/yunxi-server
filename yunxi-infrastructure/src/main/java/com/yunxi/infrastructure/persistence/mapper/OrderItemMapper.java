package com.yunxi.infrastructure.persistence.mapper;
import com.yunxi.infrastructure.persistence.po.OrderItemPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
@Mapper
public interface OrderItemMapper {
    /** 批量插入明细 */
    void insertBatch(List<OrderItemPO> items);

    /** 根据订单 ID 查询所有明细 */
    List<OrderItemPO> selectByOrderId(Long orderId);
}
