package com.yunxi.application.service;

import com.yunxi.application.dto.OrderExtras;
import com.yunxi.application.dto.OrderItemCommand;
import com.yunxi.application.dto.OrderView;
import com.yunxi.common.BusinessException;
import com.yunxi.common.PageResult;
import com.yunxi.common.enums.OrderSource;
import com.yunxi.common.enums.OrderStatus;
import com.yunxi.common.enums.PayMethod;
import com.yunxi.common.Result;
import com.yunxi.domain.order.Order;
import com.yunxi.domain.order.OrderItem;
import com.yunxi.domain.order.OrderRepository;
import com.yunxi.domain.price.ClothesCategory;
import com.yunxi.domain.price.ClothesPrice;
import com.yunxi.domain.price.PriceRepository;
import com.yunxi.domain.price.WashType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
 *   2. 谁能查看（顾客只看自己的；员工不限门店 —— 所有店长管所有订单，§4.2）
 *   3. 留痕（每次状态操作都记录操作员工）
 *
 * 不依赖 Spring 容器、不连数据库 —— 所以叫"单元"测试。
 *
 * 2026-09-11：入参改成应用层命令对象（OrderItemCommand）、出参改成 OrderView，
 * 明细里的价只能来自价目表 —— 调用方连"传一个价"的字段都没有了。
 */
class OrderAppServiceTest {

    private OrderRepository orderRepository;
    private PriceRepository priceRepository;
    private OrderAppService orderAppService;

    private static final BigDecimal TOTAL = new BigDecimal("30.00");
    private static final Long STORE_A = 1L;
    private static final Long STORE_B = 2L;

    /** 一条明细：2 件衬衫（价目表里的 15.00/件 → 总价 30.00）。命令对象不带价 */
    private static final List<OrderItemCommand> ITEMS = List.of(
            new OrderItemCommand(1L, 1L, 2, null));

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        priceRepository = mock(PriceRepository.class);
        orderAppService = new OrderAppService(orderRepository, priceRepository);
        // CAS 更新默认"成功"（Mockito 对 boolean 默认返回 false，
        // 不显式打桩的话每个状态操作测试都会撞上 409）
        when(orderRepository.updateStatusCas(any(), any())).thenReturn(true);
        // 后端算价的默认价目表：现有测试的明细都是 (分类1, 洗涤方式1)，
        // 价目表里这个组合 = 15.00 —— 与 ITEMS 的"2 件 × 15.00 = 总价 30.00"对齐
        when(priceRepository.findPricesByCategoryIds(any())).thenReturn(Map.of(
                1L, List.of(new ClothesPrice(1L, 1L, new BigDecimal("15.00")))));
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

        private final List<OrderItemCommand> items = List.of(
                new OrderItemCommand(1L, 1L, 2, null));

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
            Result<OrderView> result = orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, items, 9L,
                    new OrderExtras(LocalDateTime.now(), "3 号楼 502", "袖口有污渍"));

            assertThat(result.code()).isEqualTo(200);
            OrderView saved = result.data();
            assertThat(saved.staffId()).isEqualTo(9L);
            assertThat(saved.deliveryAddress()).isEqualTo("3 号楼 502");
            assertThat(saved.remark()).isEqualTo("袖口有污渍");
            assertThat(saved.status()).isEqualTo(OrderStatus.PENDING_PAY);
            verify(orderRepository).save(any());
        }

        @Test
        @DisplayName("网单：没有操作员工也合法（staffId 保持 null）")
        void onlineOrderNeedsNoStaff() {
            Result<OrderView> result = orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.ONLINE, items, null, OrderExtras.EMPTY);

            assertThat(result.code()).isEqualTo(200);
            assertThat(result.data().staffId()).isNull();
            assertThat(result.data().source()).isEqualTo(OrderSource.ONLINE);
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

    // ════════════════ 后端算价 ════════════════

    @Nested
    @DisplayName("后端算价：单价只认价目表")
    class Pricing {

        @Test
        @DisplayName("单价只能来自价目表 —— 调用方连个「传价」的字段都没有")
        void priceComesFromRepositoryNotFromCaller() {
            // 命令对象（OrderItemCommand）里没有价格字段，外面想塞也塞不进来；
            // 这条测试守的是"算出来的价 == 价目表里的价"，也就是算价真的发生了
            List<OrderItemCommand> twoShirts = List.of(
                    new OrderItemCommand(1L, 1L, 2, null));

            Result<OrderView> result = orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, twoShirts, 9L, OrderExtras.EMPTY);

            assertThat(result.data().items().get(0).unitPrice())
                    .isEqualByComparingTo("15.00");
            assertThat(result.data().totalAmount()).isEqualByComparingTo("30.00");
        }

        @Test
        @DisplayName("3 条明细只查 1 次价目表（不是 N+1）")
        void queriesPriceTableOnce() {
            when(priceRepository.findPricesByCategoryIds(any())).thenReturn(Map.of(
                    1L, List.of(new ClothesPrice(1L, 1L, new BigDecimal("15.00")),
                            new ClothesPrice(1L, 2L, new BigDecimal("35.00")),
                            new ClothesPrice(1L, 3L, new BigDecimal("8.00")))));
            List<OrderItemCommand> three = List.of(
                    new OrderItemCommand(1L, 1L, 1, null),
                    new OrderItemCommand(1L, 2L, 1, null),
                    new OrderItemCommand(1L, 3L, 1, null));

            Result<OrderView> result = orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, three, 9L, OrderExtras.EMPTY);

            verify(priceRepository, times(1)).findPricesByCategoryIds(any());
            assertThat(result.data().totalAmount()).isEqualByComparingTo("58.00");
        }

        @Test
        @DisplayName("价目表没有这个组合 → 400 带明细序号，且不落库（配置缺口不是 500）")
        void missingPriceRow() {
            when(priceRepository.findPricesByCategoryIds(any())).thenReturn(Map.of());

            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, ITEMS, 9L, OrderExtras.EMPTY))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("第 1 条明细的衣物分类或洗涤方式不存在");

            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("价格为 0（羽绒服·普洗）→ 400 说清是哪个分类不支持哪种洗法")
        void zeroPriceMeansUnsupported() {
            when(priceRepository.findPricesByCategoryIds(any())).thenReturn(Map.of(
                    13L, List.of(new ClothesPrice(13L, 1L, new BigDecimal("0.00")))));
            when(priceRepository.findCategoryById(13L))
                    .thenReturn(Optional.of(category(13L, "羽绒服")));
            when(priceRepository.findAllWashTypes())
                    .thenReturn(List.of(washType(1L, "普洗")));

            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE,
                    List.of(new OrderItemCommand(13L, 1L, 1, null)), 9L, OrderExtras.EMPTY))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("「羽绒服」不支持「普洗」");
        }

        @Test
        @DisplayName("空明细先被拦下 —— 不白查一次价目表")
        void emptyItemsNeverTouchesPriceTable() {
            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, List.of(), 9L, OrderExtras.EMPTY))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("至少需要一条明细");

            verify(priceRepository, never()).findPricesByCategoryIds(any());
        }

        @Test
        @DisplayName("明细还没定价就算钱 → 大声报错，不是 NPE")
        void subtotalWithoutPriceFails() {
            OrderItem unpriced = new OrderItem(1L, 1L, 1, null);

            assertThatThrownBy(unpriced::subtotal)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("后端算价");
        }

        private ClothesCategory category(long id, String name) {
            ClothesCategory c = new ClothesCategory();
            c.setId(id);
            c.setName(name);
            return c;
        }

        private WashType washType(long id, String name) {
            WashType w = new WashType();
            w.setId(id);
            w.setName(name);
            return w;
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

            assertThatThrownBy(() -> orderAppService.getOrder(404L, "staff", 9L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(404);
        }

        @Test
        @DisplayName("顾客查自己的订单 → 放行")
        void customerOwnOrder() {
            Order order = persistedOrder(1L, STORE_A, 100L);
            stubFind(order);

            Result<OrderView> result = orderAppService.getOrder(1L, "customer", 100L);
            assertThat(result.data().customerId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("顾客查别人的订单 → 403")
        void customerCannotSeeOthers() {
            Order order = persistedOrder(1L, STORE_A, 100L);
            stubFind(order);

            assertThatThrownBy(() -> orderAppService.getOrder(1L, "customer", 999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(403);
        }

        @Test
        @DisplayName("员工查别家店的订单 → 200（跨店不隔离：所有店长管所有订单）")
        void staffSeesAnyStore() {
            // 这单属于 STORE_B，员工来自哪儿不重要 —— 查单根本不传门店
            Order order = persistedOrder(1L, STORE_B, 100L);
            stubFind(order);

            Result<OrderView> result = orderAppService.getOrder(1L, "staff", 9L);
            assertThat(result.code()).isEqualTo(200);
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
        @DisplayName("员工查列表 → 不按门店筛（所有店的单在同一页）")
        void staffSeesAllStores() {
            // 两条单分属不同门店，都得出现在员工列表里
            when(orderRepository.count(null, null, null)).thenReturn(2L);
            when(orderRepository.findPage(null, null, null, 0, 20))
                    .thenReturn(List.of(persistedOrder(2L, STORE_A, 100L),
                            persistedOrder(1L, STORE_B, 100L)));

            Result<PageResult<OrderView>> result = orderAppService.listOrders(
                    "staff", 9L, null, 1, 20);

            assertThat(result.data().total()).isEqualTo(2L);
            assertThat(result.data().list()).hasSize(2);
            assertThat(result.data().totalPages()).isEqualTo(1);
            verify(orderRepository).count(null, null, null);   // 关键：没夹带门店条件
        }

        @Test
        @DisplayName("顾客查列表 → 只按自己的 customerId 筛选（即使传了别人的店也没用）")
        void customerSeesOwnOrdersOnly() {
            when(orderRepository.count(null, 100L, null)).thenReturn(1L);
            when(orderRepository.findPage(null, 100L, null, 0, 20))
                    .thenReturn(List.of(persistedOrder(1L, STORE_A, 100L)));

            Result<PageResult<OrderView>> result = orderAppService.listOrders(
                    "customer", 100L, null, 1, 20);

            assertThat(result.data().list()).hasSize(1);
            // 关键：查的是 customerId=100，没夹带门店条件
            verify(orderRepository).count(null, 100L, null);
        }

        @Test
        @DisplayName("页码越界归一化：page=0 → 1、pageSize=9999 → 100")
        void pageParamsClamped() {
            when(orderRepository.count(null, null, null)).thenReturn(0L);

            Result<PageResult<OrderView>> result = orderAppService.listOrders(
                    "staff", 9L, null, 0, 9999);

            assertThat(result.data().page()).isEqualTo(1);
            assertThat(result.data().pageSize()).isEqualTo(100);
            // 没数据时不该再查一次列表
            verify(orderRepository, never()).findPage(any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("第二页的 offset = (page-1)*pageSize")
        void secondPageOffset() {
            when(orderRepository.count(null, null, OrderStatus.WASHING)).thenReturn(30L);
            when(orderRepository.findPage(null, null, OrderStatus.WASHING, 20, 20))
                    .thenReturn(List.of());

            Result<PageResult<OrderView>> result = orderAppService.listOrders(
                    "staff", 9L, OrderStatus.WASHING, 2, 20);

            assertThat(result.data().total()).isEqualTo(30L);
            assertThat(result.data().totalPages()).isEqualTo(2);
            verify(orderRepository).findPage(null, null, OrderStatus.WASHING, 20, 20);
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

            Result<OrderView> result = orderAppService.createOrder(
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
