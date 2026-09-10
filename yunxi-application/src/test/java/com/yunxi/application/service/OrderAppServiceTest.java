package com.yunxi.application.service;

import com.yunxi.application.dto.OrderExtras;
import com.yunxi.common.BusinessException;
import com.yunxi.common.PageResult;
import com.yunxi.common.enums.OrderSource;
import com.yunxi.common.enums.OrderStatus;
import com.yunxi.common.enums.PayMethod;
import com.yunxi.common.Result;
import com.yunxi.domain.order.Order;
import com.yunxi.domain.order.OrderItem;
import com.yunxi.domain.order.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderAppService 单元测试 —— 用 Mockito 假造仓储，只测应用层规则。
 *
 * 覆盖三类规则：
 *   1. 谁能操作（顾客 token 不能推进状态 / 支付）
 *   2. 谁能查看（顾客只看自己的；员工只看本店的）
 *   3. 留痕（每次状态操作都记录操作员工）
 *
 * 不依赖 Spring 容器、不连数据库 —— 所以叫"单元"测试。
 */
class OrderAppServiceTest {

    private OrderRepository orderRepository;
    private OrderAppService orderAppService;

    private static final BigDecimal TOTAL = new BigDecimal("30.00");
    private static final Long STORE_A = 1L;
    private static final Long STORE_B = 2L;

    /** 一条明细，单价 15.00 × 2 = 30.00 */
    private static final List<OrderItem> ITEMS = List.of(
            new OrderItem(1L, 1L, 2, new BigDecimal("15.00"), null));

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderAppService = new OrderAppService(orderRepository);
        // CAS 更新默认"成功"（Mockito 对 boolean 默认返回 false，
        // 不显式打桩的话每个状态操作测试都会撞上 409）
        when(orderRepository.updateStatusCas(any(), any())).thenReturn(true);
    }

    /** 造一个已落库的门店单（含 ID） */
    private Order persistedOrder(Long id, Long storeId, Long customerId) {
        List<OrderItem> items = List.of(
                new OrderItem(1L, 1L, 2, new BigDecimal("15.00"), null));
        Order order = new Order("YX-TEST-" + id, storeId, customerId,
                OrderSource.STORE, items);
        order.setId(id);
        return order;
    }

    private void stubFind(Order order) {
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    }

    // ════════════════ 创建订单 ════════════════

    @Nested
    @DisplayName("创建订单")
    class CreateOrder {

        private final List<OrderItem> items = List.of(
                new OrderItem(1L, 1L, 2, new BigDecimal("15.00"), null));

        @Test
        @DisplayName("门店单必须有操作员工（没有 → 401，且不落库）")
        void storeOrderRequiresStaff() {
            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, items, null, OrderExtras.EMPTY))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("员工账号");

            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("门店单：操作员工与备注一起落库")
        void storeOrderRecordsOperatorAndExtras() {
            Result<Order> result = orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, items, 9L,
                    new OrderExtras(LocalDateTime.now(), "3 号楼 502", "袖口有污渍"));

            assertThat(result.code()).isEqualTo(200);
            Order saved = result.data();
            assertThat(saved.getStaffId()).isEqualTo(9L);
            assertThat(saved.getDeliveryAddress()).isEqualTo("3 号楼 502");
            assertThat(saved.getRemark()).isEqualTo("袖口有污渍");
            assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING_PAY);
            verify(orderRepository).save(saved);
        }

        @Test
        @DisplayName("网单：没有操作员工也合法（staffId 保持 null）")
        void onlineOrderNeedsNoStaff() {
            Result<Order> result = orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.ONLINE, items, null, OrderExtras.EMPTY);

            assertThat(result.code()).isEqualTo(200);
            assertThat(result.data().getStaffId()).isNull();
            assertThat(result.data().getSource()).isEqualTo(OrderSource.ONLINE);
        }

        @Test
        @DisplayName("空明细 → 400，不落库")
        void emptyItemsRejected() {
            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, List.of(), 9L, OrderExtras.EMPTY))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("至少需要一条明细");

            verify(orderRepository, never()).save(any());
        }
    }

    // ════════════════ 查询归属 ════════════════

    @Nested
    @DisplayName("查询归属校验")
    class GetOrder {

        @Test
        @DisplayName("订单不存在 → 404")
        void notFound() {
            when(orderRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderAppService.getOrder(404L, "staff", 9L, STORE_A))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(404);
        }

        @Test
        @DisplayName("顾客查自己的订单 → 放行")
        void customerOwnOrder() {
            Order order = persistedOrder(1L, STORE_A, 100L);
            stubFind(order);

            Result<Order> result = orderAppService.getOrder(1L, "customer", 100L, null);
            assertThat(result.data().getCustomerId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("顾客查别人的订单 → 403")
        void customerCannotSeeOthers() {
            Order order = persistedOrder(1L, STORE_A, 100L);
            stubFind(order);

            assertThatThrownBy(() -> orderAppService.getOrder(1L, "customer", 999L, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(403);
        }

        @Test
        @DisplayName("员工查本店订单 → 放行")
        void staffOwnStore() {
            Order order = persistedOrder(1L, STORE_A, 100L);
            stubFind(order);

            Result<Order> result = orderAppService.getOrder(1L, "staff", 9L, STORE_A);
            assertThat(result.code()).isEqualTo(200);
        }

        @Test
        @DisplayName("员工查其他门店订单 → 403（跨店隔离）")
        void staffCannotSeeOtherStore() {
            Order order = persistedOrder(1L, STORE_A, 100L);
            stubFind(order);

            assertThatThrownBy(() -> orderAppService.getOrder(1L, "staff", 9L, STORE_B))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(403);
        }

        @Test
        @DisplayName("旧 token 无 storeId → 401 提示重新登录（不是 403）")
        void staffWithoutStoreId() {
            Order order = persistedOrder(1L, STORE_A, 100L);
            stubFind(order);

            assertThatThrownBy(() -> orderAppService.getOrder(1L, "staff", 9L, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(401);
        }
    }

    // ════════════════ 状态操作权限 ════════════════

    @Nested
    @DisplayName("状态操作仅限员工")
    class StaffOnly {

        @Test
        @DisplayName("顾客 token 推进状态 → 401，订单不变")
        void customerCannotAdvance() {
            Order order = persistedOrder(1L, STORE_A, 100L);
            stubFind(order);

            assertThatThrownBy(() -> orderAppService.updateStatus(1L, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("员工账号");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAY);
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("顾客 token 支付 → 401")
        void customerCannotPay() {
            Order order = persistedOrder(1L, STORE_A, 100L);
            stubFind(order);

            assertThatThrownBy(() -> orderAppService.pay(1L, PayMethod.CASH, TOTAL, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(401);
        }

        @Test
        @DisplayName("员工支付 → 状态推进且记录操作人")
        void staffPayRecordsOperator() {
            Order order = persistedOrder(1L, STORE_A, 100L);
            stubFind(order);

            Result<Void> result = orderAppService.pay(1L, PayMethod.CASH, TOTAL, 9L);

            assertThat(result.code()).isEqualTo(200);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(order.getStaffId()).isEqualTo(9L);
            // 落库走的是 CAS：期望状态 = 操作前读到的"待支付"
            verify(orderRepository).updateStatusCas(order, OrderStatus.PENDING_PAY);
        }

        @Test
        @DisplayName("员工推进状态 → 记录操作人并落库")
        void staffAdvanceRecordsOperator() {
            Order order = persistedOrder(1L, STORE_A, 100L);
            order.setStatus(OrderStatus.PAID);          // 跳到已支付，直接测推进
            stubFind(order);

            orderAppService.updateStatus(1L, 7L);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.WASHING);
            assertThat(order.getStaffId()).isEqualTo(7L);
            verify(orderRepository).updateStatusCas(order, OrderStatus.PAID);
        }

        @Test
        @DisplayName("订单不存在时支付 → 404")
        void payMissingOrder() {
            when(orderRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orderAppService.pay(1L, PayMethod.CASH, TOTAL, 9L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(404);
        }
    }

    // ════════════════ 订单列表 ════════════════

    @Nested
    @DisplayName("订单列表分页与隔离")
    class ListOrders {

        @Test
        @DisplayName("员工查列表 → 只按本店筛选（不传 customerId）")
        void staffSeesOwnStoreOnly() {
            when(orderRepository.count(STORE_A, null, null)).thenReturn(2L);
            when(orderRepository.findPage(STORE_A, null, null, 0, 20))
                    .thenReturn(List.of(persistedOrder(2L, STORE_A, 100L),
                            persistedOrder(1L, STORE_A, 100L)));

            Result<PageResult<Order>> result = orderAppService.listOrders(
                    "staff", 9L, STORE_A, null, 1, 20);

            assertThat(result.data().total()).isEqualTo(2L);
            assertThat(result.data().list()).hasSize(2);
            assertThat(result.data().totalPages()).isEqualTo(1);
        }

        @Test
        @DisplayName("顾客查列表 → 只按自己的 customerId 筛选（即使传了别人的店也没用）")
        void customerSeesOwnOrdersOnly() {
            when(orderRepository.count(null, 100L, null)).thenReturn(1L);
            when(orderRepository.findPage(null, 100L, null, 0, 20))
                    .thenReturn(List.of(persistedOrder(1L, STORE_A, 100L)));

            Result<PageResult<Order>> result = orderAppService.listOrders(
                    "customer", 100L, null, null, 1, 20);

            assertThat(result.data().list()).hasSize(1);
            // 关键：查的是 customerId=100，没夹带门店条件
            verify(orderRepository).count(null, 100L, null);
        }

        @Test
        @DisplayName("旧 token 无 storeId 的员工查列表 → 401")
        void staffWithoutStoreIdRejected() {
            assertThatThrownBy(() -> orderAppService.listOrders(
                    "staff", 9L, null, null, 1, 20))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(401);
        }

        @Test
        @DisplayName("页码越界归一化：page=0 → 1、pageSize=9999 → 100")
        void pageParamsClamped() {
            when(orderRepository.count(STORE_A, null, null)).thenReturn(0L);

            Result<PageResult<Order>> result = orderAppService.listOrders(
                    "staff", 9L, STORE_A, null, 0, 9999);

            assertThat(result.data().page()).isEqualTo(1);
            assertThat(result.data().pageSize()).isEqualTo(100);
            // 没数据时不该再查一次列表
            verify(orderRepository, never()).findPage(any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("第二页的 offset = (page-1)*pageSize")
        void secondPageOffset() {
            when(orderRepository.count(STORE_A, null, OrderStatus.WASHING)).thenReturn(30L);
            when(orderRepository.findPage(STORE_A, null, OrderStatus.WASHING, 20, 20))
                    .thenReturn(List.of());

            Result<PageResult<Order>> result = orderAppService.listOrders(
                    "staff", 9L, STORE_A, OrderStatus.WASHING, 2, 20);

            assertThat(result.data().total()).isEqualTo(30L);
            assertThat(result.data().totalPages()).isEqualTo(2);
            verify(orderRepository).findPage(STORE_A, null, OrderStatus.WASHING, 20, 20);
        }
    }

    // ════════════════ 订单号唯一 ════════════════

    @Nested
    @DisplayName("订单号防冲突")
    class OrderNoCollision {

        @Test
        @DisplayName("订单号格式：YX + 14 位时间戳 + 4 位随机数")
        void orderNoFormat() {
            assertThat(OrderAppService.generateOrderNo())
                    .matches("YX\\d{18}")
                    .hasSize(20);
        }

        @Test
        @DisplayName("撞上唯一索引 → 换个号重试，最终成功")
        void retriesOnceThenSucceeds() {
            // 第一次 save 抛重复键（模拟撞号），第二次放行
            doThrow(new DuplicateKeyException("uk_order_no"))
                    .doNothing()
                    .when(orderRepository).save(any());

            Result<Order> result = orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, ITEMS, 9L, OrderExtras.EMPTY);

            assertThat(result.code()).isEqualTo(200);
            verify(orderRepository, times(2)).save(any());
        }

        @Test
        @DisplayName("连撞 3 次 → 报错（不无限重试）")
        void givesUpAfterMaxAttempts() {
            doThrow(new DuplicateKeyException("uk_order_no"))
                    .when(orderRepository).save(any());

            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, ITEMS, 9L, OrderExtras.EMPTY))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("订单号生成冲突");

            verify(orderRepository, times(3)).save(any());
        }
    }

    // ════════════════ 并发保护 ════════════════

    @Nested
    @DisplayName("并发保护：读-改-写之间被人抢先 → 409")
    class Concurrency {

        @Test
        @DisplayName("CAS 命中 0 行（别人先改了）→ 409 提示刷新，而不是覆盖对方")
        void casConflictReturns409() {
            Order order = persistedOrder(1L, STORE_A, 100L);
            stubFind(order);
            // 模拟：本次操作读到的状态已过期，数据库里已经不是 PENDING_PAY 了
            when(orderRepository.updateStatusCas(order, OrderStatus.PENDING_PAY))
                    .thenReturn(false);

            assertThatThrownBy(() -> orderAppService.pay(1L, PayMethod.CASH, TOTAL, 9L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(409);
        }

        @Test
        @DisplayName("CAS 冲突时不会走老的无条件 save（防止覆盖丢更新）")
        void casConflictDoesNotFallBackToSave() {
            Order order = persistedOrder(1L, STORE_A, 100L);
            stubFind(order);
            when(orderRepository.updateStatusCas(order, OrderStatus.PENDING_PAY))
                    .thenReturn(false);

            assertThatThrownBy(() -> orderAppService.updateStatus(1L, 9L))
                    .isInstanceOf(BusinessException.class);

            verify(orderRepository, never()).save(any());
        }
    }
}
