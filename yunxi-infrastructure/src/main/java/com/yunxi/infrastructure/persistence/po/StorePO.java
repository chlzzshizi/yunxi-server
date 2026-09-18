package com.yunxi.infrastructure.persistence.po;

/**
 * 门店表 stores 的数据库映射对象。
 *
 * status 是 2026-09-18 加回来的，正是当初说的那天："哪天要做管理门店（含停业），
 * 再把 status 加回来，那时它才真有下家。"下家就是门店管理 ——
 * selectAll / selectById 不过滤状态，查出来的行既可能营业也可能停业，
 * 这时候不映射它，调用方就分不出这两种行。
 *
 * 两条老查询（selectOpen / selectOpenById）**仍然不过滤在 Java 里**：
 * 它们的 SQL 就认 status=1，映射到字段只是顺带，不是让调用方去判断。
 *
 * 仍然不映射 create_time / update_time：没有任何响应会显示它们，
 * 而 DDL 里那两列的默认值（DEFAULT CURRENT_TIMESTAMP / ON UPDATE）已经把它们填好了，
 * 映射进来就是一列没有人读的数据。
 */
public class StorePO {

    private Long id;
    private String name;
    private String address;
    private String phone;
    private Integer status;      // 1=营业 0=停业

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
}
