package com.yunxi.infrastructure.persistence.mapper;

import com.yunxi.infrastructure.persistence.po.ClothesPricePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

@Mapper
public interface ClothesPriceMapper {

    /** 全量价目表（36 行左右，一次查完比按需查更省事） */
    List<ClothesPricePO> selectAll();

    /**
     * 写入价格：有则改，无则插。
     * 靠 V1 建的 uk_category_wash (category_id, wash_type_id) 唯一键判定"已存在"。
     */
    void upsert(@Param("categoryId") Long categoryId,
                @Param("washTypeId") Long washTypeId,
                @Param("price") BigDecimal price);

    /**
     * 按分类批量查价格。
     * 用 foreach 拼 IN (...)，一次往返拿回所有涉及分类的价格。
     */
    List<ClothesPricePO> selectByCategoryIds(@Param("categoryIds") Collection<Long> categoryIds);
}
