package com.yunxi.application.dto;

import com.yunxi.domain.customer.Customer;

/**
 * 顾客**自己**的档案（个人中心出参）。
 *
 * 为什么不再用 CustomerView：那个是"员工建档"的出参，带着一个 created 字段
 * （本次调用是不是新建了顾客）。个人中心里没有"新建"这回事，硬塞一个恒为
 * false 的 created 出去，前端会以为它有意义。
 *
 * 两个 DTO 都**没有密码字段**（和 CustomerView 同一个理由）：CustomerPO 有
 * password 列，出参里干脆没有这个字段，谁想漏都漏不了。
 *
 * 今天只有三个字段，看着和 CustomerView 差不多 —— 但它们会分开长：
 * 顾客以后要自己填生日、性别（customers 表有这两列），那些属于"我自己的档案"，
 * 只会加到这里；员工建档那边不需要。
 */
public record CustomerProfileView(
        Long customerId,
        String name,
        String phone
) {

    public static CustomerProfileView of(Customer customer) {
        return new CustomerProfileView(customer.getId(), customer.getName(), customer.getPhone());
    }
}
