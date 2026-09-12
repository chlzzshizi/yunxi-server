package com.yunxi.infrastructure.persistence.mapper;

import com.yunxi.infrastructure.persistence.po.StorePO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface StoreMapper {

    /** 营业中的门店，按 id 升序 */
    List<StorePO> selectOpen();

    /** 按 id 查营业中的门店；不存在或已停业返回 null */
    StorePO selectOpenById(Long id);
}
