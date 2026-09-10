package com.yunxi.infrastructure.persistence.mapper;

import com.yunxi.infrastructure.persistence.po.OrderPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderMapper {

    /** 插入订单 */
    void insert(OrderPO orderPO);

    /** 更新订单 */
    void update(OrderPO orderPO);

    /**
     * 带状态条件的更新（乐观并发控制）
     * @return 受影响行数：1=改成功；0=数据库里状态已不是 expectedStatus
     */
    int updateWithStatusCheck(@Param("po") OrderPO po,
                              @Param("expectedStatus") int expectedStatus);

    /** 根据 ID 查询 */
    OrderPO selectById(Long id);

    /** 根据订单编号查询 */
    OrderPO selectByOrderNo(String orderNo);

    /** 分页查询（筛选条件为 null 就不参与 WHERE） */
    List<OrderPO> selectPage(@Param("storeId") Long storeId,
                             @Param("customerId") Long customerId,
                             @Param("status") Integer status,
                             @Param("offset") int offset,
                             @Param("limit") int limit);

    /** 分页查询的总条数（条件与 selectPage 一致） */
    long countBy(@Param("storeId") Long storeId,
                 @Param("customerId") Long customerId,
                 @Param("status") Integer status);
}
