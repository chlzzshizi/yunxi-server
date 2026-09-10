package com.yunxi.domain.price;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 价目表仓储 —— 由 infrastructure 用 MyBatis 实现。
 *
 * 读接口刻意拆成"各查各的"（分类/洗涤方式/价格各一条 SQL），
 * 而不是一条 JOIN 拉出大宽表：三张表都是几十行的静态参考数据，
 * 拼装方式交给调用方决定（展示要名字、算价只要价格），
 * 一条 JOIN 反而要为两种用途各写一套 resultMap。
 */
public interface PriceRepository {

    /** 全部分类（一级 + 叶子，前端自己按 parentId 组树） */
    List<ClothesCategory> findAllCategories();

    /** 按 id 取分类（写价格时判断"存在且是叶子"用） */
    Optional<ClothesCategory> findCategoryById(Long id);

    /** 全部洗涤方式（固定 3 种） */
    List<WashType> findAllWashTypes();

    /** 全部价格行 */
    List<ClothesPrice> findAllPrices();

    /**
     * 按分类批量取价格 —— 下单算价用。
     *
     * 一次查完所有涉及的分类，避免"每条明细查一次库"的 N+1：
     * 一张单有 5 件衣服就查 5 次，看着不多，但它是可以一次查完的。
     *
     * @param categoryIds 涉及的分类 id（空集合时调用方应跳过，不要查库）
     * @return key = 分类 id，value = 该分类的各洗涤方式价格
     */
    Map<Long, List<ClothesPrice>> findPricesByCategoryIds(Collection<Long> categoryIds);

    /**
     * 写入一个价格（已有则覆盖）。
     * 实现必须用"有则改无则插"的原子写法，不能先查再决定。
     */
    void savePrice(Long categoryId, Long washTypeId, BigDecimal price);
}
