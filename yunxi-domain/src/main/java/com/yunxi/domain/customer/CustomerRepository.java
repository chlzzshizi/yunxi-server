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
     * 按主键查顾客（个人中心用）。
     *
     * 为什么不能拿 findByPhone 凑：个人中心的身份来自 token，token 里只有
     * customerId，**没有手机号**——那个 claim 是给前端显示用的，不该反过来
     * 当成查询依据（真要那么做，就得把手机号也签进 token，等于为了省一个
     * 方法往 token 里多塞一份个人信息）。
     */
    Optional<Customer> findById(Long customerId);

    /**
     * 新增顾客，返回自增主键（注册完要立刻用它签 token，所以必须拿得到 id）。
     *
     * 查重不可靠 —— 并发下两个请求可能同时通过 findByPhone 的检查，
     * 真正拦下后一个的是数据库唯一键 uk_phone。实现**不要吞掉这个冲突**，
     * 让异常冒上去由调用方决定怎么提示用户。
     */
    Long save(Customer customer);

    /**
     * 给门店单顾客补设密码 —— 也就是"激活"。
     *
     * 门店单顾客是柜台建档的，从没设过密码，所以他**登录不了、却也没法注册**
     * （注册会撞手机号查重说"已注册"，登录会说"未设置密码"）。这个方法是那条
     * 死循环的出口：给他补上密码，账号就通了。
     *
     * **这是一次 CAS**：只有当这一行当时确实还没有密码时才生效。
     * 并发下两个请求同时给同一手机号设密码，后到的会被挡下返回 false ——
     * 否则先设的那个人的密码会被**悄悄覆盖**，他下次登录就登不上，
     * 而且完全不知道为什么（密码是 1 毫秒前刚设的）。
     *
     * @return true = 设置成功；false = 这行已经有密码了（别人抢先激活）
     */
    boolean updatePassword(Long customerId, String passwordHash);

    /**
     * 补名字 —— 只用于档案里**还没有**名字的顾客（线上注册的 name 为 NULL）。
     *
     * 刻意不做成通用的"更新顾客"：那个口子一开，柜台顺手打个错别字就能悄悄
     * 改掉顾客档案。这个方法的名字就说明了它只干一件事、且该由调用方守着前提。
     *
     * **与 {@link #rename} 别搞混**：两者改的是同一列，但前提不同。
     * 名字上如果看不出差别（比如叫 updateName），选错那一个不会报错、
     * 只会"0 行受影响"——写的人以为改成了，实际一个字没动。
     */
    void fillName(Long customerId, String name);

    /**
     * 改名 —— 顾客在个人中心**主动改自己的**名字。
     *
     * 和 {@link #fillName} 的区别是**没有第二道锁**：这里正是要覆盖已有的名字。
     * 能开这个口子的唯一理由是"本人改本人"——调用方必须先用 token 里的
     * customerId 定死身份，绝不能让这个 customerId 来自请求体，
     * 否则任何人都能改任何人的档案。
     */
    void rename(Long customerId, String name);
}
