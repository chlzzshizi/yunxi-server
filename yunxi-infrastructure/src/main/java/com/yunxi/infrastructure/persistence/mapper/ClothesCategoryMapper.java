package com.yunxi.infrastructure.persistence.mapper;

import com.yunxi.infrastructure.persistence.po.ClothesCategoryPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ClothesCategoryMapper {

    /** 全部分类（按 sort_order 排，前端组树后顺序即页面顺序） */
    List<ClothesCategoryPO> selectAll();

    /** 按 id 查单个分类（写价格时判断"是不是叶子"用） */
    ClothesCategoryPO selectById(Long id);
}
