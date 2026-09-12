package com.yunxi.infrastructure.persistence.mapper;

import com.yunxi.infrastructure.persistence.po.CustomerPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerMapper {

    /** 根据手机号查询客户（登录用） */
    CustomerPO selectByPhone(String phone);

    /** 根据主键查询客户（个人中心用） */
    CustomerPO selectById(Long id);

    /** 新增客户（注册用），自增主键回填到 PO */
    void insert(CustomerPO customerPO);

    /** 补设密码（门店单顾客激活）。带 CAS 条件，返回受影响行数：0 = 已有密码、没生效 */
    int updatePassword(@Param("id") Long id, @Param("password") String password);

    /** 补名字（只给还没有名字的顾客用），SQL 里带 name IS NULL 的第二道锁 */
    void fillName(@Param("id") Long id, @Param("name") String name);

    /** 改名（顾客在个人中心改自己的名字），**无条件**覆盖 */
    void rename(@Param("id") Long id, @Param("name") String name);
}
