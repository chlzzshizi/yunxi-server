package com.yunxi.domain.customer;

/**
 * 顾客 —— 目前只服务于"注册 / 登录"这一件事。
 *
 * 顾客就是会员，不做等级区分（余额与会员身份无关）；顾客管理、地址簿、
 * 交易流水都还没做，字段只留认证用得到的。门店单顾客由员工在柜台建档，
 * 所以 storeId 和 passwordHash 都可能为空。
 */
public class Customer {

    private Long id;
    private Long storeId;         // 归属门店：门店单=建档店，网单=配送地址对应的店
    private String name;
    private String phone;         // 唯一标识，uk_phone 是并发下的权威兜底
    private String passwordHash;  // BCrypt 哈希；门店单顾客（柜台建档）为 null

    /**
     * 这个顾客有没有密码可用。
     *
     * 门店单顾客是员工在柜台直接建档的，从来没有密码 —— 拿他们去在线登录
     * 必须显式挡掉。不挡的话 password 为 null 传给 BCrypt 会直接抛异常，
     * 变成 500 而不是一句人话。
     */
    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
