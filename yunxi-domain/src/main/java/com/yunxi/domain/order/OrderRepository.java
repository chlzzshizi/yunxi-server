package com.yunxi.domain.order;

import com.yunxi.common.enums.OrderStatus;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    void save(Order order);
    /** 根据 ID 查找订单 */
    Optional<Order> findById(Long id);
    /** 根据订单编号查找 */
    Optional<Order> findByOrderNo(String orderNo);

    /**
     * 分页查询订单（按创建时间倒序，新单在前）。
     *
     * @param storeId  门店筛选，null=不筛（顾客端传 null）
     * @param customerId 顾客筛选，null=不筛（员工端传 null）
     * @param status   状态筛选，null=全部
     * @param offset   跳过多少条 = (page-1)*pageSize
     * @param limit    取多少条
     * @return 订单列表（不含明细 —— 列表页用不到，避免 N+1 查询）
     */
    List<Order> findPage(Long storeId, Long customerId, OrderStatus status,
                         int offset, int limit);

    /** 上面查询的总条数（和 findPage 用同一套筛选条件） */
    long count(Long storeId, Long customerId, OrderStatus status);

    /**
     * 保存状态变更 —— 乐观并发控制（CAS：只有状态还是期望值才更新）。
     *
     * 为什么需要：pay/next/finalPay 都是"先读、在内存里改、再写回"。
     * 两个员工同时操作同一单时，后写的会把先写的覆盖掉（丢更新），
     * 甚至让同一单被推进两次。让 UPDATE 带上 status 条件后，
     * 数据库替我们原子地判断"我读到的状态是否还是最新的"。
     *
     * @param order          要保存的订单（内含新状态、新金额、操作人等）
     * @param expectedStatus 操作前从库里读到的状态
     * @return true=更新成功；false=期间状态已被别人改过，本次操作作废
     */
    boolean updateStatusCas(Order order, OrderStatus expectedStatus);
}
