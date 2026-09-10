package com.yunxi.infrastructure.persistence.repository;
import com.yunxi.common.enums.OrderSource;
import com.yunxi.common.enums.OrderStatus;
import com.yunxi.common.enums.PayMethod;
import com.yunxi.domain.order.Order;
import com.yunxi.domain.order.OrderItem;
import com.yunxi.domain.order.OrderRepository;
import com.yunxi.infrastructure.persistence.mapper.OrderItemMapper;
import com.yunxi.infrastructure.persistence.mapper.OrderMapper;
import com.yunxi.infrastructure.persistence.po.OrderItemPO;
import com.yunxi.infrastructure.persistence.po.OrderPO;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 订单仓储实现 —— 用 MyBatis/MySQL 实现 domain 层定义的 OrderRepository。
 */
@Repository
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    /** 构造注入（替代 @Autowired） */
    public OrderRepositoryImpl(OrderMapper orderMapper, OrderItemMapper orderItemMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
    }

    @Override
    public void save(Order order) {
        // 1. Order → OrderPO
        OrderPO po = toOrderPO(order);
        // 2. 判断是否新建
        boolean isNew = order.getId() == null;
        if (isNew) {
            orderMapper.insert(po);
            order.setId(po.getId());
        } else {
            orderMapper.update(po);
        }
        // 3. 只在新建时插入明细，更新时不重复插
        if (isNew && !order.getItems().isEmpty()) {
            List<OrderItemPO> itemPOs = toOrderItemPOs(order);
            orderItemMapper.insertBatch(itemPOs);
        }
    }

    @Override
    public boolean updateStatusCas(Order order, OrderStatus expectedStatus) {
        if (order.getId() == null) {
            throw new IllegalStateException("订单尚未落库，不能做状态条件更新");
        }
        // 受影响行数 > 0 才算成功：0 行说明 WHERE status = 期望值 没匹配上
        return orderMapper.updateWithStatusCheck(
                toOrderPO(order), expectedStatus.getCode()) > 0;
    }

    @Override
    public Optional<Order> findById(Long id) {
        OrderPO po = orderMapper.selectById(id);
        if (po == null) {
            return Optional.empty();   // 没找到
        }
        List<OrderItemPO> itemPOs = orderItemMapper.selectByOrderId(id);
        return Optional.of(toOrder(po, itemPOs));
    }

    @Override
    public Optional<Order> findByOrderNo(String orderNo) {
        OrderPO po = orderMapper.selectByOrderNo(orderNo);
        if (po == null) {
            return Optional.empty();
        }
        List<OrderItemPO> itemPOs = orderItemMapper.selectByOrderId(po.getId());
        return Optional.of(toOrder(po, itemPOs));
    }

    @Override
    public List<Order> findPage(Long storeId, Long customerId, OrderStatus status,
                                int offset, int limit) {
        List<OrderPO> pos = orderMapper.selectPage(storeId, customerId,
                status == null ? null : status.getCode(), offset, limit);
        // 刻意不查明细：列表页不展示衣物清单，
        // 查了就是 1 次主查询 + N 次明细查询（N+1 问题）
        return pos.stream()
                .map(po -> toOrder(po, List.of()))
                .toList();
    }

    @Override
    public long count(Long storeId, Long customerId, OrderStatus status) {
        return orderMapper.countBy(storeId, customerId,
                status == null ? null : status.getCode());
    }

    // ═══════════════════ 内部转换方法 ═══════════════════

    /** Order(domain) → OrderPO(数据库) */
    private OrderPO toOrderPO(Order order) {
        OrderPO po = new OrderPO();
        po.setId(order.getId());
        po.setOrderNo(order.getOrderNo());
        po.setStoreId(order.getStoreId());
        po.setCustomerId(order.getCustomerId());
        po.setStaffId(order.getStaffId());
        po.setSource(order.getSource().getCode());        // 枚举 → 数字
        po.setStatus(order.getStatus().getCode());         // 枚举 → 数字
        po.setTotalAmount(order.getTotalAmount());
        po.setPaidAmount(order.getPaidAmount());
        po.setPayMethod(order.getPayMethod() != null
                ? order.getPayMethod().getCode() : null);  // 枚举 → 字符串
        po.setFinalPayMethod(order.getFinalPayMethod() != null
                ? order.getFinalPayMethod().getCode() : null);
        po.setAppointmentTime(order.getAppointmentTime());
        po.setDeliveryAddress(order.getDeliveryAddress());
        po.setExpressNo(order.getExpressNo());
        po.setRemark(order.getRemark());
        po.setFinishTime(order.getFinishTime());
        po.setCreateTime(order.getCreateTime());
        return po;
    }

    /** 给明细也做同样的转换 */
    private List<OrderItemPO> toOrderItemPOs(Order order) {
        return order.getItems().stream().map(item -> {
            OrderItemPO po = new OrderItemPO();
            po.setOrderId(order.getId());
            po.setCategoryId(item.getCategoryId());
            po.setWashTypeId(item.getWashTypeId());
            po.setQuantity(item.getQuantity());
            po.setUnitPrice(item.getUnitPrice());
            po.setPhotos(item.getPhotos());
            return po;
        }).toList();
    }

    /** OrderPO → Order(domain) */
    private Order toOrder(OrderPO po, List<OrderItemPO> itemPOs) {
        Order order = new Order();
        order.setId(po.getId());
        order.setOrderNo(po.getOrderNo());
        order.setStoreId(po.getStoreId());
        order.setCustomerId(po.getCustomerId());
        order.setStaffId(po.getStaffId());
        order.setSource(OrderSource.fromCode(po.getSource()));    // 数字 → 枚举
        order.setStatus(OrderStatus.fromCode(po.getStatus()));     // 数字 → 枚举
        order.setTotalAmount(po.getTotalAmount());
        order.setPaidAmount(po.getPaidAmount());
        order.setPayMethod(po.getPayMethod() != null
                ? PayMethod.fromCode(po.getPayMethod()) : null);   // 字符串 → 枚举
        order.setFinalPayMethod(po.getFinalPayMethod() != null
                ? PayMethod.fromCode(po.getFinalPayMethod()) : null);
        order.setAppointmentTime(po.getAppointmentTime());
        order.setDeliveryAddress(po.getDeliveryAddress());
        order.setExpressNo(po.getExpressNo());
        order.setRemark(po.getRemark());
        order.setFinishTime(po.getFinishTime());
        order.setCreateTime(po.getCreateTime());

        // 转换明细
        List<OrderItem> items = itemPOs.stream().map(it -> {
            OrderItem item = new OrderItem();
            item.setId(it.getId());
            item.setOrderId(it.getOrderId());
            item.setCategoryId(it.getCategoryId());
            item.setWashTypeId(it.getWashTypeId());
            item.setQuantity(it.getQuantity());
            item.setUnitPrice(it.getUnitPrice());
            item.setPhotos(it.getPhotos());
            return item;
        }).toList();
        order.setItems(items);

        return order;
    }
}
