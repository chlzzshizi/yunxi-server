package com.yunxi.domain.customer;

import java.util.Optional;

/**
 * 顾客仓储 —— 由 infrastructure 用 MyBatis 实现。
 */
public interface CustomerRepository {

    /**
     * 按手机号查顾客（登录、查重用）。
     *
     * 查不到返回空 Optional，而不是 null。
     */
    Optional<Customer> findByPhone(String phone);

    /**
     * 新增顾客，返回自增主键（注册完要立刻用它签 token，所以必须拿得到 id）。
     *
     * 查重不可靠 —— 并发下两个请求可能同时通过 findByPhone 的检查，
     * 真正拦下后一个的是数据库唯一键 uk_phone。实现**不要吞掉这个冲突**，
     * 让异常冒上去由调用方决定怎么提示用户。
     */
    Long save(Customer customer);
}
