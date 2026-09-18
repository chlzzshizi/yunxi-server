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
import com.yunxi.domain.customer.Customer;
import com.yunxi.domain.customer.CustomerRepository;
import com.yunxi.domain.order.Order;
import com.yunxi.domain.order.OrderItem;
import com.yunxi.domain.order.OrderRepository;
import com.yunxi.domain.price.ClothesCategory;
import com.yunxi.domain.price.ClothesPrice;
import com.yunxi.domain.price.PriceRepository;
import com.yunxi.domain.price.WashType;
import com.yunxi.domain.store.Store;
import com.yunxi.domain.store.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
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
    private StoreRepository storeRepository;
    private CustomerRepository customerRepository;
    private CouponAppService couponAppService;
    private OrderAppService orderAppService;

    private static final BigDecimal TOTAL = new BigDecimal("30.00");
    private static final Long STORE_A = 1L;
    private static final Long STORE_B = 2L;
    /** 门店单里员工填的顾客 id —— 现有用例里的 100L 都是它 */
    private static final Long CUSTOMER = 100L;
    /** 用在这组用例里的券 id（券服务是假的，这个数字只要前后一致就行） */
    private static final Long COUPON = 55L;
    /** 网单的配送地址 —— 网单必填，所以每个网单用例都得带上一个 */
    private static final String ADDRESS = "杭州市西湖区文一西路 100 号";

    /** 网单的一份合法 extras：有地址（网单没地址会被领域层挡下，那是另一组用例的事） */
    private static final OrderExtras ONLINE_EXTRAS =
            new OrderExtras(null, ADDRESS, null);

    /** 一条明细：2 件衬衫（价目表里的 15.00/件 → 总价 30.00）。命令对象不带价 */
    private static final List<OrderItemCommand> ITEMS = List.of(
            new OrderItemCommand(1L, 1L, 2, null));

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        priceRepository = mock(PriceRepository.class);
        storeRepository = mock(StoreRepository.class);
        customerRepository = mock(CustomerRepository.class);
        // 券的校验/核销全在 CouponAppService 里（它自己去查库），对订单应用服务来说
        // 就是个"能用就给你折扣率、不能用就抛 400"的黑盒 —— 这里假造它，
        // 订单这边的职责只是"什么时候问、拿到折扣往哪儿用、什么时候核销"
        couponAppService = mock(CouponAppService.class);
        orderAppService = new OrderAppService(
                orderRepository, priceRepository, storeRepository, customerRepository,
                couponAppService);
        // CAS 更新默认"成功"（Mockito 对 boolean 默认返回 false，
        // 不显式打桩的话每个状态操作测试都会撞上 409）
        when(orderRepository.updateStatusCas(any(), any())).thenReturn(true);
        // 后端算价的默认价目表：现有测试的明细都是 (分类1, 洗涤方式1)，
        // 价目表里这个组合 = 15.00 —— 与 ITEMS 的"2 件 × 15.00 = 总价 30.00"对齐
        when(priceRepository.findPricesByCategoryIds(any())).thenReturn(Map.of(
                1L, List.of(new ClothesPrice(1L, 1L, new BigDecimal("15.00")))));
        // STORE_A 存在且营业中。只桩这一个 —— 别的 storeId（如 999）
        // 会拿到 Mockito 对 Optional 的默认返回值 empty()，正好就是"门店不存在"
        when(storeRepository.findOpenById(STORE_A)).thenReturn(Optional.of(store(STORE_A)));
        // 现有用例的门店单顾客一律是 100L：桩它存在，让"回库查了且放行"这条路走得通。
        // 别的 id（如 999）会拿到 Mockito 对 Optional 的默认返回值 empty()，正好就是
        // "查无此人" —— 和上面只桩 STORE_A 是同一个手法，不桩的那一半自动是坏数据
        when(customerRepository.findById(CUSTOMER))
                .thenReturn(Optional.of(customer(CUSTOMER)));
    }

    /** 一个营业中的门店（Store 只有 setter，没有构造器） */
    private static Store store(Long id) {
        Store s = new Store();
        s.setId(id);
        s.setName("云洗中央门店");
        return s;
    }

    /** 一个已建档的顾客（Customer 只有 setter，没有构造器） */
    private static Customer customer(Long id) {
        Customer c = new Customer();
        c.setId(id);
        c.setPhone("13700000001");
        c.setName("张三");
        return c;
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

    /** 造一个已落库的**网单**（含 ID）—— 在线支付和快递单号都只对它有意义 */
    private Order persistedOnlineOrder(Long id, Long storeId, Long customerId) {
        List<OrderItem> items = List.of(
                new OrderItem(1L, 1L, 2, new BigDecimal("15.00"), null));
        Order order = new Order("YX-TEST-ON" + id, storeId, customerId,
                OrderSource.ONLINE, items);
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
                    STORE_A, 100L, OrderSource.STORE, items, null, OrderExtras.EMPTY, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("员工账号");

            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("门店单：操作员工与备注一起落库")
        void storeOrderRecordsOperatorAndExtras() {
            Result<OrderView> result = orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, items, 9L,
                    new OrderExtras(LocalDateTime.now(), "3 号楼 502", "袖口有污渍"), null);

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
            // extras 必须带地址 —— 网单没地址连 Order 都建不出来（见 OnlineDeliveryAddress）
            Result<OrderView> result = orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.ONLINE, items, null, ONLINE_EXTRAS, null);

            assertThat(result.code()).isEqualTo(200);
            assertThat(result.data().staffId()).isNull();
            assertThat(result.data().source()).isEqualTo(OrderSource.ONLINE);
        }

        @Test
        @DisplayName("空明细 → 400，不落库")
        void emptyItemsRejected() {
            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, List.of(), 9L, OrderExtras.EMPTY, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("至少需要一条明细");

            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("明细列表整个为 null → 400，不落库")
        void nullItemsRejected() {
            // isEmpty() 那条上面测过（传 List.of()）。这条守的是 null ——
            // 它只可能来自非 HTTP 入口（定时任务、脚本、第二个前端），
            // 而在那些入口上 NPE 会报成 500，看不出是"调用方没传明细"
            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, null, 9L, OrderExtras.EMPTY, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("至少需要一条明细");

            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("门店单不填预约时间/备注（extras=null）→ 照常建单，三列都空")
        void storeOrderWithoutExtras() {
            // 柜台最常见的那一单：顾客把衣服放下就走，员工什么都不填。
            // extras 为 null 时**跳过** fillOrderInfo（不走"全 null 就 return"那条），
            // 两条路的终点看起来一样，但只有这条能证明"没填附加信息"不会把单卡住
            Result<OrderView> result = orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, items, 9L, null, null);

            assertThat(result.code()).isEqualTo(200);
            assertThat(result.data().appointmentTime()).isNull();
            assertThat(result.data().deliveryAddress()).isNull();
            assertThat(result.data().remark()).isNull();
            verify(orderRepository).save(any());
        }

        @Test
        @DisplayName("明细缺字段 → 400 带序号（第 N 条），且在算价之前就被拦下")
        void rejectIncompleteItems() {
            // 应用层这层"字段全不全"的校验是给**非 HTTP 入口**留的（类注释：
            // 将来还会有定时任务、后台脚本、第二个前端）。controller 那份管文案，
            // 这份管"从别的门进来也拦得住" —— 所以这里测的是缺字段的**每一种**形状
            assertThatThrownBy(() -> orderAppService.createOrder(STORE_A, 100L,
                    OrderSource.STORE,
                    List.of(new OrderItemCommand(null, 1L, 2, null)),
                    9L, OrderExtras.EMPTY, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("第 1 条明细缺少");

            assertThatThrownBy(() -> orderAppService.createOrder(STORE_A, 100L,
                    OrderSource.STORE,
                    List.of(new OrderItemCommand(1L, null, 2, null)),
                    9L, OrderExtras.EMPTY, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("第 1 条明细缺少");

            assertThatThrownBy(() -> orderAppService.createOrder(STORE_A, 100L,
                    OrderSource.STORE,
                    List.of(new OrderItemCommand(1L, 1L, null, null)),
                    9L, OrderExtras.EMPTY, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("第 1 条明细缺少");

            // 序号 = 下标 + 1：坏的是第 2 条就必须报 2 —— 报成 0 或 1 会让人
            // 去改一条好明细，而真正有问题的那条一直在。List.of 不收 null 元素，
            // 「整条是 null」这种坏形状只能用 Arrays.asList 造
            assertThatThrownBy(() -> orderAppService.createOrder(STORE_A, 100L,
                    OrderSource.STORE,
                    Arrays.asList(new OrderItemCommand(1L, 1L, 2, null), null),
                    9L, OrderExtras.EMPTY, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("第 2 条明细缺少");

            verify(orderRepository, never()).save(any());
            // 形状都没对上就别白查一次价目表（"校验在算价之前"）
            verify(priceRepository, never()).findPricesByCategoryIds(any());
        }
    }

    // ════════════════ 网单门店校验 ════════════════

    /**
     * 门店**可选**（2026-09-13 口径）：不指定就存 NULL，指定了才需要它在营业。
     * 所以这组用例是"两半"，缺一不可 —— 少了上面那半（null 放行），
     * 下面那些 400 可能来自"没传门店"而不是"店是坏的"；少了下面那半，
     * 就可能把校验整个删掉而没人发现。
     */
    @Nested
    @DisplayName("网单的门店可选；给了值才需要它是营业中的")
    class OnlineStoreCheck {

        @Test
        @DisplayName("不指定门店（null）→ 建单成功，storeId 为 null，且一次都没查门店表")
        void nullStoreAllowed() {
            // 断言"没查库"比只断言"没抛异常"强：它证明 null 是在判断的第一段就短路了，
            // 而不是查了一次库、恰好被某个 Optional.empty() 放行 ——
            // 在这个用例里两者**结果相同**，但一个是"不指定"，另一个是"店不存在也放行"
            Result<OrderView> result = orderAppService.createOrder(
                    null, 100L, OrderSource.ONLINE, ITEMS, null, ONLINE_EXTRAS, null);

            assertThat(result.code()).isEqualTo(200);
            assertThat(result.data().storeId()).isNull();
            assertThat(result.data().source()).isEqualTo(OrderSource.ONLINE);
            verify(storeRepository, never()).findOpenById(any());
        }

        @Test
        @DisplayName("选了不存在/已停业的门店（999）→ 400，不落库")
        void unknownStoreRejected() {
            // 999 没打桩：Mockito 对 Optional 默认返回 empty()，
            // 正好等于"没这家店"，也等于"店存在但已停业"—— 仓储的 SQL 把两者过滤成了一件事
            assertThatThrownBy(() -> orderAppService.createOrder(
                    999L, 100L, OrderSource.ONLINE, ITEMS, null, ONLINE_EXTRAS, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("门店不存在或已停业");

            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("校验在算价之前 —— 店都选错了就别白查一次价目表")
        void storeCheckedBeforePricing() {
            // extras 带上了**合法**地址：这组用例要证明的是门店校验，不是地址校验。
            // 地址留空的话它照样会抛 400 —— 但那是另一条规则抛的，这条用例就变成
            // "测了地址"还自称测了门店（断言过的理由必须也是被测的那个）
            assertThatThrownBy(() -> orderAppService.createOrder(
                    999L, 100L, OrderSource.ONLINE, ITEMS, null, ONLINE_EXTRAS, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("门店");

            verify(priceRepository, never()).findPricesByCategoryIds(any());
        }

        @Test
        @DisplayName("门店单不查门店表 —— storeId 来自员工 token，是服务器签发的，不用回库再问一遍")
        void storeOrderSkipsStoreLookup() {
            orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, ITEMS, 9L, OrderExtras.EMPTY, null);

            verify(storeRepository, never()).findOpenById(any());
        }
    }

    // ════════════════ 门店单顾客校验 ════════════════

    /**
     * 和上一组严格对称：那边守网单的 storeId，这边守门店单的 customerId ——
     * 判据都是"这个值是不是请求体来的"。orders 对这两列都没有外键，代码是唯一防线。
     */
    @Nested
    @DisplayName("门店单必须挂在一个真实存在的顾客上")
    class StoreCustomerCheck {

        @Test
        @DisplayName("customerId 查无此人（999）→ 400，不落库")
        void unknownCustomerRejected() {
            // 999 没打桩 → Mockito 对 Optional 默认返回 empty()，正好是"查无此人"。
            // 不拦的话会建出一张挂在幽灵顾客身上的单：它不报错，但从此所有
            // "按顾客查订单"的地方都会莫名其妙地少一条，而且没有外键能帮你找回来
            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, 999L, OrderSource.STORE, ITEMS, 9L, OrderExtras.EMPTY, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("顾客不存在");

            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("顾客校验在算价之前 —— 人都没对上就别白查一次价目表")
        void customerCheckedBeforePricing() {
            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, 999L, OrderSource.STORE, ITEMS, 9L, OrderExtras.EMPTY, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("顾客");

            verify(priceRepository, never()).findPricesByCategoryIds(any());
        }

        @Test
        @DisplayName("顾客存在 → 照常建单（校验没有误伤正常路径）")
        void knownCustomerPasses() {
            Result<OrderView> result = orderAppService.createOrder(
                    STORE_A, CUSTOMER, OrderSource.STORE, ITEMS, 9L, OrderExtras.EMPTY, null);

            assertThat(result.code()).isEqualTo(200);
            verify(customerRepository).findById(CUSTOMER);
            verify(orderRepository).save(any());
        }

        @Test
        @DisplayName("网单不查顾客表 —— customerId 取自顾客 token，服务器签发的，不用回库再问一遍")
        void onlineOrderSkipsCustomerLookup() {
            orderAppService.createOrder(
                    STORE_A, CUSTOMER, OrderSource.ONLINE, ITEMS, null, ONLINE_EXTRAS, null);

            verify(customerRepository, never()).findById(any());
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
                    STORE_A, 100L, OrderSource.STORE, twoShirts, 9L, OrderExtras.EMPTY, null);

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
                    STORE_A, 100L, OrderSource.STORE, three, 9L, OrderExtras.EMPTY, null);

            verify(priceRepository, times(1)).findPricesByCategoryIds(any());
            assertThat(result.data().totalAmount()).isEqualByComparingTo("58.00");
        }

        @Test
        @DisplayName("价目表没有这个组合 → 400 带明细序号，且不落库（配置缺口不是 500）")
        void missingPriceRow() {
            when(priceRepository.findPricesByCategoryIds(any())).thenReturn(Map.of());

            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, ITEMS, 9L, OrderExtras.EMPTY, null))
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
                    List.of(new OrderItemCommand(13L, 1L, 1, null)), 9L, OrderExtras.EMPTY, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("「羽绒服」不支持「普洗」");
        }

        @Test
        @DisplayName("空明细先被拦下 —— 不白查一次价目表")
        void emptyItemsNeverTouchesPriceTable() {
            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, List.of(), 9L, OrderExtras.EMPTY, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("至少需要一条明细");

            verify(priceRepository, never()).findPricesByCategoryIds(any());
        }

        @Test
        @DisplayName("分类有价、但**没有这一种洗法** → 400，同样带序号")
        void priceRowMissingForThisWashType() {
            // 和上面 missingPriceRow 是 matchPrice 里两个不同的 return null：
            //   上面那条：这个分类一行价都没有   → 整张价目表没配
            //   这条    ：有价、但缺这一种洗法   → 漏配了一种（今天只配了普洗，顾客选了单熨）
            // 排查方向完全不同，所以两条都得有 —— 只留一条的话，另一条的
            // 文案/序号坏了没人知道，而它们都是给管理员看的线索
            when(priceRepository.findPricesByCategoryIds(any())).thenReturn(Map.of(
                    1L, List.of(new ClothesPrice(1L, 1L, new BigDecimal("15.00")))));

            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE,
                    List.of(new OrderItemCommand(1L, 3L, 2, null)), 9L, OrderExtras.EMPTY, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("第 1 条明细的衣物分类或洗涤方式不存在")
                    .hasMessageContaining("洗涤方式 3");

            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("洗法在价目表里有、在 wash_types 里查不到名 → 文案退化成编号，不是 NPE")
        void washTypeNameFallsBackToId() {
            // 数据不一致时的样子：价目表里有一行 (13, 3)、但 wash_types 读回来
            // 只有 1 号。报错文案要还能出得来 —— 这句文案是给管理员定位配置用的，
            // 它自己再炸一次（NPE 或 "null"）等于把线索弄丢了
            when(priceRepository.findPricesByCategoryIds(any())).thenReturn(Map.of(
                    13L, List.of(new ClothesPrice(13L, 3L, new BigDecimal("0.00")))));
            when(priceRepository.findCategoryById(13L))
                    .thenReturn(Optional.of(category(13L, "羽绒服")));
            when(priceRepository.findAllWashTypes())
                    .thenReturn(List.of(washType(1L, "普洗")));

            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE,
                    List.of(new OrderItemCommand(13L, 3L, 1, null)), 9L, OrderExtras.EMPTY, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("「羽绒服」不支持「洗涤方式 3」");
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
                    STORE_A, 100L, OrderSource.STORE, ITEMS, 9L, OrderExtras.EMPTY, null);

            assertThat(result.code()).isEqualTo(200);
            verify(orderRepository, times(2)).save(any());
        }

        @Test
        @DisplayName("连撞 3 次 → 报错（不无限重试）")
        void givesUpAfterMaxAttempts() {
            doThrow(new DuplicateKeyException("uk_order_no"))
                    .when(orderRepository).save(any());

            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, 100L, OrderSource.STORE, ITEMS, 9L, OrderExtras.EMPTY, null))
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

    // ════════════════ 券-订单抵扣 ════════════════

    /**
     * 这一组只测**订单侧**的编排：什么时候问券、折扣用在哪、什么时候核销。
     * "这张券能不能用"是 CouponAppService 的职责（要查库），这里把它当成黑盒 ——
     * 它要么回一个折扣率，要么抛 400，订单这边不关心它怎么判断的。
     */
    @Nested
    @DisplayName("券-订单抵扣")
    class CouponDeduction {

        /** 5 折。明细是 2 件 × 15.00 = 30.00，折后 15.00 */
        private static final BigDecimal HALF = new BigDecimal("0.50");

        @Test
        @DisplayName("门店单也能用券（2026-09-12 放开的规则）：折后价 + 记下省了多少")
        void storeOrderCanUseCoupon() {
            when(couponAppService.resolveDiscount(COUPON, CUSTOMER)).thenReturn(HALF);

            Result<OrderView> result = orderAppService.createOrder(
                    STORE_A, CUSTOMER, OrderSource.STORE, ITEMS, 9L,
                    OrderExtras.EMPTY, COUPON);

            OrderView saved = result.data();
            // total_amount 存的是**折后应付**（不是折前价）：否则收银台会按全价收钱
            assertThat(saved.totalAmount()).isEqualByComparingTo("15.00");
            // discount_amount 只作展示/对账，不参与任何状态判断
            assertThat(saved.discountAmount()).isEqualByComparingTo("15.00");
            assertThat(saved.couponId()).isEqualTo(COUPON);
            // 折后了不代表付过了 —— 建单出来还是待支付
            assertThat(saved.paidAmount()).isEqualByComparingTo("0.00");
            assertThat(saved.status()).isEqualTo(OrderStatus.PENDING_PAY);
        }

        @Test
        @DisplayName("网单用券：券服务同样被问、被核销（顾客自助，没有经手员工）")
        void onlineOrderCanUseCoupon() {
            when(couponAppService.resolveDiscount(COUPON, CUSTOMER)).thenReturn(HALF);
            stubSaveAssignsId(88L);

            orderAppService.createOrder(
                    STORE_A, CUSTOMER, OrderSource.ONLINE, ITEMS, null,
                    ONLINE_EXTRAS, COUPON);

            // 最后一个参数 null = used_staff_id 为空，这正是"顾客自己在用"的痕迹
            verify(couponAppService).consume(COUPON, CUSTOMER, 88L, null);
        }

        @Test
        @DisplayName("不带券 → 完全不碰券服务，且 discount_amount 是 0 不是 null")
        void noCouponNeverTouchesCouponService() {
            Result<OrderView> result = orderAppService.createOrder(
                    STORE_A, CUSTOMER, OrderSource.STORE, ITEMS, 9L,
                    OrderExtras.EMPTY, null);

            verify(couponAppService, never()).resolveDiscount(any(), any());
            verify(couponAppService, never()).consume(any(), any(), any(), any());
            // 0 而不是 null：discount_amount 列是 NOT NULL DEFAULT 0.00，
            // 留 null 会让**每一张不带券的订单**都插不进去（绝大多数订单都不带券）
            assertThat(result.data().discountAmount()).isEqualByComparingTo("0.00");
            assertThat(result.data().totalAmount()).isEqualByComparingTo("30.00");
        }

        @Test
        @DisplayName("券的校验在算价之前 —— 券都用不了就别白查一次价目表")
        void couponCheckedBeforePricing() {
            when(couponAppService.resolveDiscount(COUPON, CUSTOMER))
                    .thenThrow(new BusinessException("该优惠券不属于这位顾客"));

            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, CUSTOMER, OrderSource.STORE, ITEMS, 9L,
                    OrderExtras.EMPTY, COUPON))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不属于这位顾客");

            verify(priceRepository, never()).findPricesByCategoryIds(any());
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("核销发生在**落库之后**，带的是真实的自增订单 id（used_order_id 靠它）")
        void consumeHappensAfterSaveWithRealOrderId() {
            when(couponAppService.resolveDiscount(COUPON, CUSTOMER)).thenReturn(HALF);
            // 订单 id 是仓储在 save 里回填的（useGeneratedKeys）。假仓储不会真的回填，
            // 所以这里手动补上 —— 顺便证明"核销时 id 已经存在了"
            stubSaveAssignsId(77L);

            orderAppService.createOrder(
                    STORE_A, CUSTOMER, OrderSource.STORE, ITEMS, 9L,
                    OrderExtras.EMPTY, COUPON);

            // 第四个参数是经手员工 —— 门店单有值，这正是"这券是谁烧的"的答案
            verify(couponAppService).consume(COUPON, CUSTOMER, 77L, 9L);
        }

        @Test
        @DisplayName("券被抢先核销（CAS 0 行 → 409）→ 异常往外抛，不被订单号重试循环吞掉")
        void consumeConflictIsNotSwallowed() {
            when(couponAppService.resolveDiscount(COUPON, CUSTOMER)).thenReturn(HALF);
            doThrow(new BusinessException(409, "该优惠券已被使用，请刷新后重试"))
                    .when(couponAppService).consume(any(), any(), any(), any());

            // 订单号重试循环只 catch DuplicateKeyException —— 券的 409 必须穿过去，
            // 否则"券没了"会被当成"号撞了"，白白重试三次再报一个牛头不对马嘴的错
            assertThatThrownBy(() -> orderAppService.createOrder(
                    STORE_A, CUSTOMER, OrderSource.STORE, ITEMS, 9L,
                    OrderExtras.EMPTY, COUPON))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(409);

            verify(orderRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("撞号重试：重试出来的新单**同样是折后价**（折扣必须打在循环里）")
        void discountAppliedOnEveryRetryAttempt() {
            when(couponAppService.resolveDiscount(COUPON, CUSTOMER)).thenReturn(HALF);
            // 第一次 save 撞号，第二次放行
            doThrow(new DuplicateKeyException("uk_order_no"))
                    .doAnswer(inv -> null)
                    .when(orderRepository).save(any());

            Result<OrderView> result = orderAppService.createOrder(
                    STORE_A, CUSTOMER, OrderSource.STORE, ITEMS, 9L,
                    OrderExtras.EMPTY, COUPON);

            // 每次重试都是一张新 new 出来的 Order，构造器会按明细重算折前总价。
            // 折扣若写在循环外，就是对着**上一轮那张已经丢掉的**对象打的折 ——
            // 最终落库的是一张全价单。这条路径要先撞一次号才走得到，平时测不出来，
            // 所以专门在这里钉住：重试成功的那张也是 15.00
            assertThat(result.data().totalAmount()).isEqualByComparingTo("15.00");
            assertThat(result.data().discountAmount()).isEqualByComparingTo("15.00");
        }

        /** 模拟"落库时回填自增 id"（真实实现在 save 里 set 回去） */
        private void stubSaveAssignsId(Long id) {
            doAnswer(inv -> {
                ((Order) inv.getArgument(0)).setId(id);
                return null;
            }).when(orderRepository).save(any());
        }
    }

    // ════════════════ 顾客在线支付 ════════════════

    @Nested
    @DisplayName("洗后付结账（员工）")
    class FinalPay {

        /**
         * 一张走到「待取件」(5) 的门店单：下单时走洗后付（先付 0 占位），
         * 衣服洗完还没结账 —— 这正是 finalPay 存在的那个时刻。
         */
        private Order awaitingFinalPay() {
            Order order = persistedOrder(1L, STORE_A, CUSTOMER);
            order.pay(PayMethod.CASH, BigDecimal.ZERO);   // 洗后付：先付传 0
            order.updateStatus();   // 2 → 3
            order.updateStatus();   // 3 → 4
            order.updateStatus();   // 4 → 5（门店单走 5，网单才走 6）
            stubFind(order);
            return order;
        }

        @Test
        @DisplayName("5 态结账 → 收全额、记下洗后付方式、状态 5→7，并记操作人")
        void settlesAtPickup() {
            Order order = awaitingFinalPay();

            Result<Void> result = orderAppService.finalPay(1L, PayMethod.ALIPAY, 9L);

            assertThat(result.code()).isEqualTo(200);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
            // 金额来自订单，不是调用方传的 —— 端点上没有可篡改的数字
            assertThat(order.getPaidAmount()).isEqualByComparingTo(TOTAL);
            assertThat(order.getFinalPayMethod()).isEqualTo(PayMethod.ALIPAY);
            assertThat(order.getStaffId()).isEqualTo(9L);
            // 状态写入只能从这一句出去（§11.4），且期望状态是结账**之前**的 5
            verify(orderRepository).updateStatusCas(order, OrderStatus.PENDING_PICKUP);
        }

        @Test
        @DisplayName("顾客 token → 401，且**根本不看订单**（先验人、后查单）")
        void requiresStaff() {
            assertThatThrownBy(() -> orderAppService.finalPay(1L, PayMethod.CASH, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(401);

            // 顺序：requireStaff 在 load 之前。反过来的话，顾客拿一个不存在的
            // 单号会收到 404 —— 那等于用错误码告诉他"这个单号是真的，只是不归你"
            verify(orderRepository, never()).findById(any());
        }

        @Test
        @DisplayName("还没洗到 5 态（3 态）→ 400，一分钱不动、不写库")
        void wrongStatus() {
            Order order = persistedOrder(1L, STORE_A, CUSTOMER);
            order.pay(PayMethod.CASH, BigDecimal.ZERO);
            order.updateStatus();   // 2 → 3
            stubFind(order);

            assertThatThrownBy(() -> orderAppService.finalPay(1L, PayMethod.CASH, 9L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不允许洗后付结账");

            // 领域层的判据在上面已经报错了，这里守的是"应用层没有抢在领域之前动手"
            assertThat(order.getPaidAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(order.getFinalPayMethod()).isNull();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.WASHING);
            verify(orderRepository, never()).updateStatusCas(any(), any());
        }
    }

    @Nested
    @DisplayName("顾客在线支付")
    class OnlinePay {

        @Test
        @DisplayName("付自己的单 → 状态 1→2，支付方式落上，收的是**订单上的金额**")
        void paysOwnOrder() {
            Order order = persistedOnlineOrder(1L, STORE_A, CUSTOMER);
            stubFind(order);

            Result<Void> result = orderAppService.onlinePay(1L, PayMethod.WECHAT, CUSTOMER);

            assertThat(result.code()).isEqualTo(200);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(order.getPayMethod()).isEqualTo(PayMethod.WECHAT);
            assertThat(order.getPaidAmount()).isEqualByComparingTo(TOTAL);
            verify(orderRepository).updateStatusCas(order, OrderStatus.PENDING_PAY);
        }

        @Test
        @DisplayName("用券的单收的是**折后价** —— 端点上没有一个可以被篡改的数字")
        void paysTheDiscountedAmount() {
            Order order = persistedOnlineOrder(1L, STORE_A, CUSTOMER);
            order.applyCoupon(COUPON, new BigDecimal("0.50"));   // 30.00 → 15.00
            stubFind(order);

            orderAppService.onlinePay(1L, PayMethod.ALIPAY, CUSTOMER);

            // 顾客没有 amount 参数可传：金额只能来自订单，而订单上已经是折后应付。
            // 这一条把 §5.8（total_amount 存折后）和这个端点接在了一起 ——
            // 若 total_amount 存折前价，这里就会向顾客多收一倍
            assertThat(order.getPaidAmount()).isEqualByComparingTo("15.00");
            assertThat(order.getPayMethod()).isEqualTo(PayMethod.ALIPAY);
        }

        @Test
        @DisplayName("付别人的单 → 403，且一分钱没动")
        void cannotPayOthersOrder() {
            Order order = persistedOnlineOrder(1L, STORE_A, 100L);
            stubFind(order);

            assertThatThrownBy(() -> orderAppService.onlinePay(1L, PayMethod.WECHAT, 200L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(403);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAY);
            assertThat(order.getPaidAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("员工 token → 401（application 层自己也守一道，不押在 controller 的自觉上）")
        void staffTokenRejected() {
            assertThatThrownBy(() -> orderAppService.onlinePay(1L, PayMethod.WECHAT, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(401);
        }

        @Test
        @DisplayName("现金 → 400（顾客在手机上点不出柜台动作）")
        void cashRejected() {
            Order order = persistedOnlineOrder(1L, STORE_A, CUSTOMER);
            stubFind(order);

            assertThatThrownBy(() -> orderAppService.onlinePay(1L, PayMethod.CASH, CUSTOMER))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("只支持微信或支付宝");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAY);
        }

        @Test
        @DisplayName("订单不存在 → 404")
        void orderNotFound() {
            assertThatThrownBy(() -> orderAppService.onlinePay(1L, PayMethod.WECHAT, CUSTOMER))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(404);
        }

        @Test
        @DisplayName("连点两次 → 第二次读到的是已支付状态，领域层拦下（不允许重复支付）")
        void secondClickRejected() {
            Order order = persistedOnlineOrder(1L, STORE_A, CUSTOMER);
            stubFind(order);
            orderAppService.onlinePay(1L, PayMethod.WECHAT, CUSTOMER);

            assertThatThrownBy(() -> orderAppService.onlinePay(1L, PayMethod.WECHAT, CUSTOMER))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不允许支付");
        }

        @Test
        @DisplayName("两个请求同时付（CAS 0 行）→ 409，不是覆盖对方")
        void casConflictReturns409() {
            Order order = persistedOnlineOrder(1L, STORE_A, CUSTOMER);
            stubFind(order);
            when(orderRepository.updateStatusCas(order, OrderStatus.PENDING_PAY))
                    .thenReturn(false);

            assertThatThrownBy(() -> orderAppService.onlinePay(1L, PayMethod.WECHAT, CUSTOMER))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(409);
        }

        @Test
        @DisplayName("顾客支付**不能抹掉 staff_id** —— 门店单上那是建单员工（回归：recordOperator(null)）")
        void doesNotWipeStaffId() {
            // 门店单也会有顾客在线支付（衣服还在店里时先在手机上付掉）。
            // 若 saveStatusChange 无条件 recordOperator(operatorStaffId)，这里传的 null
            // 就会把 staff_id 清空 —— 那是"谁经手的这张单"唯一的线索，且**平时看不出来**
            Order order = persistedOrder(1L, STORE_A, CUSTOMER);
            order.recordOperator(9L);
            stubFind(order);

            orderAppService.onlinePay(1L, PayMethod.WECHAT, CUSTOMER);

            assertThat(order.getStaffId()).isEqualTo(9L);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        }
    }

    // ════════════════ 快递单号 ════════════════

    /**
     * 这一组只测编排（谁能不能调、走没走 CAS）。
     * "哪张单能录、什么时候能录、单号多长"是订单自己的不变量，在 OrderTest 里测
     */
    @Nested
    @DisplayName("录入快递单号")
    class ExpressNo {

        private static final String NO = "SF1234567890";

        @Test
        @DisplayName("员工给派送中的网单录入 → 单号落上，CAS 带的是 6 态")
        void recordsExpressNo() {
            Order order = onlineAtDelivering(1L, CUSTOMER);
            stubFind(order);

            Result<Void> result = orderAppService.fillExpressNo(1L, NO, 9L);

            assertThat(result.code()).isEqualTo(200);
            assertThat(order.getExpressNo()).isEqualTo(NO);
            // 状态没变，CAS 的期望值仍是 6 —— 这次更新实际是在**守**这一列
            verify(orderRepository).updateStatusCas(order, OrderStatus.DELIVERING);
            assertThat(order.getStaffId()).isEqualTo(9L);   // 员工侧照旧留痕
        }

        @Test
        @DisplayName("顾客 token → 401")
        void customerTokenRejected() {
            assertThatThrownBy(() -> orderAppService.fillExpressNo(1L, NO, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(401);
        }

        @Test
        @DisplayName("订单不存在 → 404")
        void orderNotFound() {
            assertThatThrownBy(() -> orderAppService.fillExpressNo(1L, NO, 9L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(404);
        }

        @Test
        @DisplayName("另一个员工同时把单推进到已完成（CAS 0 行）→ 409，单号不会写到已完成的单上")
        void casConflictReturns409() {
            Order order = onlineAtDelivering(1L, CUSTOMER);
            stubFind(order);
            when(orderRepository.updateStatusCas(order, OrderStatus.DELIVERING))
                    .thenReturn(false);

            assertThatThrownBy(() -> orderAppService.fillExpressNo(1L, NO, 9L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(409);
        }

        /** 把一张网单推到派送中（6 态）：先付清，再连推三次 */
        private Order onlineAtDelivering(Long id, Long customerId) {
            Order order = persistedOnlineOrder(id, STORE_A, customerId);
            order.pay(PayMethod.WECHAT, order.getTotalAmount());
            order.updateStatus();   // 2 → 3
            order.updateStatus();   // 3 → 4
            order.updateStatus();   // 4 → 6（网单走 6）
            return order;
        }
    }
}
