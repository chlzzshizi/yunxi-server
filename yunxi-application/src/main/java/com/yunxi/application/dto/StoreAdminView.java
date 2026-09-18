package com.yunxi.application.dto;

import com.yunxi.domain.store.Store;

/**
 * 门店的**管理侧出参** —— 比 StoreView 多一个 status。
 *
 * 为什么不给 StoreView 加一个 status 就完了：那两个接口的**读者不同**。
 *   · StoreView      —— 顾客下单页 / 员工建单页的下拉框。它的读者要的是
 *                       "哪些店能选"，而能选的就是营业中的，所以第一条
 *                       来自仓储就已经保证 status=1，再给一个 status
 *                       只会让前端写着 `if (store.status === 1)` 的废代码
 *   · StoreAdminView —— 管理员的门店管理页。他要看见停业的店（不然没法学着
 *                       把它重新开起来），所以 status 是这里的主角
 * 两个投影并存，各自只带自己那一面需要的字段。合成一个是把"该不该显示停业店"
 * 这个判断推给每个调用点去做。
 */
public record StoreAdminView(
        Long id,
        String name,
        String address,
        String phone,
        Integer status
) {

    public static StoreAdminView from(Store store) {
        return new StoreAdminView(store.getId(), store.getName(),
                store.getAddress(), store.getPhone(), store.getStatus());
    }
}
