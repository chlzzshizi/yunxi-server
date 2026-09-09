package com.yunxi.infrastructure.persistence.po;


import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 客户表 customers 的数据库映射对象。
 */
public class CustomerPO {

    private Long id;
    private Long storeId;            // 注册门店，网单注册时可为 null
    private String name;
    private String phone;            // 手机号，唯一标识
    private String password;         // BCrypt 加密，门店单顾客为 null
    private LocalDate birthday;
    private Integer gender;          // 0=未知 1=男 2=女
    private BigDecimal balance;      // 余额，>0 即为会员

    // ── getter / setter ──
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public LocalDate getBirthday() { return birthday; }
    public void setBirthday(LocalDate birthday) { this.birthday = birthday; }

    public Integer getGender() { return gender; }
    public void setGender(Integer gender) { this.gender = gender; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
}
