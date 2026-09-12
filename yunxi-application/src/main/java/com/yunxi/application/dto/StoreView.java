package com.yunxi.application.dto;

import com.yunxi.domain.store.Store;

/**
 * 门店的**出参**。
 *
 * 为什么不直接把 Store 交给 controller（少写一个类）：那会让 domain 对象
 * 出现在接口层，破了 §3.1 的硬规则。别小看这个"就一个门面类"——
 * 一旦松口，下次"反正就差一个字段"就会变成把 Order 整个透出去。
 *
 * 没有 status：仓储只返回营业中的门店，"能拿到 = 在营业"，
 * 前端不需要再判断一遍（判断的活已经在 SQL 里干完了）。
 */
public record StoreView(
        Long id,
        String name,
        String address,
        String phone
) {

    public static StoreView from(Store store) {
        return new StoreView(store.getId(), store.getName(),
                store.getAddress(), store.getPhone());
    }
}
