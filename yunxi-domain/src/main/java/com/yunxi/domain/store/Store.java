package com.yunxi.domain.store;

/**
 * 门店 —— **参考数据**：只有"是什么"（名字、地址、电话），没有生命周期、
 * 没有状态迁移、没有不变量要守。所以它不是聚合根，也没有任何行为方法。
 *
 * 那为什么还给它一个 domain 类型，而不像 CouponPO 那样直接用 PO + Mapper？
 *
 * 因为在**没有外键约束**的前提下（orders.store_id 只是个普通索引，见 V1），
 * "这单挂在哪个店"是个业务要自己负责的事实：应用层得能问出"这个店存在吗、
 * 还在营业吗"。而"营业中才能接单"这条判断，正是门店域仅有的那条规则。
 *
 * 若让应用层直接拿 StoreMapper，`application → infrastructure` 就多一条依赖箭头 ——
 * 那正是券模块被记成"主动简化例外"的那条（设计文档 §3.1）。门店就这么点体量，
 * 不值得为它再开一个例外。
 *
 * 一句话：**放 domain 是为了守住依赖方向，不是为了给它加行为。**
 * 将来门店真长出规则（营业时间、歇业、跨店结算），它已经站在对的位置上了。
 */
public class Store {

    private Long id;
    private String name;
    private String address;
    private String phone;

    // 刻意没有 status 字段：仓储只查营业中的门店（过滤在 SQL 里），
    // 所以"能查到 = 营业中"，这里再放一个 status 就有了第二个真相

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
}
