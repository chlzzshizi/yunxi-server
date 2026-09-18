package com.yunxi.domain.store;

import com.yunxi.common.BusinessException;

/**
 * 门店 —— **参考数据**：只有"是什么"（名字、地址、电话、开没开），没有生命周期、
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

    /** 与 stores.name 的 VARCHAR(50) 对齐 */
    public static final int NAME_MAX_LENGTH = 50;
    /** 与 stores.address 的 VARCHAR(200) 对齐 */
    public static final int ADDRESS_MAX_LENGTH = 200;
    /** 与 stores.phone 的 VARCHAR(20) 对齐 */
    public static final int PHONE_MAX_LENGTH = 20;

    private Long id;
    private String name;
    private String address;
    private String phone;

    /**
     * 1=营业 0=停业。
     *
     * 这个字段以前**故意不在这里**，理由是"仓储只查营业中的门店，能查到 = 营业中，
     * 再放一个 status 就有了第二个真相"。那个理由只在**只读**的前提下成立 ——
     * 2026-09-18 门店管理落地后，管理员要能看见停业的店、要能把它停掉，
     * 这时"查出来的行一定在营业"不再为真（findAll/findById 就不过滤 status）。
     *
     * 第二个真相并没有被放进来：两条查营业中的老方法（findOpen/findOpenById）
     * **过滤仍然只在 SQL 里**，一个字节没动。过不过滤由**方法名**说清楚 ——
     * findOpen = 只要营业的，findAll = 全都要 —— 调用方不需要自己判断 status。
     */
    private Integer status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    // ═══════════ 长度校验（照抄 Customer.requireValidName 的形状）═══════════
    //
    // **只管长度，不管必填**：null / 空串一律放行，必填与否由应用层判断。
    // 拦在这里的理由同上：超长会一路走到 INSERT 才被 MySQL 弹成英文 SQL 异常（500），
    // 50 / 200 / 20 这三个数字也只该有这一个家。
    // codePointCount 而不是 length() 的理由见 Customer.requireValidName。

    public static void requireValidName(String name) {
        requireWithin(name, NAME_MAX_LENGTH, "门店名称");
    }

    public static void requireValidAddress(String address) {
        requireWithin(address, ADDRESS_MAX_LENGTH, "地址");
    }

    public static void requireValidPhone(String phone) {
        requireWithin(phone, PHONE_MAX_LENGTH, "电话");
    }

    private static void requireWithin(String value, int max, String label) {
        if (value != null && value.codePointCount(0, value.length()) > max) {
            throw new BusinessException(label + "不能超过 " + max + " 个字");
        }
    }
}
