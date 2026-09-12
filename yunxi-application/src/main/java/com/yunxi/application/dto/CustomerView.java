package com.yunxi.application.dto;

import com.yunxi.domain.customer.Customer;

/**
 * 顾客的**出参**（门店单建档用）。
 *
 * 四个字段，**没有密码**——这不是省事，是这条 DTO 存在的首要理由。
 * CustomerPO 有 password 字段（BCrypt 哈希），直接把 PO 或 domain 对象
 * 交给接口层，就等于把哈希的出口留在那儿等哪天顺手带出去。
 * 出参里根本没有这个字段，谁想漏都漏不了。
 *
 * created 表示**本次调用**是否新建了顾客：
 *   true  → 刚建档，前端可以说一句"新顾客已建档"
 *   false → 老顾客，手机号一输名字就自动带出来
 * 它是"这次查询的结果"，不是顾客的属性 —— 所以只在出参里有，
 * domain 的 Customer 上不该有这么一个字段。
 */
public record CustomerView(
        Long customerId,
        String name,
        String phone,
        boolean created
) {

    /** 老顾客（已存在）→ created=false */
    public static CustomerView existing(Customer customer) {
        return new CustomerView(customer.getId(), customer.getName(),
                customer.getPhone(), false);
    }

    /** 刚建档 → created=true；id 是入库回填的自增主键 */
    public static CustomerView created(Long customerId, String name, String phone) {
        return new CustomerView(customerId, name, phone, true);
    }
}
