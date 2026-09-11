package com.yunxi.domain.staff;

import java.util.Optional;

/**
 * 员工仓储 —— 由 infrastructure 用 MyBatis 实现。
 *
 * 接口定义在 domain：谁需要什么数据，由需要的人说了算；
 * infrastructure 只负责"怎么取"。这样 application 才能只依赖这个接口，
 * 单元测试里用 Mockito 造个假实现就能测登录规则，不用起 Spring 也不用连库。
 */
public interface StaffRepository {

    /**
     * 按用户名查员工（登录用）。
     *
     * 查不到返回空 Optional，而不是 null —— "没这个人"是正常结果，不是异常。
     */
    Optional<Staff> findByUsername(String username);
}
