package com.yunxi.infrastructure.persistence.po;

/**
 * 门店表 stores 的数据库映射对象。
 *
 * 刻意不映射 status 列：这两条查询（营业中列表 / 按 id 查营业中）
 * 都只在 SQL 里认 status=1，查出来的行**必然是在营业的**。
 * 把它搬进 PO 就等于给了第二个真相 —— 总有人会去 if 它。
 * 哪天要做"管理门店（含停业）"，再把 status 加回来，那时它才真有下家。
 */
public class StorePO {

    private Long id;
    private String name;
    private String address;
    private String phone;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
}
