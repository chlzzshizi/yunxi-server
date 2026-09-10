package com.yunxi.infrastructure.persistence.mapper;

import com.yunxi.infrastructure.persistence.po.WashTypePO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface WashTypeMapper {

    /** 全部洗涤方式（固定 3 种，按 id 排 = 普洗/精洗/单熨） */
    List<WashTypePO> selectAll();
}
