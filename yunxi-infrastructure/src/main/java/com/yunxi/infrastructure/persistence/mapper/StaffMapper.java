package com.yunxi.infrastructure.persistence.mapper;


import com.yunxi.infrastructure.persistence.po.StaffPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface StaffMapper {

    /** 根据用户名查询员工 */
    StaffPO selectByUsername(String username);

    /** 按 id 查员工；不存在返回 null */
    StaffPO selectById(Long id);

    /** 全部员工（含已停用），按 id 升序 */
    List<StaffPO> selectAll();

    /** 新建员工；id 回填进 po.id（useGeneratedKeys） */
    int insert(StaffPO po);

    /** 按 id 更新员工（不含 username）；返回影响行数，0 = 这个 id 不存在 */
    int update(StaffPO po);

    /** 这个用户名被占了几次（0 或 1，username 上有唯一键） */
    int countByUsername(String username);
}