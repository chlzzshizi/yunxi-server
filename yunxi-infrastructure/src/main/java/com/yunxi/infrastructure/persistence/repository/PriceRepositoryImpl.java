package com.yunxi.infrastructure.persistence.repository;

import com.yunxi.domain.price.ClothesCategory;
import com.yunxi.domain.price.ClothesPrice;
import com.yunxi.domain.price.PriceRepository;
import com.yunxi.domain.price.WashType;
import com.yunxi.infrastructure.persistence.mapper.ClothesCategoryMapper;
import com.yunxi.infrastructure.persistence.mapper.ClothesPriceMapper;
import com.yunxi.infrastructure.persistence.mapper.WashTypeMapper;
import com.yunxi.infrastructure.persistence.po.ClothesCategoryPO;
import com.yunxi.infrastructure.persistence.po.ClothesPricePO;
import com.yunxi.infrastructure.persistence.po.WashTypePO;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 价目表仓储实现 —— 用 MyBatis/MySQL 实现 domain 层定义的 PriceRepository。
 */
@Repository
public class PriceRepositoryImpl implements PriceRepository {

    private final ClothesCategoryMapper clothesCategoryMapper;
    private final WashTypeMapper washTypeMapper;
    private final ClothesPriceMapper clothesPriceMapper;

    /** 构造注入（替代 @Autowired） */
    public PriceRepositoryImpl(ClothesCategoryMapper clothesCategoryMapper,
                               WashTypeMapper washTypeMapper,
                               ClothesPriceMapper clothesPriceMapper) {
        this.clothesCategoryMapper = clothesCategoryMapper;
        this.washTypeMapper = washTypeMapper;
        this.clothesPriceMapper = clothesPriceMapper;
    }

    @Override
    public List<ClothesCategory> findAllCategories() {
        return clothesCategoryMapper.selectAll().stream()
                .map(this::toCategory)
                .toList();
    }

    @Override
    public Optional<ClothesCategory> findCategoryById(Long id) {
        ClothesCategoryPO po = clothesCategoryMapper.selectById(id);
        return po == null ? Optional.empty() : Optional.of(toCategory(po));
    }

    @Override
    public List<WashType> findAllWashTypes() {
        return washTypeMapper.selectAll().stream()
                .map(this::toWashType)
                .toList();
    }

    @Override
    public List<ClothesPrice> findAllPrices() {
        return clothesPriceMapper.selectAll().stream()
                .map(this::toPrice)
                .toList();
    }

    @Override
    public Map<Long, List<ClothesPrice>> findPricesByCategoryIds(
            Collection<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return Map.of();        // 空集合会让 SQL 变成 IN ()，直接短路
        }
        return clothesPriceMapper.selectByCategoryIds(categoryIds).stream()
                .map(this::toPrice)
                .collect(Collectors.groupingBy(ClothesPrice::getCategoryId));
    }

    @Override
    public void savePrice(Long categoryId, Long washTypeId, BigDecimal price) {
        clothesPriceMapper.upsert(categoryId, washTypeId, price);
    }

    // ═══════════════════ 内部转换方法 ═══════════════════

    /** PO(数据库) → ClothesCategory(domain) */
    private ClothesCategory toCategory(ClothesCategoryPO po) {
        ClothesCategory c = new ClothesCategory();
        c.setId(po.getId());
        c.setName(po.getName());
        c.setIcon(po.getIcon());
        c.setParentId(po.getParentId());
        c.setSortOrder(po.getSortOrder() == null ? 0 : po.getSortOrder());
        return c;
    }

    private WashType toWashType(WashTypePO po) {
        WashType w = new WashType();
        w.setId(po.getId());
        w.setName(po.getName());
        w.setDescription(po.getDescription());
        return w;
    }

    private ClothesPrice toPrice(ClothesPricePO po) {
        return new ClothesPrice(po.getCategoryId(), po.getWashTypeId(), po.getPrice());
    }
}
