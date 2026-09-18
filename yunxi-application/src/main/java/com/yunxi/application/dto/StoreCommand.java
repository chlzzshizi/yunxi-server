package com.yunxi.application.dto;

/**
 * 门店的名字 / 地址 / 电话 —— **入参**，新建与修改共用。
 *
 * 为什么一个类管两件事：这三个字段在 create 和 update 里**完全一样**
 * （不像员工，create 有 username/password 而 update 没有）。
 * 硬拆成 CreateStoreCommand / UpdateStoreCommand 只会得到两个字段完全相同的类，
 * 而它们唯一的区别是"我们打算怎么用它"—— 那个区别由方法名说（insert / update），
 * 不该由类型说。
 *
 * 三个 String 挨在一起，是**最**需要 record 的那一种：
 * `update(id, name, address, phone)` 里把 address 和 phone 写反，
 * 编译器一个字都不会说，症状是"门店地址里存着电话号码"。
 *
 * 没有 status：停业走 PUT /api/stores/{id}/status —— 那是个有独立语义的动作
 * （停业的店会从顾客的选店列表里消失），不该混在"改个电话"里顺手发生。
 */
public record StoreCommand(
        String name,
        String address,
        String phone
) {}
