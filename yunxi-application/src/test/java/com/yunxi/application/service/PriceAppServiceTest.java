package com.yunxi.application.service;

import com.yunxi.application.dto.PriceItem;
import com.yunxi.application.dto.PriceRow;
import com.yunxi.common.BusinessException;
import com.yunxi.domain.price.ClothesCategory;
import com.yunxi.domain.price.ClothesPrice;
import com.yunxi.domain.price.PriceRepository;
import com.yunxi.domain.price.WashType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PriceAppService 单元测试 —— 用 Mockito 假造仓储，只测应用层规则。
 *
 * 覆盖两条最容易写错、也最容易算错钱的规则：
 *   1. 只有二级（叶子）分类能定价
 *   2. 精洗 = 普洗 + 20 是**算**出来的：有普洗时不许手填，
 *      没普洗时才允许手填（羽绒服 60.00 的逃生舱）
 *
 * 不依赖 Spring 容器、不连数据库。
 */
class PriceAppServiceTest {

    private PriceRepository priceRepository;
    private PriceAppService priceAppService;

    private static final Long SHIRT = 11L;      // 衬衫（叶子）
    private static final Long TOPS = 1L;        // 上衣（一级）

    @BeforeEach
    void setUp() {
        priceRepository = mock(PriceRepository.class);
        priceAppService = new PriceAppService(priceRepository);
    }

    // ════════════════ 造数据的小工具 ════════════════

    /** 叶子分类：有 parentId */
    private ClothesCategory leaf(long id, String name) {
        ClothesCategory c = new ClothesCategory();
        c.setId(id);
        c.setName(name);
        c.setParentId(TOPS);
        return c;
    }

    /** 一级分类：parentId 为 null */
    private ClothesCategory root(long id, String name) {
        ClothesCategory c = new ClothesCategory();
        c.setId(id);
        c.setName(name);
        c.setParentId(null);
        return c;
    }

    /** 仓储里的一条价格（读出来用） */
    private ClothesPrice price(long washTypeId, String value) {
        return new ClothesPrice(SHIRT, washTypeId, new BigDecimal(value));
    }

    /** 写入请求里的一条价格（应用层命令对象，不带 categoryId —— 它走路径参数） */
    private PriceItem item(long washTypeId, String value) {
        return new PriceItem(washTypeId, new BigDecimal(value));
    }

    /** 让仓储"该分类已有这些价格" */
    private void existingPrices(ClothesPrice... prices) {
        when(priceRepository.findPricesByCategoryIds(any()))
                .thenReturn(Map.of(SHIRT, List.of(prices)));
    }

    // ════════════════ 校验 ════════════════

    @Nested
    @DisplayName("写入前的校验")
    class Validation {

        @Test
        @DisplayName("给一级分类定价 → 400（「上衣」本身不洗）")
        void rejectRootCategory() {
            when(priceRepository.findCategoryById(TOPS))
                    .thenReturn(Optional.of(root(TOPS, "上衣")));

            assertThatThrownBy(() -> priceAppService.savePrices(
                    TOPS, List.of(item(1L, "15.00"))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("只能给二级（叶子）分类设置价格");
            verify(priceRepository, never()).savePrice(any(), any(), any());
        }

        @Test
        @DisplayName("分类不存在 → 400（不是 NullPointerException）")
        void rejectMissingCategory() {
            when(priceRepository.findCategoryById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> priceAppService.savePrices(
                    999L, List.of(item(1L, "15.00"))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("衣物分类不存在");
        }

        @Test
        @DisplayName("4 号洗涤方式不存在 → 400")
        void rejectUnknownWashType() {
            when(priceRepository.findCategoryById(SHIRT))
                    .thenReturn(Optional.of(leaf(SHIRT, "衬衫")));

            assertThatThrownBy(() -> priceAppService.savePrices(
                    SHIRT, List.of(item(4L, "15.00"))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("没有这个洗涤方式");
        }

        @Test
        @DisplayName("负数价格 → 400（0 是合法的，表示不支持）")
        void rejectNegativePrice() {
            when(priceRepository.findCategoryById(SHIRT))
                    .thenReturn(Optional.of(leaf(SHIRT, "衬衫")));

            assertThatThrownBy(() -> priceAppService.savePrices(
                    SHIRT, List.of(item(1L, "-1.00"))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能为空或负数");
        }

        @Test
        @DisplayName("空列表 → 400")
        void rejectEmptyList() {
            assertThatThrownBy(() -> priceAppService.savePrices(SHIRT, List.of()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("至少要传一条价格");
        }
    }

    // ════════════════ 精洗派生 ════════════════

    @Nested
    @DisplayName("精洗派生：有普洗就自动算")
    class DeriveRefined {

        @BeforeEach
        void leafStub() {
            when(priceRepository.findCategoryById(SHIRT))
                    .thenReturn(Optional.of(leaf(SHIRT, "衬衫")));
        }

        @Test
        @DisplayName("改普洗 20 → 精洗被自动重算成 40（不是保留旧值）")
        void deriveOnPlainWrite() {
            priceAppService.savePrices(SHIRT, List.of(item(1L, "20.00")));

            verify(priceRepository).savePrice(SHIRT, 1L, new BigDecimal("20.00"));
            verify(priceRepository).savePrice(SHIRT, 2L, new BigDecimal("40.00"));
        }

        @Test
        @DisplayName("普洗 0 → 只写普洗，不派生（0 表示不支持）")
        void noDeriveWhenPlainZero() {
            priceAppService.savePrices(SHIRT, List.of(item(1L, "0.00")));

            verify(priceRepository).savePrice(SHIRT, 1L, new BigDecimal("0.00"));
            // 普洗这笔本来就要写，所以不能用 never() 断言"一笔都没写"，
            // 要精确到"没有写精洗（washTypeId=2）那一笔"
            verify(priceRepository, never()).savePrice(any(), eq(2L), any());
        }

        @Test
        @DisplayName("已有普洗 15.00 时手填精洗 99 → 400，且一笔都不写")
        void rejectManualRefinedWhenPlainExists() {
            existingPrices(price(1L, "15.00"), price(2L, "35.00"));

            assertThatThrownBy(() -> priceAppService.savePrices(
                    SHIRT, List.of(item(2L, "99.00"))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能手工设置");
            verify(priceRepository, never()).savePrice(any(), any(), any());
        }

        @Test
        @DisplayName("一次请求里同时传普洗 20 和精洗 99 → 400（以本次普洗为准）")
        void rejectManualRefinedInSameRequest() {
            assertThatThrownBy(() -> priceAppService.savePrices(SHIRT,
                    List.of(item(1L, "20.00"), item(2L, "99.00"))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能手工设置");
            // 关键：普洗也不能"写一半"——校验在落库之前全部做完
            verify(priceRepository, never()).savePrice(any(), any(), any());
        }

        @Test
        @DisplayName("普洗为 0（无普洗）时手填精洗 60 → 成功（羽绒服逃生舱）")
        void allowManualRefinedWithoutPlain() {
            existingPrices(price(1L, "0.00"), price(2L, "60.00"));

            priceAppService.savePrices(SHIRT, List.of(item(2L, "60.00")));

            verify(priceRepository).savePrice(SHIRT, 2L, new BigDecimal("60.00"));
        }

        @Test
        @DisplayName("该分类连普洗行都没有 → 手填精洗同样放行")
        void allowManualRefinedWhenNoPlainRow() {
            priceAppService.savePrices(SHIRT, List.of(item(2L, "60.00")));

            verify(priceRepository).savePrice(SHIRT, 2L, new BigDecimal("60.00"));
        }

        @Test
        @DisplayName("单熨独立定价，不受普洗影响")
        void ironIsIndependent() {
            existingPrices(price(1L, "15.00"), price(2L, "35.00"));

            priceAppService.savePrices(SHIRT, List.of(item(3L, "8.00")));

            verify(priceRepository).savePrice(SHIRT, 3L, new BigDecimal("8.00"));
        }
    }

    // ════════════════ 读模型 ════════════════

    @Nested
    @DisplayName("价目表读模型")
    class PriceReadModel {

        @Test
        @DisplayName("拼上分类名与洗涤方式名；价格 0 行标 supported=false（前端置灰）")
        void joinsNamesAndSupportedFlag() {
            when(priceRepository.findAllCategories())
                    .thenReturn(List.of(root(TOPS, "上衣"), leaf(SHIRT, "衬衫"),
                            leaf(13L, "羽绒服")));
            when(priceRepository.findAllWashTypes()).thenReturn(List.of(
                    washType(1L, "普洗"), washType(2L, "精洗"), washType(3L, "单熨")));
            when(priceRepository.findAllPrices()).thenReturn(List.of(
                    price(1L, "15.00"), price(2L, "35.00"),
                    new ClothesPrice(13L, 1L, new BigDecimal("0.00")),
                    new ClothesPrice(13L, 2L, new BigDecimal("60.00"))));

            List<PriceRow> rows = priceAppService.listPrices().data();

            assertThat(rows).hasSize(4);
            PriceRow shirtPlain = rows.get(0);
            assertThat(shirtPlain.categoryName()).isEqualTo("衬衫");
            assertThat(shirtPlain.washTypeName()).isEqualTo("普洗");
            assertThat(shirtPlain.parentId()).isEqualTo(TOPS);
            assertThat(shirtPlain.supported()).isTrue();
            // 羽绒服·普洗 = 0 → 不支持，前端据此置灰
            assertThat(rows.get(2).supported()).isFalse();
            assertThat(rows.get(2).price()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("分类或洗涤方式被删掉的残留价格行 → 跳过，不让整页打不开")
        void skipsDanglingRows() {
            when(priceRepository.findAllCategories())
                    .thenReturn(List.of(leaf(SHIRT, "衬衫")));
            when(priceRepository.findAllWashTypes())
                    .thenReturn(List.of(washType(1L, "普洗")));
            when(priceRepository.findAllPrices()).thenReturn(List.of(
                    price(1L, "15.00"),
                    new ClothesPrice(999L, 1L, new BigDecimal("10.00")),   // 分类没了
                    new ClothesPrice(SHIRT, 99L, new BigDecimal("10.00"))  // 洗涤方式没了
            ));

            List<PriceRow> rows = priceAppService.listPrices().data();

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).categoryId()).isEqualTo(SHIRT);
        }

        private WashType washType(long id, String name) {
            WashType w = new WashType();
            w.setId(id);
            w.setName(name);
            return w;
        }
    }
}
