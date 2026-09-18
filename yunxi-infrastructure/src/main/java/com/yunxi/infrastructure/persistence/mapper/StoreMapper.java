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

    /** 全部门店（含停业），按 id 升序 */
    List<StorePO> selectAll();

    /** 按 id 查门店，**不过滤状态**；不存在返回 null */
    StorePO selectById(Long id);

    /** 新建门店；id 回填进 po.id（useGeneratedKeys） */
    int insert(StorePO po);

    /** 按 id 更新门店（名字/地址/电话/状态）；返回影响行数，0 = 这个 id 不存在 */
    int update(StorePO po);
}
