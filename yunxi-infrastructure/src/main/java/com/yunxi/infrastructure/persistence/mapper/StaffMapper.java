package com.yunxi.infrastructure.persistence.mapper;


import com.yunxi.infrastructure.persistence.po.StaffPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StaffMapper {

    /** 根据用户名查询员工 */
    StaffPO selectByUsername(String username);
}