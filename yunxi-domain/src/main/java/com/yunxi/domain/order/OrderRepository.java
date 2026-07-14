package com.yunxi.domain.order;
import java.util.Optional;
public interface OrderRepository {
    void save(Order order);
    /** 根据 ID 查找订单 */
    Optional<Order> findById(Long id);
    /** 根据订单编号查找 */
    Optional<Order> findByOrderNo(String orderNo);
}
