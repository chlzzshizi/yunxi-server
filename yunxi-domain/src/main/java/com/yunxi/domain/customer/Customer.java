package com.yunxi.domain.customer;

import com.yunxi.common.BusinessException;

/**
 * 顾客 —— 目前只服务于"注册 / 登录"这一件事。
 *
 * 顾客就是会员，不做等级区分（余额与会员身份无关）；顾客管理、地址簿、
 * 交易流水都还没做，字段只留认证用得到的。门店单顾客由员工在柜台建档，
 * 所以 storeId 和 passwordHash 都可能为空。
 */
public class Customer {

    /** 姓名长度上限，与 customers.name VARCHAR(20) 对齐 */
    public static final int NAME_MAX_LENGTH = 20;

    private Long id;
    private Long storeId;         // 归属门店：门店单=建档店，网单=配送地址对应的店
    private String name;
    private String phone;         // 唯一标识，uk_phone 是并发下的权威兜底
    private String passwordHash;  // BCrypt 哈希；门店单顾客（柜台建档）为 null

    /**
     * 姓名长度校验 —— **只管长度，不管必填**。
     *
     * 必填与否是应用层的事，而且三个场合各不相同：柜台新建必填、个人中心改名必填、
     * "补名字"是可选（不传就跳过）。所以这里对 null / 空串一律放行，
     * 由调用方在决定"要用这个名字"之后再调。
     *
     * 为什么非要拦一道：name 列是 VARCHAR(20)，超长的名字会一路走到 INSERT
     * 才被 MySQL 弹回来，报出来的是 DataTooLong 这种英文 SQL 异常（500），
     * 而不是一句给用户看的话。拦在这里，20 这个数字就只有这一个家 ——
     * 哪天列加宽了，改的是这里，不是散落在各处的 if。
     *
     * 用 codePointCount 而不是 length()：Java 的 length() 数的是 UTF-16 码元，
     * 一个 emoji（代理对）算 2，而 MySQL 的 VARCHAR(20) 数的是**字符**、算 1。
     * 用 length() 会把 11 个 emoji 的名字误判成超长 —— 不是数据损坏，但会莫名其妙拒绝。
     */
    public static void requireValidName(String name) {
        if (name != null && name.codePointCount(0, name.length()) > NAME_MAX_LENGTH) {
            throw new BusinessException("姓名不能超过 " + NAME_MAX_LENGTH + " 个字");
        }
    }

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
