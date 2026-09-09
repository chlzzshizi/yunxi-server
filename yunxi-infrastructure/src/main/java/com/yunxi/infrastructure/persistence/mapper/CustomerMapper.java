package com.yunxi.infrastructure.persistence.mapper;

import com.yunxi.infrastructure.persistence.po.CustomerPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CustomerMapper {

    /** 根据手机号查询客户（登录用） */
    CustomerPO selectByPhone(String phone);

    /** 新增客户（注册用），自增主键回填到 PO */
    void insert(CustomerPO customerPO);
}
